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
 * every silence frame we serve pushes the whole soundtrack 20ms later. Two
 * countermeasures keep the track on the master clock:
 *  - the decoder seeks to the *current* clock position (plus the configured
 *    audio lead) only once the stream is actually open, so connection time
 *    never becomes soundtrack delay;
 *  - silence served during a mid-stream underrun is repaid by dropping the
 *    same number of real frames once the decoder catches up.
 *
 * Streamed, never preloaded: a film's audio track would not fit in one array.
 */
class AudioStreamer(
    private val url: String,
    private val positionSource: () -> Long,
    private val volume: () -> Float,
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = 960 // 20ms at 48kHz
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
    private var finished = false

    /** Silence frames served after the stream was ready; repaid by skipping. */
    private var owedSkips = 0

    private val thread = Thread.ofPlatform()
        .name("premiere-audio-decode")
        .daemon()
        .start(::decodeLoop)

    private fun decodeLoop() {
        var grabber: FFmpegFrameGrabber? = null
        try {
            grabber = FFmpegFrameGrabber(url).apply {
                sampleRate = SAMPLE_RATE
                audioChannels = 1
                sampleMode = FrameGrabber.SampleMode.SHORT
                start()
            }
            // Seek to where the film is *now* (opening the URL above may have
            // taken a while), plus the lead that offsets SVC's own latency.
            val startAt = positionSource() + PremiereConfig.audioLeadMs
            if (startAt > 0) grabber.timestamp = startAt * 1000
            streamReady = true

            val pending = ShortArray(FRAME_SAMPLES)
            var pendingFill = 0
            while (running) {
                val frame = grabber.grabSamples() ?: break
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
            if (streamReady) owedSkips++
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
