package com.recorder.app.diag

import android.content.Context
import android.os.Build
import com.recorder.app.BuildConfig
import com.recorder.app.ServiceLocator
import com.recorder.app.StartupGuard
import com.recorder.app.models.ModelHealth
import com.recorder.app.service.RecordingService
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.cloud.CloudProvider
import com.recorder.app.summary.SummaryRunner
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
    private const val SUMMARY_COUNT_LIMIT = 4_000
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
        if (StartupGuard.safeMode) {
            out.line("SAFE MODE: start-up failed twice, so nothing was started and no model loaded")
        } else if (StartupGuard.unhealthyStarts > 0) {
            out.line("this start is not yet proven healthy (${StartupGuard.unhealthyStarts} unproven)")
        }
        out.line(DeviceWatch.read(context).describe())

        models(context, out)
        summaries(out)
        transcription(out)
        detector(out)
        events(out)
        failures(out)

        return out.toString()
    }

    /**
     * What is on disk and whether it is complete. First section on purpose: a model whose
     * install never finished is the fault that explains the most other symptoms at once —
     * no transcription, no corrections, and, until this release, a crash on every launch.
     */
    private fun models(context: Context, out: StringBuilder) {
        out.section("MODELS")
        val survey = ModelHealth.survey(context)
        if (survey.isEmpty()) {
            out.line("the model catalogue could not be read")
            return
        }
        survey.forEach { (entry, problem) ->
            out.line("  ${entry.displayName} (${entry.approxMb} MB) — ${problem ?: "complete"}")
        }
        out.line("speech model: ${AsrEngineFactory.modelProblem(context) ?: "loadable"}")
    }

    // --- Summaries -----------------------------------------------------------------------

    /**
     * Where summaries are sent, whether they are running, and what the last requests cost.
     *
     * This replaced two sections — a local model's residency and a correction backlog — that
     * between them took up half the report and described machinery that no longer exists. There
     * is no model on this phone to be resident, and no correction pass to be behind on.
     */
    private suspend fun summaries(out: StringBuilder) {
        out.section("SUMMARIES")
        val settings = ServiceLocator.settings
        val provider = CloudProvider.byName(
            runCatching { settings.summaryProvider.first() }.getOrNull()?.ifBlank { null },
        )
        val keySet = runCatching { settings.summaryApiKey.first().isNotBlank() }.getOrDefault(false)
        val enabled = runCatching { settings.summariesEnabled.first() }.getOrDefault(false)
        val model = runCatching { settings.summaryModel.first() }.getOrNull()
            ?.ifBlank { provider.defaultModel } ?: provider.defaultModel

        out.line("provider: ${provider.label} · model $model")
        out.line("api key set: ${if (keySet) "yes" else "no"}")
        out.line("scheduled passes: ${if (enabled) "on" else "off"}")

        val backoff = runCatching { settings.summaryBackoffUntil.first() }.getOrDefault(0L)
        val waiting = backoff - System.currentTimeMillis()
        if (waiting > 0) out.line("rate limited for another ${waiting / 1000}s")
        runCatching { settings.summaryProblem.first() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { out.line("problem: $it") }
        SummaryRunner.lastError?.let { out.line("last attempt: $it") }

        val stored = runCatching {
            ServiceLocator.database.review().recent(SUMMARY_COUNT_LIMIT).first().size
        }.getOrNull()
        out.line("summaries stored: ${stored?.toString() ?: "could not be read"}")
        val pending = runCatching { SummaryRunner.pendingSpans().size }.getOrNull()
        out.line("spans waiting: ${pending?.toString() ?: "could not be read"}")

        val passes = AiPasses.recent()
        if (passes.isEmpty()) {
            out.line("no requests recorded since this app started")
            return
        }
        // Newest first, because the interesting request is almost always the last one.
        passes.take(PASSES_LISTED).forEach { pass ->
            out.line(
                "  ${Clocks.shortTime(pass.atTs)}  ${pass.label}  ${pass.tokens} tok  " +
                    "${"%.1f".format(pass.ms / 1000.0)}s",
            )
        }
        val median = passes.map { it.ms }.sorted()[passes.size / 2]
        out.line(
            "totals: ${passes.size} request(s), ${passes.sumOf { it.tokens }} token(s); " +
                "median ${"%.1f".format(median / 1000.0)}s",
        )
    }

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
