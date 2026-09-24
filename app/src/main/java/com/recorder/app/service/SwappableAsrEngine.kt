package com.recorder.app.service

import com.recorder.core.asr.AsrEngine
import com.recorder.core.asr.NoopAsrEngine
import com.recorder.core.audio.VoiceActivityDetector

/**
 * An [AsrEngine] whose implementation can be replaced while transcription is running.
 *
 * The recorder assembles its pipeline once and then keeps the microphone open all day. That
 * is the right trade — re-opening the mic is how audio gets lost — but it meant a speech
 * model downloaded after recording started was ignored until the user stopped and restarted
 * recording, which nothing in the app told them to do. Swapping the engine behind this
 * handle takes effect on the next segment, with no gap in the recording.
 */
class SwappableAsrEngine(initial: AsrEngine) : AsrEngine {

    @Volatile
    private var delegate: AsrEngine = initial

    override val name: String get() = delegate.name

    override suspend fun transcribe(audioChunk: FloatArray, sampleRate: Int): String =
        delegate.transcribe(audioChunk, sampleRate)

    /** True while this is the honest do-nothing engine, i.e. no model is loaded. */
    fun isNoop(): Boolean = delegate is NoopAsrEngine

    fun swap(next: AsrEngine) {
        val previous = delegate
        if (previous === next) return
        delegate = next
        runCatching { previous.close() }
    }

    override fun close() {
        runCatching { delegate.close() }
    }
}

/**
 * Passes every VAD decision through to [PipelineStats] on the way to the segmenter. Kept as
 * a wrapper rather than a hook inside the detectors so the audio module stays unaware of
 * how the app logs.
 */
class ObservedVad(
    private val delegate: VoiceActivityDetector,
    private val threshold: Float,
    private val stats: PipelineStats,
) : VoiceActivityDetector {

    override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
        val probability = delegate.speechProbability(frame, sampleRate)
        stats.onFrame(frame, probability, probability >= threshold)
        return probability
    }

    override fun reset() = delegate.reset()

    override fun close() = delegate.close()
}
