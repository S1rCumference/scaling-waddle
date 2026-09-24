package com.recorder.app.service

import com.recorder.core.storage.Diagnostics
import kotlin.math.abs

/**
 * A minute-by-minute account of what the audio pipeline is actually doing, written to
 * [Diagnostics] so it can be read on the phone.
 *
 * This exists because "it doesn't record audio" is four different bugs — no frames, silent
 * frames, a detector that never fires, or an engine that returns nothing — and they are
 * indistinguishable from the outside. One heartbeat line separates them.
 *
 * Written from the capture collector only and read from the heartbeat coroutine, so the
 * counters are volatile rather than locked: a heartbeat that misses a frame it was racing
 * does not matter.
 */
class PipelineStats {

    @Volatile private var frames = 0L
    @Volatile private var speechFrames = 0L
    @Volatile private var segments = 0L
    @Volatile private var transcribed = 0L
    @Volatile private var segmentAudioMs = 0L
    @Volatile private var decodeTimeMs = 0L
    @Volatile private var rescued = 0L
    @Volatile private var peak = 0f
    @Volatile private var bestProbability = 0f

    fun onFrame(samples: FloatArray, probability: Float, speaking: Boolean) {
        frames++
        var high = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > high) high = a
        }
        if (high > peak) peak = high
        if (probability > bestProbability) bestProbability = probability
        if (speaking) speechFrames++
    }

    /**
     * One finished segment: how long the speech was, how long decoding it took, and whether
     * it produced anything. The ratio of those two is what says "the speech model is slow"
     * rather than leaving it as a guess.
     */
    /** A window the detector never called speech, captured anyway. */
    fun onFallbackCapture() {
        rescued++
    }

    fun onSegment(audioMs: Long, decodeMs: Long, hadText: Boolean) {
        segments++
        segmentAudioMs += audioMs
        decodeTimeMs += decodeMs
        if (hadText) transcribed++
    }

    /** Writes one line and clears the window. Returns false when nothing has happened at all. */
    fun heartbeat(tag: String): Boolean {
        val f = frames
        val windowPeak = peak
        val windowBest = bestProbability
        val speech = speechFrames
        val segs = segments
        val text = transcribed
        val audioMs = segmentAudioMs
        val decodeMs = decodeTimeMs
        val saved = rescued
        frames = 0
        peak = 0f
        bestProbability = 0f
        speechFrames = 0
        segments = 0
        transcribed = 0
        segmentAudioMs = 0
        decodeTimeMs = 0
        rescued = 0

        if (f == 0L) {
            Diagnostics.w(tag, "no audio reached the recorder in the last minute")
            return true
        }

        val seconds = f * FRAME_MS / 1000
        val level = (windowPeak * 100).toInt()
        Diagnostics.i(
            tag,
            "last minute: ${seconds}s of audio, loudest ${level}% of full scale, " +
                "detector best ${"%.2f".format(windowBest)}, $speech speech frames, " +
                "$segs segment(s)${if (saved > 0L) " ($saved captured anyway)" else ""}, " +
                "$text transcribed" +
                if (segs > 0L) {
                    ", ${audioMs / 1000}s of speech decoded in ${decodeMs / 1000}s " +
                        "(${"%.1f".format(if (audioMs > 0) decodeMs.toDouble() / audioMs else 0.0)}x)"
                } else {
                    ""
                },
        )

        // The three diagnoses worth spelling out, so the log answers the question directly.
        when {
            windowPeak < SILENT_LEVEL -> Diagnostics.w(
                tag,
                "the microphone is delivering near-silence — audio is flowing but there is " +
                    "nothing in it. Check that no other app holds the mic.",
            )

            speech == 0L -> Diagnostics.w(
                tag,
                "there was sound but nothing was treated as speech — the detector's best " +
                    "score was ${"%.2f".format(windowBest)}" +
                    if (saved > 0L) {
                        ", so $saved window(s) were captured anyway rather than discarded. " +
                            "Settings → Microphone sensitivity has a live meter for tuning this."
                    } else {
                        ". Settings → Microphone sensitivity has a live meter for tuning this."
                    },
            )

            segs > 0L && text == 0L -> Diagnostics.w(
                tag,
                "speech was detected but produced no text. $segs segment(s) went to the " +
                    "speech model and all came back empty. If the segments are long " +
                    "(${if (segs > 0) audioMs / segs / 1000 else 0}s each here) the detector is " +
                    "cutting on background noise rather than on speech.",
            )
        }
        return true
    }

    private companion object {
        /** 512 samples at 16 kHz. */
        const val FRAME_MS = 32L

        /** -46 dBFS. Below this a phone microphone is reporting a dead line, not a quiet room. */
        const val SILENT_LEVEL = 0.005f
    }
}
