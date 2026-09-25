package com.recorder.app.summary

import com.recorder.app.ServiceLocator
import com.recorder.app.ui.GroupKind
import com.recorder.app.ui.GroupRef
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.GroupSummary
import com.recorder.core.llm.Role
import com.recorder.core.llm.SummaryLevel
import com.recorder.core.llm.SummaryPrompt
import com.recorder.core.llm.TokenBudget
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import com.recorder.core.storage.SummaryItem
import com.recorder.core.storage.latestBySegment
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What each group in Logs was about — an hour, then the day it sits in, then the month.
 *
 * Every summary is one row in `summary_items` whose span is exactly the group's, which is why
 * no schema change was needed: that table already carried a from_ts/to_ts pair for precisely
 * this, and it survived 3.0 unused.
 *
 * The levels **roll up rather than re-read**. An hour is summarised from its transcript lines;
 * a day from its hours' summaries; a month from its days'. That is the only shape that fits on
 * a phone. A day is roughly a dozen paragraphs in and one out, and a month thirty — where
 * summarising a month from raw transcript would be tens of thousands of lines through a 1B
 * model, which is the class of thing that made the AI layer unusable before 3.0.
 *
 * Nothing here runs on its own during the day. The overnight pass calls [runDay], which does
 * the hours and then the day and then the month in one sitting on a charging, idle, cool phone;
 * everything else happens because the user pressed Summarise on a group.
 */
object SummaryRunner {

    private const val TAG = "SummaryRunner"

    /** One id: summarising shares the single model slot with correction. */
    private const val TASK = "summary"

    /**
     * Total characters of source a summary prompt may carry.
     *
     * This is the number that made summarising never finish. The previous version capped 120
     * lines at 400 characters each, which is up to 48,000 characters — roughly 12,000 tokens
     * against a native context of 8,192. The prompt overflowed the context, and an overflowed
     * context is not an error: it is a model reading a truncated prompt and answering slowly
     * and badly, with nothing anywhere saying why.
     *
     * 6,000 characters is about 1,500 tokens, which leaves the context most of its room even
     * after the system prompt and the answer.
     */
    internal const val MAX_SOURCE_CHARS = 6_000

    /** Characters per source item, so one enormous line cannot fill the prompt by itself. */
    internal const val MAX_ITEM_CHARS = 300

    /** Fewer than this and the shape of the hour is lost, so items are shortened rather than dropped. */
    private const val MIN_SOURCE_ITEMS = 12

    /** An hour with less than this in it is not a topic, it is a passing noise. */
    const val MIN_LINES_FOR_HOUR = 3

    /**
     * The whole roll-up for one day: its hours, the day, and the month it falls in.
     *
     * Sized to fit inside a WorkManager worker, which is stopped at about ten minutes — and to
     * leave room for the correction pass the overnight worker runs first. A day with twenty-four
     * busy hours needs more than this on a phone that generates at eight tokens a second, and
     * does not get it: it stores the hours it finished and says how far it got, and the next run
     * carries on from there rather than starting over.
     */
    private const val DAY_ROLLUP_DEADLINE_MS = 4 * 60 * 1000L

    private val lock = Mutex()

    private val _progress = MutableStateFlow<String?>(null)

    /** What is being summarised, or null when nothing is. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** Why the last attempt produced nothing, for the group screen. Null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Summaries stored by the run in progress, so a run that hits the ceiling can say how far it
     * got. Only ever touched under [lock], which is what makes a plain var safe here.
     */
    private var written = 0

    private val db get() = ServiceLocator.database

    // --- one group, because the user asked for it -------------------------------------------

    /**
     * Summarises [group] now, replacing any summary it already had. Returns the summary, or
     * null when there was nothing to summarise or the model would not answer.
     *
     * Summarising a day or a month first summarises whichever hours or days inside it have none
     * yet, because rolling up from nothing produces nothing.
     */
    suspend fun run(group: GroupRef): GroupSummary? = lock.withLock {
        RunningTasks.start(
            TASK,
            "Summarising ${group.title()}",
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        written = 0
        try {
            val result = withTimeoutOrNull(DAY_ROLLUP_DEADLINE_MS) { summarise(group, fillGaps = true) }
            if (result == null && lastError == null) {
                // Say what it got through. Summarising a month that has nothing under it yet is
                // hundreds of calls and will hit this ceiling — but the parts it finished are
                // stored, so pressing it again carries on rather than starting over, and saying
                // only "it stopped" would read as having achieved nothing.
                val ceiling = "${DAY_ROLLUP_DEADLINE_MS / 60_000} minutes"
                lastError = if (written > 0) {
                    "Stopped at $ceiling with $written part(s) done. Press Summarise again to carry on."
                } else {
                    "Summarising ran past $ceiling and stopped."
                }
                Diagnostics.w(TAG, "${group.title()}: $lastError")
            }
            result
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK)
            // Same rule as correction: the weights go the moment the work ends. A gigabyte
            // resident between two things that happen once a night is the opposite of the point.
            runCatching { ServiceLocator.correctionProvider.unload() }
        }
    }

