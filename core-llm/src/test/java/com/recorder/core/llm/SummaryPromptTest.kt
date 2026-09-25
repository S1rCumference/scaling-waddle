package com.recorder.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsing side of group summaries. This is where a 1B model's sloppiness lands: it will
 * bold the labels, lower-case them, put the paragraph before the name, or ignore the format
 * entirely. None of that is allowed to lose the text.
 */
class SummaryPromptTest {

    @Test
    fun `the format it was asked for`() {
        val parsed = SummaryPrompt.parse(
            """
            TOPIC: Invoice dispute with the landlord
            NOTES: You went over the unpaid November invoice twice and said you would send the
            bank statement. No date was agreed.
            """.trimIndent(),
        )
        assertEquals("Invoice dispute with the landlord", parsed.title)
        assertTrue(parsed.body.startsWith("You went over the unpaid November invoice"))
        assertTrue(parsed.body.endsWith("No date was agreed."))
    }

    @Test
    fun `bolded and lower-cased labels still parse`() {
        val parsed = SummaryPrompt.parse("**topic:** Van repairs\n**notes:** The clutch again.")
        assertEquals("Van repairs", parsed.title)
        assertEquals("The clutch again.", parsed.body)
    }

    @Test
    fun `the labels in the wrong order still parse`() {
        val parsed = SummaryPrompt.parse("NOTES: Mostly the roof quote.\nTOPIC: Roof quote")
        assertEquals("Roof quote", parsed.title)
        assertEquals("Mostly the roof quote.", parsed.body)
    }

    @Test
    fun `a reply with no labels keeps the text as the body`() {
        val parsed = SummaryPrompt.parse("You talked about the roof quote and nothing else much.")
        assertEquals("", parsed.title)
        assertEquals("You talked about the roof quote and nothing else much.", parsed.body)
        assertFalse(parsed.isBlank)
    }

    @Test
    fun `a short unpunctuated first line is treated as a heading`() {
        val parsed = SummaryPrompt.parse("Roof quote\nYou went through the numbers twice.")
        assertEquals("Roof quote", parsed.title)
        assertEquals("You went through the numbers twice.", parsed.body)
    }

    @Test
    fun `a long first line is prose, not a heading`() {
        val long = "You spent the hour going back and forth over the roof quote with two people."
        val parsed = SummaryPrompt.parse("$long\nThen it moved on.")
        assertEquals("", parsed.title)
        assertTrue(parsed.body.startsWith("You spent the hour"))
    }

    @Test
    fun `a title is trimmed of markdown and trailing punctuation`() {
        assertEquals("Van repairs", SummaryPrompt.parse("TOPIC: **Van repairs.**\nNOTES: x").title)
        assertEquals("Van repairs", SummaryPrompt.parse("TOPIC: \"Van repairs\"\nNOTES: x").title)
    }

    @Test
    fun `an over-long title is cut on a word boundary`() {
        val rambling = "TOPIC: the unpaid November invoice and the bank statement and the " +
            "deposit and everything else that came up\nNOTES: x"
        val title = SummaryPrompt.parse(rambling).title
        assertTrue("was ${title.length} chars", title.length <= SummaryPrompt.TITLE_MAX_CHARS + 1)
        assertTrue(title.endsWith("…"))
        // Cut between words, so the last thing shown is a whole word.
        assertFalse(title.dropLast(1).endsWith(" "))
    }

    @Test
    fun `an empty reply is blank rather than a summary of nothing`() {
        assertTrue(SummaryPrompt.parse("").isBlank)
        assertTrue(SummaryPrompt.parse("   \n  ").isBlank)
    }

    @Test
    fun `a title with no body survives a round trip through storage`() {
        val only = GroupSummary("Roof quote", "")
        assertEquals(only, GroupSummary.parseStored(only.stored()))
    }

    @Test
    fun `a title and body survive a round trip through storage`() {
        val both = GroupSummary("Roof quote", "You went through the numbers twice.\nTwice.")
        assertEquals(both, GroupSummary.parseStored(both.stored()))
    }

    @Test
    fun `a stored row from before titles existed reads as a body`() {
        val old = GroupSummary.parseStored("Just a paragraph with no name in front of it.")
        assertEquals("", old.title)
        assertEquals("Just a paragraph with no name in front of it.", old.body)
    }

    @Test
    fun `an hour is prompted with its lines and a day with its hours' summaries`() {
        val hour = SummaryPrompt.build(SummaryLevel.HOUR, "Tuesday, 14:00 to 15:00", listOf("a", "b"))
        assertTrue(hour.contains("what was transcribed"))
        assertTrue(hour.contains("Tuesday, 14:00 to 15:00"))

        val day = SummaryPrompt.build(SummaryLevel.DAY, "the whole of Tue 3 Mar", listOf("a", "b"))
        assertTrue(day.contains("summaries of each hour"))

        val month = SummaryPrompt.build(SummaryLevel.MONTH, "the whole of March 2026", listOf("a"))
        assertTrue(month.contains("summaries of each day"))
    }

    @Test
    fun `a source line's newlines are flattened so the list stays one item per line`() {
        val built = SummaryPrompt.build(SummaryLevel.HOUR, "an hour", listOf("first\nsecond"))
        assertTrue(built.contains("- first second"))
    }

    @Test
    fun `only the hour level reads raw transcript`() {
        assertFalse(SummaryLevel.HOUR.sourceIsSummaries)
        assertTrue(SummaryLevel.DAY.sourceIsSummaries)
        assertTrue(SummaryLevel.MONTH.sourceIsSummaries)
    }

    @Test
    fun `the system prompt forbids inventing things`() {
        // The one failure mode that matters for an always-on transcript of real conversations.
        assertTrue(SummaryPrompt.SYSTEM.contains("Do not invent"))
        assertTrue(SummaryPrompt.SYSTEM.contains(SummaryPrompt.TITLE_MARK))
        assertTrue(SummaryPrompt.SYSTEM.contains(SummaryPrompt.BODY_MARK))
    }

    @Test
    fun `a summary budget does not grow with the span`() {
        assertEquals(
            TokenBudget.forSummary("hour").maxTokens,
            TokenBudget.forSummary("month").maxTokens,
        )
        assertEquals(TokenBudget.SUMMARY_MAX_TOKENS, TokenBudget.forSummary("day").maxTokens)
    }
}
