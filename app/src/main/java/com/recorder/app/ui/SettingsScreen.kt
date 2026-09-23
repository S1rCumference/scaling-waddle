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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Section("Trigger phrases") {
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

        Section("Heavy tier") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = heavyEnabled,
                    onCheckedChange = { enabled -> viewModel.setHeavyTierEnabled(enabled) },
                )
                Text(
                    "Send transcript text to the selected provider on a schedule",
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

        Section("Connectors") {
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

        Section("Setup") {
            Text(
                "Re-run the setup wizard to download or remove models, redo permissions, or " +
                    "walk through the cover-screen settings again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRunSetup) { Text("Run setup again") }
        }

        Section("Surviving a reboot") {
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

        Section("Power report") {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    viewModel.powerReport(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Section("This device") {
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        content()
    }
}