    // --- the overnight roll-up ---------------------------------------------------------------

    /**
     * Summarises a whole day: each hour that has speech in it, then the day, then the month the
     * day falls in. Returns how many summaries were written.
     *
     * Called by the overnight worker after it has corrected the day, so the summaries are built
     * from corrected text rather than raw recognition.
     */
    suspend fun runDay(dayKey: Int): Int = lock.withLock {
        val day = GroupRef.day(dayKey)
        RunningTasks.start(
            TASK,
            "Summarising ${day.title()}",
            cancel = currentCoroutineContext()[Job]?.let { job -> { job.cancel() } },
        )
        written = 0
        try {
            val finished = withTimeoutOrNull(DAY_ROLLUP_DEADLINE_MS) {
                summariseHours(dayKey)
                summarise(day, fillGaps = false)
                // The month is rewritten every night rather than once at the end of it, so the
                // month row in Logs is never blank and never a month out of date.
                summarise(GroupRef.month(dayKey / 100), fillGaps = false)
                true
            }
            if (finished == null) {
                lastError = "The overnight roll-up ran past ${DAY_ROLLUP_DEADLINE_MS / 60_000} minutes."
                Diagnostics.w(TAG, "roll-up on $dayKey stopped at the ceiling after $written summary(ies)")
            }
            written
        } finally {
            _progress.value = null
            RunningTasks.finish(TASK, "$written summary(ies)")
            runCatching { ServiceLocator.correctionProvider.unload() }
        }
    }

    /** Every hour of [dayKey] with enough speech in it to be a topic. */
    private suspend fun summariseHours(dayKey: Int) {
        val offset = TimeZone.getDefault().getOffset(DayKey.startOf(dayKey)).toLong()
        val hours = db.transcripts()
            .hourSummaries(DayKey.startOf(dayKey), DayKey.endOf(dayKey), offset)
            .first()
            .filter { it.count >= MIN_LINES_FOR_HOUR }
        hours.forEachIndexed { index, hour ->
            _progress.value = "Summarising hour ${index + 1} of ${hours.size}"
            RunningTasks.update(TASK, "hour ${index + 1} of ${hours.size}")
            summarise(GroupRef.hour(hour.firstTs), fillGaps = false)
        }
    }

    // --- the one place a summary is produced -------------------------------------------------

