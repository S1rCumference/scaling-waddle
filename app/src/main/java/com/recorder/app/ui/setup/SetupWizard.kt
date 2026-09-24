package com.recorder.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.recorder.app.models.InstallProgress
import com.recorder.app.service.MicConflict
import com.recorder.app.service.RecordingService

/**
 * First-run setup. One decision per screen, in the order that makes the phone work:
 * permissions, then models, then the cover screen, then proof that it all runs.
 *
 * Every step is skippable and the whole thing is re-runnable from Settings, because a step
 * that depends on a Motorola settings page can fail in ways this app cannot detect.
 */
@Composable
fun SetupWizard(
    viewModel: SetupViewModel,
    onFinished: () -> Unit,
) {
    val step by viewModel.step.collectAsState()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Setup ${step.ordinal + 1} of ${SetupStep.entries.size - 1}",
            style = MaterialTheme.typography.labelMedium,
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                SetupStep.WELCOME -> WelcomeStep(viewModel)
                SetupStep.PERMISSIONS -> PermissionsStep()
                SetupStep.MODELS -> ModelsStep(viewModel)
                SetupStep.COVER_SCREEN -> CoverScreenStep()
                SetupStep.TEST -> TestStep(viewModel)
                SetupStep.DONE -> DoneStep(viewModel)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (step != SetupStep.WELCOME) {
                OutlinedButton(onClick = viewModel::back) { Text("Back") }
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
            ) {
                if (step == SetupStep.DONE) {
                    Button(
                        onClick = {
                            viewModel.finish()
                            onFinished()
                        },
                    ) { Text("Start recording") }
                } else {
                    TextButton(onClick = viewModel::next) { Text("Skip") }
                    Button(onClick = viewModel::next) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(viewModel: SetupViewModel) {
    Heading("This phone listens, and keeps it to itself")
    Body(
        "It records continuously, writes down what it hears, and flags the things you tell " +
            "it to care about.",
    )
    Body(
        "The recording never leaves this phone. Speech is turned into text here, by this " +
            "phone's own processor. There is no cloud transcription, and no account to sign in to.",
    )
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("This phone", style = MaterialTheme.typography.titleSmall)
            Body("${"%.0f".format(viewModel.ramGb)} GB of memory, tier ${viewModel.ramTier}")
            Body(
                when (viewModel.ramTier.name) {
                    "LOW_8GB" -> "Enough for recording, transcription and a small assistant."
                    "MID_12GB" -> "Enough for recording, transcription and a mid-size assistant."
                    else -> "Enough for the largest assistant this app supports."
                },
            )
        }
    }
}

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        notifGranted = result[Manifest.permission.POST_NOTIFICATIONS] ?: notifGranted
        if (micGranted) RecordingService.start(context)
    }

    // If another installed Recorder actually has the microphone, that shows up honestly once
    // recording is running — RecorderApp's silencing banner reacts to this app's own capture
    // actually going silent. Guessing about it here, before starting, could not be done
    // reliably (see MicConflict's doc comment) and used to block setup on ordinary, unrelated
    // microphone use such as an assistant hotword.
    val otherVersionInstalled = remember { MicConflict.otherVersions(context).isNotEmpty() }

    Heading("Two permissions")
    Body("Microphone — so it can hear. Without this the app does nothing at all.")
    Body(
        "Notifications — Android requires a permanent notification while an app uses the " +
            "microphone. It is also how you can tell at a glance that recording is running.",
    )

    StatusLine("Microphone", micGranted)
    StatusLine("Notifications", notifGranted)

    Button(
        onClick = {
            val wanted = buildList {
                if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
        },
        modifier = Modifier.padding(top = 12.dp),
        enabled = !(micGranted && notifGranted),
    ) { Text(if (micGranted && notifGranted) "Both granted" else "Grant permissions") }

    if (!micGranted) {
        TextButton(onClick = { context.openAppSettings() }) {
            Text("Denied by mistake? Open app settings")
        }
    }

    if (otherVersionInstalled) {
        Body(
            "Another version of Recorder is installed. Android gives the microphone to one app " +
                "at a time — if recording ever looks stuck, check whether the other one is using it.",
        )
    }
}

