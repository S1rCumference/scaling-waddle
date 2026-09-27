package com.recorder.app.summary

import com.recorder.app.ServiceLocator
import com.recorder.app.ui.GroupKind
import com.recorder.app.ui.GroupRef
import com.recorder.core.llm.GroupSummary
import com.recorder.core.llm.SummaryLevel
import com.recorder.core.llm.SummaryResult
import com.recorder.core.llm.cloud.CloudProvider
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import com.recorder.core.storage.SummaryItem
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What each stretch of the day was about — an hour, the day it sits in, the week, the month.
 *
 * Generation is no longer on this phone. A 1B model at eight tokens a second could not do this
 * usefully: correcting a transcript by rewriting it took minutes and produced nothing, and
 * summarising overflowed an 8k context. A hosted model answers in a second or two, costs the
 * phone nothing but a network request, and is a free tier away rather than a gigabyte of weights.
 *
 * **What leaves the phone.** Transcript text, to the endpoint in Settings, only when a key is set
 * and summaries are switched on. Never audio: recognition stays on the device. This is the one
 * outbound path in the app and it is off until the user turns it on.
 *
 * The levels still roll up rather than re-read: an hour from its lines, a day from its hours, a
 * week from its days, a month from its weeks. That is what keeps a month one short request
 * instead of a month of transcript, and it is why the names on the calendar stay readable as the
 * span grows.
 */
object SummaryRunner {

    private const val TAG = "SummaryRunner"
    private const val TASK = "summary"

    /** Total characters of source one prompt may carry. Generous now it is not a 8k local context. */
    internal const val MAX_SOURCE_CHARS = 12_000

    /** Characters per source item, so one enormous line cannot fill the prompt by itself. */
    internal const val MAX_ITEM_CHARS = 400

    /** Fewer lines than this in an hour and it is passing noise, not a topic. */
    const val MIN_LINES_FOR_HOUR = 3

    /** Items needed to bother rolling up: one hour's summary already says it better than a "day". */
    private const val MIN_PARTS_FOR_ROLLUP = 2

    /** Fewer source items than this and shortening beats dropping. */
    private const val MIN_SOURCE_ITEMS = 12

    private val lock = Mutex()

    private val _progress = MutableStateFlow<String?>(null)

    /** What is being summarised, or null. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** Why the last attempt produced nothing. Null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    private val db get() = ServiceLocator.database
    private val settings get() = ServiceLocator.settings

    // --- configuration ---------------------------------------------------------------------

    /** The client for the configured provider, or null when there is nothing configured yet. */
    private suspend fun client(): CloudSummariser? {
        val provider = CloudProvider.byName(settings.summaryProvider.first().ifBlank { null })
        val key = settings.summaryApiKey.first()
        if (key.isBlank()) return null
        val base = settings.summaryBaseUrl.first().ifBlank { provider.baseUrl }
        val model = settings.summaryModel.first().ifBlank { provider.defaultModel }
        return CloudSummariser(provider, base, key, model)
    }

