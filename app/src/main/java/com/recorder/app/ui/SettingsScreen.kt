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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
        Section("Models", "models") { ModelsSection(viewModel, onRunSetup) }
        Section("Correction", "correction") { CorrectionSection(viewModel) }
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
    }
}

/**
 * A collapsible settings group. Which one is open is shared state, so it stays open across a
 * fold like everything else.
 */
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
    val installed = remember { viewModel.installedModels() }

    Text(viewModel.modelSummary(), style = MaterialTheme.typography.bodySmall)
    Text(
        "Speech recognition: Parakeet TDT, on this phone. It is the only speech model this build " +
            "supports, so there is nothing to switch.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
    )

    val localOptions = installed.map { (file, label) -> label to file }
    val cloudNote = if (heavyEnabled) "Cloud" else "Cloud (switch on Cloud AI first)"

    Choice(
        "Correction model",
        listOf("Strongest that fits (auto)" to ModelChoice.AUTO) + localOptions + listOf(cloudNote to ModelChoice.CLOUD),
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
        listOf("Chat model that fits (auto)" to ModelChoice.AUTO) + localOptions + listOf(cloudNote to ModelChoice.CLOUD),
        askModel,
    ) { viewModel.setAskModel(it) }
    Text(
        "Heavy tier (the overnight folder filing and email drafts) uses the provider under Cloud AI. " +
            "Cloud choices only take effect while Cloud AI is switched on; until then the phone's own " +
            "model is used.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (installed.isEmpty()) {
        Text("No local AI model is installed yet.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onRunSetup) { Text("Download or remove models") }
}

@Composable
private fun CorrectionSection(viewModel: RecorderViewModel) {
    val enabled by viewModel.correctionEnabled.collectAsState()
    val battery by viewModel.correctionInterval.collectAsState()
    val charging by viewModel.correctionIntervalCharging.collectAsState()
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
        Text("Correct new lines in small batches", modifier = Modifier.padding(start = 8.dp))
    }
    MinutesStepper("On battery, every", battery) { viewModel.setCorrectionIntervals(it, charging) }
    MinutesStepper("While charging, every", charging) { viewModel.setCorrectionIntervals(battery, it) }
    Text(
        "Batches wait for a pause in speech, and skip entirely below 20% battery. Each one loads the " +
            "model for a few seconds of full CPU, so a longer interval on battery saves the most.",
        style = MaterialTheme.typography.bodySmall,
    )
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
    Button(onClick = viewModel::correctNow) { Text("Correct new lines now") }
}

@Composable
private fun MinutesStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    val steps = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    val index = steps.indexOfFirst { it >= minutes }.let { if (it < 0) steps.lastIndex else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(steps[(index - 1).coerceAtLeast(0)]) }, enabled = index > 0) { Text("−") }
        Text("$minutes min", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(steps[(index + 1).coerceAtMost(steps.lastIndex)]) }, enabled = index < steps.lastIndex) {
            Text("+")
        }
    }
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
