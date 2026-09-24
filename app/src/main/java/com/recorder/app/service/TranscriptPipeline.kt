package com.recorder.app.service

import android.util.Log
import com.recorder.core.asr.AsrEngine
import com.recorder.core.audio.SpeechSegment
import com.recorder.core.llm.FolderClassifier
import com.recorder.core.storage.FolderDao
import com.recorder.core.storage.SegmentSource
import com.recorder.core.storage.TranscriptDao
import com.recorder.core.storage.TranscriptSegment
import com.recorder.core.storage.ensure

/**
 * What happens to one speech segment: transcribe, store, flag, and cheaply file.
 *
 * Everything here has to be affordable once per sentence, all day. Transcription is the
 * expensive step and is unavoidable; nothing else may be. In particular there is no model
 * call on this path — folder assignment uses pure string matching, and anything it cannot
 * place is left for [com.recorder.app.work.FolderFilingWorker] to batch.
 *
 * Ordering matters. The row is written before anything else runs, so a flagged keyword or a
 * slow classifier can never cost you the transcript itself.
 */
class TranscriptPipeline(
    private val asr: AsrEngine,
    private val transcripts: TranscriptDao,
    private val folders: FolderDao,
    private val keywordWatcher: KeywordWatcher,
) {

    suspend fun process(segment: SpeechSegment) {
        val startedAt = System.nanoTime()
        val text = runCatching { asr.transcribe(segment.samples, segment.sampleRate) }
            .getOrElse { error ->
                Log.w(TAG, "transcription failed", error)
                return
            }
        PowerMetrics.recordTranscription(
            audioMs = segment.durationMs,
            cpuNanos = System.nanoTime() - startedAt,
        )
        lastSegmentAt = System.currentTimeMillis()
        if (text.isBlank()) return

        val row = TranscriptSegment(
            startTs = segment.startTs,
            endTs = segment.endTs,
            text = text,
            source = SegmentSource.MIC,
        )
        val id = transcripts.insert(row)

        keywordWatcher.inspect(row.copy(id = id))

        // Only the obvious cases, by substring match. The rest stay unfiled on purpose.
        FolderClassifier.heuristicFolder(text)?.let { name ->
            runCatching { transcripts.assignFolder(id, folders.ensure(name)) }
                .onFailure { Log.d(TAG, "folder assignment skipped: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "TranscriptPipeline"

        /** When speech was last decoded, so background work can wait for a pause. */
        @Volatile
        var lastSegmentAt: Long = 0L
            private set
    }
}
