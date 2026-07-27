package dev.zephbyte.premiere.client.audio

import org.slf4j.LoggerFactory
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One screen's soundtrack: a positional OpenAL streaming source on the
 * client, fed 48kHz stereo PCM by the video decoder — the same connection,
 * the same master clock, so lips and voices cannot drift apart.
 *
 * Sync is structural: a continuous stream consumed by the sound card at
 * exactly realtime stays aligned forever once it *starts* aligned, so the
 * only active correction is at (re)alignment points — start, seek, and
 * underrun recovery — where stale chunks are dropped until the stream's
 * timestamps reach the clock again. [trimMs] (the A/V Sync setting) shifts
 * that alignment to absorb output-device latency (Bluetooth, OS mixer),
 * which no software can measure.
 *
 * All AL calls happen on this object's own thread, against Minecraft's
 * already-current context; MC's listener tracking makes the source
 * positional for free.
 */
class MovieAudio(
    private val x: Float,
    private val y: Float,
    private val z: Float,
    private val maxDistance: Float,
    /** Raw master-clock media position (no trim), ms. */
    private val clock: () -> Long,
    /** Positive = align audio earlier, to land on late output devices. */
    private val trimMs: () -> Int,
) : AutoCloseable {

    private class Chunk(val timestampMs: Long, val pcm: ShortArray)

    private val LOGGER = LoggerFactory.getLogger("premiere/audio")

    companion object {
        const val SAMPLE_RATE = 48000
        private const val BUFFER_COUNT = 12
        private const val STALE_TOLERANCE_MS = 60
    }

    private val queue = ArrayBlockingQueue<Chunk>(16)

    @Volatile
    private var running = true

    @Volatile
    private var paused = false

    @Volatile
    private var volume = 1f

    @Volatile
    private var flushRequested = false

    private val thread = Thread.ofPlatform()
        .name("premiere-audio-out")
        .daemon()
        .start(::runLoop)

    /**
     * Decode thread. Stale chunks (already behind the clock) are dropped
     * here so post-seek catch-up never blocks; live chunks block briefly
     * when the pipeline is full, which paces decoding at realtime.
     */
    fun enqueue(timestampMs: Long, samples: ShortArray) {
        if (timestampMs < alignedClock() - STALE_TOLERANCE_MS) return
        queue.offer(Chunk(timestampMs, samples), 300, TimeUnit.MILLISECONDS)
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    /** Seek: throw away everything buffered; the stale-drop realigns. */
    fun flush() {
        queue.clear()
        flushRequested = true
    }

    override fun close() {
        running = false
        queue.clear()
        thread.interrupt()
    }

    private fun alignedClock(): Long = clock() + trimMs()

    // All state below belongs to the audio thread only. The loop must never
    // die: Minecraft owns the OpenAL context and recreates it on sound-engine
    // reloads (shader/video-settings toggles, F3+T, output device changes),
    // and alGetError() is context-global, so MC's own transient errors can
    // surface in our reads. Both are treated as "rebuild and carry on" — the
    // stale-chunk alignment re-syncs playback to the clock afterwards.
    private var boundContext = 0L
    private var source = 0
    private var buffers = IntArray(0)
    private val free = ArrayDeque<Int>()
    private var scratch: ByteBuffer? = null
    private var errorStreak = 0

    private fun runLoop() {
        try {
            while (running) {
                if (!ensureSource()) {
                    Thread.sleep(250) // context missing or mid-reload; retry
                    continue
                }
                pump()
                Thread.sleep(5)
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Throwable) {
            LOGGER.error("Movie audio thread failed", e)
        } finally {
            releaseSource(deleteAlObjects = true)
        }
    }

    /** True when a valid source exists on the currently live context. */
    private fun ensureSource(): Boolean {
        val context = ALC10.alcGetCurrentContext()
        if (context == 0L) return false
        if (context == boundContext && source != 0) return true

        // Context replaced (or first run): old AL ids died with it.
        releaseSource(deleteAlObjects = false)
        AL10.alGetError() // clear whatever state the reload left behind
        val newSource = AL10.alGenSources()
        if (AL10.alGetError() != AL10.AL_NO_ERROR) return false
        val newBuffers = IntArray(BUFFER_COUNT) { AL10.alGenBuffers() }
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            runCatching { AL10.alDeleteSources(newSource) }
            return false
        }
        AL10.alSource3f(newSource, AL10.AL_POSITION, x, y, z)
        AL10.alSourcef(newSource, AL10.AL_REFERENCE_DISTANCE, 4f)
        AL10.alSourcef(newSource, AL10.AL_MAX_DISTANCE, maxDistance)
        AL10.alSourcef(newSource, AL10.AL_ROLLOFF_FACTOR, 1f)

        source = newSource
        buffers = newBuffers
        free.clear()
        newBuffers.forEach(free::add)
        boundContext = context
        errorStreak = 0
        LOGGER.debug("Movie audio source (re)created")
        return true
    }

    private fun releaseSource(deleteAlObjects: Boolean) {
        if (deleteAlObjects && source != 0 && ALC10.alcGetCurrentContext() == boundContext) {
            runCatching {
                AL10.alSourceStop(source)
                AL10.alDeleteSources(source)
                buffers.forEach(AL10::alDeleteBuffers)
            }
        }
        source = 0
        buffers = IntArray(0)
        free.clear()
    }

    private fun pump() {
        AL10.alGetError() // don't inherit other threads' context-global errors
        AL10.alSourcef(source, AL10.AL_GAIN, volume)

        if (flushRequested) {
            flushRequested = false
            AL10.alSourceStop(source)
            repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
                free.add(AL10.alSourceUnqueueBuffers(source))
            }
        }

        val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        if (paused) {
            if (state == AL10.AL_PLAYING) AL10.alSourcePause(source)
            Thread.sleep(10)
            return
        }
        if (state == AL10.AL_PAUSED) AL10.alSourcePlay(source)

        repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
            free.add(AL10.alSourceUnqueueBuffers(source))
        }

        while (free.isNotEmpty()) {
            val chunk = queue.poll(5, TimeUnit.MILLISECONDS) ?: break
            // Re-check staleness at feed time: after an underrun, seek, or
            // source rebuild, old content must be skipped, not played late.
            if (chunk.timestampMs < alignedClock() - STALE_TOLERANCE_MS) continue
            val bytes = chunk.pcm.size * 2
            var buffer = scratch
            if (buffer == null || buffer.capacity() < bytes) {
                buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                scratch = buffer
            }
            buffer.clear()
            buffer.asShortBuffer().put(chunk.pcm)
            buffer.limit(bytes)
            val alBuffer = free.poll()
            AL10.alBufferData(alBuffer, AL10.AL_FORMAT_STEREO16, buffer, SAMPLE_RATE)
            AL10.alSourceQueueBuffers(source, alBuffer)
        }

        val queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) -
            AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING && queued >= 2) {
            AL10.alSourcePlay(source)
        }

        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            // Could be ours, could be another thread's on the shared context.
            // Repeated errors mean our ids are bad — rebuild, never die.
            if (++errorStreak >= 3) {
                LOGGER.info("Movie audio recovering after OpenAL errors")
                releaseSource(deleteAlObjects = false)
            }
        } else {
            errorStreak = 0
        }
    }
}
