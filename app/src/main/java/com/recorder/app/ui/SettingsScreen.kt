package com.recorder.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import com.recorder.app.correction.CorrectionGate
import com.recorder.app.correction.CorrectionRunner
import com.recorder.app.models.InstallProgress
import com.recorder.app.service.RecordingService
import com.recorder.core.llm.TokenBudget
import com.recorder.core.llm.local.OnDeviceModel
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DiagnosticEntry
import com.recorder.core.storage.ExportDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(viewModel: RecorderViewModel, onRunSetup: () -> Unit = {}) {
    val triggers by viewModel.triggerKeywords.collectAsState()

    var triggerText by remember(triggers) { mutableStateOf(triggers.joinToString(", ")) }

    val use24Hour by viewModel.use24HourClock.collectAsState()
    // Collected here so each row can show its current value without being opened, which is
    // the common reason for coming to this screen at all.
    val threshold by viewModel.vadThreshold.collectAsState()
    val taughtCount by viewModel.taughtCorrections.collectAsState()
    val pendingCount by viewModel.pendingCorrections.collectAsState()
    val overnightOn by viewModel.endOfDayEnabled.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (LocalCompact.current) 4.dp else 16.dp),
    ) {
        Group("Recording") {
        Section("Microphone sensitivity", "mic", value = "Opens a segment at ${"%.2f".format(threshold)}") { MicSensitivitySection(viewModel) }
            Section("Times", "clock", value = if (use24Hour) "24-hour" else "12-hour") {
                Choice(
                    "Clock",
                    listOf("12-hour" to false, "24-hour" to true),
                    use24Hour,
                ) { viewModel.setUse24HourClock(it) }
                Text(
                    "Applies everywhere a time is shown: the live feed, the logs, " +
                        "diagnostics and exports.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        Section("Flag phrases", "flags", value = if (triggers.isEmpty()) "None set" else "${triggers.size} phrase(s)") {
            Text(
                "Comma separated. Any transcript line containing one of these gets flagged.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = triggerText,
                onValueChange = { triggerText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Triggers") },
            )
            Button(
                onClick = {
                    viewModel.saveTriggers(
                        triggerText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    )
                },
            ) { Text("Save triggers") }
        }
        }
        Group("AI") {
        Section("Models", "models") { ModelsSection(viewModel, onRunSetup) }
        Section(
            "Correction",
            "correction",
            value = when {
                !overnightOn -> "Overnight pass off"
                pendingCount > 0 -> "$pendingCount line(s) never corrected"
                else -> "Nothing waiting"
            },
        ) { CorrectionSection(viewModel) }
        Section(
            "What the AI has been taught",
            "taught",
            value = if (taughtCount.isEmpty()) "Nothing yet" else "${taughtCount.size} correction(s)",
        ) { TaughtSection(viewModel) }
        }
        Group("Data") {
        Section("Export defaults", "export") { ExportDefaultsSection(viewModel) }
        Section("Diagnostics", "diagnostics") { DiagnosticsSection(viewModel) }
        Section("Self-diagnostic report", "report") { SelfReportSection(viewModel) }
        }
        Group("Device") {
        Section("Battery and setup status", "status") {
            val context = LocalContext.current
            // Recomputed on each recomposition on purpose: these can change behind the app's
            // back, so a cached answer would be a lie.
            viewModel.setupChecks().forEach { check ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (check.ok) "✓" else "✗",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(check.label, style = MaterialTheme.typography.bodyMedium)
                        Text(check.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!check.ok) {
                        check.fix?.let { fix ->
                            TextButton(onClick = { fix(context) }) { Text(check.fixLabel) }
                        }
                    }
                }
            }
        }
        Section("Power report", "power") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    viewModel.powerReport(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Section("Surviving a reboot", "reboot") {
            Text(
                viewModel.deviceOwnerStatus(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Android will not let any app start microphone recording in the background " +
                    "after a reboot. The only exceptions are a tap on a notification, or " +
                    "this app being the phone's device owner.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (viewModel.deviceOwnerActive()) {
                Button(onClick = viewModel::removeDeviceOwner) { Text("Remove device owner") }
                Text(
                    "Removing it does not wipe the phone; recording simply needs one tap " +
                        "after each restart.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "To enable it, run this once from Shizuku or adb on a phone with no " +
                        "accounts added:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        viewModel.deviceOwnerCommand(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
        Section("Lock down this phone", "lockdown") {
            Text(
                "Suspends the dialer, messaging, the Play Store and other apps so only the " +
                    "recorder runs. Every change is recorded and reversible.",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    viewModel.lockdownStatus(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (viewModel.lockdownAvailable()) {
                Row {
                    Button(onClick = viewModel::applyLockdown) { Text("Lock down") }
                    TextButton(onClick = viewModel::undoLockdown) { Text("Undo lockdown") }
                }
            }
        }
        Section("This device", "device") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    viewModel.deviceSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        }
        Group("Advanced") {
        Section("Benchmark", "benchmark") {
            val benchmarkText by viewModel.benchmark.collectAsState()
            val running by viewModel.benchmarkRunning.collectAsState()
            Text(
                "Measures this phone with the installed model. The numbers in the README " +
                    "come from running this here; they cannot be produced anywhere else.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = viewModel::runBenchmark, enabled = !running) {
                Text(if (running) "Measuring…" else "Run benchmark")
            }
            benchmarkText?.let { report ->
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        Section("Re-run setup", "setup") {
            Text(
                "Re-run the setup wizard to download or remove models, redo permissions, or " +
                    "walk through the cover-screen settings again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRunSetup) { Text("Run setup again") }
        }
        Section("Updates", "updates") {
            val updateText by viewModel.update.collectAsState()
            Text(
                "Downloads the newest release from GitHub and hands it to Android to install. " +
                    "Same signing key, so it installs over the top.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Button(onClick = viewModel::checkForUpdate) { Text("Check for updates") }
                if (viewModel.updateAvailable) {
                    TextButton(onClick = viewModel::downloadUpdate) { Text("Download") }
                }
            }
            updateText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        }
    }
}

/**
 * What went wrong, without a computer. This is the whole reason bugs in 2.1 that only showed
 * up on the phone — recording that would not start, a model that looked uninstalled — were
 * hard to explain: there was nowhere to look. This is that place.
 */
/**
 * One block of numbers about this phone, for pasting into a conversation with a model that
 * will be asked to fix what they show. Diagnostics above is the log; this is the measurement.
 */
@Composable
private fun SelfReportSection(viewModel: RecorderViewModel) {
    val report by viewModel.selfReport.collectAsState()

    Text(
        "Collects what the AI passes cost, how much speech turned into text, how the voice " +
            "detector has been scoring, when the phone charged or got hot, and everything " +
            "that failed — as figures, not prose. Built on this phone; it goes nowhere until " +
            "you copy or share it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.padding(vertical = 6.dp)) {
        Button(onClick = viewModel::generateSelfReport) {
            Text(if (report == null) "Generate report" else "Rebuild")
        }
        report?.let { text ->
            TextButton(onClick = { viewModel.copy(text) }) { Text("Copy") }
            TextButton(onClick = { viewModel.shareText(text) }) { Text("Share…") }
        }
    }
    val text = report ?: return
    Card(Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun DiagnosticsSection(viewModel: RecorderViewModel) {
    val entries by viewModel.diagnostics.collectAsState()

    Text(
        "The last ${entries.size} things worth knowing about — recording starting or " +
            "refusing to start, a model that failed to load or download, corrections that " +
            "could not run. Nothing here is sent anywhere; Copy or Share sends it only where " +
            "you choose.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.padding(vertical = 6.dp)) {
        Button(onClick = viewModel::copyDiagnostics) { Text("Copy") }
        TextButton(onClick = viewModel::shareDiagnostics) { Text("Share…") }
        TextButton(onClick = viewModel::clearDiagnostics) { Text("Clear") }
    }
    if (entries.isEmpty()) {
        Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            entries.take(40).forEach { entry ->
                val color = when (entry.level) {
                    DiagnosticEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                    DiagnosticEntry.Level.WARN -> MaterialTheme.colorScheme.tertiary
                    DiagnosticEntry.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        Clocks.time(entry.timestamp),
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        "${entry.tag}: ${entry.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (entries.size > 40) {
                Text(
                    "…and ${entries.size - 40} older entries. Share to see everything.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * A collapsible settings group. Which one is open is shared state, so it stays open across a
 * fold like everything else.
 */
/**
 * The detection threshold, with the detector's live opinion next to it.
 *
 * A threshold is impossible to choose from a number that appears once a minute in a log.
 * Talking while watching the bar move is the only way to tell "the detector never fires"
 * apart from "the threshold is a shade too high", and those two have been indistinguishable
 * from the outside for several versions.
 */
@Composable
private fun MicSensitivitySection(viewModel: RecorderViewModel) {
    val score by viewModel.micScore.collectAsState()
    val peak by viewModel.micPeak.collectAsState()
    val highest by viewModel.micHighest.collectAsState()
    val threshold by viewModel.vadThreshold.collectAsState()
    val recording by viewModel.recorderState.collectAsState()

    if (recording != RecordingService.RecorderState.RECORDING) {
        Text(
            "Start recording to see the meter move.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Text("Speech detected", style = MaterialTheme.typography.labelLarge)
    LinearProgressIndicator(
        progress = { score.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Text(
        "now ${"%.2f".format(score)} · highest ${"%.2f".format(highest)} · " +
            "opens a segment at ${"%.2f".format(threshold)}",
        style = MaterialTheme.typography.bodySmall,
    )

    Text("Loudness", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    LinearProgressIndicator(
        progress = { peak.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Text(
        "${(peak * 100).toInt()}% of full scale. If this moves while you talk and the bar " +
            "above does not, the microphone is fine and the detector is the problem.",
        style = MaterialTheme.typography.bodySmall,
    )

    Text(
        "Threshold ${"%.2f".format(threshold)}",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
    Slider(
        value = threshold,
        onValueChange = { viewModel.setVadThreshold(it) },
        valueRange = 0.05f..0.95f,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Lower catches more and transcribes more noise; higher is tidier and misses more. " +
            "Anything loud that goes a whole window without crossing this line is " +
            "transcribed anyway rather than thrown away, so erring low costs battery " +
            "rather than words. Takes effect the next time recording starts.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = viewModel::resetMicHighest) { Text("Reset the highest") }
}

/**
 * The corrections the user has made, which are fed back into later passes.
 *
 * Shown and prunable because it steers every summary from here on: a wrong entry would keep
 * teaching the wrong thing, and there is no way to tell that from the outside unless the
 * list is visible. It is a plain list of substitutions, not training of any kind.
 */
@Composable
private fun TaughtSection(viewModel: RecorderViewModel) {
    val taught by viewModel.taughtCorrections.collectAsState()

    Text(
        "Substitutions you made by hand, kept as a plain list. Nothing adds to this list in " +
            "3.0 — the summary screen that fed it is gone — so what is here is what was " +
            "taught before, and it can be read and cleared but not added to.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (taught.isEmpty()) {
        Text(
            "Nothing was ever taught.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    taught.forEach { entry ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.wrong, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (entry.corrected.isBlank()) "→ marked wrong" else "→ ${entry.corrected}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { viewModel.forgetCorrection(entry.id) }) { Text("Forget") }
            }
        }
    }
    TextButton(onClick = viewModel::forgetAllCorrections) { Text("Forget everything") }
}

/** "4 minutes ago", "yesterday" — enough to judge freshness without doing arithmetic. */
private fun relativeTime(ts: Long): String {
    val ago = System.currentTimeMillis() - ts
    return when {
        ago < 60_000 -> "just now"
        ago < 60 * 60_000 -> "${ago / 60_000} minute(s) ago"
        ago < 24 * 60 * 60_000L -> "${ago / (60 * 60_000)} hour(s) ago"
        else -> "${ago / (24 * 60 * 60_000L)} day(s) ago"
    }
}

/**
 * A card of related rows. Five of these replaced seventeen top-level entries.
 *
 * Seventeen things in one flat accordion is a list you scroll rather than a place you
 * navigate: nothing is grouped, so finding anything means reading all of it. Cards give the
 * eye somewhere to land first, and each row carries its current value on the right so the
 * common case — checking what something is set to — needs no tap at all.
 */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) { content() }
    }
}

@Composable
private fun Section(
    title: String,
    key: String,
    /** The current state, shown inline so reading it does not require opening it. */
    value: String? = null,
    content: @Composable () -> Unit,
) {
    val open by AppUiState.settingsSection.collectAsState()
    val expanded = open == key
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { AppUiState.settingsSection.value = if (expanded) null else key }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 12.dp)) { content() }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ModelsSection(viewModel: RecorderViewModel, onRunSetup: () -> Unit) {
    val downloadStates by viewModel.modelStates.collectAsState()
    val catalogue = remember { viewModel.catalogue() }

    Text(
        "3.0 runs one language model and one speech model. There is no tier to pick and no " +
            "per-task choice to make: the language model corrects transcript lines, and that " +
            "is the only thing it is ever asked to do.",
        style = MaterialTheme.typography.bodySmall,
    )
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            viewModel.modelStatus(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }

    catalogue.forEach { entry ->
        val isInstalled = viewModel.isModelInstalled(entry)
        val state = downloadStates[entry.id]
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${entry.approxMb} MB · " + when {
                        isInstalled -> "installed"
                        state is InstallProgress.Downloading -> "downloading ${state.percent}%"
                        state is InstallProgress.Queued -> "waiting its turn"
                        state is InstallProgress.Verifying -> "checking the download"
                        state is InstallProgress.Extracting -> "unpacking"
                        state is InstallProgress.Failed -> "failed: ${state.reason}"
                        else -> "not downloaded"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is InstallProgress.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state !is InstallProgress.Downloading && state !is InstallProgress.Queued) {
                TextButton(onClick = { viewModel.downloadModel(entry.id) }) {
                    Text(if (isInstalled) "Re-download" else "Download")
                }
            }
        }
    }
    Button(onClick = onRunSetup, modifier = Modifier.padding(top = 6.dp)) {
        Text("Open the models step")
    }

    ModelFileCheck(viewModel)
}

/**
 * Whether what is on disk is actually complete, and the one button that fixes it when it is
 * not. "Installed" here means the exact byte count the manifest gives, or, for the speech
 * model's unpacked archive, an install record written after the last file was in place — not
 * "a file of that name exists", which is true of a download that stopped one byte in and is
 * how a half-installed speech model took the whole app down on every launch.
 */
@Composable
private fun ModelFileCheck(viewModel: RecorderViewModel) {
    val repaired by viewModel.repairReport.collectAsState()
    var survey by remember { mutableStateOf<List<Pair<String, String?>>?>(null) }

    Text(
        "Model files",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp),
    )
    Row {
        TextButton(onClick = { survey = viewModel.modelSurvey() }) { Text("Check files") }
        TextButton(onClick = viewModel::repairModels) { Text("Remove unfinished") }
    }
    survey?.let { rows ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                rows.forEach { (name, problem) ->
                    Text(
                        "$name — ${problem ?: "complete"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    repaired?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CorrectionSection(viewModel: RecorderViewModel) {
    val pending by viewModel.pendingCorrections.collectAsState()
    val lastRun by viewModel.lastCorrectionRun.collectAsState()

    // When it last ran, how long it took, how much is waiting, and why it is or is not
    // running now. Four facts that between them answer "is the AI working".
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Automatic passes", style = MaterialTheme.typography.titleSmall)
            Text(viewModel.schedulingStatus(), style = MaterialTheme.typography.bodySmall)
            Text(
                "Runs on its own once a night, and only while charging, with the screen off, " +
                    "above ${CorrectionGate.MIN_BATTERY_PERCENT}% battery, and not already hot. " +
                    "Otherwise it waits for the next night.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                lastRun?.let {
                    "Last run ${relativeTime(it.atTs)} · ${"%.1f".format(it.durationMs / 1000.0)}s · " +
                        "${it.lines} line(s)"
                } ?: "Has not run yet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                if (pending > 0) "$pending line(s) never corrected" else "Nothing waiting",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    val endOfDay by viewModel.endOfDayEnabled.collectAsState()
    val progress by viewModel.correctionProgress.collectAsState()

    Text(
        "${OnDeviceModel.LABEL} re-reads a transcript line with the lines around it and fixes " +
            "misheard words. The original is always kept; corrected text is stored beside it " +
            "and labelled with the pass and the model that produced it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Two things start it and nothing else does: this overnight pass, and \"Correct this " +
            "group\" on a group in Logs. Each batch is capped at " +
            "${CorrectionRunner.MAX_INPUT_TOKENS} tokens in and " +
            "${TokenBudget.CORRECTION_MAX_TOKENS} out, with 45 seconds a batch and ten minutes " +
            "for the whole pass. Anything that runs past those is abandoned rather than " +
            "retried, and the model is unloaded the moment the pass ends.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = endOfDay, onCheckedChange = viewModel::setEndOfDayEnabled)
        Text("Correct overnight while charging", modifier = Modifier.padding(start = 8.dp))
    }
    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ExportDefaultsSection(viewModel: RecorderViewModel) {
    val content by viewModel.exportContent.collectAsState()
    val format by viewModel.exportFormat.collectAsState()
    Choice(
        "Text",
        listOf(
            "Corrected" to ExportDefaults.CONTENT_CORRECTED,
            "Original" to ExportDefaults.CONTENT_ORIGINAL,
            "Both" to ExportDefaults.CONTENT_BOTH,
        ),
        content,
    ) { viewModel.setExportDefaults(it, format) }
    Choice(
        "Format",
        listOf("Markdown" to ExportDefaults.FORMAT_MARKDOWN, "Plain text" to ExportDefaults.FORMAT_TEXT),
        format,
    ) { viewModel.setExportDefaults(content, it) }
    Text(
        "Saved files go to Download/Recorder/. Share opens Android's share sheet.",
        style = MaterialTheme.typography.bodySmall,
    )
}
