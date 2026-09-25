package com.recorder.app.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input ceiling, which is the one cap that cannot be enforced inside the generate loop:
 * by the time a prompt is being decoded it has already been built and sent.
 */
class CorrectionBatchingTest {

    @Test
    fun `token estimates round up rather than down`() {
        assertEquals(0, CorrectionRunner.estimateTokens(""))
        assertEquals(1, CorrectionRunner.estimateTokens("a"))
        assertEquals(1, CorrectionRunner.estimateTokens("abcd"))
        assertEquals(2, CorrectionRunner.estimateTokens("abcde"))
        // Rounding up matters: a ceiling built on an estimate that rounds down is not one.
        assertTrue(CorrectionRunner.estimateTokens("x".repeat(100)) >= 25)
    }

    @Test
    fun `batches stay under the token ceiling`() {
        val lines = List(100) { "x".repeat(40) } // 10 estimated tokens each
        val batches = CorrectionRunner.packByTokens(lines, maxTokens = 100, maxItems = 40) {
            CorrectionRunner.estimateTokens(it)
        }
        batches.forEach { batch ->
            val tokens = batch.sumOf { CorrectionRunner.estimateTokens(it) }
            assertTrue("a batch cost $tokens tokens", tokens <= 100)
        }
        assertEquals("every line must be in exactly one batch", 100, batches.sumOf { it.size })
    }

    @Test
    fun `batches stay under the line ceiling even when the lines are tiny`() {
        val lines = List(100) { "hi" }
        val batches = CorrectionRunner.packByTokens(lines, maxTokens = 10_000, maxItems = 40) {
            CorrectionRunner.estimateTokens(it)
        }
        assertTrue(batches.all { it.size <= 40 })
        assertEquals(100, batches.sumOf { it.size })
        assertEquals(3, batches.size)
    }

    @Test
    fun `one line longer than the whole budget still goes, on its own`() {
        val lines = listOf("short", "x".repeat(10_000), "short")
        val batches = CorrectionRunner.packByTokens(lines, maxTokens = 100, maxItems = 40) {
            CorrectionRunner.estimateTokens(it)
        }
        // Dropping it would lose a transcript line silently, which is worse than one
        // oversized prompt the model truncates.
        assertEquals(3, batches.sumOf { it.size })
        assertTrue(
            "the oversized line is alone in its batch",
            batches.any { it.size == 1 && it[0].length == 10_000 },
        )
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(
            emptyList<List<String>>(),
            CorrectionRunner.packByTokens(emptyList<String>(), 100, 40) { 1 },
        )
    }

    @Test
    fun `the declared ceiling is the one this release was specified with`() {
        assertEquals(1_200, CorrectionRunner.MAX_INPUT_TOKENS)
    }
}
