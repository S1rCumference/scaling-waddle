package com.recorder.core.storage

import java.util.TimeZone
import org.junit.Assert.assertEquals
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
