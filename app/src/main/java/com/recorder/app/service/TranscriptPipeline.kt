package com.recorder.app.service

import com.recorder.core.storage.Diagnostics
import com.recorder.core.asr.AsrEngine
import com.recorder.core.audio.SpeechSegment
import com.recorder.core.audio.VoicePrint
import com.recorder.core.storage.SegmentSource
import com.recorder.core.storage.TranscriptDao
import com.recorder.core.storage.TranscriptSegment

/**
 * What happens to one speech segment: transcribe, store, flag.
 *
 * Everything here has to be affordable once per sentence, all day. Transcription is the
 * expensive step and is unavoidable; nothing else may be, and there is no model call on this
 * path at all. Automatic foldering used to run from here and has gone with the rest of the
 * features 3.0 dropped: a folder nobody asked for is not worth a classifier.
 *
 * Ordering matters. The row is written before anything else runs, so a flagged keyword can
 * never cost you the transcript itself.
 */
class TranscriptPipeline(
    private val asr: AsrEngine,
    private val transcripts: TranscriptDao,
    private val keywordWatcher: KeywordWatcher,
) {

    /** Returns true when the segment produced text that was stored. */
    suspend fun process(segment: SpeechSegment): Boolean {
        val startedAt = System.nanoTime()
        val text = runCatching { asr.transcribe(segment.samples, segment.sampleRate) }
            .getOrElse { error ->
                Diagnostics.w(TAG, "transcription failed", error)
                return false
            }
        PowerMetrics.recordTranscription(
            audioMs = segment.durationMs,
            cpuNanos = System.nanoTime() - startedAt,
        )
        lastSegmentAt = System.currentTimeMillis()
        if (text.isBlank()) return false

        // One pass over samples that are already in hand, before they are dropped. Doing it
        // here rather than later is the whole reason this stays cheap: the audio is gone by
        // the time anything else could ask.
        val voice = VoicePrint.of(segment.samples)
        val row = TranscriptSegment(
            startTs = segment.startTs,
            endTs = segment.endTs,
            text = text,
            source = SegmentSource.MIC,
            levelDb = voice.levelDb,
            zeroCrossingRate = voice.zeroCrossingRate,
        )
        val id = transcripts.insert(row)

        keywordWatcher.inspect(row.copy(id = id))
        return true
    }

    companion object {
        private const val TAG = "TranscriptPipeline"

        /** When speech was last decoded, so background work can wait for a pause. */
        @Volatile
        var lastSegmentAt: Long = 0L
            private set
    }
}
