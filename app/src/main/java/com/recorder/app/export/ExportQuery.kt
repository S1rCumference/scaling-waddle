package com.recorder.app.export

import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.ExportDestinations
import java.util.Calendar
import java.util.TimeZone

/** The preset date ranges, stored as strings so a new one needs no migration. */
object ExportRange {
    const val TODAY = "today"
    const val YESTERDAY = "yesterday"
    const val LAST_7 = "last7"
    const val THIS_MONTH = "month"
    const val ALL = "all"
    const val CUSTOM = "custom"

    fun label(value: String): String = when (value) {
        TODAY -> "Today"
        YESTERDAY -> "Yesterday"
        LAST_7 -> "Last 7 days"
        THIS_MONTH -> "This month"
        ALL -> "All"
        else -> "Custom"
    }

    val all = listOf(TODAY, YESTERDAY, LAST_7, THIS_MONTH, ALL, CUSTOM)
}

/** How the file is laid out. */
object ExportGrouping {
    const val DAY = "day"
    const val HOUR = "hour"
    const val FLAT = "flat"

    fun label(value: String): String = when (value) {
        DAY -> "By day"
        HOUR -> "By hour"
        else -> "Flat"
    }

    val all = listOf(DAY, HOUR, FLAT)
}

/**
 * A window within each day, in minutes from midnight, both ends inclusive.
 *
 * Crossing midnight is not an edge case here, it is the normal way someone describes an
 * evening: 22:00 to 02:00 is one window, not an empty one. When [startMinute] is after
 * [endMinute] the window wraps, and a timestamp matches if it is after the start *or* before
 * the end — which is why this is a union rather than a range check.
 */
data class TimeWindow(val startMinute: Int, val endMinute: Int) {

    val crossesMidnight: Boolean get() = startMinute > endMinute

    fun matches(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val minute = minuteOfDay(timestampMs, zone)
        return if (crossesMidnight) {
            minute >= startMinute || minute <= endMinute
        } else {
            minute in startMinute..endMinute
        }
    }

    fun describe(): String = "${clock(startMinute)}–${clock(endMinute)}" +
        if (crossesMidnight) " (over midnight)" else ""

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun minuteOfDay(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
            val cal = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }

        /** 24-hour, because this is only used where the value must be unambiguous. */
        fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** Include and exclude terms, already split and lowercased. */
data class Keywords(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    /** True for "all of these", false for "any of these". */
    val includeAll: Boolean = false,
) {
    val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty()

    /**
     * Whether a line survives. Case-insensitive, and the unit is the whole line: a term that
     * appears anywhere in the line keeps (or drops) the line entire, which is what a
     * transcript export is for — half a sentence is not a useful thing to hand someone.
     *
     * Exclude wins. A line that matches both an include term and an exclude term is dropped,
     * because the exclusions are the ones written to keep something out of a file that is
     * about to be handed to someone else.
     */
    fun matches(text: String): Boolean {
        val lower = text.lowercase()
        if (exclude.any { it in lower }) return false
        if (include.isEmpty()) return true
        return if (includeAll) include.all { it in lower } else include.any { it in lower }
    }

    companion object {
        /** Splits a comma-separated field. Blank terms are dropped rather than matching everything. */
        fun parse(field: String, exclude: String = "", includeAll: Boolean = false) = Keywords(
            include = split(field),
            exclude = split(exclude),
            includeAll = includeAll,
        )

        private fun split(field: String): List<String> =
            field.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}

/**
 * Everything an export is: which days, which part of each day, which lines, and what the file
 * looks like. Deliberately a value type with no Android in it, so the filtering that decides
 * what ends up in someone's file is tested rather than trusted.
 */
data class ExportQuery(
    val range: String = ExportRange.TODAY,
    /** Day keys (yyyymmdd) for [ExportRange.CUSTOM], both inclusive. */
    val customFromDay: Int = 0,
    val customToDay: Int = 0,
    val timeWindow: TimeWindow? = null,
    /** Raw comma-separated fields, kept as typed so the screen can show them back. */
    val includeField: String = "",
    val excludeField: String = "",
    val includeAll: Boolean = false,
    val content: String = ExportDefaults.CONTENT_CORRECTED,
    val format: String = ExportDefaults.FORMAT_MARKDOWN,
    val grouping: String = ExportGrouping.DAY,
    val destination: String = ExportDestinations.SHARE,
    /** When set, only these lines, and the range is ignored: a long-press selection. */
    val onlyIds: Set<Long> = emptySet(),
) {

    /** The parsed terms. Derived, so the fields stay exactly as the user typed them. */
    val keywords: Keywords get() = Keywords.parse(includeField, excludeField, includeAll)

    /**
     * The timestamps to fetch: from the start of the first day to the end of the last, both
     * inclusive. The upper bound is returned exclusive because that is what the query takes.
     */
    fun bounds(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): LongRange {
        fun startOfDay(ts: Long): Long = Calendar.getInstance(zone).apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val today = startOfDay(now)
        return when (range) {
            ExportRange.TODAY -> today until (today + DAY_MS)
            ExportRange.YESTERDAY -> (today - DAY_MS) until today
            // Seven days including today, which is what someone means by "last 7 days".
            ExportRange.LAST_7 -> (today - 6 * DAY_MS) until (today + DAY_MS)
            ExportRange.THIS_MONTH -> {
                val first = Calendar.getInstance(zone).apply {
                    timeInMillis = today
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
                first until (today + DAY_MS)
            }

            ExportRange.CUSTOM -> {
                val from = dayStart(customFromDay, zone) ?: today
                val to = dayStart(customToDay, zone) ?: today
                // Given back to front, a range still means the days between them.
                val lo = minOf(from, to)
                val hi = maxOf(from, to)
                lo until (hi + DAY_MS)
            }

            else -> 0L until (today + DAY_MS)
        }
    }

    /** Whether one line's timestamp and text survive the filters. Range is applied by the query. */
    fun accepts(timestampMs: Long, text: String, zone: TimeZone = TimeZone.getDefault()): Boolean {
        if (timeWindow?.matches(timestampMs, zone) == false) return false
        return keywords.matches(text)
    }

    val isSelection: Boolean get() = onlyIds.isNotEmpty()

    private fun dayStart(dayKey: Int, zone: TimeZone): Long? {
        if (dayKey <= 0) return null
        val year = dayKey / 10_000
        val month = (dayKey / 100) % 100
        val day = dayKey % 100
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    }

    private infix fun Long.until(end: Long): LongRange = this..(end - 1)

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
