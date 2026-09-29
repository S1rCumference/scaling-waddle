package com.recorder.core.storage

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingScheduleTest {

    // 2026-09-28 is a Monday.
    private fun mon(h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, 28, h, m)
    private fun day(offset: Long, h: Int, m: Int = 0) = mon(h, m).plusDays(offset)

    private val nineToFive = DayWindow(true, 9 * 60, 17 * 60)

    private fun weekdays9to5(): RecordingSchedule {
        var s = RecordingSchedule(enabled = true)
        for (d in DayOfWeek.values()) {
            s = s.with(d, if (d.value <= 5) nineToFive else DayWindow(enabled = false))
        }
        return s
    }

    @Test fun `off schedule always records and never changes`() {
        val s = RecordingSchedule()
        assertTrue(s.isActive(mon(3)))
        assertNull(s.nextChange(mon(3)))
        assertNull(s.nextStart(mon(3)))
    }

    @Test fun `enabled all-day every day records always and never changes`() {
        val s = RecordingSchedule(enabled = true)
        assertTrue(s.isActive(mon(3)))
        assertNull(s.nextChange(mon(3)))
    }

    @Test fun `every day switched off never records and never changes`() {
        val s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        assertFalse(s.isActive(mon(12)))
        assertNull(s.nextChange(mon(12)))
        assertNull(s.nextStart(mon(12)))
    }

    @Test fun `nine to five, start inclusive and end exclusive`() {
        val s = weekdays9to5()
        assertFalse(s.isActive(mon(8, 59)))
        assertTrue(s.isActive(mon(9)))
        assertTrue(s.isActive(mon(16, 59)))
        assertFalse(s.isActive(mon(17)))
    }

    @Test fun `next change is the window edge`() {
        val s = weekdays9to5()
        assertEquals(mon(9), s.nextChange(mon(7)))
        assertEquals(mon(17), s.nextChange(mon(9)))
        assertEquals(day(1, 9), s.nextChange(mon(17)))
    }

    @Test fun `friday evening skips the weekend`() {
        val s = weekdays9to5()
        val fridayEvening = day(4, 18)
        assertFalse(s.isActive(fridayEvening))
        assertFalse(s.isActive(day(5, 12)))
        assertEquals(day(7, 9), s.nextChange(fridayEvening))
        assertEquals(day(7, 9), s.nextStart(fridayEvening))
    }

    @Test fun `next start hops over the off period`() {
        val s = weekdays9to5()
        assertEquals(day(1, 9), s.nextStart(mon(10)))
    }

    @Test fun `overnight window belongs to the day it starts`() {
        // Only Monday, 22:00 to 06:00.
        var s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        s = s.with(DayOfWeek.MONDAY, DayWindow(true, 22 * 60, 6 * 60))
        assertFalse(s.isActive(mon(5)))          // Monday morning: Sunday's window is off
        assertTrue(s.isActive(mon(23)))
        assertTrue(s.isActive(day(1, 5, 59)))     // Tuesday early: spilled over
        assertFalse(s.isActive(day(1, 6)))
        assertEquals(mon(22), s.nextChange(mon(12)))
        assertEquals(day(1, 6), s.nextChange(mon(23)))
        assertEquals(day(7, 22), s.nextChange(day(1, 6)))
    }

    @Test fun `overnight on sunday spills into monday across the week`() {
        var s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        s = s.with(DayOfWeek.SUNDAY, DayWindow(true, 23 * 60, 2 * 60))
        assertTrue(s.isActive(mon(1)))
        assertEquals(mon(2), s.nextChange(mon(1)))
        assertEquals(day(6, 23), s.nextChange(mon(2)))
    }

    @Test fun `window until midnight`() {
        var s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        s = s.with(DayOfWeek.MONDAY, DayWindow(true, 20 * 60, 1440))
        assertTrue(s.isActive(mon(23, 59)))
        assertFalse(s.isActive(day(1, 0)))
        assertEquals(day(1, 0), s.nextChange(mon(21)))
    }

    @Test fun `adjacent all-day and partial days merge without a false boundary`() {
        // Monday all day, Tuesday 00:00-12:00: recording runs Monday 00:00 through Tuesday noon.
        var s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        s = s.with(DayOfWeek.MONDAY, DayWindow())
        s = s.with(DayOfWeek.TUESDAY, DayWindow(true, 0, 12 * 60))
        assertEquals(day(1, 12), s.nextChange(mon(10)))
    }

    @Test fun `month and year boundaries`() {
        val s = weekdays9to5()
        // Wednesday 2026-09-30 evening → Thursday 2026-10-01 09:00.
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), s.nextChange(LocalDateTime.of(2026, 9, 30, 18, 0)))
        // Thursday 2026-12-31 evening → Friday 2027-01-01 09:00.
        assertEquals(LocalDateTime.of(2027, 1, 1, 9, 0), s.nextChange(LocalDateTime.of(2026, 12, 31, 18, 0)))
    }

    @Test fun `epoch millis round trip through a zone`() {
        val zone = ZoneId.of("Europe/Moscow")
        val s = weekdays9to5()
        val from = mon(7).atZone(zone).toInstant().toEpochMilli()
        val expected = mon(9).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, s.nextChange(from, zone))
        assertFalse(s.isActive(from, zone))
    }

    @Test fun `daylight saving gap does not break next change`() {
        // Europe/Berlin springs forward 2027-03-28 02:00 → 03:00 (a Sunday). A 02:30 start
        // lands in the gap; atZone shifts it forward, so the answer is still a real instant.
        val zone = ZoneId.of("Europe/Berlin")
        var s = RecordingSchedule(true, List(7) { DayWindow(enabled = false) })
        s = s.with(DayOfWeek.SUNDAY, DayWindow(true, 2 * 60 + 30, 5 * 60))
        val from = LocalDateTime.of(2027, 3, 28, 1, 0).atZone(zone).toInstant().toEpochMilli()
        val next = s.nextChange(from, zone)!!
        assertTrue(next > from)
        assertTrue(s.isActive(next, zone))
    }

    @Test fun `encode and decode round trip`() {
        val s = weekdays9to5().with(DayOfWeek.SATURDAY, DayWindow(true, 1320, 360))
        assertEquals(s.days, RecordingSchedule.decodeDays(s.encode()))
    }

    @Test fun `malformed text decodes to all day every day`() {
        val default = List(7) { DayWindow() }
        for (bad in listOf(null, "", "garbage", "v2;1,0,0", "v1;1,0,0", "v1;" + List(7) { "1,x,0" }.joinToString(";"),
            "v1;" + List(7) { "1,1440,0" }.joinToString(";"), "v1;" + List(7) { "1,0,1441" }.joinToString(";"))) {
            assertEquals(bad.toString(), default, RecordingSchedule.decodeDays(bad))
        }
    }

    @Test fun `record-now override wins until it ends`() {
        val zone = ZoneId.of("UTC")
        val s = weekdays9to5()
        val now = mon(20).atZone(zone).toInstant().toEpochMilli()
        val until = now + 3_600_000
        val d = ScheduleGate.decide(s, ScheduleOverride(true, until), now, zone)
        assertTrue(d.record)
        assertEquals(until, d.nextCheckMs)
        val after = ScheduleGate.decide(s, ScheduleOverride(true, until), until, zone)
        assertFalse(after.record)
    }

    @Test fun `off-until-next-start override pauses an on period`() {
        val zone = ZoneId.of("UTC")
        val s = weekdays9to5()
        val now = mon(10).atZone(zone).toInstant().toEpochMilli()
        val until = s.nextStart(now, zone)!!
        val d = ScheduleGate.decide(s, ScheduleOverride(false, until), now, zone)
        assertFalse(d.record)
        // The schedule's own 17:00 edge comes first; waking then is harmless.
        assertEquals(mon(17).atZone(zone).toInstant().toEpochMilli(), d.nextCheckMs)
        assertTrue(ScheduleGate.decide(s, ScheduleOverride(false, until), until, zone).record)
    }

    @Test fun `no schedule and no override records with nothing to wake for`() {
        val d = ScheduleGate.decide(RecordingSchedule(), null, 0L, ZoneId.of("UTC"))
        assertTrue(d.record)
        assertNull(d.nextCheckMs)
    }
}
