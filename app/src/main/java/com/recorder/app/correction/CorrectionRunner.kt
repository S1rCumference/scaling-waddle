package com.recorder.app.correction

import android.content.Context
import com.recorder.app.ServiceLocator
import com.recorder.core.llm.CorrectionWindow
import com.recorder.core.llm.DayVocabulary
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

/**
 * The correction layer: listen → transcribe → *correct*.
 *
 * Text only. Nothing here sees audio; it reads rows the recorder already wrote and appends
 * corrected versions beside them in `segment_corrections`. The original text is never
 * rewritten, so a bad correction costs nothing that cannot be seen and ignored.
 *
 * Three ways in, one at a time (a single lock, since they share one model slot):
 *  - [runBatch]: a small window of new lines, every few minutes behind recording;
 *  - [runDay]: the overnight pass over a whole day, with the whole day informing each window;
 *  - [runRange]: re-run by hand on one group.
 */
object CorrectionRunner {

    private const val TAG = "CorrectionRunner"

    /** Lines per live batch. Small, so one batch is a few seconds of model time. */
    private const val BATCH_LINES = 20

    /** Only recent speech is picked up by the live batches; older days are the overnight pass's job. */
    private const val BATCH_LOOKBACK_MS = 24 * 60 * 60 * 1000L

    private const val LOCAL_WINDOW = 20
    private const val LOCAL_CONTEXT = 6
    private const val CLOUD_WINDOW = 120
    private const val CLOUD_CONTEXT = 30

    /** One id, because only one correction runs at a time (they share the model slot). */
    private const val TASK = "correction"

    private val lock = Mutex()

    private val _progress = MutableStateFlow<String?>(null)

    /** Human-readable progress of whatever is running, or null when idle. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val db get() = ServiceLocator.database

    /**
     * Corrects up to one window of recent uncorrected lines. With [drain], keeps going until
     * none are left (used while charging, when energy is free). Returns lines corrected.
     */
    suspend fun runBatch(drain: Boolean): Int = lock.withLock {
        val since = System.currentTimeMillis() - BATCH_LOOKBACK_MS
        var total = 0
        do {
            val targets = db.transcripts().uncorrected(since, BATCH_LINES)
            if (targets.isEmpty()) break
            val before = db.transcripts().before(targets.first().startTs, LOCAL_CONTEXT).asReversed()
            val done = correctWindow(targets, before.map { it.text }, emptyList(), emptyList(), CorrectionPass.BATCH)
            if (done <= 0) break
            total += done
        } while (drain)
        total
    }

    /**
     * The end-of-day pass: every line of [dayKey] again, newest correction wins. The full
     * day informs each window through its recurring names and terms, and each window sees
     * the lines around it.
     */
    suspend fun runDay(dayKey: Int): Int = lock.withLock {
        val segments = db.transcripts().onDay(dayKey)
        if (segments.isEmpty()) return@withLock 0
        val corrected = runWindows(segments, CorrectionPass.END_OF_DAY, "Overnight pass on ${label(dayKey)}")
        if (corrected > 0) {
            val chosen = ServiceLocator.providers.correctionProvider()
            db.dayPasses().upsert(
                DayPass(
                    dayKey = dayKey,
                    coveredUntilTs = segments.last().startTs,
                    completedTs = System.currentTimeMillis(),
                    engine = chosen.label,
                ),
            )
        }
        corrected
    }

    /** Re-runs correction on one group, by hand. */
    suspend fun runRange(fromTs: Long, toTs: Long): Int = lock.withLock {
        val segments = db.transcripts().inRange(fromTs, toTs)
        runWindows(segments, CorrectionPass.MANUAL, "Re-correcting")
    }

