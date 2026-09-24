package com.recorder.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineTotalsTest {

    private fun stats(): PipelineStats = PipelineStats()

    @Test
    fun `the rates the report quotes come from the lifetime counters, not the last minute`() {
        val stats = stats()
        repeat(100) { stats.onFrame(FloatArray(512) { 0.2f }, probability = 0.8f, speaking = true) }
        repeat(100) { stats.onFrame(FloatArray(512) { 0.01f }, probability = 0.05f, speaking = false) }
        stats.onSegment(audioMs = 4_000, decodeMs = 1_000, hadText = true)
        stats.onSegment(audioMs = 2_000, decodeMs = 800, hadText = false)

        // A heartbeat clears its own window. The totals must survive it, or every rate in
        // the report would be measured over whatever happened in the last sixty seconds.
        stats.heartbeat("test")

        val totals = stats.totals()
        assertEquals(200L, totals.frames)
        assertEquals(100L, totals.speechFrames)
        assertEquals(2L, totals.segments)
        assertEquals(1L, totals.transcribed)
        assertEquals(0.5, totals.transcribedFraction!!, 0.001)
        assertEquals(0.3, totals.realtimeFactor!!, 0.001)
        assertEquals(200 * PipelineStats.FRAME_MS, totals.audioMs)
    }

    @Test
    fun `the score distribution keeps every frame, in the bucket its score falls in`() {
        val stats = stats()
        repeat(3) { stats.onFrame(FloatArray(512), probability = 0.05f, speaking = false) }
        repeat(2) { stats.onFrame(FloatArray(512), probability = 0.95f, speaking = true) }
        stats.heartbeat("test")

        val spread = stats.totals().scoreSpread()
        assertEquals("0.0-0.1: 3, 0.9-1.0: 2", spread)
    }

    @Test
    fun `nothing measured reads as nothing measured rather than as zero`() {
        val totals = stats().totals()
        assertNull(totals.scoreSpread())
        assertNull(totals.transcribedFraction)
        assertNull(totals.realtimeFactor)
        assertEquals(0L, totals.frames)
    }
}
