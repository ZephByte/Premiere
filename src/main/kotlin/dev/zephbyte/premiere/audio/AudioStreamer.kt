package dev.zephbyte.premiere.audio

import dev.zephbyte.premiere.Premiere
import dev.zephbyte.premiere.PremiereConfig
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.nio.ShortBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the audio track of the media at [url] into the exact shape Simple
 * Voice Chat wants: 48kHz 16-bit mono PCM in 20ms frames (960 samples). A
 * producer thread decodes ahead into a bounded queue (~1s) so network hiccups
 * don't starve SVC's realtime consumer; brief underruns are papered over with
 * silence rather than ending playback.
 *
 * Sync: SVC consumes one frame per 20ms from the moment the player starts, so
 * the only way the track stays on the master clock is to feed it exactly
 * right. Three mechanisms:
 *  - the decoder seeks only once the stream is open (connection time never
 *    becomes soundtrack delay), then discards frames until the *frame
 *    timestamps* reach the target — ffmpeg seeks land on the keyframe before
 *    the target, seconds early on films with sparse keyframes;
 *  - silence served during a mid-stream underrun is repaid by dropping the
 *    same number of real frames once the decoder catches up;
 *  - [estimatedPositionMs] exposes where the soundtrack actually is, so the
 *    server can audit drift against the master clock and rebuild if the two
 *    ever diverge for real.
 *
 * Streamed, never preloaded: a film's audio track would not fit in one array.
 */
