package com.recorder.core.llm


/**
 * What a group of transcript is about, in a name and a paragraph.
 *
 * The shape is the request: "the name, like the date, and it would be like the context." The
 * date the group already knows, so what the model is asked for is the name and the context —
 * and the name is the part that does the work, because a month of named hours is a list of
 * topics you can scan, while a month of paragraphs is a wall.
 */
data class GroupSummary(
    /** A few words. What this stretch of time would be called if it were a calendar entry. */
    val title: String,
    /** Two or three sentences on what was actually said. */
    val body: String,
) {
    val isBlank: Boolean get() = title.isBlank() && body.isBlank()

    /**
     * Title on the first line, body under it.
     *
     * One string because it is stored in one column: `summary_items.text` already exists and
     * already carries a from_ts/to_ts span, so a summary per group needs no new table and no
     * schema migration. [GroupSummary.parseStored] reads it back.
     */
    fun stored(): String = if (title.isBlank()) body else "$title\n$body"

    companion object {
        /**
         * The reverse of [stored], and lossless both ways.
         *
         * The split is on the first newline rather than on lines, so a title with no body
         * round-trips (it is stored with a trailing newline) and a body with no title — which
         * is what a row written before titles existed looks like — reads as a body.
         */
        fun parseStored(text: String): GroupSummary {
            val newline = text.indexOf('\n')
            if (newline < 0) return GroupSummary(title = "", body = text.trim())
            return GroupSummary(
                title = text.substring(0, newline).trim(),
                body = text.substring(newline + 1).trim(),
            )
        }
    }
}

/**
 * What a summary is being made of, and how wide a span it covers.
 *
 * The three levels roll up rather than each re-reading the transcript. An hour is summarised
 * from its lines; a day from its hours' summaries; a month from its days'. That is the only
 * version that fits on a phone: summarising a month from raw text would be tens of thousands
 * of lines through a 1B model, where summarising it from thirty paragraphs is one prompt.
 */
enum class SummaryLevel(val label: String, val sourceIsSummaries: Boolean, val partLabel: String) {
    /** Straight from the transcript lines. */
    HOUR("hour", sourceIsSummaries = false, partLabel = "line"),

    /** From the hours of that day. */
    DAY("day", sourceIsSummaries = true, partLabel = "hour"),

    /** From the days of that week. */
    WEEK("week", sourceIsSummaries = true, partLabel = "day"),

    /** From the weeks of that month, falling back to its days when the weeks have none. */
    MONTH("month", sourceIsSummaries = true, partLabel = "week"),
}

/**
 * Prompt building and answer parsing for group summaries. Pure, so the parsing — which is
 * where a 1B model's sloppiness has to be absorbed — is unit tested without a model.
 */
object SummaryPrompt {

    /** The marker the model is asked to put in front of the name. */
    const val TITLE_MARK = "TOPIC:"

    /** And in front of the paragraph. */
    const val BODY_MARK = "NOTES:"

    /**
     * Told what it is reading and what to leave out.
     *
     * "Do not invent" is in here twice on purpose. A small model asked to summarise a
     * fragmentary always-on transcript will happily fill gaps with a plausible meeting, and a
     * summary that says something was agreed when it was not is worse than no summary at all
     * for the thing this app is for.
     */
    val SYSTEM = buildString {
        append("You summarise a transcript of the user's own day, recorded continuously. ")
        append("It is fragmentary: half-heard lines, other people talking, long gaps. ")
        append("Say only what the text supports. Do not invent events, names, numbers, ")
        append("decisions or outcomes. If the text is too thin to summarise, say so plainly. ")
        append("Write in the second person about the user (\"you\"). ")
        append("Answer in exactly two parts and nothing else:\n")
        append("$TITLE_MARK a name of two to six words for what this was about\n")
        append("$BODY_MARK two or three sentences on what was said\n")
        append("No lists, no headings, no preamble, no reasoning.")
    }

    /** The prompt for one group. [what] is the span in words — "Tuesday 3pm to 4pm". */
    fun build(level: SummaryLevel, what: String, source: List<String>): String = buildString {
        append("This is ").append(what).append(".\n\n")
        if (level.sourceIsSummaries) {
            append("Below are the summaries of each ")
            append(level.partLabel)
            append(" inside it. Roll them up into one, keeping the topics that recur ")
            append("and dropping the ones that were mentioned once.\n\n")
        } else {
            append("Below is what was transcribed.\n\n")
        }
        source.forEach { append("- ").append(it.replace('\n', ' ').trim()).append('\n') }
        append('\n')
        append("Now answer with the two parts, $TITLE_MARK then $BODY_MARK.")
    }

