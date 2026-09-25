package com.recorder.core.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window Silero is actually handed. Getting this wrong is not a crash and not a log line:
 * the model returns about 0.001 for every frame of everything, which is indistinguishable from
 * a correct detector in a quiet room. So the shape is asserted here rather than trusted.
 */
class FrameContextTest {

    @Test
    fun `sixteen kilohertz carries sixty four samples, eight carries thirty two`() {
        assertEquals(64, FrameContext.sizeFor(16_000))
        assertEquals(32, FrameContext.sizeFor(8_000))
    }

    @Test
    fun `the first window is the frame behind silence, at the length the model wants`() {
        val context = FrameContext(64)
        val frame = FloatArray(512) { 1f }

        val window = context.window(frame)

        assertEquals(576, window.size)
        assertTrue("the carried context starts empty", window.take(64).all { it == 0f })
        assertTrue("the frame follows it", window.drop(64).all { it == 1f })
    }

    @Test
    fun `the next window carries the tail of the frame before it`() {
        val context = FrameContext(64)
        context.window(FloatArray(512) { 7f })

        val window = context.window(FloatArray(512) { 9f })

        assertEquals(576, window.size)
        assertTrue("the previous frame's tail leads", window.take(64).all { it == 7f })
        assertTrue("then this frame", window.drop(64).all { it == 9f })
    }

    @Test
    fun `the carried tail is the last samples of the stream, not of one frame`() {
        // A frame shorter than the context itself must not lose the samples before it: the
        // context is defined by the audio stream, not by frame boundaries.
        val context = FrameContext(64)
        context.window(FloatArray(512) { 1f })
        context.window(FloatArray(10) { 2f })

        val window = context.window(FloatArray(512) { 3f })

        assertEquals(54, window.take(64).count { it == 1f })
        assertEquals(10, window.take(64).count { it == 2f })
    }

    @Test
    fun `reset forgets the carried audio, as a pause must`() {
        val context = FrameContext(64)
        context.window(FloatArray(512) { 5f })
        context.reset()

        assertTrue(context.window(FloatArray(512) { 1f }).take(64).all { it == 0f })
    }

    @Test
    fun `a zero-length context is a plain passthrough, which is what v4 needs`() {
        val context = FrameContext(0)
        val frame = FloatArray(1536) { 4f }

        val window = context.window(frame)

        assertEquals(1536, window.size)
        assertTrue(window.all { it == 4f })
    }

    @Test
    fun `the built-in speech probe is voiced, bounded and long enough to score`() {
        val probe = SileroVad.speechProbe(16_000)

        assertEquals(8_000, probe.size)
        assertTrue("half a second is at least fifteen analysis frames", probe.size / 512 >= 15)
        val peak = probe.maxOf { abs(it) }
        assertTrue("it has real amplitude: $peak", peak > 0.1f)
        assertTrue("and never clips: $peak", peak <= 1f)
        // A four-per-second envelope means the quiet parts really are quiet, which is what
        // makes it look like syllables rather than a tone.
        val firstHalfPeak = probe.take(probe.size / 2).maxOf { abs(it) }
        val quietest = probe.toList().chunked(512).minOf { chunk -> chunk.maxOf { abs(it) } }
        assertTrue("the envelope dips: $quietest vs $firstHalfPeak", quietest < firstHalfPeak / 2)
        assertTrue("and it is finite throughout", probe.all { it.isFinite() })
    }
}