    /**
     * Everything outstanding, in one pass, wherever the request came from.
     *
     * Both the automatic path and the "process now" button land here, so there is one
     * definition of "do the work" rather than a scheduled trickle and a separate manual
     * route that behave differently. Draining is the point: a slice every quarter hour was
     * what made the phone hot, and finishing the backlog then stopping is cheaper than
     * never finishing it.
     */
    suspend fun runAllPending(context: Context, label: String): Int {
        if (CorrectionGate.tooHotForAnything(context)) {
            Diagnostics.w(TAG, "$label skipped: the phone is too hot")
            lastError = "The phone is too hot. Let it cool down and try again."
            return 0
        }
        val startedAt = System.currentTimeMillis()
        val done = runCatching { runBatch(drain = true) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Diagnostics.w(TAG, "$label failed", error)
                -1
            }
        val elapsed = System.currentTimeMillis() - startedAt
        if (done >= 0) {
            ServiceLocator.settings.recordCorrectionRun(elapsed, done)
            Diagnostics.i(
                TAG,
                "$label: $done line(s) in ${"%.1f".format(elapsed / 1000.0)}s",
            )
        }
        return done.coerceAtLeast(0)
    }

    /** How much text is waiting for a pass, for the backlog figure and the button. */
    fun pendingCount(): Flow<Int> =
        db.transcripts().uncorrectedCount(System.currentTimeMillis() - BATCH_LOOKBACK_MS)

    /** Whether [dayKey] has speech the last overnight pass did not cover. */
    suspend fun dayNeedsPass(dayKey: Int): Boolean {
        val last = db.transcripts().lastStartOnDay(dayKey) ?: return false
        val pass = db.dayPasses().get(dayKey) ?: return true
        return last > pass.coveredUntilTs
    }

    private suspend fun runWindows(segments: List<TranscriptSegment>, pass: String, what: String): Int {
        if (segments.isEmpty()) return 0
        val cloud = ServiceLocator.providers.correctionProvider().cloud
        val window = if (cloud) CLOUD_WINDOW else LOCAL_WINDOW
        val contextLines = if (cloud) CLOUD_CONTEXT else LOCAL_CONTEXT
        val vocabulary = DayVocabulary.extract(segments.map { it.text })

        val windows = segments.chunked(window)
        var total = 0
        // Registered with the running job so the Cancel button stops the pass rather than
        // hiding the bar and leaving the model chewing through the rest of the windows.
        RunningTasks.start(
            TASK,
            what,
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        Diagnostics.i(
            TAG,
            "$what: ${segments.size} line(s) in ${windows.size} window(s), " +
                "${if (cloud) "cloud" else "on device"}",
        )
        try {
            windows.forEachIndexed { index, targets ->
                _progress.value = "$what: part ${index + 1} of ${windows.size}"
                RunningTasks.update(TASK, "part ${index + 1} of ${windows.size}")
                val start = index * window
                val before = segments.subList((start - contextLines).coerceAtLeast(0), start).map { it.text }
                val end = start + targets.size
                val after = segments.subList(end, (end + contextLines).coerceAtMost(segments.size)).map { it.text }
                val done = correctWindow(targets, before, after, vocabulary, pass)
                // A model that cannot answer once will not answer for the rest either.
                if (done < 0) return total
                total += done
            }
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK, "$total line(s) corrected")
        }
        return total
    }

    /** Returns lines stored, 0 if the model answered nothing usable, -1 if it is unavailable. */
    private suspend fun correctWindow(
        targets: List<TranscriptSegment>,
        before: List<String>,
        after: List<String>,
        vocabulary: List<String>,
        pass: String,
    ): Int {
        val chosen = ServiceLocator.providers.correctionProvider()
        val startedAt = System.currentTimeMillis()
        val result = TranscriptCorrector(chosen.provider)
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
                    engine = chosen.label,
                    createdTs = now,
                    uncertain = it.uncertain.joinToString("\u001f"),
                )
            },
        )
        // Both passes, end to end, against the target. The per-pass token counts and timings
        // are logged by the engine itself, so this is the line that says whether the split
        // actually bought anything.
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

    private fun label(dayKey: Int): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(DayKey.startOf(dayKey)))
}
