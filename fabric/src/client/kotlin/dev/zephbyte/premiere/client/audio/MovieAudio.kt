package dev.zephbyte.premiere.client.audio

import org.slf4j.LoggerFactory
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.SOFTSourceSpatialize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * One screen's soundtrack: a positional OpenAL streaming source on the
 * client, fed 48kHz stereo PCM by the video decoder. OpenAL Soft's explicit
 * spatial-stereo mode preserves both channels when available; other backends
 * fall back to mono because ordinary stereo OpenAL sources ignore position.
 * PCM retains its media
 * timestamp so catch-up and post-seek audio that is already behind the master
 * clock can be discarded instead of being played late.
 *
 * PCM is never dropped merely because it is early. A negative client A/V trim
 * intentionally builds a few seconds of audio delay, so the Java FIFO is
 * sized for the complete trim range and applies lossless backpressure. OpenAL
 * receives only a timestamp-contiguous run whose first sample is due.
 */
class MovieAudio(
    private val x: Float,
    private val y: Float,
    private val z: Float,
    private val maxDistance: Float,
    private val debugName: String,
    /** Unadjusted authoritative media position, for diagnostics. */
    private val serverClock: () -> Long,
    /** Media timestamp that should be reaching the audio device now. */
    private val clock: () -> Long,
    /** Media timestamp that should be visible now, for diagnostics. */
    private val videoClock: () -> Long,
    /** Per-player gain, independent of the staff-controlled source volume. */
    private val localVolume: () -> Float,
) : AutoCloseable {

    private class Chunk(val timestampMs: Long, val pcm: ShortArray) {
        val durationMs: Long = pcm.size.toLong() * 1_000L / (SAMPLE_RATE * 2L)
    }
    private class QueuedBuffer(val id: Int, val timestampMs: Long, val durationMs: Long) {
        val endTimestampMs: Long = timestampMs + durationMs
    }

    private val LOGGER = LoggerFactory.getLogger("premiere/audio")

    companion object {
        const val SAMPLE_RATE = 48000
        private const val BUFFER_COUNT = 12
        // 160 x 32 ms ~= 5.1 seconds, enough for the +/- 3 second client trim
        // plus normal decoder read-ahead without dropping interleaved audio.
        private const val PCM_QUEUE_CAPACITY = 160
        private const val STALE_TOLERANCE_MS = 60
        private const val START_EARLY_TOLERANCE_MS = 20
        private const val TIMESTAMP_GAP_TOLERANCE_MS = 8
        private const val DIAGNOSTIC_INTERVAL_MS = 2_000L
        private const val CONTEXT_WARNING_INTERVAL_MS = 5_000L
    }

    private val queue = ArrayBlockingQueue<Chunk>(PCM_QUEUE_CAPACITY)

    @Volatile
    private var running = true

    @Volatile
    private var paused = false

    @Volatile
    private var volume = 1f

    /** Audience-zone attenuation, calculated on the Minecraft client tick. */
    @Volatile
    private var distanceGain = 0f

    @Volatile
    private var flushRequested = false

    // Cross-thread diagnostic counters. These are intentionally aggregated so
    // an INFO log remains useful without printing once per audio packet.
    private val enqueuedChunks = AtomicLong()
    private val fedChunks = AtomicLong()
    private val staleAtEnqueue = AtomicLong()
    private val staleAtFeed = AtomicLong()
    private val backpressureEvents = AtomicLong()
    private val latestTimestampMs = AtomicLong(Long.MIN_VALUE)

    private val thread = Thread.ofPlatform()
        .name("premiere-audio-out")
        .daemon()
        .start(::runLoop)

    /**
     * Stale chunks never enter the FIFO. Live chunks wait losslessly when it
     * is full: dropping one would turn its timestamp gap into an audible jump
     * and make the output clock controller oscillate.
     */
    fun enqueue(timestampMs: Long, samples: ShortArray) {
        latestTimestampMs.set(timestampMs)
        if (timestampMs < alignedClock() - STALE_TOLERANCE_MS) {
            staleAtEnqueue.incrementAndGet()
            return
        }
        if (queue.remainingCapacity() == 0) backpressureEvents.incrementAndGet()
        queue.put(Chunk(timestampMs, samples))
        enqueuedChunks.incrementAndGet()
    }

    fun setPaused(value: Boolean) {
        if (paused != value) {
            LOGGER.info("Premiere audio diag [{}] paused={} fifo={} clock={}ms", debugName, value, queue.size, clock())
        }
        paused = value
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    fun setDistanceGain(value: Float) {
        distanceGain = value.coerceIn(0f, 1f)
    }

    /** Seek: throw away everything buffered; the stale-drop realigns. */
    fun flush() {
        LOGGER.info(
            "Premiere audio diag [{}] flush requested fifo={} clock={}ms latestPcm={}ms",
            debugName,
            queue.size,
            clock(),
            latestTimestampMs.get().takeUnless { it == Long.MIN_VALUE } ?: -1,
        )
        queue.clear()
        flushRequested = true
    }

    override fun close() {
        running = false
        queue.clear()
        thread.interrupt()
    }

    private fun alignedClock(): Long = clock()

    // All state below belongs to the audio thread. Minecraft can recreate its
    // OpenAL context on sound-engine reloads, so a missing or replaced context
    // is retried and the source is rebuilt without killing the decoder.
    private var boundContext = 0L
    private var source = 0
    private var buffers = IntArray(0)
    private val free = ArrayDeque<Int>()
    private val queuedTimeline = ArrayDeque<QueuedBuffer>()
    private var scratch: ByteBuffer? = null
    private var errorStreak = 0
    private var lastDiagnosticMs = 0L
    private var lastContextWarningMs = 0L
    private var lastSourceState = Int.MIN_VALUE
    private var lastSourceStateLogMs = 0L
    private var spatialStereo = false

    private fun runLoop() {
        try {
            while (running) {
                if (!ensureSource()) {
                    val now = System.currentTimeMillis()
                    if (now - lastContextWarningMs >= CONTEXT_WARNING_INTERVAL_MS) {
                        lastContextWarningMs = now
                        LOGGER.warn(
                            "Premiere audio diag [{}] no current OpenAL context on audio thread; fifo={} latestPcm={}ms",
                            debugName,
                            queue.size,
                            latestTimestampMs.get().takeUnless { it == Long.MIN_VALUE } ?: -1,
                        )
                    }
                    Thread.sleep(250)
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
        // Gain falloff is audience-shaped rather than OpenAL's spherical
        // model. Keep positioning/panning, but apply attenuation ourselves.
        AL10.alSourcef(newSource, AL10.AL_REFERENCE_DISTANCE, 1f)
        AL10.alSourcef(newSource, AL10.AL_MAX_DISTANCE, maxDistance)
        AL10.alSourcef(newSource, AL10.AL_ROLLOFF_FACTOR, 0f)
        spatialStereo = runCatching {
            if (!AL.getCapabilities().AL_SOFT_source_spatialize) return@runCatching false
            AL10.alSourcei(
                newSource,
                SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT,
                AL10.AL_TRUE,
            )
            AL10.alGetError() == AL10.AL_NO_ERROR
        }.getOrDefault(false)

        source = newSource
        buffers = newBuffers
        free.clear()
        newBuffers.forEach(free::add)
        boundContext = context
        errorStreak = 0
        LOGGER.info(
            "Premiere audio diag [{}] source created id={} context={} buffers={} format={} position=({}, {}, {}) maxDistance={}",
            debugName,
            source,
            java.lang.Long.toHexString(boundContext),
            buffers.size,
            if (spatialStereo) "stereo-spatialized" else "mono-fallback",
            x,
            y,
            z,
            maxDistance,
        )
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
        queuedTimeline.clear()
        spatialStereo = false
    }

    private fun pump() {
        AL10.alGetError() // don't inherit other threads' context-global errors
        AL10.alSourcef(
            source,
            AL10.AL_GAIN,
            (volume * localVolume() * distanceGain).coerceIn(0f, 1f),
        )

        if (flushRequested) {
            flushRequested = false
            AL10.alSourceStop(source)
            // Once stopped, every queued buffer belongs back in the free pool;
            // relying only on AL_BUFFERS_PROCESSED can strand buffers when a
            // seek arrives while the source is paused.
            repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)) {
                recycleBuffer(AL10.alSourceUnqueueBuffers(source))
            }
            LOGGER.info("Premiere audio diag [{}] OpenAL queue flushed free={}", debugName, free.size)
        }

        val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        if (paused) {
            if (state == AL10.AL_PLAYING) AL10.alSourcePause(source)
            return
        }

        if (state == AL10.AL_PAUSED) {
            AL10.alSourcePlay(source)
        }

        repeat(AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)) {
            recycleBuffer(AL10.alSourceUnqueueBuffers(source))
        }

        // Packets can become stale while waiting for their presentation time.
        // Remove them before deciding whether a recovery prebuffer is ready.
        while (true) {
            val head = queue.peek() ?: break
            if (head.timestampMs >= alignedClock() - STALE_TOLERANCE_MS) break
            queue.poll()
            staleAtFeed.incrementAndGet()
        }

        // Once an OpenAL source reaches STOPPED, every buffer queued on it is
        // reported as processed. Keep the first recovery chunk in our Java
        // FIFO until a second is available, then upload both in one pump and
        // explicitly restart the source below.
        val totalQueuedBeforeFill = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
        val firstPendingTimestamp = queue.peek()?.timestampMs
        val firstPendingIsDue = firstPendingTimestamp != null &&
            firstPendingTimestamp <= alignedClock() + START_EARLY_TOLERANCE_MS
        val waitingForRecoveryPrebuffer =
            state != AL10.AL_PLAYING && totalQueuedBeforeFill == 0 &&
                (queue.size < 2 || !firstPendingIsDue)
        if (!waitingForRecoveryPrebuffer) {
            while (free.isNotEmpty()) {
                val chunk = queue.peek() ?: break
                // Re-check after queueing: clock movement, a seek, or an
                // underrun may have made this chunk stale since enqueue().
                if (chunk.timestampMs < alignedClock() - STALE_TOLERANCE_MS) {
                    staleAtFeed.incrementAndGet()
                    queue.poll()
                    continue
                }
                // OpenAL has no timestamp awareness. Never queue across a
                // media-time gap or it will play the later chunk immediately;
                // wait until the current run drains and that chunk is due.
                val tail = queuedTimeline.peekLast()
                val contiguous = tail == null ||
                    chunk.timestampMs <= tail.endTimestampMs + TIMESTAMP_GAP_TOLERANCE_MS
                val dueAsNewRun = tail == null &&
                    chunk.timestampMs <= alignedClock() + START_EARLY_TOLERANCE_MS
                if (!contiguous || (tail == null && !dueAsNewRun)) break
                queue.poll()
                val frames = chunk.pcm.size / 2
                val bytes = if (spatialStereo) chunk.pcm.size * 2 else frames * 2
                var buffer = scratch
                if (buffer == null || buffer.capacity() < bytes) {
                    buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                    scratch = buffer
                }
                buffer.clear()
                val samples = buffer.asShortBuffer()
                if (spatialStereo) {
                    samples.put(chunk.pcm)
                } else {
                    // Fallback backends only spatialize mono. Average each
                    // L/R frame while preserving the same media duration.
                    for (frame in 0 until frames) {
                        val left = chunk.pcm[frame * 2].toInt()
                        val right = chunk.pcm[frame * 2 + 1].toInt()
                        samples.put(((left + right) / 2).toShort())
                    }
                }
                buffer.limit(bytes)
                val alBuffer = free.poll()
                AL10.alBufferData(
                    alBuffer,
                    if (spatialStereo) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16,
                    buffer,
                    SAMPLE_RATE,
                )
                AL10.alSourceQueueBuffers(source, alBuffer)
                queuedTimeline.add(QueuedBuffer(alBuffer, chunk.timestampMs, chunk.durationMs))
                fedChunks.incrementAndGet()
            }
        }

        val totalQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
        // Prebuffer two chunks so a freshly started source survives normal
        // scheduling jitter. Use the total—not queued minus processed—because
        // OpenAL reports all buffers as processed while a source is STOPPED.
        val queuedHeadIsDue = queuedTimeline.peekFirst()?.timestampMs?.let {
            it <= alignedClock() + START_EARLY_TOLERANCE_MS
        } == true
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING &&
            totalQueued >= 2 && queuedHeadIsDue
        ) {
            AL10.alSourcePlay(source)
        }

        val finalState = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        val processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        val sourceOffsetMs = (AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) * 1_000f).toLong()
        val outputMediaMs = queuedTimeline.peekFirst()?.let { it.timestampMs + sourceOffsetMs } ?: -1L
        reportSourceState(finalState, totalQueued, processed)
        reportDiagnostics(finalState, totalQueued, processed, sourceOffsetMs, outputMediaMs)

        val alError = AL10.alGetError()
        if (alError != AL10.AL_NO_ERROR) {
            LOGGER.warn(
                "Premiere audio diag [{}] OpenAL error={} streak={} state={} fifo={} alQueued={}",
                debugName,
                alError,
                errorStreak + 1,
                sourceStateName(finalState),
                queue.size,
                totalQueued,
            )
            // Could be ours, could be another thread's on the shared context.
            // Repeated errors mean our ids are bad — rebuild, never die.
            if (++errorStreak >= 3) {
                LOGGER.warn("Premiere audio diag [{}] rebuilding source after repeated OpenAL errors", debugName)
                releaseSource(deleteAlObjects = false)
            }
        } else {
            errorStreak = 0
        }
    }

    private fun reportSourceState(state: Int, queued: Int, processed: Int) {
        if (state == lastSourceState) return
        val now = System.currentTimeMillis()
        // Preserve meaningful transitions but cap rapid underrun oscillation.
        if (now - lastSourceStateLogMs >= 500) {
            LOGGER.info(
                "Premiere audio diag [{}] source {} -> {} paused={} fifo={} alQueued={} alProcessed={} free={}",
                debugName,
                sourceStateName(lastSourceState),
                sourceStateName(state),
                paused,
                queue.size,
                queued,
                processed,
                free.size,
            )
            lastSourceStateLogMs = now
        }
        lastSourceState = state
    }

    private fun reportDiagnostics(
        state: Int,
        queued: Int,
        processed: Int,
        sourceOffsetMs: Long,
        outputMediaMs: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnosticMs = now
        val rawClock = serverClock()
        val audioClock = alignedClock()
        val visibleClock = videoClock()
        val latest = latestTimestampMs.get()
        LOGGER.info(
            "Premiere audio diag [{}] state={} paused={} gain={} sourceVolume={} localVolume={} rawClock={}ms audioClock={}ms videoClock={}ms outputMedia={}ms outputVsAudio={}ms outputVsVideo={}ms sourceOffset={}ms latestPcm={}ms pcmLead={}ms fifo={}/{} alQueued={} alProcessed={} free={} +enqueued={} +fed={} +staleIn={} +staleFeed={} +backpressure={}",
            debugName,
            sourceStateName(state),
            paused,
            distanceGain,
            volume,
            localVolume(),
            rawClock,
            audioClock,
            visibleClock,
            outputMediaMs,
            if (outputMediaMs < 0) -1 else outputMediaMs - audioClock,
            if (outputMediaMs < 0) -1 else outputMediaMs - visibleClock,
            sourceOffsetMs,
            latest.takeUnless { it == Long.MIN_VALUE } ?: -1,
            if (latest == Long.MIN_VALUE) -1 else latest - audioClock,
            queue.size,
            PCM_QUEUE_CAPACITY,
            queued,
            processed,
            free.size,
            enqueuedChunks.getAndSet(0),
            fedChunks.getAndSet(0),
            staleAtEnqueue.getAndSet(0),
            staleAtFeed.getAndSet(0),
            backpressureEvents.getAndSet(0),
        )
    }

    private fun recycleBuffer(bufferId: Int) {
        free.add(bufferId)
        val expected = queuedTimeline.pollFirst() ?: return
        if (expected.id != bufferId) {
            LOGGER.warn(
                "Premiere audio diag [{}] OpenAL queue order mismatch expectedBuffer={} actualBuffer={}",
                debugName,
                expected.id,
                bufferId,
            )
            queuedTimeline.clear()
        }
    }

    private fun sourceStateName(state: Int): String = when (state) {
        AL10.AL_INITIAL -> "INITIAL"
        AL10.AL_PLAYING -> "PLAYING"
        AL10.AL_PAUSED -> "PAUSED"
        AL10.AL_STOPPED -> "STOPPED"
        Int.MIN_VALUE -> "NONE"
        else -> "UNKNOWN($state)"
    }
}
