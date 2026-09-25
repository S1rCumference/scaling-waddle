package com.recorder.core.llm

import com.recorder.core.storage.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPromptTest {

    private fun seg(id: Long, text: String) = TranscriptSegment(id = id, startTs = id * 1000, endTs = id * 1000 + 500, text = text)

    @Test
    fun `the real example from the logs is accepted as a correction`() {
        assertTrue(CorrectionPrompt.plausible("go through my contacts", "go through my content"))
    }

    @Test
    fun `a paraphrase or summary is rejected`() {
        val original = "so I was thinking we could maybe move the delivery to Thursday if that works"
        assertFalse(CorrectionPrompt.plausible(original, "Delivery moved to Thursday."))
        assertFalse(CorrectionPrompt.plausible(original, ""))
    }

    @Test
    fun `numbered lines are parsed in several sloppy formats`() {
        val out = """
            Here you go:
            1| go through my content
            2: the invoice is due Friday
            **3.** call Dave back
            1| a repeated first line is ignored
            9| out of range is ignored
        """.trimIndent()
        val parsed = CorrectionPrompt.parse(out, 3)
        assertEquals(mapOf(0 to "go through my content", 1 to "the invoice is due Friday", 2 to "call Dave back"), parsed)
    }

    @Test
    fun `lines after the last answered one are left for the next batch`() {
        val targets = listOf(seg(1, "go through my contacts"), seg(2, "hello there"), seg(3, "see you tomorrow"))
        val resolved = CorrectionPrompt.resolve(targets, mapOf(0 to "go through my content"))
        assertEquals(mapOf(1L to "go through my content"), resolved)
    }

    @Test
    fun `skipped middle lines count as unchanged and implausible answers keep the original`() {
        val targets = listOf(
            seg(1, "go through my contacts"),
            seg(2, "hello there"),
            seg(3, "we should order twelve more boxes of the large ones"),
        )
        val resolved = CorrectionPrompt.resolve(
            targets,
            mapOf(0 to "go through my content", 2 to "Order boxes."),
        )
        assertEquals("go through my content", resolved[1L])
        assertEquals("hello there", resolved[2L])
        assertEquals("we should order twelve more boxes of the large ones", resolved[3L])
    }

    @Test
    fun `nothing parseable means nothing is stored`() {
        assertTrue(CorrectionPrompt.resolve(listOf(seg(1, "hi")), emptyMap()).isEmpty())
    }

    @Test
    fun `the prompt numbers only the targets and marks context as context`() {
        val prompt = CorrectionPrompt.build(
            CorrectionWindow(before = listOf("earlier line"), targets = listOf(seg(1, "a b c")), after = listOf("later line")),
        )
        assertTrue("1| a b c" in prompt)
        assertTrue("- earlier line" in prompt)
        assertTrue("- later line" in prompt)
        assertFalse("2|" in prompt)
    }

    @Test
    fun `day vocabulary picks recurring names over filler`() {
        val texts = List(4) { "Then I told Marguerite about the Hendricks invoice, really" } + "yeah yeah okay"
        val vocab = DayVocabulary.extract(texts)
        assertTrue("Marguerite" in vocab)
        assertTrue("Hendricks" in vocab)
        assertFalse(vocab.any { it.equals("really", ignoreCase = true) })
    }

}

/** The uncertainty marks the draft pass leaves for the repair pass. */
class CorrectionMarkTest {

    @Test
    fun `marked phrases are found`() {
        val line = "go through my <<contacts>> before the <<stand up>>"
        assertEquals(listOf("contacts", "stand up"), CorrectionPrompt.marks(line))
        assertTrue(CorrectionPrompt.hasMarks(line))
    }

    @Test
    fun `stripping leaves the words and drops the marks`() {
        assertEquals(
            "go through my content today",
            CorrectionPrompt.stripMarks("go through my <<content>> today"),
        )
    }

    @Test
    fun `stripping does not leave double spaces behind`() {
        assertEquals("one two three", CorrectionPrompt.stripMarks("one << two >> three"))
    }

    @Test
    fun `a clean line has nothing to repair`() {
        assertTrue(CorrectionPrompt.marks("nothing uncertain here").isEmpty())
        assertFalse(CorrectionPrompt.hasMarks("nothing uncertain here"))
    }

    @Test
    fun `an unclosed mark is not treated as a span`() {
        // A truncated answer can end mid-mark; that must not swallow the rest of the line.
        assertTrue(CorrectionPrompt.marks("the << thing").isEmpty())
        assertEquals("the << thing", CorrectionPrompt.stripMarks("the << thing"))
    }
}

/** The ceilings that stop a pass running for minutes. */
class TokenBudgetTest {

    @Test
    fun `a correction budget scales with the lines but stays bounded`() {
        assertEquals(128, TokenBudget.forLines(1).maxTokens)
        // The hard output ceiling for 3.0: a batch cannot buy itself a bigger budget.
        assertEquals(TokenBudget.CORRECTION_MAX_TOKENS, TokenBudget.forLines(20).maxTokens)
        assertEquals(TokenBudget.CORRECTION_MAX_TOKENS, TokenBudget.forLines(500).maxTokens)
    }

    @Test
    fun `a repair budget is smaller than a full pass`() {
        assertTrue(TokenBudget.forRepair(1).maxTokens < TokenBudget.forLines(20).maxTokens)
    }

    @Test
    fun `every budget has a deadline`() {
        assertEquals(TokenBudget.CORRECTION_DEADLINE_MS, TokenBudget.forLines(20).deadlineMs)
        assertEquals(TokenBudget.CORRECTION_DEADLINE_MS, TokenBudget.forRepair(3).deadlineMs)
    }
}