@Composable
private fun ModelsStep(viewModel: SetupViewModel) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsState()
    val installing by viewModel.installing.collectAsState()
    val pending = viewModel.pendingBytes()
    val free = viewModel.freeBytes()
    val unmetered = viewModel.onUnmeteredNetwork()
    val otherVersionInstalled = remember { MicConflict.otherVersions(context).isNotEmpty() }

    Heading("Download what it needs to hear you")
    Body(
        "These files do the transcription on the phone. They are downloaded once and then " +
            "work offline forever.",
    )
    if (otherVersionInstalled) {
        Body(
            "Another version of Recorder is installed. Android keeps every app's files " +
                "separate, even between versions of the same app, so its downloaded models " +
                "are not visible here — this needs its own copy, even if you already " +
                "downloaded them once for the other one.",
        )
    }

    if (!unmetered) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Not on Wi-Fi", style = MaterialTheme.typography.titleSmall)
                Body("This is a large download. Connect to Wi-Fi, or allow mobile data.")
                TextButton(onClick = viewModel::allowMetered) { Text("Use mobile data anyway") }
            }
        }
    }

    models.forEach { state ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.selected || state.installed,
                        onCheckedChange = { viewModel.toggle(state.entry.id) },
                        // Deliberately still live while a download runs: ticking one more
                        // model adds it to the queue instead of making you wait and come back.
                        enabled = !state.entry.required && !state.installed,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(state.entry.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${state.entry.approxMb} MB · ${state.entry.license}" +
                                if (state.entry.required) " · required" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                state.entry.notes?.let { Body(it) }
                if (!state.entry.hashVerified) {
                    Body("Note: this download's checksum could not be verified in advance.")
                }
                ProgressLine(state.progress) { viewModel.retry(state.entry.id) }
                if (state.installed && !state.entry.required) {
                    TextButton(onClick = { viewModel.remove(state.entry.id) }) { Text("Remove") }
                }
            }
        }
    }

    Body(
        "About ${pending / (1024 * 1024)} MB to download. " +
            "${free / (1024 * 1024)} MB free on this phone.",
    )

    val failed = models.count { it.progress is InstallProgress.Failed }
    if (failed > 0) {
        Body(
            "$failed download(s) failed. Each one says why above, with a Try again button " +
                "where retrying can help. Downloading continues if you leave this screen.",
        )
    }
    viewModel.message.collectAsState().value?.let { message ->
        Body(message)
        TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
    }

    Row {
        Button(onClick = viewModel::selectAllAndDownload, enabled = pending > 0) {
            Text(if (installing) "Add the rest" else "Download all")
        }
        if (installing) {
            TextButton(onClick = viewModel::cancelInstall) { Text("Stop") }
        }
        if (!installing && failed > 0) {
            TextButton(onClick = viewModel::retryFailed) { Text("Retry all failed") }
        }
    }
    if (installing) {
        Body(
            "Carry on with setup — this keeps going in the background, with the screen off " +
                "and the app closed, and there is a notification with the progress. Each " +
                "model starts being used the moment it lands; nothing needs restarting.",
        )
    } else if (pending > 0) {
        Body(
            "\"Download all\" takes everything above. You can press Next straight after: " +
                "downloading continues behind the rest of setup.",
        )
    }
}

@Composable
private fun ProgressLine(progress: InstallProgress, onRetry: () -> Unit) {
    when (progress) {
        // Nothing claimed for a model nothing is happening to. Showing a stalled progress bar
        // for a download that is not running was half of why failures looked like silence.
        is InstallProgress.Idle -> Unit

        is InstallProgress.Queued -> Text(
            "Waiting its turn…",
            style = MaterialTheme.typography.labelSmall,
        )

        is InstallProgress.Downloading -> Column(Modifier.padding(top = 6.dp)) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${progress.percent}% · ${progress.bytes / (1024 * 1024)} of " +
                    "${progress.total / (1024 * 1024)} MB",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        is InstallProgress.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp))
            Text("Checking the download is intact…", style = MaterialTheme.typography.labelSmall)
        }

        is InstallProgress.Extracting -> Text("Unpacking…", style = MaterialTheme.typography.labelSmall)
        is InstallProgress.Done -> Text("✓ Installed", style = MaterialTheme.typography.labelSmall)

        is InstallProgress.Failed -> Column(Modifier.padding(top = 4.dp)) {
            Text(
                "Failed: ${progress.reason}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            // A retry button either way: "not retryable" means the same attempt will fail
            // the same way, not that the user has no move. The screenshot that prompted this
            // showed a bug being reported as "free up space", which was simply untrue.
            TextButton(onClick = onRetry) { Text("Try again") }
            if (!progress.retryable) {
                Text(
                    "Retrying alone may not be enough — if it says the same thing again, " +
                        "Settings → Diagnostics has the detail worth sending on.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CoverScreenStep() {
    val context = LocalContext.current

    Heading("Show it on the cover screen")
    Body(
        "So that closing the phone shows the live transcript instead of Motorola's home " +
            "screen, this app has to be allowed on the outer display.",
    )
    Body("Motorola puts this in its own settings, which cannot be linked to directly. The path is:")
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Settings → Display → External display → App settings →\n" +
                "Recorder → Allow on external display → Auto transition",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
    Button(onClick = { context.openDisplaySettings() }) { Text("Open display settings") }
    Body(
        "If you cannot find it, skip this. Everything still records; you would just open " +
            "\"Recorder Cover\" from the cover screen's app list yourself.",
    )
}

@Composable
private fun TestStep(viewModel: SetupViewModel) {
    val recorderState by RecordingService.state.collectAsState()

    Heading("Say something")
    Body("Talk near the phone for a few seconds, then check the Live tab for your words.")

    StatusLine("Recording running", recorderState == RecordingService.RecorderState.RECORDING)
    StatusLine("Transcription ready", viewModel.transcriptionReady())
    StatusLine("On-device chat ready", viewModel.localChatReady())

    viewModel.localChatBlocker()?.let {
        Body("Chat is not available yet: $it. Recording and transcription do not depend on it.")
    }
}

@Composable
private fun DoneStep(viewModel: SetupViewModel) {
    Heading("Ready")
    StatusLine("Required models installed", viewModel.requiredInstalled())
    StatusLine("Transcription ready", viewModel.transcriptionReady())
    Body("Recording runs from now on, including after a reboot. Close the phone to see the cover screen.")
    Body("You can run this setup again any time from Settings.")
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text(
        "${if (ok) "✓" else "✗"}  $label",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.openDisplaySettings() {
    // There is no public action for Motorola's external-display page, so this lands on the
    // general display settings and the exact tap path is shown above it.
    runCatching {
        startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
