package com.recorder.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    /** 32 ms per frame, matching the real capture configuration. */
    private fun frame(index: Int) = AudioFrame(
        samples = FloatArray(512),
        sampleRate = 16_000,
        timestampMs = index * 32L,
    )

    private fun SpeechSegmenter.feed(range: IntRange, speech: Boolean): SpeechSegment? {
        var out: SpeechSegment? = null
        for (i in range) {
            accept(frame(i), if (speech) 1f else 0f)?.let { out = it }
        }
        return out
    }

    @Test
    fun `silence alone never produces a segment`() {
        val segmenter = SpeechSegmenter()
        assertNull(segmenter.feed(0..100, speech = false))
    }

    @Test
    fun `segment closes after the hangover window`() {
        val segmenter = SpeechSegmenter(hangoverMs = 320, minSpeechMs = 100)
        assertNull(segmenter.feed(0..20, speech = false))
        assertNull(segmenter.feed(21..40, speech = true))

        val segment = segmenter.feed(41..60, speech = false)
        assertNotNull("expected a segment once silence exceeded the hangover", segment)
        assertTrue(segment!!.durationMs >= 100)
    }

    @Test
    fun `pre-roll keeps audio from before the detector fired`() {
        val segmenter = SpeechSegmenter(hangoverMs = 320, minSpeechMs = 100, preRollMs = 320)
        segmenter.feed(0..20, speech = false)
        segmenter.feed(21..40, speech = true)
        val segment = segmenter.feed(41..60, speech = false)!!

        // The segment must start before the first speech frame at index 21 (672 ms).
        assertTrue("segment should start in the pre-roll", segment.startTs < 21 * 32L)
    }

    @Test
    fun `a long monologue is force-cut instead of growing forever`() {
        val segmenter = SpeechSegmenter(maxSegmentMs = 1_000, minSpeechMs = 100)
        val segment = segmenter.feed(0..60, speech = true)
        assertNotNull("expected a force cut at the max length", segment)
        assertTrue(segment!!.durationMs >= 1_000)
    }

    @Test
    fun `flush closes an in-flight segment`() {
        val segmenter = SpeechSegmenter(minSpeechMs = 100)
        segmenter.feed(0..40, speech = true)
        val flushed = segmenter.flush(nowTs = 40 * 32L)
        assertNotNull(flushed)
        assertEquals(16_000, flushed!!.sampleRate)
    }
}
