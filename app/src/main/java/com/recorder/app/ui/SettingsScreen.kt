package com.recorder.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.recorder.app.models.InstallProgress
import com.recorder.app.service.RecordingService
import com.recorder.core.llm.cloud.CloudProvider
import com.recorder.app.summary.SummaryWorker
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val diagnostics by viewModel.diagnostics.collectAsState()
    val diagnosticCount = diagnostics.size
    val batteryLine = remember(diagnosticCount) { viewModel.batterySummary() }
    val summariesOn by viewModel.summariesEnabled.collectAsState()
    val summaryKeySet by viewModel.summaryKeySet.collectAsState()
    val summaryProblem by viewModel.summaryProblem.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (LocalCompact.current) 4.dp else 16.dp),
    ) {
        Group("Recording") {
        Section("Microphone sensitivity", "mic", value = "Opens a segment at ${"%.2f".format(threshold)}") { MicSensitivitySection(viewModel) }
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
        Section("Speech models", "models", value = "Voice detector and recogniser") {
            ModelsSection(viewModel, onRunSetup)
        }
        }
        Group("Summaries") {
        Section(
            "Where summaries come from",
            "summaries",
            value = when {
                summaryProblem.isNotBlank() -> summaryProblem.take(48)
                !summaryKeySet -> "No key set — off"
                summariesOn -> "On, every six hours"
                else -> "Key set, switched off"
            },
        ) { SummariesSection(viewModel) }
        }
        Group("Data") {
        Section("Export defaults", "export", value = "Set on the Export screen") { ExportDefaultsSection(viewModel) }
        Section("Diagnostics", "diagnostics", value = "${diagnosticCount} entries") { DiagnosticsSection(viewModel) }
        Section("Self-diagnostic report", "report", value = "Built on demand") { SelfReportSection(viewModel) }
        }
        Group("Device") {
        Section("Battery and setup status", "status", value = batteryLine) {
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
        Section("Power report", "power", value = "Measured on this phone") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    viewModel.powerReport(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Section("Surviving a reboot", "reboot", value = if (viewModel.deviceOwnerActive()) "Device owner" else "Not device owner") {
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
        Section("Lock down this phone", "lockdown", value = if (viewModel.lockdownAvailable()) "Available" else "Not available") {
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
        Section("Time format", "clock", value = if (use24Hour) "24-hour" else "12-hour") {
            Choice(
                "Clock",
                listOf("12-hour" to false, "24-hour" to true),
                use24Hour,
            ) { viewModel.setUse24HourClock(it) }
            Text(
                "Applies everywhere a time is shown: the live feed, the logs, diagnostics and " +
                    "the readable export formats. CSV and JSON Lines stay 24-hour so a " +
                    "spreadsheet does not have to guess.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Updates", "updates", value = "v${com.recorder.app.BuildConfig.VERSION_NAME}") {
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
        // Clears the counters this report is built from, not just the text on screen — so the
        // next one answers "did that fix it?" instead of repeating last week's numbers.
        TextButton(onClick = viewModel::clearDiagnostics) { Text("Clear") }
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
        TextButton(onClick = viewModel::clearDiagnostics) { Text("Clear all") }
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
        "Two files, about 460 MB, downloaded once and then never again: a voice detector that " +
            "decides when somebody is speaking, and a speech recogniser that writes it down. " +
            "Both run on this phone and neither ever leaves it. There is no language model here " +
            "any more — summaries are a network request, so there is nothing to download for them.",
        style = MaterialTheme.typography.bodySmall,
    )

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
 * Where summaries are sent, and the key that lets them be.
 *
 * This card is the whole of the AI surface now. What it replaced was three: a model card listing
 * a gigabyte of weights, a correction card with token ceilings and battery thresholds, and a list
 * of things the user had taught a model that no longer exists.
 *
 * The key is write-only from the screen's point of view. It is stored in the app's own
 * preferences, it is never displayed back, and it is never in the build — a build with a key in it
 * would be a key published to everyone who installs the app.
 */
@Composable
private fun SummariesSection(viewModel: RecorderViewModel) {
    val enabled by viewModel.summariesEnabled.collectAsState()
    val keySet by viewModel.summaryKeySet.collectAsState()
    val providerName by viewModel.summaryProviderName.collectAsState()
    val baseUrl by viewModel.summaryBaseUrl.collectAsState()
    val modelName by viewModel.summaryModelName.collectAsState()
    val problem by viewModel.summaryProblem.collectAsState()
    val backoffUntil by viewModel.summaryBackoffUntil.collectAsState()
    val progress by viewModel.summaryProgress.collectAsState()
    val provider = CloudProvider.byName(providerName.ifBlank { null })

    var keyField by remember { mutableStateOf("") }
    var urlField by remember(baseUrl, providerName) { mutableStateOf(baseUrl.ifBlank { provider.baseUrl }) }
    var modelField by remember(modelName, providerName) { mutableStateOf(modelName.ifBlank { provider.defaultModel }) }

    Text(
        "Summaries are the one thing this app sends anywhere. Transcript text goes to the " +
            "endpoint below — never audio, which is recognised on the phone and stays there. " +
            "Nothing is sent until you paste a key and switch this on.",
        style = MaterialTheme.typography.bodySmall,
    )

    Choice(
        label = "Provider",
        options = CloudProvider.entries.map { it.label },
        selected = provider.label,
        onSelect = { label ->
            CloudProvider.entries.firstOrNull { it.label == label }?.let(viewModel::setSummaryProvider)
        },
    )
    Text(provider.freeTier, style = MaterialTheme.typography.bodySmall)
    Text(
        "Get a key: ${provider.keyUrl}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = keyField,
        onValueChange = { keyField = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(if (keySet) "Replace the API key" else "API key") },
    )
    Row {
        Button(
            onClick = {
                viewModel.setSummaryApiKey(keyField)
                keyField = ""
            },
            enabled = keyField.isNotBlank(),
        ) { Text("Save key") }
        if (keySet) {
            TextButton(onClick = { viewModel.setSummaryApiKey("") }) { Text("Remove key") }
        }
    }

    OutlinedTextField(
        value = urlField,
        onValueChange = { urlField = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Base URL") },
    )
    OutlinedTextField(
        value = modelField,
        onValueChange = { modelField = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Model") },
    )
    Row {
        TextButton(
            onClick = {
                viewModel.setSummaryBaseUrl(urlField)
                viewModel.setSummaryModel(modelField)
            },
        ) { Text("Save endpoint") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = enabled,
            onCheckedChange = viewModel::setSummariesEnabled,
            enabled = keySet,
        )
        Text(
            if (keySet) "Summarise automatically" else "Paste a key first",
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Text(
        "Runs about four times a day over Wi-Fi, ${SummaryWorker.BATCH} spans at a time, oldest " +
            "first: hours, then the days they make up, then weeks, then months. Spreading it out " +
            "is what keeps a per-minute rate limit out of the way and a free allowance lasting.",
        style = MaterialTheme.typography.bodySmall,
    )

    Row(Modifier.padding(top = 6.dp)) {
        Button(onClick = viewModel::summariseNow, enabled = keySet) { Text("Summarise now") }
    }

    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (problem.isNotBlank()) {
        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val waiting = backoffUntil - System.currentTimeMillis()
    if (waiting > 0) {
        Text(
            "Rate limited — next attempt after ${Clocks.shortTime(backoffUntil)}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExportDefaultsSection(viewModel: RecorderViewModel) {
    Text(
        "Every export setting — the date range, the time-of-day window, the keywords, the " +
            "format, the grouping and where the file goes — is remembered on the Export " +
            "screen itself and used as the next export's defaults. There is nothing to set " +
            "here that is not set better there, with a live match count beside it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.padding(top = 6.dp)) {
        Button(onClick = { viewModel.openExport() }) { Text("Open Export") }
        TextButton(onClick = viewModel::resetExport) { Text("Reset to defaults") }
    }
}

/**
 * A labelled row of chips, one of which is selected.
 *
 * It lived in ExportDialog until 3.0 deleted that file, and it is here rather than in
 * ExportScreen because this is the only screen left with a one-of-these setting to offer.
 */
@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (text, value) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}
