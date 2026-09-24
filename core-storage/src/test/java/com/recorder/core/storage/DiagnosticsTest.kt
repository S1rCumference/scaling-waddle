package com.recorder.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the in-memory side only: [Diagnostics.init] needs a real [android.content.Context]
 * for the persisted file, which a plain JVM test does not have. Without calling it, [Diagnostics]
 * behaves exactly the same except nothing is written to disk — appendToFile no-ops when
 * uninitialised — so the ordering, capping and rendering logic that matters most is still
 * covered here.
 */
class DiagnosticsTest {

    @Before
    fun reset() {
        Diagnostics.clear()
    }

    @Test
    fun `entries are newest first`() {
        Diagnostics.i("A", "first")
        Diagnostics.w("B", "second")
        Diagnostics.e("C", "third")

        val entries = Diagnostics.entries.value
        assertEquals(listOf("third", "second", "first"), entries.map { it.message })
        assertEquals(
            listOf(DiagnosticEntry.Level.ERROR, DiagnosticEntry.Level.WARN, DiagnosticEntry.Level.INFO),
            entries.map { it.level },
        )
    }

    @Test
    fun `a throwable's message is folded into the entry, not a stack trace`() {
        Diagnostics.w("Tag", "load failed", IllegalStateException("bad state"))
        assertEquals("load failed (bad state)", Diagnostics.entries.value.first().message)
    }

    @Test
    fun `a throwable with no message falls back to its class name`() {
        Diagnostics.e("Tag", "crashed", RuntimeException())
        assertTrue(Diagnostics.entries.value.first().message.endsWith("(RuntimeException)"))
    }

    @Test
    fun `newlines in a message cannot break the one-line-per-entry file format`() {
        Diagnostics.i("Tag", "line one\nline two")
        assertEquals("line one line two", Diagnostics.entries.value.first().message)
    }

    @Test
    fun `the list is capped so it cannot grow without bound`() {
        repeat(320) { i -> Diagnostics.i("Tag", "entry $i") }
        assertEquals(300, Diagnostics.entries.value.size)
        // Newest first, so the cap must have dropped the oldest, not the newest.
        assertEquals("entry 319", Diagnostics.entries.value.first().message)
    }

    @Test
    fun `clear empties the list`() {
        Diagnostics.i("Tag", "something")
        Diagnostics.clear()
        assertTrue(Diagnostics.entries.value.isEmpty())
    }

    @Test
    fun `renderText includes the header and every entry oldest first`() {
        Diagnostics.i("A", "one")
        Diagnostics.w("B", "two")
        val text = Diagnostics.renderText("Device summary here")
        assertTrue(text.startsWith("Device summary here"))
        assertTrue(text.indexOf("A: one") < text.indexOf("B: two"))
    }

    @Test
    fun `renderText says so when nothing has been logged`() {
        assertTrue(Diagnostics.renderText("header").contains("nothing logged yet"))
    }
}
