package com.recorder.app.export

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filters that decide what ends up in someone's file. Wrong here means an export that is
 * silently missing lines, or one that contains lines the user was trying to leave out — both of
 * which are only noticeable after the file has been sent.
 */
class ExportQueryTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private val now = at(2026, 9, 25, 14, 30)

    // --- date ranges ---------------------------------------------------------------------

    @Test
    fun `today is the whole of today and nothing of yesterday or tomorrow`() {
        val bounds = ExportQuery(range = ExportRange.TODAY).bounds(now, utc)
        assertTrue(at(2026, 9, 25, 0, 0) in bounds)
        assertTrue(at(2026, 9, 25, 23, 59) in bounds)
        assertFalse(at(2026, 9, 24, 23, 59) in bounds)
        assertFalse(at(2026, 9, 26, 0, 0) in bounds)
    }

    @Test
    fun `yesterday excludes today`() {
        val bounds = ExportQuery(range = ExportRange.YESTERDAY).bounds(now, utc)
        assertTrue(at(2026, 9, 24, 0, 0) in bounds)
        assertTrue(at(2026, 9, 24, 23, 59) in bounds)
        assertFalse(at(2026, 9, 25, 0, 0) in bounds)
    }

    @Test
    fun `last seven days includes today and the six before it, not the seventh`() {
        val bounds = ExportQuery(range = ExportRange.LAST_7).bounds(now, utc)
        assertTrue("today", at(2026, 9, 25, 9, 0) in bounds)
        assertTrue("six days back", at(2026, 9, 19, 0, 0) in bounds)
        assertFalse("seven days back", at(2026, 9, 18, 23, 59) in bounds)
    }

    @Test
    fun `this month starts on the first and ends today`() {
        val bounds = ExportQuery(range = ExportRange.THIS_MONTH).bounds(now, utc)
        assertTrue(at(2026, 9, 1, 0, 0) in bounds)
        assertTrue(at(2026, 9, 25, 23, 59) in bounds)
        assertFalse(at(2026, 8, 31, 23, 59) in bounds)
        assertFalse(at(2026, 9, 26, 0, 0) in bounds)
    }

    @Test
    fun `all reaches back to the epoch and still stops at the end of today`() {
        val bounds = ExportQuery(range = ExportRange.ALL).bounds(now, utc)
        assertTrue(0L in bounds)
        assertTrue(at(2019, 1, 1) in bounds)
        assertFalse(at(2026, 9, 26, 0, 0) in bounds)
    }

    @Test
    fun `a custom range includes both of its days`() {
        val bounds = ExportQuery(
            range = ExportRange.CUSTOM,
            customFromDay = 20260901,
            customToDay = 20260903,
        ).bounds(now, utc)
        assertTrue("first day from midnight", at(2026, 9, 1, 0, 0) in bounds)
        assertTrue("last day to the last second", at(2026, 9, 3, 23, 59) in bounds)
        assertFalse(at(2026, 8, 31, 23, 59) in bounds)
        assertFalse(at(2026, 9, 4, 0, 0) in bounds)
    }

    @Test
    fun `a custom range given back to front still means the days between`() {
        val forwards = ExportQuery(ExportRange.CUSTOM, 20260901, 20260903).bounds(now, utc)
        val backwards = ExportQuery(ExportRange.CUSTOM, 20260903, 20260901).bounds(now, utc)
        assertEquals(forwards, backwards)
    }

    // --- time of day --------------------------------------------------------------------

    @Test
    fun `a window inside one day accepts only that stretch`() {
        val window = TimeWindow(9 * 60, 17 * 60)
        assertTrue(window.matches(at(2026, 9, 25, 9, 0), utc))
        assertTrue(window.matches(at(2026, 9, 25, 13, 0), utc))
        assertTrue("the end minute is inclusive", window.matches(at(2026, 9, 25, 17, 0), utc))
        assertFalse(window.matches(at(2026, 9, 25, 8, 59), utc))
        assertFalse(window.matches(at(2026, 9, 25, 17, 1), utc))
    }

    @Test
    fun `a window over midnight is one window, not an empty one`() {
        val window = TimeWindow(22 * 60, 2 * 60)
        assertTrue(window.crossesMidnight)
        assertTrue("late evening", window.matches(at(2026, 9, 25, 23, 30), utc))
        assertTrue("the small hours", window.matches(at(2026, 9, 25, 1, 15), utc))
        assertTrue("both ends inclusive", window.matches(at(2026, 9, 25, 22, 0), utc))
        assertTrue(window.matches(at(2026, 9, 25, 2, 0), utc))
        assertFalse("the afternoon is not in it", window.matches(at(2026, 9, 25, 14, 0), utc))
        assertFalse(window.matches(at(2026, 9, 25, 21, 59), utc))
        assertFalse(window.matches(at(2026, 9, 25, 2, 1), utc))
    }

    @Test
    fun `a window of one minute accepts that minute only`() {
        val window = TimeWindow(12 * 60, 12 * 60)
        assertFalse(window.crossesMidnight)
        assertTrue(window.matches(at(2026, 9, 25, 12, 0), utc))
        assertFalse(window.matches(at(2026, 9, 25, 12, 1), utc))
        assertFalse(window.matches(at(2026, 9, 25, 11, 59), utc))
    }

    @Test
    fun `no window means every time of day`() {
        val query = ExportQuery(timeWindow = null)
        assertTrue(query.accepts(at(2026, 9, 25, 3, 0), "anything", utc))
        assertTrue(query.accepts(at(2026, 9, 25, 15, 0), "anything", utc))
    }

    // --- keywords -----------------------------------------------------------------------

    @Test
    fun `include with any of these keeps a line matching one term`() {
        val keywords = Keywords.parse("invoice, Marguerite", includeAll = false)
        assertTrue(keywords.matches("send the invoice tomorrow"))
        assertTrue(keywords.matches("I spoke to MARGUERITE about it"))
        assertFalse(keywords.matches("nothing relevant here"))
    }

    @Test
    fun `include with all of these needs every term in the same line`() {
        val keywords = Keywords.parse("invoice, Hendricks", includeAll = true)
        assertTrue(keywords.matches("the Hendricks invoice is late"))
        assertFalse("one term is not enough", keywords.matches("the invoice is late"))
        assertFalse(keywords.matches("Hendricks called"))
    }

    @Test
    fun `exclude drops a line even when it matches an include term`() {
        val keywords = Keywords.parse("invoice", exclude = "draft")
        assertTrue(keywords.matches("the invoice is ready"))
        assertFalse("exclusions win", keywords.matches("the draft invoice is ready"))
    }

    @Test
    fun `matching is case insensitive both ways`() {
        val keywords = Keywords.parse("INVOICE", exclude = "DRAFT")
        assertTrue(keywords.matches("an invoice"))
        assertFalse(keywords.matches("a draft"))
    }

    @Test
    fun `blank and whitespace-only terms are dropped rather than matching everything`() {
        val keywords = Keywords.parse(" , ,, ", exclude = ",  ,")
        assertTrue(keywords.isEmpty)
        assertTrue(keywords.matches("any line at all"))
    }

    @Test
    fun `no keywords keeps everything`() {
        assertTrue(Keywords().matches(""))
        assertTrue(Keywords().matches("whatever was said"))
    }

    @Test
    fun `accepts applies the window and the keywords together`() {
        val query = ExportQuery(
            timeWindow = TimeWindow(22 * 60, 2 * 60),
            includeField = "invoice",
            excludeField = "draft",
        )
        assertTrue(query.accepts(at(2026, 9, 25, 23, 0), "the invoice", utc))
        assertFalse("right words, wrong hour", query.accepts(at(2026, 9, 25, 15, 0), "the invoice", utc))
        assertFalse("right hour, wrong words", query.accepts(at(2026, 9, 25, 23, 0), "hello", utc))
        assertFalse("excluded", query.accepts(at(2026, 9, 25, 23, 0), "draft invoice", utc))
    }

    @Test
    fun `a selection ignores the range but still honours the keywords`() {
        val query = ExportQuery(onlyIds = setOf(1L, 2L), includeField = "invoice")
        assertTrue(query.isSelection)
        assertTrue(query.accepts(at(2019, 1, 1), "an old invoice", utc))
        assertFalse(query.accepts(at(2019, 1, 1), "something else", utc))
    }
}
