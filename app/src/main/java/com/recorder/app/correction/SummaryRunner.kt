package com.recorder.app.correction

import com.recorder.app.ServiceLocator
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.Role
import com.recorder.core.llm.SummaryPrompt
import com.recorder.core.llm.TokenBudget
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import com.recorder.core.storage.SummaryItem
import com.recorder.core.storage.latestBySegment
import com.recorder.core.storage.UserCorrection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Produces the reviewable items for a stretch of transcript, and remembers what the user
 * says about them.
 *
 * Separate from [CorrectionRunner] because it answers a different question — that one asks
 * "what was actually said", this one asks "what does it amount to" — but it runs on the
 * corrected text and inherits the uncertainty the draft pass admitted to, so an item built
 * out of lines the model was unsure about says so.
 */
object SummaryRunner {

    private const val TAG = "SummaryRunner"
    private const val TASK = "summary"

    /** How many past corrections go into a prompt. Beyond this they cost more than they save. */
    const val TAUGHT_IN_PROMPT = 12

    /** How many are kept at all. Old ones stop being about the vocabulary of today. */
    const val TAUGHT_KEPT = 100

    private val lock = Mutex()

    private val db get() = ServiceLocator.database

    /**
     * Rebuilds the items for one group. Replaces whatever was there, because a second run is
     * a re-read of the same stretch rather than more of it — but the user's own edits live
     * in the corrections list, so they survive the replacement and steer the new run.
     */
    suspend fun summarise(fromTs: Long, toTs: Long): Int = lock.withLock {
        val segments = db.transcripts().inRange(fromTs, toTs)
        if (segments.isEmpty()) return@withLock 0

        val corrections = db.corrections().forSegments(segments.map { it.id }).latestBySegment()
        val lines = segments.map { corrections[it.id]?.text ?: it.text }
        val taught = db.review().recentCorrections(TAUGHT_IN_PROMPT).map { it.wrong to it.corrected }

        val chosen = ServiceLocator.providers.correctionProvider()
        val startedAt = System.currentTimeMillis()
        val response = RunningTasks.track(TASK, "Summarising") {
            chosen.provider.complete(
                listOf(
                    ChatMessage(Role.SYSTEM, SummaryPrompt.SYSTEM),
                    ChatMessage(Role.USER, SummaryPrompt.build(lines, taught)),
                ),
                TokenBudget.SUMMARY,
            )
        }
        if (response.isError) {
            Diagnostics.w(TAG, "summary skipped: ${response.error}")
            lastError = response.error
            return@withLock 0
        }

        val items = SummaryPrompt.parse(response.text)
        if (items.isEmpty()) {
            lastError = "The model's answer had no list in it."
            return@withLock 0
        }
        lastError = null

        // Phrases nothing ever resolved, carried from the correction pass so an item built
        // on shaky lines is visibly shaky.
        val stillUnsure = corrections.values
            .flatMap { it.uncertainPhrases }
            .distinct()
            .take(8)

        db.review().clearIn(fromTs, toTs)
        db.review().insertItems(
            items.map {
                SummaryItem(
                    fromTs = fromTs,
                    toTs = toTs,
                    text = it,
                    uncertain = stillUnsure.joinToString("\u001f"),
                )
            },
        )
        Diagnostics.i(
            TAG,
            "${items.size} item(s) in ${"%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)}s",
        )
        return@withLock items.size
    }

    /**
     * Records that the AI got something wrong, so later passes are told about it.
     *
     * Both halves are useful: an edit teaches the right answer, and a bare flag at least
     * teaches that the claim was not true. Trimmed on every write rather than on a schedule,
     * so the list cannot quietly outgrow the prompt it feeds.
     */
    suspend fun remember(wrong: String, corrected: String) {
        if (wrong.isBlank()) return
        db.review().remember(UserCorrection(wrong = wrong.take(300), corrected = corrected.take(300)))
        db.review().trimTo(TAUGHT_KEPT)
        Diagnostics.i(TAG, "remembered a correction from the user")
    }

    /** Why the last attempt produced nothing, for the UI. */
    @Volatile
    var lastError: String? = null
        private set
}
