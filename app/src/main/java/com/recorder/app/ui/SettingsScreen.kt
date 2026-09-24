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
import com.recorder.app.models.InstallProgress
import com.recorder.app.correction.SummaryRunner
import com.recorder.app.service.RecordingService
import com.recorder.core.storage.DiagnosticEntry
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.ModelChoice
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.recorder.core.llm.ProviderIds

@Composable
fun SettingsScreen(viewModel: RecorderViewModel, onRunSetup: () -> Unit = {}) {
    val triggers by viewModel.triggerKeywords.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val heavyEnabled by viewModel.heavyTierEnabled.collectAsState()

    var triggerText by remember(triggers) { mutableStateOf(triggers.joinToString(", ")) }
    var selectedProvider by remember(activeProvider) {
        mutableStateOf(activeProvider.ifBlank { ProviderIds.CLAUDE })
    }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    var googleClientId by remember { mutableStateOf("") }
    var googleClientSecret by remember { mutableStateOf("") }
    var googleRefreshToken by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val (id, savedEndpoint, savedModel) = viewModel.providerSettings()
        if (id.isNotBlank()) selectedProvider = id
        endpoint = savedEndpoint.ifBlank { ProviderIds.defaultEndpoint(selectedProvider) }
        model = savedModel.ifBlank { ProviderIds.defaultModel(selectedProvider) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (LocalCompact.current) 4.dp else 16.dp),
    ) {
        Section("Microphone sensitivity", "mic") { MicSensitivitySection(viewModel) }
        Section("Models", "models") { ModelsSection(viewModel, onRunSetup) }
        Section("Correction", "correction") { CorrectionSection(viewModel) }
        Section("What the AI has been taught", "taught") { TaughtSection(viewModel) }
        Section("Cloud AI (optional)", "cloud") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = heavyEnabled,
                    onCheckedChange = { enabled -> viewModel.setHeavyTierEnabled(enabled) },
                )
                Text(
                    "Allow sending transcript text to this provider (never audio)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "Audio never leaves the device either way. Only text is sent, and only when this is on.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ProviderIds.all.forEach { id ->
                    FilterChip(
                        selected = selectedProvider == id,
                        onClick = {
                            selectedProvider = id
                            endpoint = ProviderIds.defaultEndpoint(id)
                            model = ProviderIds.defaultModel(id)
                        },
                        label = { Text(ProviderIds.label(id)) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Endpoint") },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key (stored encrypted; leave blank to keep existing)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row {
                Button(
                    onClick = {
                        viewModel.saveProvider(selectedProvider, endpoint, model, apiKey)
                        apiKey = ""
                    },
                ) { Text("Save provider") }
                TextButton(onClick = viewModel::syncNow) { Text("Sync now") }
            }
        }
        Section("Flag phrases", "flags") {
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
        Section("Export defaults", "export") { ExportDefaultsSection(viewModel) }
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
        Section("What the AI can do", "ai") { CapabilitiesList() }
        Section("Connectors", "connectors") {
            Text(
                "Gmail, Calendar and Drive. Outbound actions are always queued as drafts for " +
                    "your approval. Run scripts/google_oauth.sh on a computer to get a refresh token.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = googleClientId,
                onValueChange = { googleClientId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google client id") },
                singleLine = true,
            )
            OutlinedTextField(
                value = googleClientSecret,
                onValueChange = { googleClientSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google client secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = googleRefreshToken,
                onValueChange = { googleRefreshToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google refresh token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = {
                    viewModel.saveGoogleCredentials(
                        googleClientId,
                        googleClientSecret,
                        googleRefreshToken,
                    )
                    googleClientSecret = ""
                    googleRefreshToken = ""
                },
            ) { Text("Save Google credentials") }
        }
        Section("Re-run setup", "setup") {
            Text(
                "Re-run the setup wizard to download or remove models, redo permissions, or " +
                    "walk through the cover-screen settings again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRunSetup) { Text("Run setup again") }
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
        Section("Diagnostics", "diagnostics") { DiagnosticsSection(viewModel) }
    }
}

/**
 * What went wrong, without a computer. This is the whole reason bugs in 2.1 that only showed
 * up on the phone — recording that would not start, a model that looked uninstalled — were
 * hard to explain: there was nowhere to look. This is that place.
 */
@Composable
private fun DiagnosticsSection(viewModel: RecorderViewModel) {
    val entries by viewModel.diagnostics.collectAsState()
    val clock = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

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
                        clock.format(java.util.Date(entry.timestamp)),
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
        "When you mark a summary item wrong or fix its wording, the change is kept here and " +
            "put in front of the model next time. The newest ${SummaryRunner.TAUGHT_IN_PROMPT} " +
            "go into each prompt; the newest ${SummaryRunner.TAUGHT_KEPT} are kept at all.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (taught.isEmpty()) {
        Text(
            "Nothing yet. Mark something wrong in a summary and it will appear here.",
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

@Composable
private fun Section(title: String, key: String, content: @Composable () -> Unit) {
    val open by AppUiState.settingsSection.collectAsState()
    val expanded = open == key
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { AppUiState.settingsSection.value = if (expanded) null else key }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
    val correctionModel by viewModel.correctionModel.collectAsState()
    val correctionEngine by viewModel.correctionEngine.collectAsState()
    val askModel by viewModel.askModel.collectAsState()
    val heavyEnabled by viewModel.heavyTierEnabled.collectAsState()
    val downloadStates by viewModel.modelStates.collectAsState()
    val catalogue = remember { viewModel.catalogue() }
    val installed = remember { viewModel.installedModels() }

    Text(
        "Three tiers, and nothing that needs more than a 12 GB phone.",
        style = MaterialTheme.typography.bodySmall,
    )
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            viewModel.modelSummary(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
    Text(
        "LOW (6–8 GB) uses Gemma 3 1B all day. MEDIUM (12 GB) uses Qwen 3 1.7B all day — " +
            "chosen to run beside the recorder for a whole day rather than to win a benchmark. " +
            "HIGH (12 GB) uses Qwen 3 4B and only ever runs while the phone is plugged in.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Speech recognition is Parakeet TDT, the only speech model this build supports, so " +
            "there is nothing to switch there.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
    )

    // --- per-role switchers -------------------------------------------------------------
    val localOptions = installed.map { (file, label) -> label to file }
    val cloudNote = if (heavyEnabled) "Cloud" else "Cloud (switch on Cloud AI first)"

    Choice(
        "Correction model",
        listOf("Best that fits (auto)" to ModelChoice.AUTO) + localOptions +
            listOf(cloudNote to ModelChoice.CLOUD),
        if (correctionEngine == ModelChoice.CLOUD) ModelChoice.CLOUD else correctionModel,
    ) { choice ->
        if (choice == ModelChoice.CLOUD) {
            viewModel.setCorrectionEngine(ModelChoice.CLOUD)
        } else {
            viewModel.setCorrectionEngine(ModelChoice.LOCAL)
            viewModel.setCorrectionModel(choice)
        }
    }
    Choice(
        "Ask model (questions, summaries, drafts)",
        listOf("All-day model (auto)" to ModelChoice.AUTO) + localOptions +
            listOf(cloudNote to ModelChoice.CLOUD),
        askModel,
    ) { viewModel.setAskModel(it) }
    Text(
        "\"Auto\" uses the charging-only model when the phone is plugged in and the all-day " +
            "model otherwise. Cloud choices only take effect while Cloud AI is switched on.",
        style = MaterialTheme.typography.bodySmall,
    )

    // --- what is actually on disk -------------------------------------------------------
    Text(
        "Downloads",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp),
    )
    if (catalogue.isEmpty()) {
        Text("Could not read the model list.", style = MaterialTheme.typography.bodySmall)
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
            if (!isInstalled && state !is InstallProgress.Downloading && state !is InstallProgress.Queued) {
                TextButton(onClick = { viewModel.downloadModel(entry.id) }) {
                    Text(if (state is InstallProgress.Failed) "Retry" else "Download")
                }
            }
        }
    }
    Button(onClick = onRunSetup, modifier = Modifier.padding(top = 6.dp)) {
        Text("Open the models step")
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
                "Runs on its own only while charging with the screen off, and stops when the " +
                    "phone is hot or saving power.",
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
                if (pending > 0) "$pending line(s) waiting" else "Nothing waiting",
                style = MaterialTheme.typography.bodySmall,
            )
            if (pending > 0) {
                TextButton(onClick = viewModel::processNow) { Text("Process now") }
            }
        }
    }

    val enabled by viewModel.correctionEnabled.collectAsState()
    val endOfDay by viewModel.endOfDayEnabled.collectAsState()
    val progress by viewModel.correctionProgress.collectAsState()

    Text(
        "After a line is transcribed, the strongest model this phone can hold re-reads it with the " +
            "lines around it and fixes misheard words. The original is always kept; corrected text " +
            "is stored beside it and labelled with the pass and model that produced it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = viewModel::setCorrectionEnabled)
        Text("Let the AI correct new lines", modifier = Modifier.padding(start = 8.dp))
    }
    // The interval steppers are gone rather than left showing a number nothing reads.
    // Passes are not on a timer any more; the conditions above are the schedule.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = endOfDay, onCheckedChange = viewModel::setEndOfDayEnabled)
        Text("End-of-day pass while charging overnight", modifier = Modifier.padding(start = 8.dp))
    }
    Text(
        "Re-corrects the whole day with the whole day as context — its recurring names and terms, " +
            "and the lines around each one. Stored as the newest version.",
        style = MaterialTheme.typography.bodySmall,
    )
    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = viewModel::processNow) { Text("Process everything waiting now") }
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
