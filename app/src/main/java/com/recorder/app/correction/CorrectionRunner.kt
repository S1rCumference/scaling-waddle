package com.recorder.app.correction

import com.recorder.app.ServiceLocator
import com.recorder.core.llm.CorrectionWindow
import com.recorder.core.llm.DayVocabulary
import com.recorder.core.llm.TokenBudget
import com.recorder.core.llm.TranscriptCorrector
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.DayPass
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import com.recorder.core.storage.SegmentCorrection
import com.recorder.core.storage.TranscriptSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The correction layer: listen → transcribe → *correct*. The only AI feature in 3.0.
 *
 * Text only. Nothing here sees audio; it reads rows the recorder already wrote and appends
 * corrected versions beside them in `segment_corrections`. The original text is never
 * rewritten, so a bad correction costs nothing that cannot be seen and ignored.
 *
 * Two ways in, one at a time (a single lock, since they share one model slot):
 *  - [runDay]: the overnight pass over the day that just ended, run by [EndOfDayWorker];
 *  - [runRange]: "Correct this group", pressed in Logs.
 *
 * The live batches that used to run every three or fifteen minutes are gone. They were the
 * whole reason the phone was warm all day, and the work they did was the same work the
 * overnight pass does with the whole day for context.
 *
 * Every ceiling here is enforced in code rather than asked for: an input budget per batch, an
 * output budget per call, a wall clock on each batch, and a wall clock on the pass. A pass that
 * runs out of time stops and records why. Nothing retries.
 */
object CorrectionRunner {

    private const val TAG = "CorrectionRunner"

    /**
     * Input ceiling per batch, in estimated tokens. Counts the lines being corrected, the
     * context lines around them and the day's vocabulary, because those all go in the prompt.
     */
    const val MAX_INPUT_TOKENS = 1_200

    /** Never send one enormous line as a batch of one and never send a hundred tiny ones. */
    private const val MAX_LINES_PER_BATCH = 40

    /** Lines either side of a batch, sent as context but not corrected. */
    private const val CONTEXT_LINES = 6

    /** One batch. Hit this and the batch is abandoned, not retried. */
    private const val BATCH_DEADLINE_MS = 45_000L

    /** The whole pass, however many batches that is. */
    private const val PASS_DEADLINE_MS = 10 * 60 * 1000L

    /** Rough and deliberately so: four characters per token is close enough to budget with. */
    private const val CHARS_PER_TOKEN = 4

    /** One id, because only one correction runs at a time (they share the model slot). */
    private const val TASK = "correction"

    /** How far back the waiting-lines figure looks, for the number Settings shows. */
    private const val PENDING_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1000L

    private val lock = Mutex()

    private val _progress = MutableStateFlow<String?>(null)

    /** Human-readable progress of whatever is running, or null when idle. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val db get() = ServiceLocator.database

    /**
     * The overnight pass: every line of [dayKey] again, newest correction wins. The whole day
     * informs each batch through its recurring names and terms, and each batch sees the lines
     * around it.
     */
    suspend fun runDay(dayKey: Int): Int = lock.withLock {
        val segments = db.transcripts().onDay(dayKey)
        if (segments.isEmpty()) return@withLock 0
        val corrected = runBatches(segments, CorrectionPass.END_OF_DAY, "Overnight pass on ${label(dayKey)}")
        if (corrected > 0) {
            db.dayPasses().upsert(
                DayPass(
                    dayKey = dayKey,
                    coveredUntilTs = segments.last().startTs,
                    completedTs = System.currentTimeMillis(),
                    engine = ServiceLocator.correctionProvider.modelLabel,
                ),
            )
        }
        corrected
    }

    /** "Correct this group", pressed in Logs. The only on-demand path. */
    suspend fun runRange(fromTs: Long, toTs: Long): Int = lock.withLock {
        val segments = db.transcripts().inRange(fromTs, toTs)
        runBatches(segments, CorrectionPass.MANUAL, "Correcting this group")
    }

    /** How much text has never been corrected, for the figure Settings shows. */
    fun pendingCount(): Flow<Int> =
        db.transcripts().uncorrectedCount(System.currentTimeMillis() - PENDING_LOOKBACK_MS)

    /** Whether [dayKey] has speech the last overnight pass did not cover. */
    suspend fun dayNeedsPass(dayKey: Int): Boolean {
        val last = db.transcripts().lastStartOnDay(dayKey) ?: return false
        val pass = db.dayPasses().get(dayKey) ?: return true
        return last > pass.coveredUntilTs
    }

