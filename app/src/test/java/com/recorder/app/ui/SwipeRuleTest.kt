package com.recorder.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one number that decides whether swiping a row deletes it. Worth pinning: too small and
 * scrolling a list with a diagonal finger throws away a recording, too large and the gesture
 * is not reachable one-handed on a four-inch cover screen.
 */
class SwipeRuleTest {

    @Test
    fun `a short drag does not delete`() {
        assertFalse(SwipeRule.past(offset = 40f, width = 1080f))
        assertFalse(SwipeRule.past(offset = -40f, width = 1080f))
    }

    @Test
    fun `a third of the row deletes, either direction`() {
        assertTrue(SwipeRule.past(offset = 360f, width = 1080f))
        assertTrue(SwipeRule.past(offset = -360f, width = 1080f))
    }

    @Test
    fun `a narrow row keeps a floor so a stray touch cannot cross it`() {
        // A third of 120px is 40px, which is inside the slop of an ordinary tap.
        assertFalse(SwipeRule.past(offset = 40f, width = 120f))
        assertTrue(SwipeRule.past(offset = SwipeRule.MINIMUM_PX, width = 120f))
    }

    @Test
    fun `no drag at all is never a delete`() {
        assertFalse(SwipeRule.past(offset = 0f, width = 1080f))
    }
}
