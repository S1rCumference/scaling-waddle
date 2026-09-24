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
    private var loudRun = 0L
    private var longestLoudRun = 0L
    private var auditedFrames = 0L
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
        if (fallback.speechProbability(frame, sampleRate) >= threshold) {
            loudRun++
            if (loudRun > longestLoudRun) longestLoudRun = loudRun
        } else {
            loudRun = 0
        }

        auditedFrames++
        if (auditedFrames > AUDIT_FRAMES) {
            // Long enough. A detector that has run this long without tripping the test is
            // working; stop paying for a second detector on every frame.
            primaryProven = true
            return probability
        }

        if (auditedFrames >= MIN_AUDIT_FRAMES &&
            longestLoudRun >= LOUD_RUN_BEFORE_SWITCH &&
            bestProbability < NEVER_CLOSE
        ) {
            val seconds = longestLoudRun * frame.size / sampleRate.coerceAtLeast(1)
            switchTo(
                "${seconds}s of unbroken sound went by without it ever reporting speech " +
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
        loudRun = 0
        longestLoudRun = 0
        auditedFrames = 0
        bestProbability = 0f
        primaryProven = false
        consecutiveFailures = 0
        primaryVad = next
        fallenBack = false
        return true
    }

    /** Whatever the detector doing the work has to say about itself. */
    override fun note(): String? = if (fallenBack) fallback.note() else primaryVad?.note()

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

        /**
         * How much *unbroken* sound above the room's floor it takes to call the primary
         * detector broken: 937 frames of 32 ms, about thirty seconds.
         *
         * This counts a continuous run, not a total, and that distinction is the whole
         * point. The first version counted every loud frame in the session, and on a desk
         * with a mechanical keyboard it demoted a perfectly good Silero after four seconds:
         * typing is loud, so the energy detector flagged it, and Silero scored it 0.002
         * because it is not speech — which is the correct answer. Scattered clicks reset
         * the run; a person talking for ten seconds does not.
         */
        const val LOUD_RUN_BEFORE_SWITCH = 937L

        /**
         * And at least two minutes of audio before the question is even asked. Ten
         * seconds of unbroken sound demoted a detector nine seconds into a car
         * journey, where road noise never stops and the energy detector is useless
         * anyway. Demoting has to be a considered verdict, not a first impression.
         */
        const val MIN_AUDIT_FRAMES = 3_750L

        /** ~10 minutes of 32 ms frames. After this the primary has earned its place. */
        const val AUDIT_FRAMES = 18_750L

        /** Below this the model is not "unsure", it is not working. */
        const val NEVER_CLOSE = 0.10f
    }
}
