package com.recorder.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The detector's live opinion, for the sensitivity meter in Settings.
 *
 * Published rather than logged because a threshold is impossible to choose from a number
 * that appears once a minute. Watching the score move while you talk is the only way to
 * tell "the detector never fires" from "the threshold is a shade too high", and those two
 * have looked identical from the outside for several versions now.
 *
 * Updated from the capture thread and read by the UI, so the writes are cheap and
 * rate-limited: a value ten times a second is plenty for a bar and costs nothing.
 */
object MicLevels {

    private val _score = MutableStateFlow(0f)

    /** The most recent speech probability, 0 to 1. */
    val score: StateFlow<Float> = _score.asStateFlow()

    private val _peak = MutableStateFlow(0f)

    /** The most recent frame's loudest sample, 0 to 1. */
    val peak: StateFlow<Float> = _peak.asStateFlow()

    private val _highest = MutableStateFlow(0f)

    /** The highest score seen since [resetHighest], so a brief spike is not missed. */
    val highest: StateFlow<Float> = _highest.asStateFlow()

    @Volatile
    private var lastPublishedAt = 0L

    fun publish(score: Float, peak: Float) {
        if (score > _highest.value) _highest.value = score
        val now = System.currentTimeMillis()
        if (now - lastPublishedAt < MIN_INTERVAL_MS) return
        lastPublishedAt = now
        _score.value = score
        _peak.value = peak
    }

    fun resetHighest() {
        _highest.value = 0f
    }

    /** Ten updates a second: smooth enough to watch, cheap enough to ignore. */
    private const val MIN_INTERVAL_MS = 100L
}
