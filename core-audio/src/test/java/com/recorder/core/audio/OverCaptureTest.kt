package com.recorder.core.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OverCaptureTest {

    private val sampleRate = 16_000
    private val frameSize = 512
    private val frameMs = 32L

    private fun frame(index: Int, amplitude: Float): AudioFrame = AudioFrame(
        samples = FloatArray(frameSize) { i ->
            (amplitude * sin(2.0 * PI * 220.0 * i / sampleRate)).toFloat()
        },
        sampleRate = sampleRate,
        timestampMs = index * frameMs,
    )

    /** Enough frames to cross a 20-second window, plus one. */
    private val windowFrames = (20_000 / frameMs).toInt() + 1

    @Test
    fun `a loud window the detector never called speech is captured anyway`() {
        val net = OverCapture()
        var captured: SpeechSegment? = null
        repeat(windowFrames) { i ->
            net.accept(frame(i, 0.4f), speechProbability = 0.01f)?.let { captured = it }
        }

        assertNotNull("loud audio with no detected speech should be rescued", captured)
        assertEquals(sampleRate, captured!!.sampleRate)
    }

    @Test
    fun `a quiet window is left alone`() {
        val net = OverCapture()
        var captured: SpeechSegment? = null
        repeat(windowFrames) { i ->
            net.accept(frame(i, 0.0005f), speechProbability = 0.01f)?.let { captured = it }
        }

        // Silence is not worth a decode pass, and this is the common case overnight.
        assertNull(captured)
    }

    @Test
    fun `a window the detector did recognise is not captured twice`() {
        val net = OverCapture()
        var captured: SpeechSegment? = null
        repeat(windowFrames) { i ->
            // One frame over the line is enough: the detector is working here.
            val probability = if (i == 5) 0.9f else 0.01f
            net.accept(frame(i, 0.4f), probability)?.let { captured = it }
        }

        assertNull(captured)
    }

    @Test
    fun `a real segment clears the window`() {
        val net = OverCapture()
        var captured: SpeechSegment? = null
        repeat(windowFrames - 2) { i ->
            net.accept(frame(i, 0.4f), speechProbability = 0.01f)?.let { captured = it }
        }
        // The segmenter emitted something of its own, so this stretch is already spoken for.
        net.onSegment()
        repeat(3) { i ->
            net.accept(frame(windowFrames + i, 0.4f), speechProbability = 0.01f)?.let { captured = it }
        }

        assertNull("the window restarts after a real segment", captured)
    }

    @Test
    fun `the captured audio is the audio that went in`() {
        val net = OverCapture()
        var captured: SpeechSegment? = null
        repeat(windowFrames) { i ->
            net.accept(frame(i, 0.4f), speechProbability = 0.01f)?.let { captured = it }
        }

        val segment = requireNotNull(captured)
        assertEquals(windowFrames * frameSize, segment.samples.size)
        assertEquals(0L, segment.startTs)
    }
}
