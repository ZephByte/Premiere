package dev.zephbyte.premiere.client.video

import dev.zephbyte.premiere.Premiere
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
import kotlin.math.min

/**
 * Decodes one URL on a background thread and hands finished RGBA frames to the
 * render thread, which owns the GPU texture.
 *
 * Sync model: the last server payload anchors a media position to the local
 * clock; the decoder chases that target. Small drift (under [DROP_BEHIND_MS])
 * is tolerated, being slightly ahead just waits, and anything past
 * [HARD_SEEK_MS] hard-seeks the demuxer instead of creeping.
 */
class VideoPlayer(
    screenName: String,
    val url: String,
    private val onFirstFrame: (() -> Unit)? = null,
) : AutoCloseable {

    companion object {
        private const val DROP_BEHIND_MS = 100L
        private const val HARD_SEEK_MS = 2500L
        private val NEXT_ID = AtomicInteger()
    }

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

    // Frame handoff (decode thread -> render thread)
    private val frameLock = Any()
    private var frameBytes: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var hasNewFrame = false

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

    fun updateSync(mediaPositionMs: Long, playing: Boolean) {
        anchorMediaMs = mediaPositionMs
        anchorLocalMs = System.currentTimeMillis()
        this.playing = playing
    }

    private fun targetMediaMs(): Long =
        if (playing) anchorMediaMs + (System.currentTimeMillis() - anchorLocalMs) else anchorMediaMs

    /** Master-clock media position; also drives the subtitle overlay. */
    fun currentMediaMs(): Long = targetMediaMs().coerceAtLeast(0)

    // Embedded text subtitles, collected as their packets stream in alongside
    // the video. Keyed by start time: naturally sorted, and re-decoded packets
    // after a hard seek simply overwrite themselves.
    private val embeddedCues =
        java.util.concurrent.ConcurrentSkipListMap<Long, dev.zephbyte.premiere.client.subtitles.SubtitleCue>()

    @Volatile
    private var hasSubtitleTrack = false

    fun hasEmbeddedSubtitles(): Boolean = hasSubtitleTrack

    fun activeEmbeddedCue(positionMs: Long): dev.zephbyte.premiere.client.subtitles.SubtitleCue? {
        val entry = embeddedCues.floorEntry(positionMs) ?: return null
        return if (positionMs < entry.value.endMs) entry.value else null
    }

    private fun decodeLoop() {
        MediaUrls.validateResolved(url)?.let { error ->
            Premiere.LOGGER.warn("Not playing '{}': {}", url, error)
            return
        }
        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = FFmpegFrameGrabber(url).apply {
                pixelFormat = avutil.AV_PIX_FMT_RGBA
                start()
            }
            val subtitleTrack = dev.zephbyte.premiere.client.subtitles.EmbeddedSubtitles.pickTrack(grabber)
            hasSubtitleTrack = subtitleTrack != null
            val durationMs = grabber.lengthInTime / 1000
            var lastTsMs = 0L
            while (running) {
                if (!playing && hasFrame()) {
                    // Paused with a frame on screen: idle cheaply, but keep
                    // checking in case of resume or a pause-position change.
                    if (abs(lastTsMs - targetMediaMs()) < DROP_BEHIND_MS) {
                        Thread.sleep(50)
                        continue
                    }
                }
                val target = targetMediaMs().coerceAtLeast(0)
                if (durationMs > 0 && target > durationMs + 1000) {
                    // Film over: hold the last frame, but stay alive — a replay
                    // of the same URL resets the target to ~0 and we seek back.
                    Thread.sleep(200)
                    continue
                }
                if (abs(lastTsMs - target) > HARD_SEEK_MS) {
                    grabber.setTimestamp(target * 1000, true)
                }
                // doData=true also surfaces subtitle packets from the same stream.
                val frame = grabber.grabFrame(false, true, true, false, subtitleTrack != null)
                if (frame == null) {
                    // EOF (or a demuxer hiccup): wait for a restart/seek
                    // instead of dying with the last frame frozen on screen.
                    Thread.sleep(200)
                    continue
                }
                if (frame.data != null) {
                    if (subtitleTrack != null) {
                        dev.zephbyte.premiere.client.subtitles.EmbeddedSubtitles.parsePacket(frame, subtitleTrack)
                            ?.let { cue -> embeddedCues[cue.startMs] = cue }
                    }
                    continue
                }
                if (frame.image == null) continue
                lastTsMs = frame.timestamp / 1000
                if (lastTsMs < target - DROP_BEHIND_MS) continue // behind: drop
                if (lastTsMs > target) {
                    Thread.sleep(min(lastTsMs - target, 200))
                }
                publish(frame)
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Throwable) {
            Premiere.LOGGER.error("Video decode failed for {}", url, e)
        } finally {
            runCatching { grabber?.stop(); grabber?.release() }
        }
    }

    private fun hasFrame(): Boolean = synchronized(frameLock) { frameBytes != null }

    private var firstFrameReported = false

    private fun publish(frame: org.bytedeco.javacv.Frame) {
        if (!firstFrameReported) {
            firstFrameReported = true
            onFirstFrame?.invoke()
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
            hasNewFrame = true
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
                if (pixels != null) {
                    MemoryUtil.memByteBuffer(pixels.pointer, bytes.size).put(bytes)
                    texture!!.upload()
                }
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
        thread.interrupt()
    }
}
