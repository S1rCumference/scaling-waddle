package com.recorder.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A contiguous run of speech, ready to hand to an ASR engine. */
data class SpeechSegment(
    val samples: FloatArray,
    val sampleRate: Int,
    val startTs: Long,
    val endTs: Long,
) {
    val durationMs: Long get() = endTs - startTs

    override fun equals(other: Any?): Boolean =
        this === other || (other is SpeechSegment && startTs == other.startTs && endTs == other.endTs)

    override fun hashCode(): Int = 31 * startTs.hashCode() + endTs.hashCode()
}

/**
 * Turns a stream of VAD-scored frames into speech segments.
 *
 * Frames are buffered for [preRollMs] even while silent, so a segment does not start
 * mid-syllable once the detector catches up. A segment closes after [hangoverMs] of
 * silence, or is force-cut at [maxSegmentMs] so a long monologue still reaches the
 * database instead of growing without bound.
 */
class SpeechSegmenter(
    private val threshold: Float = 0.5f,
    private val hangoverMs: Long = 700,
    private val minSpeechMs: Long = 300,
    private val maxSegmentMs: Long = 20_000,
    private val preRollMs: Long = 320,
) {
    private val preRoll = ArrayDeque<AudioFrame>()
    private val active = ArrayList<AudioFrame>()
    private var speaking = false
    private var lastSpeechTs = 0L
    private var segmentStartTs = 0L

    fun accept(frame: AudioFrame, speechProbability: Float): SpeechSegment? {
        val frameMs = frameMs(frame)
        val isSpeech = speechProbability >= threshold

        if (!speaking) {
            preRoll.addLast(frame)
            while (preRoll.size * frameMs > preRollMs && preRoll.isNotEmpty()) preRoll.removeFirst()

            if (isSpeech) {
                speaking = true
                active.addAll(preRoll)
                preRoll.clear()
                segmentStartTs = active.first().timestampMs
                lastSpeechTs = frame.timestampMs
            }
            return null
        }

        active.add(frame)
        if (isSpeech) lastSpeechTs = frame.timestampMs

        val silenceMs = frame.timestampMs - lastSpeechTs
        val lengthMs = frame.timestampMs - segmentStartTs
        return when {
            silenceMs >= hangoverMs -> finish(frame.timestampMs)
            lengthMs >= maxSegmentMs -> finish(frame.timestampMs, forceCut = true)
            else -> null
        }
    }

    /** Closes any in-flight segment, e.g. when the service is shutting down. */
    fun flush(nowTs: Long = System.currentTimeMillis()): SpeechSegment? =
        if (speaking) finish(nowTs) else null

    fun reset() {
        preRoll.clear()
        active.clear()
        speaking = false
    }

    private fun finish(endTs: Long, forceCut: Boolean = false): SpeechSegment? {
        val frames = ArrayList(active)
        active.clear()
        speaking = false

        // A force-cut segment continues, so keep the tail as the next segment's pre-roll.
        if (forceCut && frames.isNotEmpty()) {
            val frameMs = frameMs(frames.last())
            val keep = ((preRollMs / frameMs).toInt()).coerceAtMost(frames.size)
            preRoll.addAll(frames.subList(frames.size - keep, frames.size))
        }

        if (frames.isEmpty()) return null
        val spokenMs = endTs - segmentStartTs
        if (spokenMs < minSpeechMs) return null

        val sampleRate = frames.first().sampleRate
        val samples = FloatArray(frames.sumOf { it.samples.size })
        var offset = 0
        for (f in frames) {
            f.samples.copyInto(samples, offset)
            offset += f.samples.size
        }
        return SpeechSegment(samples, sampleRate, segmentStartTs, endTs)
    }

    private fun frameMs(frame: AudioFrame): Long =
        (frame.samples.size * 1000L / frame.sampleRate).coerceAtLeast(1)
}

/** Wires mic frames through a detector into speech segments. */
fun Flow<AudioFrame>.segmentSpeech(
    vad: VoiceActivityDetector,
    segmenter: SpeechSegmenter = SpeechSegmenter(),
): Flow<SpeechSegment> = flow {
    collect { frame ->
        val probability = vad.speechProbability(frame.samples, frame.sampleRate)
        segmenter.accept(frame, probability)?.let { emit(it) }
    }
    segmenter.flush()?.let { emit(it) }
}
