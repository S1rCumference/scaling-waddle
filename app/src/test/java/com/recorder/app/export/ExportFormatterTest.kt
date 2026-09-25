package com.recorder.app.export

import com.recorder.app.ui.LineView
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.SegmentCorrection
import com.recorder.core.storage.TranscriptSegment
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The exact bytes of each format, because a file is what the user is left holding. */
class ExportFormatterTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val day = DayKey.startOf(20260923, zone)

    private fun line(minute: Int, original: String, corrected: String? = null): LineView {
        val ts = day + 14 * 3_600_000L + minute * 60_000L
        val segment = TranscriptSegment(id = minute.toLong(), startTs = ts, endTs = ts + 2_000, text = original)
        val correction = corrected?.let {
            SegmentCorrection(
                segmentId = segment.id,
                text = it,
                pass = CorrectionPass.END_OF_DAY,
                engine = "Gemma 3 1B Instruct (Q4_K_M)",
            )
        }
        return LineView(segment, correction)
    }

    /**
     * Exports follow the app's clock setting, so these assertions pin it rather than
     * inheriting whatever the default happens to be. The 12-hour case is covered below.
     */
    @Before
    fun use24HourClock() {
        Clocks.set(true)
    }

    private val lines = listOf(
        line(2, "go through my contacts", "go through my content"),
        line(5, "see you tomorrow", "see you tomorrow"),
    )

    private fun query(
        format: String = ExportDefaults.FORMAT_MARKDOWN,
        content: String = ExportDefaults.CONTENT_CORRECTED,
        grouping: String = ExportGrouping.DAY,
    ) = ExportQuery(format = format, content = content, grouping = grouping)

    private fun render(
        q: ExportQuery,
        rows: List<LineView> = lines,
        flagged: Set<Long> = emptySet(),
    ) = ExportFormatter.render("Tue 23 Sep", rows, q, flagged, exportedAt = day, zone = zone)

    // --- markdown -------------------------------------------------------------------------

    @Test
    fun `markdown has a title, a provenance line, a day heading and timestamped bullets`() {
        val md = render(query())
        assertTrue(md.startsWith("# Tue 23 Sep"))
        assertTrue("names the model", "Gemma 3 1B Instruct (Q4_K_M)" in md)
        assertTrue("## Wednesday 23 September 2026" in md)
        assertTrue("- **14:02:00** go through my content" in md)
        assertFalse("corrected means corrected", "contacts" in md)
    }

    @Test
    fun `markdown by hour adds an hour heading, flat adds neither`() {
        assertTrue("### 14:00" in render(query(grouping = ExportGrouping.HOUR)))
        val flat = render(query(grouping = ExportGrouping.FLAT))
        assertFalse("## Wednesday" in flat)
        assertFalse("### 14:00" in flat)
        assertTrue("the lines are still there", "- **14:02:00** go through my content" in flat)
    }

    @Test
    fun `both shows the original only under lines that changed`() {
        val md = render(query(content = ExportDefaults.CONTENT_BOTH))
        assertTrue("  - _original:_ go through my contacts" in md)
        assertEquals(1, Regex("_original:_").findAll(md).count())
    }

    // --- plain text -----------------------------------------------------------------------

    @Test
    fun `plain text uses brackets and no markdown punctuation`() {
        val txt = render(query(format = ExportDefaults.FORMAT_TEXT, content = ExportDefaults.CONTENT_ORIGINAL))
        assertTrue("[14:02:00] go through my contacts" in txt)
        assertTrue("== Wednesday 23 September 2026 ==" in txt)
        assertFalse("#" in txt)
    }

    @Test
    fun `a twelve-hour export reads as a twelve-hour clock`() {
        Clocks.set(false)
        val txt = render(query(format = ExportDefaults.FORMAT_TEXT, content = ExportDefaults.CONTENT_ORIGINAL))
        assertTrue("2:02:00" in txt)
        assertFalse("[14:02:00]" in txt)
    }

    // --- csv ------------------------------------------------------------------------------

    @Test
    fun `csv has the declared header and one row per line`() {
        val csv = render(query(format = ExportDefaults.FORMAT_CSV)).trim().lines()
        assertEquals("date,time,text,source,flagged", csv.first())
        assertEquals(ExportFormatter.CSV_HEADER, csv.first())
        assertEquals(3, csv.size)
        assertEquals("2026-09-23,14:02:00,go through my content,corrected,false", csv[1])
        assertEquals("2026-09-23,14:05:00,see you tomorrow,original,false", csv[2])
    }

    @Test
    fun `csv times are 24-hour and dates are ISO whatever the clock setting says`() {
        Clocks.set(false)
        val csv = render(query(format = ExportDefaults.FORMAT_CSV)).trim().lines()
        assertTrue("a spreadsheet must not have to guess", csv[1].startsWith("2026-09-23,14:02:00,"))
    }

    @Test
    fun `csv marks a flagged line`() {
        val csv = render(query(format = ExportDefaults.FORMAT_CSV), flagged = setOf(2L)).trim().lines()
        assertTrue(csv[1].endsWith(",true"))
        assertTrue(csv[2].endsWith(",false"))
    }

    @Test
    fun `csv quotes commas, quotes and newlines rather than corrupting the row`() {
        val awkward = listOf(line(1, """he said "yes, of course" and left"""))
        val csv = render(query(format = ExportDefaults.FORMAT_CSV, content = ExportDefaults.CONTENT_ORIGINAL), awkward)
            .trim().lines()
        assertEquals("""2026-09-23,14:01:00,"he said ""yes, of course"" and left",original,false""", csv[1])
    }

    @Test
    fun `csv both writes the corrected row then the original row`() {
        val csv = render(query(format = ExportDefaults.FORMAT_CSV, content = ExportDefaults.CONTENT_BOTH))
            .trim().lines()
        assertEquals(4, csv.size)
        assertTrue(csv[1].contains("go through my content,corrected"))
        assertTrue(csv[2].contains("go through my contacts,original"))
    }

    // --- json lines -----------------------------------------------------------------------

    @Test
    fun `json lines is one object per line with escaped text`() {
        val awkward = listOf(line(1, "he said \"yes\"\tthen left"))
        val out = render(query(format = ExportDefaults.FORMAT_JSONL, content = ExportDefaults.CONTENT_ORIGINAL), awkward)
        val row = out.trim()
        assertTrue(row.startsWith("{") && row.endsWith("}"))
        assertTrue("\"text\":\"he said \\\"yes\\\"\\tthen left\"" in row)
        assertTrue("\"source\":\"original\"" in row)
        assertTrue("\"flagged\":false" in row)
        assertTrue("\"epochMs\":" in row)
    }

    // --- names and sizes ------------------------------------------------------------------

    @Test
    fun `file names carry ISO dates and the format's extension`() {
        assertEquals("recorder_2026-09-23_to_2026-09-23.md", ExportFormatter.fileName(lines, query(), zone))
        assertEquals(
            "recorder_2026-09-23_to_2026-09-23.csv",
            ExportFormatter.fileName(lines, query(format = ExportDefaults.FORMAT_CSV), zone),
        )
        assertEquals("txt", ExportFormatter.extension(ExportDefaults.FORMAT_TEXT))
        assertEquals("jsonl", ExportFormatter.extension(ExportDefaults.FORMAT_JSONL))
    }

    @Test
    fun `a multi-day export spans both dates in its name`() {
        val later = line(2, "next day").let { l ->
            LineView(l.segment.copy(startTs = l.segment.startTs + 2 * ExportQuery.DAY_MS), null)
        }
        val name = ExportFormatter.fileName(lines + later, query(), zone)
        assertEquals("recorder_2026-09-23_to_2026-09-25.md", name)
    }

    @Test
    fun `the size estimate is in the right order of magnitude`() {
        // Within a factor of two of the real thing is enough for a number that exists to stop
        // someone exporting blind; being exact would mean rendering the file to measure it.
        val real = render(query()).toByteArray().size.toLong()
        val estimate = ExportFormatter.estimateBytes(lines, query())
        assertTrue("estimate $estimate vs real $real", estimate in (real / 2)..(real * 2))
    }
}
