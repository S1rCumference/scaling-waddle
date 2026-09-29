package com.recorder.core.storage

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One weekday's recording window.
 *
 * [startMinute] and [endMinute] are minutes after local midnight. Three shapes, and only three:
 *  - start == end: the whole day;
 *  - start < end: that stretch of the day, e.g. 540..1020 for 9:00 to 17:00 (end may be 1440,
 *    meaning "until midnight");
 *  - start > end: overnight — from start on this day to end on the next, e.g. 1320..360 for
 *    22:00 to 06:00. The window belongs to the day it starts on, which is how people say it.
 */
data class DayWindow(
    val enabled: Boolean = true,
    val startMinute: Int = 0,
    val endMinute: Int = 0,
) {
    val allDay: Boolean get() = startMinute == endMinute
    val overnight: Boolean get() = startMinute > endMinute

    /** Whether this window covers [minute] on its own day. */
    internal fun coversSameDay(minute: Int): Boolean = when {
        !enabled -> false
        allDay -> true
        overnight -> minute >= startMinute
        else -> minute in startMinute until endMinute
    }

    /** Whether this window, having started yesterday, still covers [minute] today. */
    internal fun spillsInto(minute: Int): Boolean = enabled && overnight && minute < endMinute
}

/**
 * When the recorder is allowed to listen. Off by default, and when off the recorder listens
 * always — which is what it did before this existed.
 *
 * Pure and zone-explicit so every boundary it can get wrong is a unit test rather than a day
 * spent recording at the wrong time.
 */
data class RecordingSchedule(
    val enabled: Boolean = false,
    /** Seven windows, Monday first, matching java.time's ISO numbering. */
    val days: List<DayWindow> = List(7) { DayWindow() },
) {
    init {
        require(days.size == 7) { "a schedule has seven days, not ${days.size}" }
    }

    fun window(day: DayOfWeek): DayWindow = days[day.value - 1]

    fun with(day: DayOfWeek, window: DayWindow): RecordingSchedule =
        copy(days = days.toMutableList().also { it[day.value - 1] = window })

    /** Whether the schedule says to record at [at]. Always true when the schedule is off. */
    fun isActive(at: LocalDateTime): Boolean {
        if (!enabled) return true
        val minute = at.hour * 60 + at.minute
        return window(at.dayOfWeek).coversSameDay(minute) ||
            window(at.dayOfWeek.minus(1)).spillsInto(minute)
    }

    fun isActive(atMs: Long, zone: ZoneId): Boolean = isActive(local(atMs, zone))

    /**
     * The next moment the answer to [isActive] changes, or null if it never does — every day
     * all-day, or every day switched off.
     *
     * Only ever at a window's own start or end, so those are the only candidates: eight days of
     * them covers any weekly pattern, including an overnight window starting on the last day.
     */
    fun nextChange(from: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        val now = isActive(from)
        return boundaries(from.toLocalDate())
            .filter { it.isAfter(from) }
            .sorted()
            .firstOrNull { isActive(it) != now }
    }

    fun nextChange(fromMs: Long, zone: ZoneId): Long? =
        nextChange(local(fromMs, zone))?.atZone(zone)?.toInstant()?.toEpochMilli()

    /** The next moment recording is due to start, skipping over an "off" period if one comes first. */
    fun nextStart(from: LocalDateTime): LocalDateTime? {
        var t = nextChange(from) ?: return null
        // At most one hop: from on → off → on.
        if (!isActive(t)) t = nextChange(t) ?: return null
        return t
    }

    fun nextStart(fromMs: Long, zone: ZoneId): Long? =
        nextStart(local(fromMs, zone))?.atZone(zone)?.toInstant()?.toEpochMilli()

    private fun boundaries(today: LocalDate): List<LocalDateTime> =
        (-1..8).flatMap { offset ->
            val date = today.plusDays(offset.toLong())
            val w = window(date.dayOfWeek)
            when {
                !w.enabled -> emptyList()
                w.allDay -> listOf(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                w.overnight -> listOf(at(date, w.startMinute), at(date.plusDays(1), w.endMinute))
                else -> listOf(at(date, w.startMinute), at(date, w.endMinute))
            }
        }

    private fun at(date: LocalDate, minute: Int): LocalDateTime =
        if (minute >= MINUTES_PER_DAY) date.plusDays(1).atStartOfDay()
        else date.atTime(LocalTime.of(minute / 60, minute % 60))

    /** Compact, versioned, and readable in a preference dump: "v1;1,540,1020;…" Monday first. */
    fun encode(): String =
        "v1;" + days.joinToString(";") { "${if (it.enabled) 1 else 0},${it.startMinute},${it.endMinute}" }

    companion object {
        const val MINUTES_PER_DAY = 1440

        /**
         * The inverse of [encode]. Anything malformed gives the default — all day, every day —
         * because a schedule that cannot be read must fail towards recording, not towards
         * silence.
         */
        fun decodeDays(text: String?): List<DayWindow> {
            val default = List(7) { DayWindow() }
            if (text.isNullOrBlank() || !text.startsWith("v1;")) return default
            val parts = text.removePrefix("v1;").split(';')
            if (parts.size != 7) return default
            return parts.map { part ->
                val f = part.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (f.size != 3) return default
                val (on, start, end) = f
                if (start !in 0 until MINUTES_PER_DAY || end !in 0..MINUTES_PER_DAY) return default
                DayWindow(on == 1, start, end)
            }
        }

        private fun local(ms: Long, zone: ZoneId): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    }
}

/**
 * A one-off exception to the schedule: "record now" during an off period, or "off until the next
 * start" during an on one. It ends by itself at [untilMs], so forgetting it cannot leave the
 * recorder permanently wrong.
 */
data class ScheduleOverride(val record: Boolean, val untilMs: Long) {
    fun activeAt(nowMs: Long): Boolean = nowMs < untilMs
}

/** What the recorder should be doing now, and when to look again. */
data class ScheduleDecision(val record: Boolean, val nextCheckMs: Long?)

object ScheduleGate {
    fun decide(
        schedule: RecordingSchedule,
        override: ScheduleOverride?,
        nowMs: Long,
        zone: ZoneId,
    ): ScheduleDecision {
        val live = override?.takeIf { it.activeAt(nowMs) }
        val record = live?.record ?: schedule.isActive(nowMs, zone)
        val next = listOfNotNull(live?.untilMs, schedule.nextChange(nowMs, zone)).minOrNull()
        return ScheduleDecision(record, next)
    }
}
