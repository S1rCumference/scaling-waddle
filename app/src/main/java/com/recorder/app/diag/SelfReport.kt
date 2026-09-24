package com.recorder.app.diag

import android.content.Context
import android.os.Build
import com.recorder.app.BuildConfig
import com.recorder.app.ServiceLocator
import com.recorder.app.correction.CorrectionGate
import com.recorder.app.correction.CorrectionRunner
import com.recorder.app.service.RecordingService
import com.recorder.core.llm.local.LocalModelRuntime
import com.recorder.core.storage.AiPasses
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DiagnosticEntry
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.flow.first

/**
 * One block of text that says what this phone has actually been doing, meant to be pasted
 * into a conversation with a model that will be asked to fix it.
 *
 * Written for that reader, which is what shapes every choice here: counts and durations
 * rather than adjectives, rates with their denominators attached, the stop reason for every
 * AI pass, and "not recorded yet" spelled out rather than a zero that reads like a
 * measurement. No advice and no diagnosis — the numbers are the useful part, and a 1.7B
 * model's opinion about them is the part that would be wrong.
 *
 * It is assembled here rather than written by the on-device model on purpose. A report is
 * worth pasting only if its numbers are exactly the phone's numbers, and the one thing a
 * small local model reliably does to a table of figures is round it, reorder it and invent a
 * plausible extra row. It also costs a minute of a hot CPU to produce, which is the problem
 * this report exists to describe.
 */
object SelfReport {

    private const val PASSES_LISTED = 12
    private const val FAILURES_LISTED = 20
    private const val EVENTS_LISTED = 12

    suspend fun build(context: Context): String {
        val out = StringBuilder()

        out.line("RECORDER SELF-DIAGNOSTIC · ${Clocks.stamp(System.currentTimeMillis())}")
        out.line(
            "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})",
        )
        out.line("recorder state: ${RecordingService.state.value}")
        out.line(DeviceWatch.read(context).describe())
        out.line("automatic AI passes: ${CorrectionGate.describe(context).lowercase()}")

        aiPasses(out)
        corrections(out)
        transcription(out)
        detector(out)
        events(out)
        failures(out)

        return out.toString()
    }

    // --- AI ------------------------------------------------------------------------------

    private fun aiPasses(out: StringBuilder) {
        out.section("AI PASSES")
        out.line(
            "model: " + (
                LocalModelRuntime.unavailableReason
                    ?: ((LocalModelRuntime.current ?: "none resident") +
                        " · llama.cpp @ ${LocalModelRuntime.commit.take(12)}")
                ),
        )
        LocalModelRuntime.lastError?.let { out.line("last load failure: $it") }

        val passes = AiPasses.recent()
        if (passes.isEmpty()) {
            out.line("no passes recorded since this app started — nothing has asked the model anything")
            return
        }

        // Newest first, because the interesting pass is almost always the last one.
        passes.take(PASSES_LISTED).forEach { pass ->
            out.line(
                "  ${Clocks.shortTime(pass.atTs)}  ${pass.label.padEnd(17)}" +
                    "${pass.tokens.toString().padStart(4)} tok  " +
                    "${"%6.1f".format(pass.ms / 1000.0)}s  " +
                    "${"%5.1f".format(pass.tokensPerSecond)} tok/s  " +
                    (pass.stoppedBy?.let { "cut off by $it" } ?: "ran to its own stop") +
                    (pass.reloadedBecause?.let { "; reloaded because $it" } ?: "; context reused"),
            )
        }
        if (passes.size > PASSES_LISTED) out.line("  (${passes.size - PASSES_LISTED} older passes not listed)")

        val ms = passes.map { it.ms }.sorted()
        val tokens = passes.sumOf { it.tokens }
        out.line(
            "totals: ${passes.size} pass(es), ${tokens} token(s); " +
                "median ${"%.1f".format(median(ms) / 1000.0)}s, " +
                "slowest ${"%.1f".format(ms.last() / 1000.0)}s, " +
                "fastest ${"%.1f".format(ms.first() / 1000.0)}s, " +
                "total ${"%.1f".format(ms.sum() / 1000.0)}s of generation",
        )
        val byLabel = passes.groupBy { it.label }
        byLabel.forEach { (label, group) ->
            out.line(
                "  $label: ${group.size}, median ${"%.1f".format(median(group.map { it.ms }.sorted()) / 1000.0)}s, " +
                    "median ${median(group.map { it.tokens.toLong() }.sorted())} tokens",
            )
        }
        val cut = passes.mapNotNull { it.stoppedBy }.groupingBy { it }.eachCount()
        if (cut.isEmpty()) {
            out.line("cut off: none — every pass stopped on its own")
        } else {
            cut.entries.sortedByDescending { it.value }.forEach { (why, count) ->
                out.line("cut off by $why: $count")
            }
        }
        val reloads = passes.count { it.reloadedBecause != null }
        out.line("model reloads: $reloads of ${passes.size} passes")
    }

