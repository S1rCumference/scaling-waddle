package com.recorder.core.audio

import kotlin.math.max

/**
 * A voice-activity detector that cannot silently stop working.
 *
 * Two failures are guarded, and they are the two that actually happened on a real phone:
 *
 *  - **It throws.** ONNX Runtime can load a model and still fail every inference — a shape
 *    mismatch, a missing operator, an unsupported build. [SileroVad] used to swallow that
 *    and return 0, so every frame looked like silence, no segment was ever opened, and
 *    nothing was ever transcribed. The app looked exactly like a broken microphone.
 *  - **It runs, and is always wrong.** A model that reports "no speech" for every frame is
 *    indistinguishable from a quiet room unless something else is listening. So an energy
 *    detector runs alongside it until the model proves itself by reporting speech once. If
 *    the energy detector hears several seconds of sound clearly above the room's noise
 *    floor and the model has never once come close to its threshold, the model is wrong.
 *
 * Either way this switches to [EnergyVad] for the rest of the session and says why. The
 * energy detector is what shipped in 0.2.0 and it transcribed fine, so falling back to it
 * is a return to a known-good state rather than a degraded mode.
 *
 * The audit costs one RMS pass over a 512-sample frame and stops the first time the model
 * reports speech, so in the normal case it is paid for a few seconds after start-up.
 */
class ResilientVad(
    primary: VoiceActivityDetector?,
    private val fallback: VoiceActivityDetector = EnergyVad(),
    private val threshold: Float = 0.5f,
    /** Called once, with a sentence explaining the switch, when the primary is dropped. */
    private val onFallback: (String) -> Unit = {},
) : VoiceActivityDetector {

    @Volatile
    private var primaryVad: VoiceActivityDetector? = primary

    @Volatile
    private var fallenBack: Boolean = primary == null

    /** Set only when a primary was dropped for misbehaving, so it is never re-adopted. */
    @Volatile
    private var droppedForFailure = false

    private var consecutiveFailures = 0
    private var loudFrames = 0L
    private var bestProbability = 0f
    private var primaryProven = false

    /** What is actually deciding right now, for the notification and diagnostics. */
    val activeName: String
        get() = if (fallenBack) "energy" else "Silero"

    /** True once the primary detector has been dropped for this session. */
    val usingFallback: Boolean get() = fallenBack

    /** The highest probability the primary ever reported, for diagnostics. */
    val primaryBest: Float get() = bestProbability

    override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
        val detector = primaryVad
        if (fallenBack || detector == null) return fallback.speechProbability(frame, sampleRate)

        val probability = try {
            detector.speechProbability(frame, sampleRate).also { consecutiveFailures = 0 }
        } catch (t: Throwable) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_FAILURES) {
                val why = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                switchTo("it failed $consecutiveFailures times in a row ($why)")
                return fallback.speechProbability(frame, sampleRate)
            }
            // A single hiccup is treated as silence, exactly as before, but it is now counted.
            return 0f
        }

        if (!probability.isFinite()) {
            switchTo("it returned $probability instead of a probability")
            return fallback.speechProbability(frame, sampleRate)
        }
        if (primaryProven) return probability

        bestProbability = max(bestProbability, probability)
        if (probability >= threshold) {
            // It works. Stop auditing and stop paying for the second detector.
            primaryProven = true
            return probability
        }

        // The fallback is consulted on every audited frame, which also keeps its noise floor
        // warm, so a switch does not begin with a detector that has never heard the room.
        if (fallback.speechProbability(frame, sampleRate) >= threshold) loudFrames++

        if (loudFrames >= LOUD_FRAMES_BEFORE_SWITCH && bestProbability < NEVER_CLOSE) {
            val seconds = loudFrames * frame.size / sampleRate.coerceAtLeast(1)
            switchTo(
                "about ${seconds}s of clear sound went by without it ever reporting speech " +
                    "(its highest score was ${"%.3f".format(bestProbability)}, it needs $threshold)",
            )
        }
        return probability
    }

    private fun switchTo(reason: String) {
        if (fallenBack) return
        fallenBack = true
        droppedForFailure = true
        onFallback("Speech detection switched to the energy fallback: $reason.")
        runCatching { primaryVad?.close() }
        primaryVad = null
    }

    /**
     * Adopts a detector that was not installed yet when recording started, so a model that
     * finishes downloading mid-session takes effect without the microphone being reopened.
     *
     * Refused once a primary has been dropped for misbehaving: a detector that has already
     * proven it does not work does not get a second turn in the same session.
     */
    fun adopt(next: VoiceActivityDetector): Boolean {
        if (droppedForFailure || primaryVad != null) return false
        loudFrames = 0
        bestProbability = 0f
        primaryProven = false
        consecutiveFailures = 0
        primaryVad = next
        fallenBack = false
        return true
    }

    override fun reset() {
        runCatching { primaryVad?.reset() }
        fallback.reset()
    }

    override fun close() {
        runCatching { primaryVad?.close() }
        runCatching { fallback.close() }
    }

    private companion object {
        /** Four in a row is a broken model; one is a hiccup worth riding out. */
        const val MAX_FAILURES = 4

        /** ~150 frames of 32 ms = about five seconds of sound above the noise floor. */
        const val LOUD_FRAMES_BEFORE_SWITCH = 150L

        /** Below this the model is not "unsure", it is not working. */
        const val NEVER_CLOSE = 0.10f
    }
}
