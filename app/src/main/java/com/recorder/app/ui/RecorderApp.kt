package com.recorder.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.app.service.RecordingService
import kotlinx.coroutines.delay

/** True on the cover screen (or any small window): the compact layout. */
val LocalCompact = compositionLocalOf { false }

/** Colours of the cover screen's look, kept exactly as 2.0 had them. */
object CoverColors {
    val live = Color(0xFF7FD1AE)
    val dim = Color(0xFF777777)
    val muted = Color(0xFFBBBBBB)
    val faint = Color(0xFF888888)
}

/**
 * Compact on the cover display, or on any window too small for the full layout. Decided by
 * size and display, never by fold state — the same rule works whichever activity is showing
 * and whether Motorola moved it across or the recorder launched it.
 */
@Composable
fun isCompactWindow(): Boolean {
    val config = LocalConfiguration.current
    val onSecondaryDisplay = LocalView.current.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true
    return onSecondaryDisplay || maxOf(config.screenWidthDp, config.screenHeightDp) < 700
}

/**
 * The whole app, for both screens. One composable, one shared state ([AppUiState]), one
 * database: folding swaps the layout, not the place you were in.
 */
@Composable
fun RecorderApp(viewModel: RecorderViewModel, onRunSetup: () -> Unit) {
    val compact = isCompactWindow()
    val scheme = if (compact) {
        darkColorScheme(background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White)
    } else {
        null
    }
    CompositionLocalProvider(LocalCompact provides compact) {
        if (scheme != null) {
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { CompactShell(viewModel, onRunSetup) }
            }
        } else {
            RecorderTheme { ExpandedShell(viewModel, onRunSetup) }
        }
        CapabilitiesDialog(viewModel)
    }
}

@Composable
private fun TabContent(viewModel: RecorderViewModel, tab: AppTab, onRunSetup: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SilencedBanner(viewModel)
        when (tab) {
            AppTab.LIVE -> LiveTab(viewModel)
            AppTab.LOGS -> LogsTab(viewModel)
            AppTab.FLAGS -> FlagsTab(viewModel)
            AppTab.SETTINGS -> SettingsScreen(viewModel, onRunSetup)
        }
    }
}

// ---------------------------------------------------------------- compact (cover screen)

/**
 * The cover-screen layout: true black, few large touch targets, text tabs at the bottom.
 * Built for a 4 inch OLED held at arm's length, where every lit pixel costs battery.
 */
@Composable
private fun CompactShell(viewModel: RecorderViewModel, onRunSetup: () -> Unit) {
    val tab by viewModel.tab.collectAsState()
    val status by viewModel.status.collectAsState()

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 8.dp, vertical = 6.dp)) {
        CoverStatusBar(viewModel)
        status?.let { message ->
            LaunchedEffect(message) {
                delay(2_500)
                viewModel.clearStatus()
            }
            Text(message, color = CoverColors.live, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        Box(Modifier.weight(1f)) { TabContent(viewModel, tab, onRunSetup) }
        CompactTabs(tab, viewModel.flagged.collectAsState().value.size, viewModel.drafts.collectAsState().value.size) {
            viewModel.selectTab(it)
        }
    }
}

@Composable
private fun CoverStatusBar(viewModel: RecorderViewModel) {
    val state by viewModel.recorderState.collectAsState()
    val since by RecordingService.recordingSince.collectAsState()
    val progress by viewModel.correctionProgress.collectAsState()
    val context = LocalContext.current

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
            value = context.batteryPercentNow()
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
            color = if (live) CoverColors.live else CoverColors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (progress != null) Text("correcting…", color = CoverColors.dim, fontSize = 12.sp)
        Text(if (battery >= 0) "$battery%" else "", color = CoverColors.muted, fontSize = 13.sp)
    }
}

@Composable
private fun CompactTabs(current: AppTab, flagCount: Int, draftCount: Int, onSelect: (AppTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        AppTab.entries.forEach { entry ->
            val label = when (entry) {
                AppTab.FLAGS -> if (draftCount > 0) "Flags ($draftCount)" else "Flags"
                AppTab.SETTINGS -> "More"
                else -> entry.label
            }
            Text(
                text = label,
                color = if (entry == current) Color.White else CoverColors.dim,
                fontSize = 15.sp,
                fontWeight = if (entry == current) FontWeight.Bold else FontWeight.Normal,
                // Generous padding rather than a Button: bigger thumb target, less drawn.
                modifier = Modifier.clickable { onSelect(entry) }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- expanded (inner screen)

/**
 * The inner-screen layout. Live, Logs and Flags are tabs; Settings opens as a panel from the
 * side, over whatever you were looking at, so closing it puts you straight back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedShell(viewModel: RecorderViewModel, onRunSetup: () -> Unit) {
    val tab by viewModel.tab.collectAsState()
    val status by viewModel.status.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val progress by viewModel.correctionProgress.collectAsState()
    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    // Settings is a side panel here, so the tab underneath is the last content tab.
    val underneath by AppUiState.contentTab.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Recorder 2.1")
                            progress?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    actions = {
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
                        TextButton(onClick = { viewModel.selectTab(AppTab.SETTINGS) }) { Text("Settings") }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(AppTab.LIVE, AppTab.LOGS, AppTab.FLAGS).forEach { entry ->
                        NavigationBarItem(
                            selected = underneath == entry,
                            onClick = { viewModel.selectTab(entry) },
                            icon = {},
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { TabContent(viewModel, underneath, onRunSetup) }
        }

        if (tab == AppTab.SETTINGS) {
            // Scrim, then the panel from the end edge.
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .clickable { viewModel.selectTab(underneath) },
            )
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    Modifier.fillMaxHeight().widthIn(max = 460.dp).fillMaxWidth(0.92f),
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Settings", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { viewModel.selectTab(underneath) }) { Text("Close") }
                        }
                        SettingsScreen(viewModel, onRunSetup)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

/**
 * A list state whose position lives in [AppUiState], so it survives a fold.
 *
 * Only the screen in front writes it. When a screen comes back to the front — the inner
 * screen on unfolding, say, after you scrolled on the cover — it jumps to wherever the other
 * one left off rather than to where it was itself.
 */
@Composable
fun rememberSharedListState(key: String): LazyListState {
    val saved = AppUiState.scroll[key]
    val state = rememberLazyListState(saved?.index ?: 0, saved?.offset ?: 0)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AppUiState.scroll[key]?.let { pos ->
                if (pos.index != state.firstVisibleItemIndex || pos.offset != state.firstVisibleItemScrollOffset) {
                    state.scrollToItem(pos.index, pos.offset)
                }
            }
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> AppUiState.scroll[key] = ScrollPos(index, offset) }
        }
    }
    return state
}

@Composable
private fun SilencedBanner(viewModel: RecorderViewModel) {
    val silenced by viewModel.micSilenced.collectAsState()
    if (!silenced) return
    Text(
        "The microphone is taken by another app — probably the other Recorder. This one is " +
            "recording silence until that one stops.",
        color = Color(0xFFFFB4A9),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(8.dp)
            .clickable { viewModel.openOtherRecorder() },
    )
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun Context.batteryPercentNow(): Int = runCatching {
    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    } ?: -1
}.getOrDefault(-1)

@Composable
fun EmptyState(message: String) {
    val compact = LocalCompact.current
    Column(
        Modifier.fillMaxSize().padding(if (compact) 8.dp else 24.dp),
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = if (compact) CoverColors.faint else MaterialTheme.colorScheme.onBackground,
            fontSize = if (compact) 15.sp else MaterialTheme.typography.bodyMedium.fontSize,
        )
    }
}