    /**
     * Produces and stores the summary of one group.
     *
     * [fillGaps] makes a day or month first summarise the parts inside it that have none —
     * which is what "Summarise" on a group the user opened should do, and not what the
     * overnight pass should do, since it has just built those parts itself.
     */
    private suspend fun summarise(group: GroupRef, fillGaps: Boolean): GroupSummary? {
        val level = levelOf(group) ?: return null
        _progress.value = "Summarising ${group.title()}"

        val source = if (level.sourceIsSummaries) {
            if (fillGaps) fillMissingParts(group, level)
            thinToFit(
                db.review().within(group.fromTs, group.toTs)
                    .distinctBy { it.fromTs to it.toTs }
                    .map { row ->
                        val part = row.asGroupSummary()
                        "${part.title}: ${part.body}".trim(':', ' ')
                    }
                    .filter { it.isNotBlank() },
            )
        } else {
            transcriptLines(group)
        }

        if (source.size < minimumSourceFor(level)) {
            lastError = when (level) {
                SummaryLevel.HOUR -> "Not enough said in this hour to summarise."
                SummaryLevel.DAY -> "Summarise the hours inside this day first."
                SummaryLevel.MONTH -> "Summarise the days inside this month first."
            }
            return null
        }

        val provider = ServiceLocator.correctionProvider
        val startedAt = System.currentTimeMillis()
        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, SummaryPrompt.SYSTEM),
                ChatMessage(Role.USER, SummaryPrompt.build(level, spanInWords(group, level), source)),
            ),
            TokenBudget.forSummary(level.label),
        )
        if (response.isError) {
            lastError = response.error ?: "the model was unavailable"
            Diagnostics.w(TAG, "${group.title()}: $lastError")
            return null
        }

        val summary = SummaryPrompt.parse(response.text)
        if (summary.isBlank) {
            lastError = "The model answered nothing usable."
            Diagnostics.w(TAG, "${group.title()}: empty answer")
            return null
        }

        // Replace rather than accumulate: a group has one summary, and the newest wins.
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
        lastError = null
        written++
        Diagnostics.i(
            TAG,
            "${group.title()}: \"${summary.title}\" from ${source.size} source(s) in " +
                "${"%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)}s",
        )
        return summary
    }

    /** Summarises the hours of a day, or the days of a month, that have no summary yet. */
    private suspend fun fillMissingParts(group: GroupRef, level: SummaryLevel) {
        val have = db.review().within(group.fromTs, group.toTs).map { it.fromTs to it.toTs }.toSet()
        val parts = when (level) {
            SummaryLevel.DAY -> {
                val offset = TimeZone.getDefault().getOffset(group.fromTs).toLong()
                db.transcripts().hourSummaries(group.fromTs, group.toTs, offset).first()
                    .filter { it.count >= MIN_LINES_FOR_HOUR }
                    .map { GroupRef.hour(it.firstTs) }
            }

            SummaryLevel.MONTH -> db.transcripts().daySummaries().first()
                .filter { it.firstTs >= group.fromTs && it.firstTs < group.toTs }
                .map { GroupRef.day(it.dayKey) }

            SummaryLevel.HOUR -> emptyList()
        }
        val missing = parts.filter { (it.fromTs to it.toTs) !in have }
        missing.forEachIndexed { index, part ->
            _progress.value = "Summarising part ${index + 1} of ${missing.size}"
            RunningTasks.update(TASK, "part ${index + 1} of ${missing.size}")
            // One level of recursion: a month fills its days, and each day fills its hours,
            // which is where it stops, because an hour reads the transcript.
            summarise(part, fillGaps = level == SummaryLevel.MONTH)
        }
    }

    /**
     * The text an hour is summarised from: corrected where a correction exists, original where
     * it does not, thinned to fit [MAX_SOURCE_CHARS].
     *
     * Thinned by sampling evenly across the span rather than by taking the first N lines. The
     * first version took the first 120, which meant a busy hour was named after its first ten
     * minutes and the rest of it never reached the model at all — the summary was confidently
     * about the wrong thing.
     */
    private suspend fun transcriptLines(group: GroupRef): List<String> {
        val segments = db.transcripts().inRange(group.fromTs, group.toTs)
        if (segments.isEmpty()) return emptyList()
        val corrections = db.corrections().forSegments(segments.map { it.id }).latestBySegment()
        val lines = segments.map { (corrections[it.id]?.text ?: it.text).trim() }
            .filter { it.isNotEmpty() }
        return thinToFit(lines)
    }

    /**
     * Cuts [lines] down to [MAX_SOURCE_CHARS], keeping the span covered end to end.
     *
     * Takes every nth line so the sample is spread across the whole stretch, then shortens what
     * is left if it still does not fit. Shortening is the last resort rather than the first:
     * half of every line is worse input than all of every other line.
     */
    internal fun thinToFit(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        var kept = lines.map { it.take(MAX_ITEM_CHARS) }
        // Each item costs its own characters plus the "- " and newline around it.
        fun cost(items: List<String>) = items.sumOf { it.length + 3 }

        if (cost(kept) <= MAX_SOURCE_CHARS) return kept

        // Every nth line, spread across the span. Chosen so the result is under the ceiling in
        // one step rather than by repeated halving, which would overshoot on a long hour.
        val stride = ((cost(kept).toDouble() / MAX_SOURCE_CHARS) + 0.999).toInt().coerceAtLeast(2)
        kept = kept.filterIndexed { index, _ -> index % stride == 0 }
        if (kept.size < MIN_SOURCE_ITEMS) {
            kept = lines.take(MIN_SOURCE_ITEMS).map { it.take(MAX_ITEM_CHARS) }
        }

        // Still over — long lines rather than many of them. Shorten what is left, evenly.
        while (cost(kept) > MAX_SOURCE_CHARS && kept.isNotEmpty()) {
            val room = (MAX_SOURCE_CHARS / kept.size - 3).coerceAtLeast(20)
            val shorter = kept.map { it.take(room) }
            if (shorter == kept) {
                // Cannot shrink further without dropping items; drop the tail instead.
                kept = kept.dropLast(1)
            } else {
                kept = shorter
            }
        }
        return kept
    }

    /**
     * How many source items are worth summarising at each level.
     *
     * One hour does not make a day worth rolling up — the hour's own summary already says it
     * better — so a day needs two, and so does a month.
     */
    private fun minimumSourceFor(level: SummaryLevel): Int =
        if (level == SummaryLevel.HOUR) MIN_LINES_FOR_HOUR else 2

    /** Which level a group is, or null for spans that are not part of the roll-up. */
    internal fun levelOf(group: GroupRef): SummaryLevel? = when (group.kind) {
        GroupKind.HOUR -> SummaryLevel.HOUR
        GroupKind.DAY -> SummaryLevel.DAY
        GroupKind.MONTH -> SummaryLevel.MONTH
        // A hand-picked range and "everything" are export shapes, not calendar groups. There is
        // nowhere to show their summary and nothing above them to roll into.
        GroupKind.RANGE, GroupKind.ALL -> null
    }

    /** The span in words, for the prompt: the model is told what it is reading. */
    internal fun spanInWords(group: GroupRef, level: SummaryLevel): String = when (level) {
        SummaryLevel.HOUR ->
            "${Clocks.date(group.fromTs)}, ${Clocks.shortTime(group.fromTs)} to ${Clocks.shortTime(group.toTs)}"
        SummaryLevel.DAY -> "the whole of ${Clocks.date(group.fromTs)}"
        SummaryLevel.MONTH -> "the whole of ${Clocks.monthAndYear(group.fromTs)}"
    }
}

/** The stored title-and-body form of a summary row. */
fun SummaryItem.asGroupSummary(): GroupSummary = GroupSummary.parseStored(display)