    /**
     * Estimated prompt tokens for a stretch of text. Deliberately crude: the point is a
     * ceiling that cannot be exceeded by much, not an exact count, and an exact count would
     * mean running the tokeniser on text that may never be sent.
     */
    internal fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Splits [items] into batches whose estimated tokens stay under [maxTokens], never longer
     * than [maxItems]. An item bigger than the whole budget still goes on its own rather than
     * being dropped: a line that long is rare, and losing it silently would be worse than one
     * oversized prompt the model can truncate.
     */
    internal fun <T> packByTokens(
        items: List<T>,
        maxTokens: Int,
        maxItems: Int,
        tokensOf: (T) -> Int,
    ): List<List<T>> {
        val batches = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var tokens = 0
        for (item in items) {
            val cost = tokensOf(item)
            val full = current.isNotEmpty() && (tokens + cost > maxTokens || current.size >= maxItems)
            if (full) {
                batches += current
                current = mutableListOf()
                tokens = 0
            }
            current += item
            tokens += cost
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private suspend fun runBatches(segments: List<TranscriptSegment>, pass: String, what: String): Int {
        if (segments.isEmpty()) return 0
        val vocabulary = DayVocabulary.extract(segments.map { it.text })

        // The scaffolding that goes in every prompt regardless of the batch: the system
        // prompt, the context lines and the vocabulary list. Budgeted for, so the ceiling is
        // a ceiling on what is actually sent rather than on the corrected lines alone.
        val overhead = SCAFFOLD_TOKENS + vocabulary.sumOf { estimateTokens(it) + 1 } +
            CONTEXT_LINES * 2 * AVERAGE_LINE_TOKENS
        val budget = (MAX_INPUT_TOKENS - overhead).coerceAtLeast(MIN_BATCH_TOKENS)

        val batches = packByTokens(segments, budget, MAX_LINES_PER_BATCH) { estimateTokens(it.text) }
        var total = 0
        var index = 0

        // Registered with the running job so Cancel stops the pass rather than hiding the bar
        // and leaving the model chewing through the rest of the batches.
        RunningTasks.start(
            TASK,
            what,
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        Diagnostics.i(
            TAG,
            "$what: ${segments.size} line(s) in ${batches.size} batch(es), " +
                "$budget input tokens per batch, ${TokenBudget.CORRECTION_MAX_TOKENS} out",
        )
        try {
            val finished = withTimeoutOrNull(PASS_DEADLINE_MS) {
                var start = 0
                for (targets in batches) {
                    index++
                    _progress.value = "$what: part $index of ${batches.size}"
                    RunningTasks.update(TASK, "part $index of ${batches.size}")
                    val before = segments
                        .subList((start - CONTEXT_LINES).coerceAtLeast(0), start)
                        .map { it.text }
                    val end = start + targets.size
                    val after = segments
                        .subList(end, (end + CONTEXT_LINES).coerceAtMost(segments.size))
                        .map { it.text }

                    // One batch, one wall clock. On expiry the batch is abandoned and the
                    // pass moves on; there is no retry, because a batch that could not be
                    // answered in forty-five seconds will not be answered in ninety.
                    val done = withTimeoutOrNull(BATCH_DEADLINE_MS) {
                        correctBatch(targets, before, after, vocabulary, pass)
                    }
                    when {
                        done == null -> {
                            lastError = "A batch ran past ${BATCH_DEADLINE_MS / 1000}s and was abandoned."
                            Diagnostics.w(TAG, "$what: part $index timed out; moving on")
                        }
                        // A model that cannot answer once will not answer for the rest either.
                        done < 0 -> return@withTimeoutOrNull false
                        else -> total += done
                    }
                    start = end
                }
                true
            }
            if (finished == null) {
                lastError = "The pass ran past ${PASS_DEADLINE_MS / 60_000} minutes and stopped."
                Diagnostics.w(TAG, "$what stopped at the ${PASS_DEADLINE_MS / 60_000}-minute ceiling")
            }
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK, "$total line(s) corrected")
            // The weights go the moment the pass ends, however it ended. This model exists
            // for one job that happens once a night; holding a gigabyte resident between
            // those is the opposite of what it is for.
            runCatching { ServiceLocator.correctionProvider.unload() }
        }
        return total
    }

    /** Returns lines stored, 0 if the model answered nothing usable, -1 if it is unavailable. */
    private suspend fun correctBatch(
        targets: List<TranscriptSegment>,
        before: List<String>,
        after: List<String>,
        vocabulary: List<String>,
        pass: String,
    ): Int {
        val provider = ServiceLocator.correctionProvider
        val startedAt = System.currentTimeMillis()
        val result = TranscriptCorrector(provider)
            .correct(CorrectionWindow(before, targets, after, vocabulary))
        val corrected = result.getOrElse { error ->
            Diagnostics.w(TAG, "correction skipped: ${error.message}")
            lastError = error.message
            return -1
        }
        lastError = null
        val now = System.currentTimeMillis()
        db.corrections().insertAll(
            corrected.map {
                SegmentCorrection(
                    segmentId = it.segmentId,
                    text = it.text,
                    pass = pass,
                    engine = provider.modelLabel,
                    createdTs = now,
                    uncertain = it.uncertain.joinToString("\u001f"),
                )
            },
        )
        val stillUnsure = corrected.sumOf { it.uncertain.size }
        Diagnostics.i(
            TAG,
            "${corrected.size} line(s) in ${"%.1f".format((now - startedAt) / 1000.0)}s" +
                if (stillUnsure > 0) ", $stillUnsure phrase(s) still unresolved" else "",
        )
        return corrected.size
    }

    /** Why the last attempt did nothing, for Settings. Null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    /** The system prompt and the shape around it, which every prompt pays for. */
    private const val SCAFFOLD_TOKENS = 260

    /** A transcript line is short; this is what the context allowance is costed at. */
    private const val AVERAGE_LINE_TOKENS = 20

    /** However heavy the scaffolding, a batch still gets room for a few lines. */
    private const val MIN_BATCH_TOKENS = 200

    private fun label(dayKey: Int): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(DayKey.startOf(dayKey)))
}
