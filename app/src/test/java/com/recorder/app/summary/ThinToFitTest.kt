package com.recorder.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap on how much transcript a summary prompt carries.
 *
 * This is the number that made summarising never finish. The first version capped 120 lines at
 * 400 characters each — up to 48,000 characters, roughly 12,000 tokens, against a native context
 * of 8,192. The prompt overflowed, and an overflowed context is not an error: it is a model
 * reading a truncated prompt and answering slowly and badly with nothing anywhere saying why.
 */
class ThinToFitTest {

    /** The runner's own ceiling, read from it rather than restated here. */
    private val ceiling = SummaryRunner.MAX_SOURCE_CHARS

    private fun cost(items: List<String>) = items.sumOf { it.length + 3 }

    @Test
    fun `a short hour is passed through untouched`() {
        val lines = List(20) { "this is line $it of a fairly quiet hour" }
        assertEquals(lines, SummaryRunner.thinToFit(lines))
    }

    @Test
    fun `a busy hour is cut to the ceiling`() {
        val lines = List(600) { "this is line $it of a very busy hour with a lot of talking in it" }
        val kept = SummaryRunner.thinToFit(lines)
        assertTrue("cost was ${cost(kept)}", cost(kept) <= ceiling)
        assertTrue("something must survive", kept.isNotEmpty())
    }

    @Test
    fun `thinning samples across the whole span rather than taking the start`() {
        // The first version took the first 120 lines, so a busy hour was summarised from its
        // first ten minutes and named confidently after the wrong thing.
        val lines = List(600) { "line $it" }
        val kept = SummaryRunner.thinToFit(lines)
        assertTrue("the start must be represented", kept.first().contains("line 0"))
        val lastIndex = kept.last().removePrefix("line ").trim().toInt()
        assertTrue(
            "the end of the hour must be represented, got up to line $lastIndex",
            lastIndex > 400,
        )
    }

    @Test
    fun `a few enormous lines are shortened rather than all but one dropped`() {
        val lines = List(6) { "x".repeat(4_000) }
        val kept = SummaryRunner.thinToFit(lines)
        assertTrue("cost was ${cost(kept)}", cost(kept) <= ceiling)
        // Half of each of several lines beats all of one line: the summary needs the shape of
        // the hour, not one paragraph of it in full.
        assertTrue("kept ${kept.size} item(s)", kept.size >= 2)
    }

    @Test
    fun `one line longer than the whole ceiling is still carried, shortened`() {
        val kept = SummaryRunner.thinToFit(listOf("y".repeat(50_000)))
        assertEquals(1, kept.size)
        assertTrue(cost(kept) <= ceiling)
        assertTrue(kept.single().isNotEmpty())
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(SummaryRunner.thinToFit(emptyList()).isEmpty())
    }

    @Test
    fun `the result never exceeds the ceiling, at any input size`() {
        listOf(1, 5, 40, 121, 500, 2_000).forEach { n ->
            val kept = SummaryRunner.thinToFit(List(n) { "a line of roughly ordinary length $it" })
            assertTrue("$n lines cost ${cost(kept)}", cost(kept) <= ceiling)
        }
    }
}
