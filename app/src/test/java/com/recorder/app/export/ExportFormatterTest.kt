package com.recorder.app.export

import com.recorder.app.ui.LineView
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.SegmentCorrection
import com.recorder.core.storage.TranscriptSegment
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatterTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val day = DayKey.startOf(20260923, zone)

    private fun line(minute: Int, original: String, corrected: String? = null): LineView {
        val ts = day + 14 * 3_600_000L + minute * 60_000L
        val segment = TranscriptSegment(id = minute.toLong(), startTs = ts, endTs = ts + 2_000, text = original)
        val correction = corrected?.let {
            SegmentCorrection(segmentId = segment.id, text = it, pass = CorrectionPass.END_OF_DAY, engine = "Qwen 3 4B (on this phone)")
        }
        return LineView(segment, correction)
    }

    private val lines = listOf(
        line(2, "go through my contacts", "go through my content"),
        line(5, "see you tomorrow", "see you tomorrow"),
    )

    @Test
    fun `markdown corrected export has timestamps and names the pass`() {
        val md = ExportFormatter.render("Tue 23 Sep", lines, ExportDefaults.CONTENT_CORRECTED, ExportDefaults.FORMAT_MARKDOWN, day, zone)
        assertTrue(md.startsWith("# Tue 23 Sep"))
        assertTrue("- **14:02:00** go through my content" in md)
        assertTrue("end-of-day pass" in md)
        assertFalse("contacts" in md)
    }

    @Test
    fun `both shows the original only under lines that changed`() {
        val md = ExportFormatter.render("t", lines, ExportDefaults.CONTENT_BOTH, ExportDefaults.FORMAT_MARKDOWN, day, zone)
        assertTrue("  - _original:_ go through my contacts" in md)
        assertEquals(1, Regex("_original:_").findAll(md).count())
    }

    @Test
    fun `plain text original export`() {
        val txt = ExportFormatter.render("t", lines, ExportDefaults.CONTENT_ORIGINAL, ExportDefaults.FORMAT_TEXT, day, zone)
        assertTrue("[14:02:00] go through my contacts" in txt)
        assertFalse("#" in txt)
    }

    @Test
    fun `file names are safe and carry the format`() {
        assertEquals("recorder-recorder-tue-23-sep.md", ExportFormatter.fileName("Recorder — Tue 23 Sep", ExportDefaults.FORMAT_MARKDOWN))
        assertTrue(ExportFormatter.fileName("x", ExportDefaults.FORMAT_TEXT).endsWith(".txt"))
    }
}
