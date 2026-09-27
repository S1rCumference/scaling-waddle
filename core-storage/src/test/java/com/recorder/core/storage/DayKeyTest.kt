package com.recorder.core.storage

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayKeyTest {

    private val nyc = TimeZone.getTimeZone("America/New_York")
    private val india = TimeZone.getTimeZone("Asia/Kolkata")

    @Test
    fun `day key follows the local calendar, not UTC`() {
        // 2026-09-24 02:16 UTC is still the 23rd in New York.
        val ts = 1_790_216_200_000L
        assertEquals(20260923, DayKey.of(ts, nyc))
        assertEquals(20260924, DayKey.of(ts, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `start and end bracket the day`() {
        val start = DayKey.startOf(20260923, nyc)
        val end = DayKey.endOf(20260923, nyc)
        assertEquals(20260923, DayKey.of(start, nyc))
        assertEquals(20260923, DayKey.of(end - 1, nyc))
        assertEquals(20260924, DayKey.of(end, nyc))
        assertEquals(24 * 3_600_000L, end - start)
    }

    @Test
    fun `a daylight saving day is 25 hours long`() {
        val start = DayKey.startOf(20261101, nyc)
        val end = DayKey.endOf(20261101, nyc)
        assertEquals(25 * 3_600_000L, end - start)
    }

    @Test
    fun `previous crosses month and year boundaries`() {
        assertEquals(20260930, DayKey.previous(20261001, india))
        assertEquals(20251231, DayKey.previous(20260101, india))
    }

    @Test
    fun `hour start respects half-hour zones`() {
        val ts = DayKey.startOf(20260923, india) + 5 * 3_600_000L + 45 * 60_000L
        assertEquals(DayKey.startOf(20260923, india) + 5 * 3_600_000L, DayKey.hourStart(ts, india))
    }
}

/**
 * Month boundaries, which the Logs calendar groups days by and a month-wide summary spans.
 * Worth pinning because the arithmetic looks like it should be dayKey / 100 and is not: a month
 * does not have a fixed length, so the end has to come from the calendar.
 */
class MonthBoundsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `a month starts on its first day`() {
        assertEquals(DayKey.startOf(20_260_301, utc), DayKey.monthStart(202_603, utc))
    }

    @Test
    fun `a month ends where the next one starts`() {
        assertEquals(DayKey.startOf(20_260_401, utc), DayKey.monthEnd(202_603, utc))
    }

    @Test
    fun `February is 28 or 29 days, not 30`() {
        val days2026 = (DayKey.monthEnd(202_602, utc) - DayKey.monthStart(202_602, utc)) / 86_400_000
        assertEquals(28L, days2026)
        val days2024 = (DayKey.monthEnd(202_402, utc) - DayKey.monthStart(202_402, utc)) / 86_400_000
        assertEquals(29L, days2024)
    }

    @Test
    fun `December rolls into the next year`() {
        assertEquals(DayKey.startOf(20_270_101, utc), DayKey.monthEnd(202_612, utc))
    }

    @Test
    fun `every day of a month falls inside its bounds`() {
        val from = DayKey.monthStart(202_603, utc)
        val to = DayKey.monthEnd(202_603, utc)
        listOf(20_260_301, 20_260_315, 20_260_331).forEach { day ->
            val start = DayKey.startOf(day, utc)
            assertTrue("$day", start >= from && start < to)
        }
        assertTrue(DayKey.startOf(20_260_401, utc) >= to)
    }
}

/**
 * Week boundaries, which the month roll-up is built from.
 *
 * Asserted as properties rather than "weeks start on Monday", because which day a week starts on
 * is the locale's business — Calendar's firstDayOfWeek is Monday in most of the world and Sunday
 * in the US — and a test that hard-coded one would pass or fail on the machine, not the code.
 */
class WeekBoundsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    private fun at(dayKey: Int, hour: Int = 12) =
        DayKey.startOf(dayKey, utc) + hour * 3_600_000L

    @Test
    fun `a week is seven days long`() {
        val ts = at(20_260_923)
        assertEquals(7 * day, DayKey.weekEnd(ts, utc) - DayKey.weekStart(ts, utc))
    }

    @Test
    fun `the timestamp is inside its own week`() {
        val ts = at(20_260_923, hour = 17)
        assertTrue(ts >= DayKey.weekStart(ts, utc))
        assertTrue(ts < DayKey.weekEnd(ts, utc))
    }

    @Test
    fun `a week starts at midnight`() {
        val start = DayKey.weekStart(at(20_260_923), utc)
        val c = java.util.Calendar.getInstance(utc).apply { timeInMillis = start }
        assertEquals(0, c.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(java.util.Calendar.MINUTE))
        assertEquals(0, c.get(java.util.Calendar.SECOND))
        assertEquals(0, c.get(java.util.Calendar.MILLISECOND))
    }

    @Test
    fun `a week starts on whatever day this locale starts weeks on`() {
        val start = DayKey.weekStart(at(20_260_923), utc)
        val c = java.util.Calendar.getInstance(utc).apply { timeInMillis = start }
        assertEquals(c.firstDayOfWeek, c.get(java.util.Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `every day of one week agrees on where that week starts`() {
        // The property the roll-up depends on: seven days must map to one week, or a month would
        // be built from overlapping spans.
        val start = DayKey.weekStart(at(20_260_923), utc)
        val starts = (0..6).map { DayKey.weekStart(start + it * day + 3_600_000L, utc) }.distinct()
        assertEquals(listOf(start), starts)
    }

    @Test
    fun `the next day after a week ends belongs to the next week`() {
        val ts = at(20_260_923)
        val end = DayKey.weekEnd(ts, utc)
        assertEquals(end, DayKey.weekStart(end, utc))
    }

    @Test
    fun `it is idempotent`() {
        val start = DayKey.weekStart(at(20_260_923), utc)
        assertEquals(start, DayKey.weekStart(start, utc))
    }

    @Test
    fun `a week spanning a month boundary still works`() {
        // The case the naive DAY_OF_WEEK arithmetic got wrong: walking back past the 1st.
        val ts = at(20_261_001, hour = 9)
        assertTrue(ts >= DayKey.weekStart(ts, utc))
        assertTrue(ts < DayKey.weekEnd(ts, utc))
        assertEquals(7 * day, DayKey.weekEnd(ts, utc) - DayKey.weekStart(ts, utc))
    }

    @Test
    fun `a week spanning a year boundary still works`() {
        val ts = at(20_270_101, hour = 9)
        assertTrue(ts >= DayKey.weekStart(ts, utc))
        assertTrue(ts < DayKey.weekEnd(ts, utc))
        assertEquals(7 * day, DayKey.weekEnd(ts, utc) - DayKey.weekStart(ts, utc))
    }
}
