package com.recorder.core.storage

/**
 * What the on-device model actually cost, one pass at a time.
 *
 * [Diagnostics] already carries a line per pass, but a line is not a measurement: answering
 * "are corrections slow, or is one kind of pass slow, and is it the ceiling or the deadline
 * that stops them" out of prose means reading three hundred entries and doing arithmetic by
 * eye. These are the same facts as numbers, so the self-diagnostic report can state medians
 * and cut-off counts rather than paraphrase a log.
 *
 * Process-wide and in memory only. Forty passes is a few hours of real use, which is the
 * window worth reasoning about; keeping them across restarts would mean writing a row per
 * pass to disk to answer a question nobody asks about last week.
 */
object AiPasses {

    data class Pass(
        val atTs: Long,
        /** Which job asked: "correction draft", "repair", "summary", "answer". */
        val label: String,
        val tokens: Int,
        val ms: Long,
        /** Why generation stopped early, or null when the model stopped on its own. */
        val stoppedBy: String?,
        /** Why the weights were reloaded first, or null when the context was reused. */
        val reloadedBecause: String?,
    ) {
        val tokensPerSecond: Double get() = if (ms > 0L) tokens * 1000.0 / ms else 0.0
    }

    private const val KEEP = 40

    private val lock = Any()

    /** Newest first. */
    private var passes: List<Pass> = emptyList()

    fun record(
        label: String,
        tokens: Int,
        ms: Long,
        stoppedBy: String?,
        reloadedBecause: String?,
    ) {
        val pass = Pass(System.currentTimeMillis(), label, tokens, ms, stoppedBy, reloadedBecause)
        synchronized(lock) { passes = (listOf(pass) + passes).take(KEEP) }
    }

    /** Newest first. Empty until the model has been asked something. */
    fun recent(): List<Pass> = synchronized(lock) { passes }

    fun clear() {
        synchronized(lock) { passes = emptyList() }
    }
}
