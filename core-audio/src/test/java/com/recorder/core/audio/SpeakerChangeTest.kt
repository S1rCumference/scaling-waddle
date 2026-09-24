package com.recorder.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar is high on purpose: a false marker splits one person's sentence and reads as
 * nonsense, a missed one only leaves the transcript as flat as it already was.
 */
class SpeakerChangeTest {

    private val low = VoicePrint(levelDb = -20f, zeroCrossingRate = 0.05f)
    private val loudAndHigh = VoicePrint(levelDb = -10f, zeroCrossingRate = 0.12f)

    @Test
    fun `a breath between sentences is the same person`() {
        assertFalse(SpeakerChange.between(low, loudAndHigh, gapMs = 400))
    }

    @Test
    fun `a pause and a different voice is somebody else`() {
        assertTrue(SpeakerChange.between(low, loudAndHigh, gapMs = 1_500))
    }

    @Test
    fun `a pause with the same voice is not a change`() {
        assertFalse(SpeakerChange.between(low, low.copy(levelDb = -21f), gapMs = 1_500))
    }

    @Test
    fun `a long silence starts a new situation whatever it sounded like`() {
        assertTrue(SpeakerChange.between(low, low, gapMs = 60_000))
    }

    @Test
    fun `segments recorded before this existed never produce a marker`() {
        // Older rows carry a zero voice print; guessing from that would be inventing.
        assertFalse(SpeakerChange.between(VoicePrint.NONE, loudAndHigh, gapMs = 2_000))
        assertFalse(SpeakerChange.between(low, VoicePrint.NONE, gapMs = 2_000))
    }

    @Test
    fun `a voice print is computed from the samples`() {
        val quiet = VoicePrint.of(FloatArray(512) { 0.001f })
        val loud = VoicePrint.of(FloatArray(512) { if (it % 2 == 0) 0.5f else -0.5f })
        assertTrue(loud.levelDb > quiet.levelDb)
        // Alternating samples cross zero on every step; a constant never does.
        assertTrue(loud.zeroCrossingRate > quiet.zeroCrossingRate)
    }

    @Test
    fun `an empty segment has no voice print`() {
        assertTrue(VoicePrint.of(FloatArray(0)) == VoicePrint.NONE)
    }
}
