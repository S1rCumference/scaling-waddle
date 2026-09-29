package com.recorder.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupCodecTest {

    private fun seg(start: Long, text: String) = TranscriptSegment(
        id = 99,
        startTs = start,
        endTs = start + 1500,
        text = text,
        dayKey = 20260929,
        levelDb = -31.5f,
        zeroCrossingRate = 0.125f,
    )

    @Test fun `round trip keeps everything but the id`() {
        val rows = listOf(
            seg(1_000L, "plain English line"),
            seg(2_000L, "Завтра в девять утра у меня встреча"),
            seg(3_000L, "quotes \" backslash \\ newline \n tab \t and a \u0001 control"),
        )
        val back = BackupCodec.decode(BackupCodec.encode(rows))
        assertEquals(0, back.skipped)
        assertEquals(rows.map { it.copy(id = 0) }, back.segments)
    }

    @Test fun `damaged lines are skipped and counted, the rest kept`() {
        val good = BackupCodec.encode(listOf(seg(5_000L, "kept")))
        val text = "not json\n" + good + "{\"start\":1,\"text\":\"unterminated\n{\"text\":\"no start\"}\n\n"
        val back = BackupCodec.decode(text)
        assertEquals(listOf("kept"), back.segments.map { it.text })
        assertEquals(3, back.skipped)
    }

    @Test fun `a minimal hand-written line is accepted`() {
        val back = BackupCodec.decode("{ \"start\": 1727600000000, \"text\": \"hi\\u0021\" }")
        assertEquals("hi!", back.segments.single().text)
        assertEquals(1727600000000L, back.segments.single().endTs)
    }

    @Test fun `file name is the calendar day`() {
        assertEquals("recorder-2026-09-29.jsonl", BackupCodec.fileName(20260929))
        assertEquals("recorder-2027-01-02.jsonl", BackupCodec.fileName(20270102))
    }
}
