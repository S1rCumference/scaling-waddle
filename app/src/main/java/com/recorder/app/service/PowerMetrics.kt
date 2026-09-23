package com.recorder.app.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Counters behind the Power report screen.
 *
 * Deliberately in-memory and lock-free: this is measured on the recording hot path, so it
 * must cost close to nothing. The numbers reset when the process does, and the screen says
 * so — a persisted metric that needed a disk write per sentence would be the opposite of
 * the point.
 */
object PowerMetrics {

    private val startedAtMs = AtomicLong(0)
    private val transcriptions = AtomicLong(0)
    private val audioMsTranscribed = AtomicLong(0)
    private val asrCpuNanos = AtomicLong(0)
    private val modelLoads = AtomicLong(0)
    private val modelUnloads = AtomicLong(0)

    fun onRecordingStarted() {
        startedAtMs.set(System.currentTimeMillis())
    }

    fun recordTranscription(audioMs: Long, cpuNanos: Long) {
        transcriptions.incrementAndGet()
        audioMsTranscribed.addAndGet(audioMs)
        asrCpuNanos.addAndGet(cpuNanos)
    }

    fun onModelLoaded() {
        modelLoads.incrementAndGet()
    }

    fun onModelUnloaded() {
        modelUnloads.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        val started = startedAtMs.get()
        return Snapshot(
            micUptimeMs = if (started == 0L) 0 else System.currentTimeMillis() - started,
            transcriptions = transcriptions.get(),
            audioMsTranscribed = audioMsTranscribed.get(),
            asrCpuMs = asrCpuNanos.get() / 1_000_000,
            modelLoads = modelLoads.get(),
            modelUnloads = modelUnloads.get(),
        )
    }

    data class Snapshot(
        val micUptimeMs: Long,
        val transcriptions: Long,
        val audioMsTranscribed: Long,
        val asrCpuMs: Long,
        val modelLoads: Long,
        val modelUnloads: Long,
    ) {
        /**
         * Share of wall-clock time spent decoding speech. This is the number that decides
         * whether a day of listening fits in the battery: the microphone is cheap, the
         * decoder is not.
         */
        val asrDutyCyclePercent: Double
            get() = if (micUptimeMs <= 0) 0.0 else asrCpuMs * 100.0 / micUptimeMs

        /** How much of the day was actually speech, as heard by the VAD. */
        val speechSharePercent: Double
            get() = if (micUptimeMs <= 0) 0.0 else audioMsTranscribed * 100.0 / micUptimeMs
    }
}
