package com.recorder.core.audio

import kotlin.math.abs

/**
 * The safety net: loud audio that the detector never called speech is transcribed anyway.
 *
 * The policy this implements is deliberately lopsided. Transcribing a stretch of traffic
 * noise costs some decoder time and a line of nonsense that can be ignored; missing a real
 * conversation costs the thing the app exists for. So when a whole window goes by with
 * sound clearly present and not one frame over the detector's threshold, that window is
 * handed to the speech model regardless of what the detector thought.
 *
 * It only ever fires in the gap between real segments: any segment the detector does
 * produce clears the buffer, so nothing is transcribed twice.
 */
class OverCapture(
    /** How long to wait, with sound and no detected speech, before capturing anyway. */
    private val windowMs: Long = 20_000,
    /** Peak amplitude the window must reach. Below this the microphone heard nothing. */
    private val loudEnough: Float = 0.02f,
    /** A frame at or above this probability means the detector is working; reset the wait. */
    private val speechThreshold: Float = 0.5f,
) {
    private val buffered = ArrayList<AudioFrame>()
    private var peak = 0f
    private var sawSpeech = false

    /**
     * Offers one scored frame. Returns a segment when the window has gone by loud and
     * unrecognised, otherwise null.
     */
    fun accept(frame: AudioFrame, speechProbability: Float): SpeechSegment? {
        buffered.add(frame)
        for (sample in frame.samples) {
            val level = abs(sample)
            if (level > peak) peak = level
        }
        if (speechProbability >= speechThreshold) sawSpeech = true

        val spanMs = buffered.last().timestampMs - buffered.first().timestampMs
        if (spanMs < windowMs) return null

        // Long enough to judge. Either it was worth keeping or it was silence.
        val worthKeeping = peak >= loudEnough && !sawSpeech
        val segment = if (worthKeeping) build() else null
        clear()
        return segment
    }

    /** Called when the detector produced a segment of its own, so this window is spoken for. */
    fun onSegment() = clear()

    private fun clear() {
        buffered.clear()
        peak = 0f
        sawSpeech = false
    }

    private fun build(): SpeechSegment? {
        if (buffered.isEmpty()) return null
        val sampleRate = buffered.first().sampleRate
        val samples = FloatArray(buffered.sumOf { it.samples.size })
        var offset = 0
        for (f in buffered) {
            f.samples.copyInto(samples, offset)
            offset += f.samples.size
        }
        return SpeechSegment(
            samples = samples,
            sampleRate = sampleRate,
            startTs = buffered.first().timestampMs,
            endTs = buffered.last().timestampMs,
        )
    }
}
