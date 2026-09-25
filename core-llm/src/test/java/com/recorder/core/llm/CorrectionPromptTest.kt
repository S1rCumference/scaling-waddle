package com.recorder.core.llm

import com.recorder.core.storage.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun seg(id: Long, text: String) =
    TranscriptSegment(id = id, startTs = id * 1000, endTs = id * 1000 + 500, text = text)

/**
 * The guards on a correction. These are the only thing between a 1B model's guess and somebody's
 * record of a real conversation, so each one is pinned individually rather than tested as a
 * whole-answer smoke test.
 */
class CorrectionPromptTest {

    private val source = "I need to go through my contacts before Friday\n" +
        "Dave said the invoice was already paid\n" +
        "we should check the contacts again"

    @Test
    fun `a clean answer becomes substitutions`() {
        val fixes = CorrectionPrompt.parse("contacts > content\nDave > Dev", source)
        assertEquals(2, fixes.size)
        assertEquals("contacts", fixes[0].wrong)
        assertEquals("content", fixes[0].right)
    }

    @Test
    fun `NONE means nothing was misheard, not a failure to answer`() {
        assertTrue(CorrectionPrompt.parse("NONE", source).isEmpty())
        assertTrue(CorrectionPrompt.parse("none", source).isEmpty())
    }

    @Test
    fun `decorated answers still parse`() {
        val fixes = CorrectionPrompt.parse("- **contacts** > `content`\n* \"Dave\" > 'Dev'", source)
        assertEquals(listOf("contacts", "Dave"), fixes.map { it.wrong })
        assertEquals(listOf("content", "Dev"), fixes.map { it.right })
    }

    @Test
    fun `preamble and trailing prose are ignored`() {
        val reply = "Here are the mistakes I found:\ncontacts > content\nThat is all I could see."
        val fixes = CorrectionPrompt.parse(reply, source)
        assertEquals(1, fixes.size)
        assertEquals("content", fixes.single().right)
    }

    // --- the guards ----------------------------------------------------------------------

    @Test
    fun `a fix whose wrong side is not in the transcript is thrown away`() {
        // The guard that makes a hallucinated correction impossible rather than unlikely.
        assertTrue(CorrectionPrompt.parse("mortgage > storage", source).isEmpty())
    }

    @Test
    fun `a one or two letter wrong side is thrown away`() {
        // "I" is in the transcript, and replacing it would fire on half the lines.
        assertTrue(CorrectionPrompt.parse("I > we", source).isEmpty())
        assertTrue(CorrectionPrompt.parse("go > do", source).isEmpty())
    }

    @Test
    fun `a substitution that changes nothing is thrown away`() {
        assertTrue(CorrectionPrompt.parse("contacts > contacts", source).isEmpty())
        assertTrue(CorrectionPrompt.parse("contacts > CONTACTS", source).isEmpty())
    }

    @Test
    fun `a replacement nothing like the length of the original is thrown away`() {
        // A mishearing sounds like what was said. This is a paraphrase.
        assertTrue(
            CorrectionPrompt.parse(
                "contacts > the list of people I have been meaning to call",
                source,
            ).isEmpty(),
        )
    }

    @Test
    fun `a multi-clause replacement is thrown away`() {
        val reply = "Dave said the invoice > Dave mentioned that the invoice for last month"
        assertTrue(CorrectionPrompt.parse(reply, source).isEmpty())
    }

    @Test
    fun `the same fix twice counts once`() {
        val fixes = CorrectionPrompt.parse("contacts > content\ncontacts > content", source)
        assertEquals(1, fixes.size)
    }

    @Test
    fun `an answer cannot rewrite a whole hour`() {
        val many = (1..60).joinToString("\n") { "contacts > content$it" }
        assertTrue(CorrectionPrompt.parse(many, source).size <= CorrectionPrompt.MAX_FIXES)
    }

    @Test
    fun `a line with no arrow is skipped`() {
        assertTrue(CorrectionPrompt.parse("contacts should be content", source).isEmpty())
    }

    @Test
    fun `an empty or blank answer is no fixes`() {
        assertTrue(CorrectionPrompt.parse("", source).isEmpty())
        assertTrue(CorrectionPrompt.parse("   \n \n", source).isEmpty())
    }

    // --- applying -------------------------------------------------------------------------

    @Test
    fun `one fix corrects every line the word appears in`() {
        // The reason substitutions beat rewriting: recognition mishears a word the same way
        // every time, so one fix is worth however many lines contain it.
        val targets = listOf(
            seg(1, "I need to go through my contacts before Friday"),
            seg(2, "nothing relevant here"),
            seg(3, "we should check the contacts again"),
        )
        val fixed = CorrectionPrompt.apply(targets, listOf(Mishearing("contacts", "content")))
        assertEquals(listOf(1L, 3L), fixed.map { it.segmentId })
        assertEquals("I need to go through my content before Friday", fixed[0].text)
        assertEquals("we should check the content again", fixed[1].text)
    }

