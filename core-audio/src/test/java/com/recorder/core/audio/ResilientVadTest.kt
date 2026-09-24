package com.recorder.core.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientVadTest {

    private val sampleRate = 16_000
    private val frameSize = 512

    private fun silence() = FloatArray(frameSize)

    private fun tone(amplitude: Float = 0.3f) = FloatArray(frameSize) { i ->
        (amplitude * sin(2.0 * PI * 220.0 * i / sampleRate)).toFloat()
    }

    private class Stub(
        var probability: Float = 0f,
        var throwOnEveryCall: Boolean = false,
    ) : VoiceActivityDetector {
        var closed = false
        override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
            if (throwOnEveryCall) error("inference exploded")
            return probability
        }

        override fun reset() = Unit
        override fun close() { closed = true }
    }

    @Test
    fun `a detector that throws every time is replaced, not treated as silence`() {
        var reason: String? = null
        val broken = Stub(throwOnEveryCall = true)
        val vad = ResilientVad(primary = broken, onFallback = { reason = it })

        // A detector that only ever throws used to make a room full of speech look silent.
        repeat(200) { vad.speechProbability(tone(), sampleRate) }

        assertTrue(vad.usingFallback)
        assertEquals("energy", vad.activeName)
        assertTrue(reason!!.contains("failed"))
        assertTrue(broken.closed)
    }

    @Test
    fun `one bad frame is not enough to drop the detector`() {
        val flaky = Stub(throwOnEveryCall = true)
        val vad = ResilientVad(primary = flaky)
        vad.speechProbability(tone(), sampleRate)
        flaky.throwOnEveryCall = false
        flaky.probability = 0.9f

        assertFalse(vad.usingFallback)
        assertEquals(0.9f, vad.speechProbability(tone(), sampleRate), 1e-6f)
    }

    @Test
    fun `a detector that never reports speech while the room is loud is replaced`() {
        var reason: String? = null
        val deaf = Stub(probability = 0.01f)
        val vad = ResilientVad(primary = deaf, onFallback = { reason = it })

        // Let the energy detector settle on a quiet floor first, then talk over it.
        repeat(50) { vad.speechProbability(silence(), sampleRate) }
        repeat(400) { vad.speechProbability(tone(), sampleRate) }

        assertTrue(vad.usingFallback)
        assertTrue(reason!!.contains("reporting speech"))
    }

    @Test
    fun `a working detector is left alone`() {
        var switched = false
        val good = Stub(probability = 0.8f)
        val vad = ResilientVad(primary = good, onFallback = { switched = true })

        repeat(400) { vad.speechProbability(tone(), sampleRate) }

        assertFalse(switched)
        assertFalse(vad.usingFallback)
        assertEquals("Silero", vad.activeName)
    }

    @Test
    fun `a quiet room does not look like a broken detector`() {
        var switched = false
        val vad = ResilientVad(primary = Stub(probability = 0.02f), onFallback = { switched = true })

        repeat(2_000) { vad.speechProbability(silence(), sampleRate) }

        assertFalse(switched)
    }

    @Test
    fun `a detector installed mid-session is adopted`() {
        val vad = ResilientVad(primary = null)
        assertTrue(vad.usingFallback)

        val late = Stub(probability = 0.7f)
        assertTrue(vad.adopt(late))
        assertFalse(vad.usingFallback)
        assertEquals(0.7f, vad.speechProbability(tone(), sampleRate), 1e-6f)
    }

    @Test
    fun `a detector dropped for failing is not adopted again`() {
        val vad = ResilientVad(primary = Stub(throwOnEveryCall = true))
        repeat(20) { vad.speechProbability(tone(), sampleRate) }
        assertTrue(vad.usingFallback)

        assertFalse(vad.adopt(Stub(probability = 0.9f)))
        assertTrue(vad.usingFallback)
    }
}