    /** Whether a rate limit has told us to wait, and we still should. */
    private suspend fun inBackoff(): Long {
        val until = settings.summaryBackoffUntil.first()
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // --- one group, because the user pressed the button --------------------------------------

    /**
     * Summarises [group] now, replacing any summary it had. Returns it, or null with [lastError]
     * set.
     *
     * A day, week or month first summarises whichever parts inside it have none, because rolling
     * up from nothing produces nothing.
     */
    suspend fun run(group: GroupRef): GroupSummary? = lock.withLock {
        val api = client() ?: run {
            lastError = "No API key yet. Settings → Summaries has a field for one."
            return@withLock null
        }
        RunningTasks.start(
            TASK,
            "Summarising ${group.title()}",
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        try {
            summarise(api, group, fillGaps = true)
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK)
        }
    }

    // --- the scheduled pass ------------------------------------------------------------------

    /**
     * Summarises up to [limit] spans that have none yet, oldest first, hours before days before
     * weeks before months. Returns how many were written.
     *
     * Called a few times a day rather than once at midnight, so a long day is caught up in
     * pieces and an hour is named while it is still worth reading. [limit] is what keeps one run
     * from spending a whole free-tier allowance in a burst.
     */
    suspend fun catchUp(limit: Int): Int = lock.withLock {
        if (!settings.summariesEnabled.first()) return@withLock 0
        val waiting = inBackoff()
        if (waiting > 0) {
            Diagnostics.i(TAG, "skipping: rate limited for another ${waiting / 1000}s")
            return@withLock 0
        }
        val api = client() ?: run {
            settings.setSummaryProblem("No API key. Settings → Summaries has a field for one.")
            return@withLock 0
        }

        val pending = pendingSpans().take(limit)
        if (pending.isEmpty()) return@withLock 0

        RunningTasks.start(
            TASK,
            "Summarising ${pending.size} span(s)",
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        var written = 0
        try {
            for ((index, group) in pending.withIndex()) {
                RunningTasks.update(TASK, "${index + 1} of ${pending.size}")
                // fillGaps is false: the ordering already does the filling, hours before days,
                // and a scheduled pass must not quietly turn into an unbounded recursion.
                if (summarise(api, group, fillGaps = false) != null) {
                    written++
                } else if (stopForNow) {
                    // A rate limit or a refusal. Either way, stop this run rather than working
                    // through the whole list collecting the same error.
                    break
                }
            }
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK, "$written summary(ies)")
        }
        if (written > 0) settings.setSummaryProblem("")
        Diagnostics.i(TAG, "catch-up wrote $written of ${pending.size} span(s)")
        written
    }

    /** Set when the last attempt failed in a way that means stop asking for now. */
    @Volatile
    private var stopForNow = false

    /**
     * Spans with speech in them and no summary, in the order they should be done: hours first,
     * because every wider level is built from them, then days, weeks and months.
     *
     * Only *finished* spans. The hour in progress is still filling, and summarising it would
     * produce a name for a third of an hour that is then never revisited.
     */
    internal suspend fun pendingSpans(): List<GroupRef> {
        val now = System.currentTimeMillis()
        val have = db.review().recent(SUMMARY_SCAN_LIMIT).first()
            .map { it.fromTs to it.toTs }
            .toSet()
        fun missing(group: GroupRef) = (group.fromTs to group.toTs) !in have && group.toTs <= now

        val days = db.transcripts().daySummaries().first()
        if (days.isEmpty()) return emptyList()

        val hours = days.flatMap { day ->
            val offset = TimeZone.getDefault().getOffset(DayKey.startOf(day.dayKey)).toLong()
            db.transcripts()
                .hourSummaries(DayKey.startOf(day.dayKey), DayKey.endOf(day.dayKey), offset)
                .first()
                .filter { it.count >= MIN_LINES_FOR_HOUR }
                .map { GroupRef.hour(it.firstTs) }
        }

        val weeks = days.map { GroupRef.week(DayKey.startOf(it.dayKey)) }.distinct()
        val months = days.map { GroupRef.month(it.dayKey / 100) }.distinct()
        val dayGroups = days.map { GroupRef.day(it.dayKey) }

        // Oldest first within each level: a name for last Tuesday is worth more than a second
        // attempt at this morning, and the wider levels need their parts to exist first.
        return (
            hours.filter(::missing).sortedBy { it.fromTs } +
                dayGroups.filter(::missing).sortedBy { it.fromTs } +
                weeks.filter(::missing).sortedBy { it.fromTs } +
                months.filter(::missing).sortedBy { it.fromTs }
            )
    }

    // --- the one place a summary is produced -------------------------------------------------

    private suspend fun summarise(
        api: CloudSummariser,
        group: GroupRef,
        fillGaps: Boolean,
    ): GroupSummary? {
        val level = levelOf(group) ?: return null
        _progress.value = "Summarising ${group.title()}"
        stopForNow = false

        val source = if (level.sourceIsSummaries) {
            if (fillGaps) fillMissingParts(api, group, level)
            thinToFit(partSummaries(group))
        } else {
            transcriptLines(group)
        }

        if (source.size < minimumSourceFor(level)) {
            lastError = when (level) {
                SummaryLevel.HOUR -> "Not enough said in this hour to summarise."
                else -> "Summarise the ${level.partLabel}s inside this first."
            }
            return null
        }

        return when (val result = api.summarise(level, spanInWords(group, level), source)) {
            is SummaryResult.Ok -> {
                store(group, result.summary)
                lastError = null
                result.summary
            }

            is SummaryResult.Backoff -> {
                val wait = result.retryAfterMs ?: DEFAULT_BACKOFF_MS
                settings.setSummaryBackoff(System.currentTimeMillis() + wait)
                settings.setSummaryProblem("Paused: ${result.reason}")
                lastError = "Paused for ${wait / 60_000} min: ${result.reason}"
                stopForNow = true
                Diagnostics.w(TAG, "${group.title()}: $lastError")
                null
            }

            is SummaryResult.Refused -> {
                settings.setSummaryProblem(result.reason)
                lastError = result.reason
                stopForNow = true
                Diagnostics.w(TAG, "${group.title()}: ${result.reason}")
                null
            }

            is SummaryResult.Unusable -> {
                lastError = result.reason
                Diagnostics.w(TAG, "${group.title()}: ${result.reason}")
                null
            }
        }
    }

    private suspend fun store(group: GroupRef, summary: GroupSummary) {
        // Replace rather than accumulate: a group has one summary and the newest wins.
        db.review().clearGroup(group.fromTs, group.toTs)
        db.review().insertItems(
            listOf(
                SummaryItem(
                    fromTs = group.fromTs,
                    toTs = group.toTs,
                    text = summary.stored(),
                    createdTs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /** The summaries of the spans strictly inside [group], newest per span, as prompt lines. */
    private suspend fun partSummaries(group: GroupRef): List<String> =
        db.review().within(group.fromTs, group.toTs)
            .distinctBy { it.fromTs to it.toTs }
            .map { row ->
                val part = row.asGroupSummary()
                "${part.title}: ${part.body}".trim(':', ' ')
            }
            .filter { it.isNotBlank() }

    /** Summarises the parts of [group] that have none yet, one level down. */
    private suspend fun fillMissingParts(api: CloudSummariser, group: GroupRef, level: SummaryLevel) {
        val have = db.review().within(group.fromTs, group.toTs).map { it.fromTs to it.toTs }.toSet()
        val parts = when (level) {
            SummaryLevel.DAY -> {
                val offset = TimeZone.getDefault().getOffset(group.fromTs).toLong()
                db.transcripts().hourSummaries(group.fromTs, group.toTs, offset).first()
                    .filter { it.count >= MIN_LINES_FOR_HOUR }
                    .map { GroupRef.hour(it.firstTs) }
            }

            SummaryLevel.WEEK, SummaryLevel.MONTH -> db.transcripts().daySummaries().first()
                .filter { it.firstTs >= group.fromTs && it.firstTs < group.toTs }
                .map { if (level == SummaryLevel.MONTH) GroupRef.week(it.firstTs) else GroupRef.day(it.dayKey) }
                .distinct()

            SummaryLevel.HOUR -> emptyList()
        }
        val missing = parts.filter { (it.fromTs to it.toTs) !in have }
        for ((index, part) in missing.withIndex()) {
            _progress.value = "Summarising part ${index + 1} of ${missing.size}"
            RunningTasks.update(TASK, "part ${index + 1} of ${missing.size}")
            summarise(api, part, fillGaps = level != SummaryLevel.DAY)
            if (stopForNow) return
        }
    }

    /**
     * The text an hour is summarised from: corrected where a correction exists from an older
     * release, original otherwise, thinned to fit.
     *
     * Thinned by sampling evenly across the span rather than by taking the first N lines, so a
     * busy hour is not named after its first ten minutes.
     */
    private suspend fun transcriptLines(group: GroupRef): List<String> {
        val segments = db.transcripts().inRange(group.fromTs, group.toTs)
        if (segments.isEmpty()) return emptyList()
        return thinToFit(segments.map { it.text.trim() }.filter { it.isNotEmpty() })
    }

    /** Cuts [lines] down to [MAX_SOURCE_CHARS], keeping the span covered end to end. */
    internal fun thinToFit(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        var kept = lines.map { it.take(MAX_ITEM_CHARS) }
        fun cost(items: List<String>) = items.sumOf { it.length + 3 }
        if (cost(kept) <= MAX_SOURCE_CHARS) return kept

        val stride = ((cost(kept).toDouble() / MAX_SOURCE_CHARS) + 0.999).toInt().coerceAtLeast(2)
        kept = kept.filterIndexed { index, _ -> index % stride == 0 }
        if (kept.size < MIN_SOURCE_ITEMS) {
            kept = lines.take(MIN_SOURCE_ITEMS).map { it.take(MAX_ITEM_CHARS) }
        }
        while (cost(kept) > MAX_SOURCE_CHARS && kept.isNotEmpty()) {
            val room = (MAX_SOURCE_CHARS / kept.size - 3).coerceAtLeast(20)
            val shorter = kept.map { it.take(room) }
            kept = if (shorter == kept) kept.dropLast(1) else shorter
        }
        return kept
    }

    private fun minimumSourceFor(level: SummaryLevel): Int =
        if (level == SummaryLevel.HOUR) MIN_LINES_FOR_HOUR else MIN_PARTS_FOR_ROLLUP

    /** Which level a group is, or null for spans that are not part of the roll-up. */
    internal fun levelOf(group: GroupRef): SummaryLevel? = when (group.kind) {
        GroupKind.HOUR -> SummaryLevel.HOUR
        GroupKind.DAY -> SummaryLevel.DAY
        GroupKind.WEEK -> SummaryLevel.WEEK
        GroupKind.MONTH -> SummaryLevel.MONTH
        // A hand-picked range and "everything" are export shapes, not calendar groups.
        GroupKind.RANGE, GroupKind.ALL -> null
    }

    /** The span in words, for the prompt: the model is told what it is reading. */
    internal fun spanInWords(group: GroupRef, level: SummaryLevel): String = when (level) {
        SummaryLevel.HOUR ->
            "${Clocks.date(group.fromTs)}, ${Clocks.shortTime(group.fromTs)} to ${Clocks.shortTime(group.toTs)}"
        SummaryLevel.DAY -> "the whole of ${Clocks.date(group.fromTs)}"
        SummaryLevel.WEEK -> "the week beginning ${Clocks.date(group.fromTs)}"
        SummaryLevel.MONTH -> "the whole of ${Clocks.monthAndYear(group.fromTs)}"
    }

    /** How far back the pending scan looks for existing summaries. */
    private const val SUMMARY_SCAN_LIMIT = 4_000

    /** How long to wait when rate limited and not told how long. */
    private const val DEFAULT_BACKOFF_MS = 60 * 60 * 1000L
}

/** The stored title-and-body form of a summary row. */
fun SummaryItem.asGroupSummary(): GroupSummary = GroupSummary.parseStored(display)
