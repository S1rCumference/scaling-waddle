package com.recorder.app.service

import android.util.Log
import com.recorder.core.asr.AsrEngine
import com.recorder.core.audio.SpeechSegment
import com.recorder.core.llm.FolderClassifier
import com.recorder.core.llm.LlmProviderFactory
import com.recorder.core.storage.FolderDao
import com.recorder.core.storage.SegmentSource
import com.recorder.core.storage.TranscriptDao
import com.recorder.core.storage.TranscriptSegment
import com.recorder.core.storage.ensure

/**
 * What happens to one speech segment: transcribe, store, flag, file.
 *
 * Ordering matters. The row is written before anything slower runs, so a flagged keyword
 * or a stalled model can never cost you the transcript itself.
 */
class TranscriptPipeline(
    private val asr: AsrEngine,
    private val transcripts: TranscriptDao,
    private val folders: FolderDao,
    private val keywordWatcher: KeywordWatcher,
    private val providers: LlmProviderFactory,
) {

    private val classifier by lazy { FolderClassifier(providers.onDeviceSmallModel()) }

    suspend fun process(segment: SpeechSegment) {
        val text = runCatching { asr.transcribe(segment.samples, segment.sampleRate) }
            .getOrElse { error ->
                Log.w(TAG, "transcription failed", error)
                return
            }
        if (text.isBlank()) return

        val row = TranscriptSegment(
            startTs = segment.startTs,
            endTs = segment.endTs,
            text = text,
            source = SegmentSource.MIC,
        )
        val id = transcripts.insert(row)

        keywordWatcher.inspect(row.copy(id = id))
        assignFolder(row.copy(id = id))
    }

    private suspend fun assignFolder(segment: TranscriptSegment) {
        runCatching {
            val existing = folders.allOnce()
            val name = classifier.classify(segment, existing)
            transcripts.assignFolder(segment.id, folders.ensure(name))
        }.onFailure { Log.d(TAG, "folder assignment skipped: ${it.message}") }
    }

    private companion object {
        const val TAG = "TranscriptPipeline"
    }
}
