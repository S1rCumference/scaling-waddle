package com.recorder.app.service

import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.FlaggedItemDao
import com.recorder.core.storage.RecorderSettings
import com.recorder.core.storage.TranscriptSegment

/**
 * Scans each new transcript row for the user's trigger phrases and records a flag.
 *
 * Matching is case-insensitive substring matching on purpose: ASR output has no reliable
 * punctuation, so "remind me" has to match inside "uh remind me to call him".
 */
class KeywordWatcher(
    private val flagged: FlaggedItemDao,
    private val settings: RecorderSettings,
) {

    suspend fun inspect(segment: TranscriptSegment) {
        val text = segment.text.lowercase()
        settings.triggerKeywordsNow()
            .filter { keyword -> keyword.isNotBlank() && keyword.lowercase() in text }
            .forEach { keyword ->
                flagged.insert(FlaggedItem(segmentId = segment.id, keyword = keyword))
            }
    }
}