    /**
     * Reads a [GroupSummary] out of whatever the model produced.
     *
     * Tolerant by necessity. The marks may be missing, lower case, followed by a newline
     * instead of a space, or wrapped in the asterisks small models like putting round labels.
     * A reply with no marks at all is treated as a body with no title rather than discarded:
     * a paragraph is still worth showing, and the title is regenerated next pass.
     */
    fun parse(reply: String): GroupSummary {
        val cleaned = reply.trim()
        if (cleaned.isBlank()) return GroupSummary("", "")

        val titleAt = indexOfMark(cleaned, TITLE_MARK)
        val bodyAt = indexOfMark(cleaned, BODY_MARK)

        if (titleAt < 0 && bodyAt < 0) {
            // No marks. Take the first sentence-ish line as the title only if the model
            // clearly wrote a heading — a short line followed by more text.
            val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val first = lines.firstOrNull().orEmpty()
            return if (lines.size > 1 && first.length <= HEADING_MAX_CHARS && !first.endsWith('.')) {
                GroupSummary(tidyTitle(first), lines.drop(1).joinToString(" "))
            } else {
                GroupSummary("", cleaned)
            }
        }

        val title = if (titleAt >= 0) {
            val from = titleAt + TITLE_MARK.length
            val to = if (bodyAt > titleAt) bodyAt else cleaned.length
            tidyTitle(cleaned.substring(from, to))
        } else {
            ""
        }
        val body = when {
            bodyAt >= 0 -> {
                val from = bodyAt + BODY_MARK.length
                val to = if (titleAt > bodyAt) titleAt else cleaned.length
                tidyBody(cleaned.substring(from, to))
            }
            // A name and then a paragraph, with the second label left off. Whatever follows
            // the first line is the paragraph; if there is no second line there is no body,
            // rather than the title repeated as one.
            else -> {
                val after = cleaned.substring(titleAt + TITLE_MARK.length)
                val newline = after.indexOf('\n')
                if (newline < 0) "" else tidyBody(after.substring(newline + 1))
            }
        }
        return GroupSummary(title, body)
    }

    /** A paragraph, stripped of the asterisks small models wrap their labels and text in. */
    private fun tidyBody(raw: String): String = raw.trim().trim('*').trim()

    /** Case-insensitive, and finds the mark whether or not the model bolded it. */
    private fun indexOfMark(text: String, mark: String): Int =
        text.indexOf(mark, ignoreCase = true)

    /**
     * A title, not a sentence: one line, no markdown, no trailing full stop, and short enough
     * to sit on a group row on a four-inch screen without pushing the line count off it.
     */
    private fun tidyTitle(raw: String): String {
        val oneLine = raw.lines().firstOrNull { it.isNotBlank() }.orEmpty()
        val bare = oneLine.trim().trim('*', '#', '"', '’', '\'').trim().trimEnd('.', ':', ';')
        return if (bare.length <= TITLE_MAX_CHARS) {
            bare
        } else {
            // Cut on a word rather than mid-word, and say it was cut.
            bare.take(TITLE_MAX_CHARS).substringBeforeLast(' ').trimEnd(',') + "…"
        }
    }

    /** Two to six words fits well inside this; the cap is for when the model ignores that. */
    const val TITLE_MAX_CHARS = 48

    /** A first line longer than this is prose, not a heading. */
    private const val HEADING_MAX_CHARS = 60
}


/**
 * One turn of a chat request. All that survives of an interface that once abstracted five
 * backends — three cloud vendors, a local server and llama.cpp — and now describes the shape
 * every OpenAI-compatible endpoint takes.
 */
enum class Role { SYSTEM, USER }

data class ChatMessage(val role: Role, val content: String) {
    /** The wire name, which is lower case in every implementation of this API. */
    val wireRole: String get() = name(role)

    private fun name(role: Role) = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
    }
}

/**
 * What one summary is allowed to spend.
 *
 * A ceiling on the answer, not on the span: a month is not allowed a longer summary than an
 * hour, because the point of rolling hours up into days and days into months is that the result
 * stays readable as the span grows. Kept even though generation is no longer on this phone —
 * an unbounded answer is now somebody's free-tier allowance rather than a flat battery, and a
 * budget nobody enforces is not a budget.
 */
data class SummaryBudget(
    val maxTokens: Int = MAX_TOKENS,
    val timeoutMs: Long = TIMEOUT_MS,
    val label: String = "summary",
) {
    companion object {
        /** A name and two or three sentences. */
        const val MAX_TOKENS = 200

        /** A network call to a hosted model, not a phone grinding through tokens. */
        const val TIMEOUT_MS = 45_000L

        fun forLevel(level: SummaryLevel): SummaryBudget =
            SummaryBudget(label = "summary of one ${level.label}")
    }
}

/** What a summary attempt produced, or why it produced nothing. */
sealed interface SummaryResult {
    data class Ok(val summary: GroupSummary) : SummaryResult

    /** The request failed in a way that retrying later might fix. [retryAfterMs] when told. */
    data class Backoff(val reason: String, val retryAfterMs: Long?) : SummaryResult

    /** The request failed in a way that retrying will not fix — a bad key, a bad model id. */
    data class Refused(val reason: String) : SummaryResult

    /** The call worked and the answer was unusable. */
    data class Unusable(val reason: String) : SummaryResult
}
