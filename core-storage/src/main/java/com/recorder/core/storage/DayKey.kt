package com.recorder.core.storage

import java.util.Calendar
import java.util.TimeZone

/**
 * Local calendar days as yyyymmdd integers — sortable, human-readable in the database, and
 * cheap to index. All conversions take the timezone explicitly so they can be tested.
 */
object DayKey {

    fun of(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /** Local midnight at the start of [dayKey]. */
    fun startOf(dayKey: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        calendarAt(dayKey, zone).timeInMillis

    /** Local midnight at the start of the following day (exclusive end of [dayKey]). */
    fun endOf(dayKey: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        calendarAt(dayKey, zone).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    fun today(zone: TimeZone = TimeZone.getDefault()): Int = of(System.currentTimeMillis(), zone)

    fun previous(dayKey: Int, zone: TimeZone = TimeZone.getDefault()): Int =
        of(calendarAt(dayKey, zone).apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis + 1, zone)

    /** Start of the local hour containing [timestampMs]. */
    fun hourStart(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = timestampMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Local midnight at the first of the month [monthKey] (yyyymm). */
    fun monthStart(monthKey: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        startOf(monthKey * 100 + 1, zone)

    /** Local midnight at the first of the following month (exclusive end of [monthKey]). */
    fun monthEnd(monthKey: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        calendarAt(monthKey * 100 + 1, zone).apply { add(Calendar.MONTH, 1) }.timeInMillis

    private fun calendarAt(dayKey: Int, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply {
            clear()
            set(dayKey / 10_000, (dayKey / 100) % 100 - 1, dayKey % 100, 0, 0, 0)
        }
}
