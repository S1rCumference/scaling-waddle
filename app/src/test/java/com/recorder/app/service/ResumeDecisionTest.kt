package com.recorder.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeDecisionTest {

    @Test
    fun `a deliberate stop is never overridden`() {
        assertEquals(
            ResumeAction.NOTHING,
            ResumeDecision.afterBoot(recordingEnabled = false, isDeviceOwner = true),
        )
        assertEquals(
            ResumeAction.NOTHING,
            ResumeDecision.onWatchdogCheck(
                recordingEnabled = false,
                serviceRunning = false,
                isDeviceOwner = true,
            ),
        )
    }

    @Test
    fun `device owner starts recording itself after a reboot`() {
        assertEquals(
            ResumeAction.START_DIRECTLY,
            ResumeDecision.afterBoot(recordingEnabled = true, isDeviceOwner = true),
        )
    }

    /**
     * The important one: without device owner, a direct start from a boot receiver throws,
     * so the only correct answer is to ask for a tap.
     */
    @Test
    fun `without device owner a reboot asks for a tap instead of starting`() {
        assertEquals(
            ResumeAction.ASK_WITH_NOTIFICATION,
            ResumeDecision.afterBoot(recordingEnabled = true, isDeviceOwner = false),
        )
    }

    @Test
    fun `a healthy service is left alone`() {
        assertEquals(
            ResumeAction.NOTHING,
            ResumeDecision.onWatchdogCheck(
                recordingEnabled = true,
                serviceRunning = true,
                isDeviceOwner = false,
            ),
        )
    }

    @Test
    fun `a dead service is recovered according to privilege`() {
        assertEquals(
            ResumeAction.START_DIRECTLY,
            ResumeDecision.onWatchdogCheck(
                recordingEnabled = true,
                serviceRunning = false,
                isDeviceOwner = true,
            ),
        )
        assertEquals(
            ResumeAction.ASK_WITH_NOTIFICATION,
            ResumeDecision.onWatchdogCheck(
                recordingEnabled = true,
                serviceRunning = false,
                isDeviceOwner = false,
            ),
        )
    }
}