    private suspend fun corrections(out: StringBuilder) {
        out.section("CORRECTION BACKLOG")
        val settings = ServiceLocator.settings
        val pending = runCatching { CorrectionRunner.pendingCount().first() }.getOrNull()
        out.line("lines waiting for a pass: ${pending?.toString() ?: "could not be read"}")
        val last = runCatching { settings.lastCorrectionRun.first() }.getOrNull()
        out.line(
            "last completed run: " + (
                last?.let {
                    "${Clocks.dayAndTime(it.atTs)}, ${it.lines} line(s) in " +
                        "${"%.1f".format(it.durationMs / 1000.0)}s"
                } ?: "none recorded"
                ),
        )
        CorrectionRunner.lastError?.let { out.line("last run's problem: $it") }
        val enabled = runCatching { settings.correctionEnabled.first() }.getOrNull()
        out.line(
            "automatic passes enabled: " + when (enabled) {
                true -> "yes"
                false -> "no"
                null -> "could not be read"
            },
        )
        val taught = runCatching { ServiceLocator.database.review().recentCorrections(500).size }.getOrNull()
        out.line("corrections the user has taught it: ${taught?.toString() ?: "could not be read"}")
    }

    // --- capture -------------------------------------------------------------------------

    private fun transcription(out: StringBuilder) {
        out.section("TRANSCRIPTION")
        val t = RecordingService.stats.totals()
        if (t.frames == 0L) {
            out.line("no audio frames have reached the recorder since this app started")
            return
        }
        out.line(
            "since ${Clocks.shortTime(t.sinceTs)}: ${t.frames} frames " +
                "(${t.audioMs / 1000}s of audio), ${t.speechFrames} called speech " +
                "(${percent(t.speechFrames, t.frames)})",
        )
        out.line(
            "segments: ${t.segments} reached the speech model, ${t.transcribed} produced text " +
                "(${percent(t.transcribed, t.segments)}), ${t.segments - t.transcribed} came back empty",
        )
        out.line("captured despite the detector saying no speech: ${t.rescued} window(s)")
        if (t.segmentAudioMs > 0L) {
            out.line(
                "decode: ${t.segmentAudioMs / 1000}s of speech in ${t.decodeTimeMs / 1000}s " +
                    "(${"%.2f".format(t.realtimeFactor)}x realtime, " +
                    "mean segment ${t.segmentAudioMs / t.segments / 1000}s)",
            )
        }
    }

    private fun detector(out: StringBuilder) {
        out.section("VOICE DETECTION")
        out.line("detector: ${RecordingService.detectorName ?: "not started in this process"}")
        out.line("state check: ${RecordingService.detectorNote ?: "not reported yet"}")
        val spread = RecordingService.stats.totals().scoreSpread()
        out.line("score distribution: ${spread ?: "nothing scored yet"}")
    }

    // --- what happened -------------------------------------------------------------------

    private fun events(out: StringBuilder) {
        out.section("BATTERY AND THERMAL")
        val events = DeviceWatch.recent()
        if (events.isEmpty()) {
            out.line("no transitions recorded yet (the recorder logs these once a minute while running)")
            return
        }
        events.take(EVENTS_LISTED).forEach { out.line("  ${it.render()}") }
    }

    private fun failures(out: StringBuilder) {
        out.section("FAILURES AND WARNINGS")
        val bad = Diagnostics.entries.value.filter { it.level != DiagnosticEntry.Level.INFO }
        if (bad.isEmpty()) {
            out.line("nothing at WARN or ERROR in the last ${Diagnostics.entries.value.size} log entries")
            return
        }
        out.line("${bad.size} of the last ${Diagnostics.entries.value.size} log entries, newest first:")
        bad.take(FAILURES_LISTED).forEach { out.line("  ${it.render()}") }
        if (bad.size > FAILURES_LISTED) out.line("  (${bad.size - FAILURES_LISTED} older ones not listed)")
    }

    // --- formatting ----------------------------------------------------------------------

    private fun StringBuilder.line(text: String) = append(text).append('\n')

    private fun StringBuilder.section(title: String) {
        append('\n').append(title).append('\n')
    }

    private fun median(sorted: List<Long>): Long =
        if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]

    private fun percent(part: Long, whole: Long): String =
        if (whole <= 0L) "no denominator" else "${"%.1f".format(part * 100.0 / whole)}%"
}
