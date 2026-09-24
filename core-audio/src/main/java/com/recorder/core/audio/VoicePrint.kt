package com.recorder.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Two cheap numbers about how a stretch of speech sounded, computed from samples the
 * recorder already has in hand.
 *
 * This is deliberately not diarization and will not become it by accident. There is no
 * model, no enrolment, no stored audio, and no claim about *who* was speaking — only a
 * crude "that sounded like someone else" so a two-person transcript reads as a conversation
 * rather than one continuous monologue. Real diarization replaces [SpeakerChange.between]
 * and nothing else.
 *
 *  - [levelDb] is loudness. Two people at the same table are rarely the same distance from
 *    the microphone.
 *  - [zeroCrossingRate] rises with the proportion of high-frequency energy, so it separates
 *    a low voice from a high one about as well as one number can.
 *
 * Both are one pass over the samples, which is nothing next to the decode that follows.
 */
data class VoicePrint(
    val levelDb: Float,
    val zeroCrossingRate: Float,
) {
    companion object {
        val NONE = VoicePrint(levelDb = 0f, zeroCrossingRate = 0f)

        fun of(samples: FloatArray): VoicePrint {
            if (samples.isEmpty()) return NONE
            var sum = 0.0
            var crossings = 0
            for (i in samples.indices) {
                val s = samples[i]
                sum += (s * s).toDouble()
                if (i > 0 && (s >= 0f) != (samples[i - 1] >= 0f)) crossings++
            }
            val rms = sqrt(sum / samples.size).toFloat()
            return VoicePrint(
                levelDb = 20f * log10(rms.coerceAtLeast(1e-7f)),
                zeroCrossingRate = crossings.toFloat() / samples.size,
            )
        }
    }
}

/**
 * Whether two consecutive segments probably came from different people.
 *
 * The bar is deliberately high. A false marker splits one person's sentence in half and
 * reads as nonsense; a missed one costs nothing except that the transcript stays as flat as
 * it is today. So a change needs a real pause *and* a real acoustic difference — one alone
 * is much more likely to be the same person pausing, or leaning back.
 */
object SpeakerChange {

    /** Below this the two segments are one breath apart and almost certainly one person. */
    const val MIN_GAP_MS = 900L

    /** A gap this long is a new situation regardless of how it sounded. */
    const val LONG_GAP_MS = 20_000L

    /** Decibels of difference that stop being mic distance and start being another mouth. */
    const val LEVEL_DELTA_DB = 6f

    /** Relative change in zero-crossing rate, which tracks how high the voice sits. */
    const val ZCR_DELTA_RATIO = 0.35f

    fun between(previous: VoicePrint, current: VoicePrint, gapMs: Long): Boolean {
        if (gapMs >= LONG_GAP_MS) return true
        if (gapMs < MIN_GAP_MS) return false
        if (previous == VoicePrint.NONE || current == VoicePrint.NONE) return false

        val levelMoved = abs(current.levelDb - previous.levelDb) >= LEVEL_DELTA_DB
        val base = maxOf(previous.zeroCrossingRate, 1e-4f)
        val pitchMoved = abs(current.zeroCrossingRate - previous.zeroCrossingRate) / base >= ZCR_DELTA_RATIO
        return levelMoved || pitchMoved
    }
}