    @Test
    fun `a line nothing applies to is not stored`() {
        val targets = listOf(seg(1, "nothing relevant here"))
        assertTrue(CorrectionPrompt.apply(targets, listOf(Mishearing("contacts", "content"))).isEmpty())
    }

    @Test
    fun `matching is case-insensitive and whole-word`() {
        val targets = listOf(
            seg(1, "Contacts first, then the rest"),
            // "contacted" contains "contact" but is a different word.
            seg(2, "I contacted them already"),
        )
        val fixed = CorrectionPrompt.apply(targets, listOf(Mishearing("contact", "content")))
        assertTrue("contacted must not be touched", fixed.none { it.segmentId == 2L })
    }

    @Test
    fun `a word at the edge of punctuation is still matched`() {
        val targets = listOf(seg(1, "go through my contacts, then Friday (contacts)."))
        val fixed = CorrectionPrompt.apply(targets, listOf(Mishearing("contacts", "content")))
        assertEquals("go through my content, then Friday (content).", fixed.single().text)
    }

    @Test
    fun `several fixes on one line all apply and are all recorded`() {
        val targets = listOf(seg(1, "Dave went through my contacts"))
        val fixed = CorrectionPrompt.apply(
            targets,
            listOf(Mishearing("contacts", "content"), Mishearing("Dave", "Dev")),
        )
        assertEquals("Dev went through my content", fixed.single().text)
        assertEquals(2, fixed.single().applied.size)
    }

    @Test
    fun `no fixes means nothing is touched`() {
        val targets = listOf(seg(1, "anything at all"))
        assertTrue(CorrectionPrompt.apply(targets, emptyList()).isEmpty())
    }

    @Test
    fun `a replacement containing a dollar sign is inserted literally`() {
        // Regex.replace treats $1 as a group reference; getting this wrong corrupts the line.
        val targets = listOf(seg(1, "it cost fifty dollars"))
        val fixed = CorrectionPrompt.apply(targets, listOf(Mishearing("fifty dollars", "$50")))
        assertEquals("it cost $50", fixed.single().text)
    }

    // --- the prompt -----------------------------------------------------------------------

    @Test
    fun `the prompt carries the transcript and the day's vocabulary`() {
        val built = CorrectionPrompt.build(
            CorrectionWindow(listOf(seg(1, "go through my contacts")), listOf("Hendricks", "Larkspur")),
        )
        assertTrue(built.contains("go through my contacts"))
        assertTrue(built.contains("Hendricks, Larkspur"))
        assertTrue(built.contains(CorrectionPrompt.ARROW))
        assertTrue(built.contains(CorrectionPrompt.NOTHING))
    }

    @Test
    fun `a line's newlines are flattened so one transcript line stays one prompt line`() {
        val built = CorrectionPrompt.build(CorrectionWindow(listOf(seg(1, "first\nsecond"))))
        assertTrue(built.contains("- first second"))
    }

    @Test
    fun `the system prompt forbids rewriting and demands an exact copy on the left`() {
        assertTrue(CorrectionPrompt.SYSTEM.contains("copied exactly"))
        assertTrue(CorrectionPrompt.SYSTEM.contains("Do not fix grammar"))
        assertTrue(CorrectionPrompt.SYSTEM.contains(CorrectionPrompt.NOTHING))
    }

    @Test
    fun `sourceText is every target line, which is what a fix is checked against`() {
        val text = CorrectionPrompt.sourceText(listOf(seg(1, "one"), seg(2, "two")))
        assertTrue(text.contains("one"))
        assertTrue(text.contains("two"))
    }
}

class TokenBudgetTest {

    @Test
    fun `a correction's output ceiling does not scale with the batch`() {
        // The whole point of the substitution shape: reading more transcript costs prefill,
        // which is fast, and never buys a longer answer, which is slow.
        assertEquals(TokenBudget.CORRECTION_MAX_TOKENS, TokenBudget.forFixes().maxTokens)
    }

    @Test
    fun `a summary budget does not grow with the span`() {
        assertEquals(
            TokenBudget.forSummary("hour").maxTokens,
            TokenBudget.forSummary("month").maxTokens,
        )
        assertEquals(TokenBudget.SUMMARY_MAX_TOKENS, TokenBudget.forSummary("day").maxTokens)
    }

    @Test
    fun `only a summary demands an empty context`() {
        // Correction wants the previous window's history; a summary of one hour must not be
        // written with the previous hour still resident, or the model blends them.
        assertTrue(TokenBudget.forSummary("hour").freshContext)
        assertFalse(TokenBudget.forFixes().freshContext)
    }

    @Test
    fun `every budget has a wall clock`() {
        assertEquals(TokenBudget.CORRECTION_DEADLINE_MS, TokenBudget.forFixes().deadlineMs)
        assertEquals(TokenBudget.SUMMARY_DEADLINE_MS, TokenBudget.forSummary("day").deadlineMs)
    }
}
