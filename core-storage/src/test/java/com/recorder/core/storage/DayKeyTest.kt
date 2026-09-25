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
