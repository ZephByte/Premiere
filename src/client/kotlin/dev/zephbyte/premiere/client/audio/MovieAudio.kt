package dev.zephbyte.premiere.client.audio

import dev.zephbyte.premiere.Premiere
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

    private fun runLoop() {
        if (ALC10.alcGetCurrentContext() == 0L) {
            Premiere.LOGGER.warn("No OpenAL context; movie audio unavailable")
            return
        }
        val source = AL10.alGenSources()
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            Premiere.LOGGER.warn("Could not create an OpenAL source; movie audio unavailable")
            return
        }
        val buffers = IntArray(BUFFER_COUNT) { AL10.alGenBuffers() }
        val free = ArrayDeque<Int>().apply { buffers.forEach(::add) }
        try {
            AL10.alSource3f(source, AL10.AL_POSITION, x, y, z)
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4f)
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, maxDistance)
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1f)

            var scratch: ByteBuffer? = null
            while (running) {
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
                    continue
                }
                if (state == AL10.AL_PAUSED) AL10.alSourcePlay(source)

                repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
                    free.add(AL10.alSourceUnqueueBuffers(source))
                }

                while (free.isNotEmpty()) {
                    val chunk = queue.poll(5, TimeUnit.MILLISECONDS) ?: break
                    // Re-check staleness at feed time: after an underrun or
                    // seek, old content must be skipped, not played late.
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
                    Premiere.LOGGER.warn("OpenAL error during movie playback; stopping audio")
                    break
                }
                Thread.sleep(5)
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Throwable) {
            Premiere.LOGGER.error("Movie audio thread failed", e)
        } finally {
            runCatching {
                AL10.alSourceStop(source)
                repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
                    AL10.alSourceUnqueueBuffers(source)
                }
                AL10.alDeleteSources(source)
                buffers.forEach(AL10::alDeleteBuffers)
            }
        }
    }
}
