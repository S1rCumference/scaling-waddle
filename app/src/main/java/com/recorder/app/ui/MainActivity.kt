package com.recorder.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.recorder.app.service.RecordingService
import com.recorder.app.ui.setup.SetupViewModel
import com.recorder.app.ui.setup.SetupWizard
import com.recorder.core.storage.PendingAction
import com.recorder.core.storage.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)

class MainActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingService.start(this)
        }
    }

    private val setupViewModel: SetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecorderTheme {
                val setupComplete by viewModel.setupComplete.collectAsState()
                var rerunSetup by remember { mutableStateOf(false) }

                when {
                    // Still reading the flag; showing nothing beats flashing the wizard.
                    setupComplete == null -> Unit

                    setupComplete == false || rerunSetup -> SetupWizard(setupViewModel) {
                        rerunSetup = false
                        requestPermissionsThenRecord()
                    }

                    else -> {
                        LaunchedEffect(Unit) { requestPermissionsThenRecord() }
                        MainScreen(viewModel, onRunSetup = {
                            setupViewModel.reload()
                            rerunSetup = true
                        })
                    }
                }
            }
        }
    }

    private fun requestPermissionsThenRecord() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) RecordingService.start(this) else permissionLauncher.launch(needed.toTypedArray())
    }
}

private enum class Tab(val label: String) {
    LIVE("Live"), ASK("Ask"), FLAGGED("Flagged"), DRAFTS("Drafts"), SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: RecorderViewModel, onRunSetup: () -> Unit = {}) {
    var tab by remember { mutableStateOf(Tab.LIVE) }
    val snackbar = remember { SnackbarHostState() }
    val status by viewModel.status.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorder") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (recorderState) {
                                RecordingService.RecorderState.RECORDING -> "recording"
                                RecordingService.RecorderState.NEEDS_PERMISSION -> "no mic permission"
                                RecordingService.RecorderState.ERROR -> "error"
                                RecordingService.RecorderState.STOPPED -> "stopped"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Switch(
                            checked = recorderState == RecordingService.RecorderState.RECORDING,
                            onCheckedChange = viewModel::setRecording,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.LIVE -> TranscriptList(viewModel)
                Tab.ASK -> ChatPanel(viewModel, compact = false)
                Tab.FLAGGED -> FlaggedList(viewModel)
                Tab.DRAFTS -> DraftList(viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel, onRunSetup)
            }
        }
    }
}

@Composable
private fun TranscriptList(viewModel: RecorderViewModel) {
    val segments by viewModel.transcripts.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val folderNames = remember(folders) { folders.associate { it.id to it.name } }

    if (segments.isEmpty()) {
        EmptyState("Nothing transcribed yet. Say something near the phone.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(segments, key = { it.id }) { segment ->
            TranscriptRow(segment, folderNames[segment.folderId])
        }
    }
}

@Composable
private fun TranscriptRow(segment: TranscriptSegment, folderName: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CLOCK.format(Date(segment.startTs)), style = MaterialTheme.typography.labelSmall)
            folderName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        Text(segment.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FlaggedList(viewModel: RecorderViewModel) {
    val flags by viewModel.flagged.collectAsState()
    val segments by viewModel.transcripts.collectAsState()
    val byId = remember(segments) { segments.associateBy { it.id } }

    if (flags.isEmpty()) {
        EmptyState("No triggers matched yet. Trigger phrases live in Settings.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(flags, key = { it.id }) { flag ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("\"${flag.keyword}\"", style = MaterialTheme.typography.labelLarge)
                    Text(
                        byId[flag.segmentId]?.text ?: "(segment ${flag.segmentId})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { viewModel.dismissFlag(flag.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun DraftList(viewModel: RecorderViewModel) {
    val drafts by viewModel.drafts.collectAsState()
    if (drafts.isEmpty()) {
        EmptyState("No drafts waiting. Nothing is ever sent without your approval.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(drafts, key = { it.id }) { draft -> DraftCard(draft, viewModel) }
    }
}

@Composable
private fun DraftCard(draft: PendingAction, viewModel: RecorderViewModel) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(draft.title, style = MaterialTheme.typography.titleSmall)
            Text(draft.preview, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { viewModel.approveDraft(draft.id) }) { Text("Approve & send") }
                TextButton(onClick = { viewModel.rejectDraft(draft.id) }) { Text("Discard") }
            }
        }
    }
}

@Composable
fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
