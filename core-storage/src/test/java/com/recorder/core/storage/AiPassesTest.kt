package com.recorder.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AiPassesTest {

    @Before
    fun clear() = AiPasses.clear()

    @Test
    fun `passes come back newest first`() {
        AiPasses.record("correction draft", tokens = 10, ms = 1_000, stoppedBy = null, reloadedBecause = null)
        AiPasses.record("repair", tokens = 20, ms = 2_000, stoppedBy = "the 384-token ceiling", reloadedBecause = null)

        val passes = AiPasses.recent()
        assertEquals(listOf("repair", "correction draft"), passes.map { it.label })
        assertEquals("the 384-token ceiling", passes.first().stoppedBy)
        assertNull(passes.last().stoppedBy)
    }

    @Test
    fun `the list is capped, and it is the oldest that go`() {
        repeat(50) { AiPasses.record("pass $it", tokens = 1, ms = 1, stoppedBy = null, reloadedBecause = null) }

        val passes = AiPasses.recent()
        assertEquals(40, passes.size)
        assertEquals("pass 49", passes.first().label)
        assertEquals("pass 10", passes.last().label)
    }

    @Test
    fun `tokens per second is reported per pass, and zero duration does not divide by zero`() {
        AiPasses.record("answer", tokens = 120, ms = 4_000, stoppedBy = null, reloadedBecause = null)
        AiPasses.record("answer", tokens = 5, ms = 0, stoppedBy = null, reloadedBecause = null)

        assertEquals(0.0, AiPasses.recent().first().tokensPerSecond, 0.001)
        assertEquals(30.0, AiPasses.recent().last().tokensPerSecond, 0.001)
    }
}
