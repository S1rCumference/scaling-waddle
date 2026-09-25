package com.recorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.app.service.RecordingService
import kotlinx.coroutines.delay
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.HourSummary


/**
 * Live: the transcript as it lands, and today's logs grouped by hour below it. On the cover
 * screen the hourly list is one tap away rather than squeezed underneath.
 */
/**
 * A progress bar with the name of whatever is running, or nothing at all when idle.
 *
 * Rendered by the shell rather than by one tab, so it is on every tab of both screens —
 * the cover screen is where a long wait is most likely to be noticed. It is
 * indeterminate on purpose: loading a model and generating tokens have no honest
 * percentage, and a fake one that sticks at 90% is worse than a bar that simply moves.
 * The label carries the real detail — "Thinking · 96 tokens", "Correcting · part 3 of 12".
 */
@Composable
fun BusyBar(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val busy by viewModel.busy.collectAsState()
    val running by viewModel.runningTasks.collectAsState()
    val label = busy ?: return

    // One second is the right granularity for "is this still going". Ticking only while
    // something is running means an idle app does no work for this at all.
    val elapsed by produceState(0L, running.isNotEmpty()) {
        while (running.isNotEmpty()) {
            value = running.minOfOrNull { System.currentTimeMillis() - it.startedAt } ?: 0L
            delay(1_000)
        }
    }
    val cancellable = running.any { it.cancel != null }

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$label · ${formatRunTime(elapsed)}",
                color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(top = 2.dp),
            )
            if (cancellable) {
                TextButton(onClick = viewModel::cancelRunning) {
                    Text("Cancel", fontSize = if (compact) 11.sp else 13.sp)
                }
            }
        }
    }
}

/** m:ss, which is the scale these actually run on. */
private fun formatRunTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * One line: stop now, or pause for an hour and have it come back on its own.
 *
 * Lives at the top of the live view, which is what both screens show, so the same control
 * is on the phone open and closed without a second copy to keep in step. One line, because
 * on the cover screen every line is a third of what there is.
 */
@Composable
private fun PauseBar(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val state by viewModel.recorderState.collectAsState()
    val pausedUntil by viewModel.pausedUntil.collectAsState()
    if (state != RecordingService.RecorderState.RECORDING) return

    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pausedUntil > 0L) {
            Text(
                "Paused until ${Clocks.shortTime(pausedUntil)}",
                color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (compact) 12.sp else 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::resumeNow) { Text("Resume", fontSize = 13.sp) }
        } else {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.pauseFor(PAUSE_MINUTES) }) {
                Text("Pause 1h", fontSize = 13.sp)
            }
            TextButton(onClick = { viewModel.setRecording(false) }) {
                Text("Stop", fontSize = 13.sp)
            }
        }
    }
}

/** "Pause it for, let's say, an hour." */
private const val PAUSE_MINUTES = 60

@Composable
fun LiveTab(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val hours by viewModel.todayHours.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PauseBar(viewModel)
        if (compact) {
            if (hours.isNotEmpty()) {
                Text(
                    "Today by hour (${hours.size}) ›",
                    color = CoverColors.live,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        viewModel.openGroup(null)
                        viewModel.selectTab(AppTab.LOGS)
                    }.padding(vertical = 4.dp),
                )
            }
            LiveFeed(viewModel, Modifier.weight(1f))
        } else {
            LiveFeed(viewModel, Modifier.weight(0.55f).padding(horizontal = 12.dp))
            HorizontalDivider()
            Text(
                "Today by hour",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            )
            HourList(hours, Modifier.weight(0.45f)) { viewModel.openGroup(GroupRef.hour(it.firstTs)) }
        }
    }
}

/** Newest at the bottom, following along until you scroll back to read something. */
@Composable
private fun LiveFeed(viewModel: RecorderViewModel, modifier: Modifier) {
    val compact = LocalCompact.current
    val segments by viewModel.coverTranscripts.collectAsState()
    val listState = rememberSharedListState("live")

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
        }
    }
    LaunchedEffect(segments.size, atBottom) {
        if (atBottom && segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }

    if (segments.isEmpty()) {
        Column(modifier) { EmptyState("Listening… nothing transcribed yet.") }
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(segments, key = { it.id }) { segment ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    Clocks.shortTime(segment.startTs),
                    color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp,
                )
                Text(
                    segment.text,
                    color = if (compact) Color.White else MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
fun HourList(hours: List<HourSummary>, modifier: Modifier = Modifier, onOpen: (HourSummary) -> Unit) {
    val compact = LocalCompact.current
    if (hours.isEmpty()) {
        Column(modifier) { EmptyState("Nothing recorded today yet.") }
        return
    }
    val state = rememberSharedListState("today-hours")
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        items(hours, key = { it.bucket }) { hour ->
            val start = com.recorder.core.storage.DayKey.hourStart(hour.firstTs)
            val label = "${Clocks.shortTime(start)}–${Clocks.shortTime(start + 3_600_000)}"
            if (compact) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(hour) }.padding(vertical = 8.dp)) {
                    Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("${hour.count} lines", color = CoverColors.dim, fontSize = 13.sp)
                }
            } else {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable { onOpen(hour) }) {
                    Row(Modifier.padding(12.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${hour.count} lines", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
