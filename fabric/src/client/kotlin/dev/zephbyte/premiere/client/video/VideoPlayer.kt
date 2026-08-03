package dev.zephbyte.premiere.client.video

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.client.subtitles.EmbeddedSubtitleTracks
import dev.zephbyte.premiere.client.subtitles.SubtitleCue
import dev.zephbyte.premiere.util.MediaUrls
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes one URL on a background thread and hands finished RGBA frames to the
 * render thread, which owns the GPU texture.
 *
 * Sync model: each server observation anchors a media position to the local
 * clock and the decoder chases that target. Small drift (under
 * [DROP_BEHIND_MS]) is tolerated and being slightly ahead just waits. Normal
 * decoder lag is repaid by omitting stale frame uploads; explicit generation
 * changes and genuinely large drift reposition the demuxer.
 */
class VideoPlayer(
    screenName: String,
    val url: String,
    audioPosition: net.minecraft.world.phys.Vec3,
    audioDistance: Float,
    private val audioLanguage: String,
    private val onFirstFrame: ((durationMs: Long) -> Unit)? = null,
) : AutoCloseable {

    companion object {
        private const val DROP_BEHIND_MS = 100L
        private const val HARD_SEEK_MS = 2500L
        private const val POST_SEEK_GRACE_MS = 5_000L
        private val NEXT_ID = AtomicInteger()
    }

    private val debugName = screenName

    // Unique per instance so a replaced player never fights the old one for a
    // texture id.
    private val textureId: Identifier = Premiere.id(
        "video/" + screenName.lowercase().replace(Regex("[^a-z0-9_.-]"), "_") + "_" + NEXT_ID.incrementAndGet()
    )

    @Volatile
    private var anchorMediaMs = 0L

    @Volatile
    private var anchorLocalMs = System.currentTimeMillis()

    @Volatile
    private var playing = false

    @Volatile
    private var running = true

    @Volatile
    private var forceSeekRequested = false

    /**
     * A renderable frame belongs to a timeline epoch. A seek increments the
     * requested epoch immediately, so an old frame already in flight cannot
     * dismiss the loading indicator or flash after the seek.
     */
    private val requestedFrameEpoch = AtomicInteger()

    @Volatile
    private var publishedFrameEpoch = -1

    /** True until this timeline has produced a fresh video frame. */
    val isLoading: Boolean
        get() = publishedFrameEpoch != requestedFrameEpoch.get()

    // Frame handoff (decode thread -> render thread)
    private val frameLock = Any()
    private var frameBytes: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var pendingFrameTimestampMs = -1L
    private var hasNewFrame = false

    @Volatile
    private var displayedFrameTimestampMs = -1L

    // Render-thread only
    private var texture: DynamicTexture? = null
    private var textureWidth = 0
    private var textureHeight = 0

    val aspect: Float
        get() = synchronized(frameLock) {
            if (frameHeight > 0) frameWidth.toFloat() / frameHeight else 16f / 9f
        }

    private val thread = Thread.ofPlatform()
        .name("premiere-video-decode")
        .daemon()
        .start(::decodeLoop)

    /** False once the decode thread has died (decode error, bad URL, closed). */
    val isAlive: Boolean
        get() = running && thread.isAlive

    fun updateSync(mediaPositionMs: Long, playing: Boolean, forceSeek: Boolean = false) {
        val previousPlaying = this.playing
        // Every server observation refreshes the local master-clock anchor.
        // This is only a clock correction; the demuxer is repositioned below
        // only for an explicit generation seek or genuinely large drift.
        anchorMediaMs = mediaPositionMs
        anchorLocalMs = System.currentTimeMillis()
        this.playing = playing
        Premiere.LOGGER.info(
            "Premiere video diag [{}] sync position={}ms playing={} stateChanged={} forceSeek={} frameEpoch={}",
            debugName,
            mediaPositionMs,
            playing,
            previousPlaying != playing,
            forceSeek,
            requestedFrameEpoch.get(),
        )
        if (forceSeek) {
            requestedFrameEpoch.incrementAndGet()
            forceSeekRequested = true
            audio.flush()
        }
        audio.setPaused(!playing)
    }

    /** Untrimmed authoritative server timeline. */
    private fun rawMediaMs(): Long =
        if (playing) anchorMediaMs + (System.currentTimeMillis() - anchorLocalMs) else anchorMediaMs

    /**
     * Apply A/V trim by delaying whichever track is early. In particular, a
     * negative trim delays PCM instead of asking the shared demuxer to decode
     * picture into the future; that used to fill the audio FIFO and stall both
     * tracks. Neither timeline is allowed to run ahead of the server clock.
     */
    private fun videoMediaMs(rawMs: Long = rawMediaMs(), trimMs: Int = currentTrimMs()): Long =
        (rawMs - max(trimMs, 0)).coerceAtLeast(0)

    private fun audioMediaMs(rawMs: Long = rawMediaMs(), trimMs: Int = currentTrimMs()): Long =
        // Preserve a negative value near the start of a film: timestamp zero
        // must not become due until the requested audio delay has elapsed.
        rawMs + min(trimMs, 0)

    private fun currentTrimMs(): Int =
        dev.zephbyte.premiere.client.PremiereClientConfig.avSyncMs

    /** Presentation position used by the subtitle overlay. */
    fun currentMediaMs(): Long = videoMediaMs()

    private val subtitles = EmbeddedSubtitleTracks()

    // The soundtrack: our own OpenAL positional source, clock-locked here.
    private val audio = dev.zephbyte.premiere.client.audio.MovieAudio(
        audioPosition.x.toFloat(), audioPosition.y.toFloat(), audioPosition.z.toFloat(),
        audioDistance,
        debugName = debugName,
        serverClock = { rawMediaMs() },
        clock = { audioMediaMs() },
        videoClock = { videoMediaMs() },
        localVolume = { dev.zephbyte.premiere.client.PremiereClientConfig.movieVolume },
    )

    fun setVolume(value: Float) = audio.setVolume(value)

    fun hasEmbeddedSubtitles(): Boolean = subtitles.any()

    fun activeEmbeddedCue(positionMs: Long): SubtitleCue? = subtitles.activeCue(positionMs)

    private fun decodeLoop() {
        MediaUrls.validateResolved(url)?.let { error ->
            Premiere.LOGGER.warn("Not playing '{}': {}", url, error)
            return
        }
        var grabber: FFmpegFrameGrabber? = null
        try {
            val configure: (FFmpegFrameGrabber) -> Unit = {
                it.pixelFormat = avutil.AV_PIX_FMT_RGBA
                it.sampleRate = dev.zephbyte.premiere.client.audio.MovieAudio.SAMPLE_RATE
                it.audioChannels = 2
                it.sampleMode = org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT
            }
            grabber = FFmpegFrameGrabber(url).also { configure(it); it.start() }
            grabber = dev.zephbyte.premiere.client.audio.AudioTracks
                .reopenForLanguage(grabber, url, audioLanguage, configure)
            subtitles.discover(grabber)
            val wantSubtitles = subtitles.any()
            val hasAudio = grabber.audioChannels > 0 && grabber.audioStream >= 0
            val durationMs = grabber.lengthInTime / 1000
            this.durationMs = durationMs
            Premiere.LOGGER.info(
                "Premiere video diag [{}] decoder started duration={}ms video={}x{} audioChannels={} sampleRate={} audioStream={} format={}",
                debugName,
                durationMs,
                grabber.imageWidth,
                grabber.imageHeight,
                grabber.audioChannels,
                grabber.sampleRate,
                grabber.audioStream,
                grabber.format ?: "unknown",
            )
            var lastTsMs = 0L
            var lastAudioTsMs = -1L
            var decodeEpoch = requestedFrameEpoch.get()
            var diagnosticAtMs = 0L
            var eofLogAtMs = 0L
            var audioChunks = 0L
            var audioShorts = 0L
            var videoFrames = 0L
            var droppedVideoFrames = 0L
            var appliedTrimMs = currentTrimMs()
            var lastSeekAtMs = 0L
            while (running) {
                if (!playing && !forceSeekRequested && !isLoading) {
                    // A paused player normally holds its last frame. A newly
                    // loaded or late-joined player is the exception: keep
                    // decoding at the stationary target until one picture is
                    // published. That first picture completes /pm load and
                    // gives an already-paused screen something to display.
                    Thread.sleep(50)
                    continue
                }
                val diagnosticNow = System.currentTimeMillis()
                val rawClock = rawMediaMs().coerceAtLeast(0)
                val requestedTrimMs = currentTrimMs()
                val trimChanged = requestedTrimMs != appliedTrimMs
                val target = videoMediaMs(rawClock, requestedTrimMs)
                val audioTarget = audioMediaMs(rawClock, requestedTrimMs)
                if (diagnosticNow - diagnosticAtMs >= 2_000) {
                    diagnosticAtMs = diagnosticNow
                    Premiere.LOGGER.info(
                        "Premiere video diag [{}] playing={} loading={} rawClock={}ms avSync={}ms videoClock={}ms audioClock={}ms decodedVideoTs={}ms decodedVideoDelta={}ms displayedVideoTs={}ms displayedVideoDelta={}ms decodedAudioTs={}ms decodedAudioLead={}ms +audioChunks={} +audioShorts={} +videoFrames={} +videoDrops={}",
                        debugName,
                        playing,
                        isLoading,
                        rawClock,
                        requestedTrimMs,
                        target,
                        audioTarget,
                        lastTsMs,
                        lastTsMs - target,
                        displayedFrameTimestampMs,
                        if (displayedFrameTimestampMs < 0) -1 else displayedFrameTimestampMs - target,
                        lastAudioTsMs,
                        if (lastAudioTsMs < 0) -1 else lastAudioTsMs - audioTarget,
                        audioChunks,
                        audioShorts,
                        videoFrames,
                        droppedVideoFrames,
                    )
                    audioChunks = 0
                    audioShorts = 0
                    videoFrames = 0
                    droppedVideoFrames = 0
                }
                if (durationMs > 0 && target > durationMs + 1000) {
                    // Film over: hold the last frame, but stay alive — a replay
                    // of the same URL resets the target to ~0 and we seek back.
                    Thread.sleep(200)
                    continue
                }
                val serverSeek = forceSeekRequested
                val explicitSeek = serverSeek || trimChanged
                val driftMs = lastTsMs - target
                val driftSeekAllowed = diagnosticNow - lastSeekAtMs >= POST_SEEK_GRACE_MS
                if (explicitSeek || (abs(driftMs) > HARD_SEEK_MS && driftSeekAllowed)) {
                    forceSeekRequested = false
                    // One demuxer serves both tracks. Start at the earlier
                    // adjusted timeline so delayed audio or picture packets
                    // remain available instead of being skipped.
                    val demuxTarget = min(audioTarget, target).coerceAtLeast(0)
                    Premiere.LOGGER.info(
                        "Premiere video diag [{}] demux seek reason={} presentationTarget={}ms audioTarget={}ms demuxTarget={}ms previousVideoTs={}ms drift={}ms epoch={}",
                        debugName,
                        when {
                            trimChanged -> "av-sync"
                            explicitSeek -> "generation"
                            else -> "large-drift"
                        },
                        target,
                        audioTarget,
                        demuxTarget,
                        lastTsMs,
                        driftMs,
                        requestedFrameEpoch.get(),
                    )
                    // Explicit server seeks already advance the epoch in
                    // updateSync(). Large local drift advances it here so an
                    // old in-flight frame cannot flash after repositioning.
                    if (!serverSeek) {
                        requestedFrameEpoch.incrementAndGet()
                    }
                    decodeEpoch = requestedFrameEpoch.get()
                    audio.flush()
                    grabber.setTimestamp(demuxTarget * 1000, true)
                    lastTsMs = demuxTarget
                    appliedTrimMs = requestedTrimMs
                    lastSeekAtMs = diagnosticNow
                }
                // JavaCV's processing flag covers samples as well as images.
                // The old skim disabled it and silently discarded PCM. When
                // the whole demuxer is behind, request processed audio only;
                // JavaCV skips expensive video conversion until the audio
                // cursor reaches the picture target, then normal A/V grabbing
                // resumes at the next frame. If audio is already current, a
                // slightly old video timestamp alone must not trigger a skim.
                val demuxCursorMs = max(lastTsMs, lastAudioTsMs)
                val audioOnlyCatchUp = hasAudio && target - demuxCursorMs > DROP_BEHIND_MS
                val frame = grabber.grabFrame(true, !audioOnlyCatchUp, true, false, wantSubtitles)
                if (frame == null) {
                    val now = System.currentTimeMillis()
                    if (now - eofLogAtMs >= 5_000) {
                        eofLogAtMs = now
                        Premiere.LOGGER.warn(
                            "Premiere video diag [{}] grabFrame returned null target={}ms videoTs={}ms audioTs={}ms duration={}ms",
                            debugName,
                            target,
                            lastTsMs,
                            lastAudioTsMs,
                            durationMs,
                        )
                    }
                    // EOF (or a demuxer hiccup): wait for a restart/seek
                    // instead of dying with the last frame frozen on screen.
                    Thread.sleep(200)
                    continue
                }
                if (frame.samples != null) {
                    val samples = frame.samples?.getOrNull(0) as? java.nio.ShortBuffer
                    if (samples != null) {
                        val pcm = ShortArray(samples.remaining()).also { samples.duplicate().get(it) }
                        lastAudioTsMs = frame.timestamp / 1000
                        audioChunks++
                        audioShorts += pcm.size
                        audio.enqueue(lastAudioTsMs, pcm)
                    }
                    continue
                }
                if (frame.data != null) {
                    subtitles.collect(frame)
                    continue
                }
                lastTsMs = frame.timestamp / 1000
                if (frame.image == null) {
                    droppedVideoFrames++
                    continue
                }
                if (lastTsMs < target - DROP_BEHIND_MS) {
                    droppedVideoFrames++
                    continue // behind: drop
                }
                if (lastTsMs > target) {
                    Thread.sleep(min(lastTsMs - target, 200))
                }
                videoFrames++
                publish(frame, decodeEpoch)
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Throwable) {
            Premiere.LOGGER.error("Video decode failed for {}", url, e)
        } finally {
            runCatching { grabber?.stop(); grabber?.release() }
            Premiere.LOGGER.info("Premiere video diag [{}] decoder stopped running={}", debugName, running)
        }
    }

    private var firstFrameReported = false

    @Volatile
    private var durationMs = 0L

    private fun publish(frame: org.bytedeco.javacv.Frame, epoch: Int) {
        if (epoch != requestedFrameEpoch.get()) return
        if (!firstFrameReported) {
            firstFrameReported = true
            onFirstFrame?.invoke(durationMs)
        }
        val src = frame.image[0] as ByteBuffer
        val width = frame.imageWidth
        val height = frame.imageHeight
        val stride = frame.imageStride
        val rowBytes = width * 4
        synchronized(frameLock) {
            var out = frameBytes
            if (out == null || frameWidth != width || frameHeight != height) {
                out = ByteArray(rowBytes * height)
                frameBytes = out
                frameWidth = width
                frameHeight = height
            }
            val view = src.duplicate()
            if (stride == rowBytes) {
                view.position(0)
                view.get(out, 0, out.size)
            } else {
                for (row in 0 until height) {
                    view.position(row * stride)
                    view.get(out, row * rowBytes, rowBytes)
                }
            }
            pendingFrameTimestampMs = frame.timestamp / 1000
            hasNewFrame = true
            publishedFrameEpoch = epoch
        }
    }

    /**
     * Render thread only. Uploads a pending frame and returns the texture to
     * draw with, or null while no frame has been decoded yet.
     */
    fun textureForRender(): Identifier? {
        synchronized(frameLock) {
            val bytes = frameBytes ?: return null
            if (hasNewFrame) {
                if (texture == null || textureWidth != frameWidth || textureHeight != frameHeight) {
                    destroyTexture()
                    texture = DynamicTexture({ "premiere video $textureId" }, frameWidth, frameHeight, false)
                    textureWidth = frameWidth
                    textureHeight = frameHeight
                    Minecraft.getInstance().textureManager.register(textureId, texture!!)
                }
                val pixels = texture!!.pixels
                MemoryUtil.memByteBuffer(pixels.pointer, bytes.size).put(bytes)
                texture!!.upload()
                displayedFrameTimestampMs = pendingFrameTimestampMs
                hasNewFrame = false
            }
            return if (texture != null) textureId else null
        }
    }

    /** Render thread only. */
    fun destroyTexture() {
        if (texture != null) {
            Minecraft.getInstance().textureManager.release(textureId)
            texture = null
        }
    }

    override fun close() {
        running = false
        audio.close()
        thread.interrupt()
    }
}
