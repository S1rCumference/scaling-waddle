package com.recorder.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.app.service.RecordingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val SHORT_CLOCK = SimpleDateFormat("HH:mm", Locale.US)

/**
 * The closed-phone screen: live transcript, and a chat box wired to the on-device model.
 *
 * Built for a 4.0 inch 1272x1080 panel, held at arm's length and tapped with a thumb, on an
 * OLED where every lit pixel costs battery. Hence: true black, no decoration, few and large
 * touch targets.
 *
 * It reads the same database the recorder writes to and never touches the service, so
 * nothing about opening or closing the phone can interrupt recording.
 */
class CoverActivity : ComponentActivity() {

    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecorderTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CoverScreen(viewModel)
                }
            }
        }
    }
}

private enum class CoverView { TRANSCRIPT, ASK, FLAGGED }

@Composable
private fun CoverScreen(viewModel: RecorderViewModel) {
    var view by remember { mutableStateOf(CoverView.TRANSCRIPT) }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        CoverStatusBar(viewModel)

        Box(Modifier.weight(1f)) {
            when (view) {
                CoverView.TRANSCRIPT -> LiveFeed(viewModel)
                CoverView.ASK -> ChatPanel(viewModel, compact = true)
                CoverView.FLAGGED -> FlaggedFeed(viewModel)
            }
        }

        CoverTabs(
            current = view,
            draftCount = viewModel.drafts.collectAsState().value.size,
            onSelect = { view = it },
        )
    }
}

@Composable
private fun CoverStatusBar(viewModel: RecorderViewModel) {
    val state by viewModel.recorderState.collectAsState()
    val since by RecordingService.recordingSince.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Recomputed once a second at most; the cover screen is idle most of its life.
    val elapsed by produceState(initialValue = "", since, state) {
        while (true) {
            value = since
                ?.takeIf { state == RecordingService.RecorderState.RECORDING }
                ?.let { formatElapsed(System.currentTimeMillis() - it) }
                .orEmpty()
            delay(1_000)
        }
    }

    val battery by produceState(initialValue = -1) {
        while (true) {
            value = context.batteryPercent()
            delay(60_000)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val live = state == RecordingService.RecorderState.RECORDING
        Text(
            text = if (live) "● $elapsed" else "○ paused",
            color = if (live) Color(0xFF7FD1AE) else Color(0xFFBBBBBB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (battery >= 0) "$battery%" else "",
            color = Color(0xFFBBBBBB),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun LiveFeed(viewModel: RecorderViewModel) {
    val segments by viewModel.coverTranscripts.collectAsState()
    val listState = rememberLazyListState()

    // Newest at the bottom, but auto-scroll stops the moment the user scrolls back to read
    // something — nothing is more annoying than a feed that yanks itself away.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= (segments.lastIndex - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(segments.size, atBottom) {
        if (atBottom && segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }

    if (segments.isEmpty()) {
        Text("Listening…", color = Color(0xFF888888), fontSize = 15.sp, modifier = Modifier.padding(8.dp))
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(segments, key = { it.id }) { segment ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    SHORT_CLOCK.format(Date(segment.startTs)),
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                )
                Text(segment.text, color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun FlaggedFeed(viewModel: RecorderViewModel) {
    val flags by viewModel.flagged.collectAsState()
    if (flags.isEmpty()) {
        Text("Nothing flagged.", color = Color(0xFF888888), fontSize = 15.sp, modifier = Modifier.padding(8.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(flags, key = { it.id }) { flag ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Text("\"${flag.keyword}\"", color = Color(0xFF7FD1AE), fontSize = 13.sp)
                Text(
                    SHORT_CLOCK.format(Date(flag.createdTs)),
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CoverTabs(current: CoverView, draftCount: Int, onSelect: (CoverView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CoverView.entries.forEach { entry ->
            val label = when (entry) {
                CoverView.TRANSCRIPT -> "Live"
                CoverView.ASK -> "Ask"
                CoverView.FLAGGED -> if (draftCount > 0) "Flags ($draftCount)" else "Flags"
            }
            Text(
                text = label,
                color = if (entry == current) Color.White else Color(0xFF777777),
                fontSize = 15.sp,
                fontWeight = if (entry == current) FontWeight.Bold else FontWeight.Normal,
                // Generous padding rather than a Button: bigger thumb target, less drawn.
                modifier = Modifier
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun Context.batteryPercent(): Int = runCatching {
    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    } ?: -1
}.getOrDefault(-1)
