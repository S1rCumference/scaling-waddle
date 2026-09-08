package com.recorder.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.recorder.app.service.RecordingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SHORT_CLOCK = SimpleDateFormat("HH:mm", Locale.US)

/**
 * The closed-phone screen: live transcript, and a chat box wired to the on-device model.
 *
 * Launcher-visible so the Razr's cover-screen app list starts it directly. It reads the
 * same database the recorder writes to and does not touch the service, so nothing about
 * opening or closing the phone can interrupt recording.
 */
class CoverActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecorderTheme {
                Surface(Modifier.fillMaxSize()) { CoverScreen(viewModel) }
            }
        }
    }
}

@Composable
private fun CoverScreen(viewModel: RecorderViewModel) {
    var chatMode by remember { mutableStateOf(false) }
    val recorderState by viewModel.recorderState.collectAsState()

    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (recorderState == RecordingService.RecorderState.RECORDING) "● live" else "○ idle",
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = { chatMode = !chatMode }) {
                Text(if (chatMode) "Transcript" else "Ask", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (chatMode) {
            ChatPanel(viewModel, compact = true)
        } else {
            LiveFeed(viewModel)
        }
    }
}

@Composable
private fun LiveFeed(viewModel: RecorderViewModel) {
    val segments by viewModel.transcripts.collectAsState()
    val listState = rememberLazyListState()

    // Newest first, so the latest line is always in view without scrolling.
    LaunchedEffect(segments.firstOrNull()?.id) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(0)
    }

    if (segments.isEmpty()) {
        Text("Listening…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(segments, key = { it.id }) { segment ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(SHORT_CLOCK.format(Date(segment.startTs)), style = MaterialTheme.typography.labelSmall)
                Text(segment.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