class AudioStreamer(
    private val url: String,
    private val positionSource: () -> Long,
    private val volume: () -> Float,
    private val preferredLanguage: String = "",
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = 960 // 20ms at 48kHz
        private const val FRAME_MS = 20L
        private const val QUEUE_FRAMES = 50 // ~1s of decode-ahead
        private val END_MARKER = ShortArray(0)
    }

    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_FRAMES)

    @Volatile
    private var running = true

    @Volatile
    private var failed = false

    @Volatile
    private var streamReady = false

    @Volatile
    private var startAtMs = 0L

    /** Single-writer (SVC's consumer thread); volatile for the audit reader. */
    @Volatile
    private var servedFrames = 0L

    @Volatile
    private var owedSkips = 0

    @Volatile
    var finished = false
        private set

    private val thread = Thread.ofPlatform()
        .name("premiere-audio-decode")
        .daemon()
        .start(::decodeLoop)

    /**
     * Where the soundtrack actually is on the media timeline (including the
     * configured lead), or null before the stream is ready. Serving is
     * clocked by SVC at exactly 50 frames/sec, so this tracks wall time;
     * outstanding underrun debt is subtracted because those slots carried
     * silence, not media.
     */
    fun estimatedPositionMs(): Long? =
        if (!streamReady) null else startAtMs + (servedFrames - owedSkips) * FRAME_MS

    private fun configure(grabber: FFmpegFrameGrabber) {
        grabber.sampleRate = SAMPLE_RATE
        grabber.audioChannels = 1
        grabber.sampleMode = FrameGrabber.SampleMode.SHORT
    }

    /**
     * Opens the stream, honoring the preferred audio language on multi-audio
     * films. The wanted stream is only knowable after the container is open,
     * and stream selection is fixed at open time, so a language match costs
     * one reopen. ffmpeg's own default pick is unreliable (it ignores the
     * container's default flag), so an explicit preference is worth it.
     */
    private fun openGrabber(): FFmpegFrameGrabber {
        var grabber = FFmpegFrameGrabber(url).also { configure(it); it.start() }
        if (preferredLanguage.isNotBlank()) {
            val wanted = pickAudioStream(grabber, preferredLanguage)
            if (wanted >= 0) {
                runCatching { grabber.stop(); grabber.release() }
                grabber = FFmpegFrameGrabber(url).also {
                    configure(it)
                    it.audioStream = wanted // absolute stream index
                    it.start()
                }
            } else {
                Premiere.LOGGER.info("No '{}' audio track; using the file's default", preferredLanguage)
            }
        }
        return grabber
    }

    /** Absolute index of the best audio stream in [language], or -1. */
    private fun pickAudioStream(grabber: FFmpegFrameGrabber, language: String): Int {
        val context = grabber.formatContext ?: return -1
        var best = -1
        var bestScore = 0
        for (i in 0 until context.nb_streams()) {
            val stream = context.streams(i) ?: continue
            val par = stream.codecpar() ?: continue
            if (par.codec_type() != org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO) continue
            val streamLanguage = org.bytedeco.ffmpeg.global.avutil
                .av_dict_get(stream.metadata(), "language", null, 0)
                ?.value()?.string?.lowercase() ?: ""
            if (!streamLanguage.startsWith(language)) continue
            var score = 10
            if (stream.disposition() and org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT != 0) score += 5
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun decodeLoop() {
        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = openGrabber()
            // Seek to where the film is *now* (opening the URL above may have
            // taken a while), plus the lead that offsets SVC's own latency.
            val startAt = positionSource() + PremiereConfig.audioLeadMs
            startAtMs = startAt
            var skipUntilMicros = 0L
            if (startAt > 0) {
                grabber.timestamp = startAt * 1000
                skipUntilMicros = startAt * 1000
            }

            val pending = ShortArray(FRAME_SAMPLES)
            var pendingFill = 0
            while (running) {
                val frame = grabber.grabSamples() ?: break
                // ffmpeg seeks to the keyframe *before* the target; discard by
                // the frame's own timestamp (grabber.timestamp reads back the
                // requested value right after a seek, so it can't be trusted
                // here) until the target is actually reached.
                if (frame.timestamp < skipUntilMicros) continue
                if (!streamReady) streamReady = true
                val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
                while (samples.hasRemaining() && running) {
                    val n = minOf(samples.remaining(), FRAME_SAMPLES - pendingFill)
                    samples.get(pending, pendingFill, n)
                    pendingFill += n
                    if (pendingFill == FRAME_SAMPLES) {
                        offer(pending.copyOf())
                        pendingFill = 0
                    }
                }
            }
            streamReady = true // a no-audio file must still be able to finish
            if (pendingFill > 0) offer(pending.copyOf()) // trailing partial frame, zero-padded
        } catch (e: Throwable) {
            Premiere.LOGGER.error("Audio decode failed for {}", url, e)
            failed = true
        } finally {
            runCatching { grabber?.stop(); grabber?.release() }
            offer(END_MARKER)
        }
    }

    private fun offer(frame: ShortArray) {
        while (running) {
            if (queue.offer(frame, 100, TimeUnit.MILLISECONDS)) return
        }
    }

    /**
     * SVC [java.util.function.Supplier] contract: one 960-sample frame per call,
     * null to end playback.
     */
    fun nextFrame(): ShortArray? {
        if (finished) return null
        var frame = queue.poll(5, TimeUnit.MILLISECONDS)
        if (frame == null) {
            if (failed) return null
            // Underruns before the stream is open sit *before* the seek point
            // and cost nothing; underruns after it delay the track and are
            // repaid below.
            if (streamReady) {
                owedSkips++
                servedFrames++
            }
            return ShortArray(FRAME_SAMPLES) // silence; decoder is catching up
        }
        while (owedSkips > 0 && frame !== END_MARKER && frame!!.isNotEmpty()) {
            val next = queue.poll() ?: break
            frame = next
            owedSkips--
        }
        if (frame === END_MARKER || frame!!.isEmpty()) {
            finished = true
            return null
        }
        servedFrames++
        val gain = volume().coerceIn(0f, 1f)
        if (gain < 0.999f) {
            for (i in frame.indices) {
                frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return frame
    }

    override fun close() {
        running = false
        queue.clear()
        thread.interrupt()
    }
}
