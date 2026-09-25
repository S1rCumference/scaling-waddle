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
     * Transcript lines an hour's summary is built from.
     *
     * An hour of dense speech can be several hundred lines, which is more than a 1B model will
     * read usefully and more than the context window wants. The cap is on lines rather than
     * characters because the lines are short and evenly sized.
     */
    private const val MAX_SOURCE_LINES = 120

    /** Characters per source item, so one enormous line cannot fill the prompt by itself. */
    private const val MAX_SOURCE_CHARS = 400

    /** An hour with less than this in it is not a topic, it is a passing noise. */
    const val MIN_LINES_FOR_HOUR = 3

    /** The whole roll-up for one day: its hours, the day, and the month it falls in. */
    private const val DAY_ROLLUP_DEADLINE_MS = 12 * 60 * 1000L

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
            db.review().within(group.fromTs, group.toTs)
                .distinctBy { it.fromTs to it.toTs }
                .map { row ->
                    val part = row.asGroupSummary()
                    "${part.title}: ${part.body}".trim(':', ' ')
                }
                .filter { it.isNotBlank() }
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
     * it does not. The newest lines are the ones dropped when there are too many, because an
     * hour is usually named by how it started.
     */
    private suspend fun transcriptLines(group: GroupRef): List<String> {
        val segments = db.transcripts().inRange(group.fromTs, group.toTs)
        if (segments.isEmpty()) return emptyList()
        val corrections = db.corrections().forSegments(segments.map { it.id }).latestBySegment()
        return segments.take(MAX_SOURCE_LINES).map { segment ->
            (corrections[segment.id]?.text ?: segment.text).take(MAX_SOURCE_CHARS)
        }
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
