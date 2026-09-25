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
 *  - [runRange]: "Correct", pressed on a group in Logs.
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
     * Input ceiling per batch, in estimated tokens.
     *
     * Deliberately large now. The model reads at hundreds of tokens a second and writes at
     * about eight, so the expensive half of a call is the answer, not the prompt — and the
     * answer is a short list of substitutions whatever the batch size. Reading more transcript
     * per call therefore costs almost nothing and saves a whole generation, which is why this
     * went up by a factor of two while the number of calls went down by four.
     *
     * The ceiling is what it is because the native context is 8192 tokens and holds up to
     * [MAX_TURNS_PER_LOAD] turns of history, so no single prompt may be more than a share of it.
     */
    const val MAX_INPUT_TOKENS = 2_400

    /** Never send one enormous line as a batch of one and never send four hundred tiny ones. */
    private const val MAX_LINES_PER_BATCH = 90

    /** One batch. Hit this and the batch is abandoned, not retried. */
    private const val BATCH_DEADLINE_MS = 60_000L

    /**
     * The whole pass, however many batches that is.
     *
     * Five minutes rather than ten, and not because passes got slower. Both entry points are
     * WorkManager workers now, so that the work survives leaving the screen — and a worker gets
     * about ten minutes before the system stops it. The overnight worker runs a correction pass
     * and then a summary roll-up, so the two ceilings have to add up to less than that with room
     * to spare. A pass is roughly four times faster than it was, so five minutes is more room
     * than the old ten gave.
     */
    private const val PASS_DEADLINE_MS = 5 * 60 * 1000L

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

    /** "Correct", pressed on a group in Logs. The only on-demand path. */
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

        // The scaffolding every prompt pays for regardless of the batch: the system prompt and
        // the vocabulary list. No context-line allowance any more — every line in a window is
        // context for every other line, because the whole window is read and none of it is
        // rewritten.
        val overhead = SCAFFOLD_TOKENS + vocabulary.sumOf { estimateTokens(it) + 1 }
        val budget = (MAX_INPUT_TOKENS - overhead).coerceAtLeast(MIN_BATCH_TOKENS)

        val batches = packByTokens(segments, budget, MAX_LINES_PER_BATCH) { estimateTokens(it.text) }
        var total = 0
        var fixesFound = 0
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
                for (targets in batches) {
                    index++
                    _progress.value = "$what: part $index of ${batches.size}"
                    RunningTasks.update(TASK, "part $index of ${batches.size}")

                    // One batch, one wall clock. On expiry the batch is abandoned and the pass
                    // moves on; there is no retry, because a batch that could not be answered
                    // in a minute will not be answered in two.
                    val done = withTimeoutOrNull(BATCH_DEADLINE_MS) {
                        correctBatch(targets, vocabulary, pass)
                    }
                    when {
                        done == null -> {
                            lastError = "A batch ran past ${BATCH_DEADLINE_MS / 1000}s and was abandoned."
                            Diagnostics.w(TAG, "$what: part $index timed out; moving on")
                        }
                        // A model that cannot answer once will not answer for the rest either.
                        done.lines < 0 -> return@withTimeoutOrNull false
                        else -> {
                            total += done.lines
                            fixesFound += done.fixes
                        }
                    }
                }
                true
            }
            if (finished == null) {
                lastError = "The pass ran past ${PASS_DEADLINE_MS / 60_000} minutes and stopped."
                Diagnostics.w(TAG, "$what stopped at the ${PASS_DEADLINE_MS / 60_000}-minute ceiling")
            }
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK, "$fixesFound word(s) fixed in $total line(s)")
            // The weights go the moment the pass ends, however it ended. This model exists
            // for one job that happens once a night; holding a gigabyte resident between
            // those is the opposite of what it is for.
            runCatching { ServiceLocator.correctionProvider.unload() }
        }
        return total
    }

    /** What one batch produced: lines stored, and how many distinct mishearings did it. */
    private data class BatchResult(val lines: Int, val fixes: Int)

    /**
     * Runs one batch. [BatchResult.lines] is -1 when the model is unavailable, which stops the
     * pass; zero lines with no error means the model read the batch and found nothing misheard,
     * which is the common and correct outcome rather than a failure.
     */
    private suspend fun correctBatch(
        targets: List<TranscriptSegment>,
        vocabulary: List<String>,
        pass: String,
    ): BatchResult {
        val provider = ServiceLocator.correctionProvider
        val startedAt = System.currentTimeMillis()
        val result = TranscriptCorrector(provider).correct(CorrectionWindow(targets, vocabulary))
        val corrected = result.getOrElse { error ->
            Diagnostics.w(TAG, "correction skipped: ${error.message}")
            lastError = error.message
            return BatchResult(-1, 0)
        }
        lastError = null
        val elapsed = System.currentTimeMillis() - startedAt

        if (corrected.isEmpty()) {
            Diagnostics.i(
                TAG,
                "${targets.size} line(s) read in ${"%.1f".format(elapsed / 1000.0)}s, " +
                    "nothing misheard",
            )
            return BatchResult(0, 0)
        }

        val now = System.currentTimeMillis()
        db.corrections().insertAll(
            corrected.map {
                SegmentCorrection(
                    segmentId = it.segmentId,
                    text = it.text,
                    pass = pass,
                    engine = provider.modelLabel,
                    createdTs = now,
                    // Nothing is left unresolved by this shape: a substitution either survived
                    // every guard and was applied, or it was thrown away.
                    uncertain = "",
                )
            },
        )
        val distinct = corrected.flatMap { line -> line.applied.map { it.wrong.lowercase() } }.distinct()
        Diagnostics.i(
            TAG,
            "${distinct.size} word(s) fixed in ${corrected.size} of ${targets.size} line(s) in " +
                "${"%.1f".format(elapsed / 1000.0)}s: " +
                corrected.flatMap { it.applied }.distinctBy { it.wrong.lowercase() }
                    .take(LOGGED_FIXES).joinToString(", ") { "${it.wrong} -> ${it.right}" },
        )
        return BatchResult(corrected.size, distinct.size)
    }

    /** Why the last attempt did nothing, for Settings. Null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    /** The system prompt and the shape around it, which every prompt pays for. */
    private const val SCAFFOLD_TOKENS = 300

    /** However heavy the scaffolding, a batch still gets room for a useful stretch. */
    private const val MIN_BATCH_TOKENS = 400

    /** Enough fixes in the log line to see what it did, without pasting the whole list. */
    private const val LOGGED_FIXES = 8

    private fun label(dayKey: Int): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(DayKey.startOf(dayKey)))
}
