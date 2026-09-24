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
    @Volatile private var peak = 0f
    @Volatile private var bestProbability = 0f
    @Volatile private var reportedTotal = 0L

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

    fun onSegment(hadText: Boolean) {
        segments++
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
        frames = 0
        peak = 0f
        bestProbability = 0f
        speechFrames = 0
        segments = 0
        transcribed = 0
        reportedTotal += f

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
                "$segs segment(s), $text transcribed",
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
                    "score was ${"%.2f".format(windowBest)}. Lower the sensitivity in Settings " +
                    "if this keeps happening in a room where someone was talking.",
            )

            segs > 0L && text == 0L -> Diagnostics.w(
                tag,
                "speech was detected but produced no text — the speech model is missing or " +
                    "failing to decode.",
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
