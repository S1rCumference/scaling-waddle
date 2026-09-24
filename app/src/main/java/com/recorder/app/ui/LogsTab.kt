package com.recorder.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.core.storage.CorrectionPass
import com.recorder.core.storage.DayKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * Logs: everything, grouped without anyone tagging anything — today by hour, earlier days by
 * day. Opening a group shows its lines (original, corrected, or both), exports it, and asks
 * questions about just that group.
 */
@Composable
fun LogsTab(viewModel: RecorderViewModel) {
    val group by viewModel.openGroup.collectAsState()
    val current = group
    if (current == null) GroupList(viewModel) else GroupDetail(viewModel, current)
}

@Composable
private fun GroupList(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val hours by viewModel.recentHours.collectAsState()
    val days by viewModel.days.collectAsState()
    val months by viewModel.months.collectAsState()
    val openDay by viewModel.openDay.collectAsState()
    val openDayHours by viewModel.openDayHours.collectAsState()
    var openMonth by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val query by viewModel.logQuery.collectAsState()
    val matches by viewModel.logMatches.collectAsState()
    val state = rememberSharedListState("logs")

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp), state = state) {
        item {
            // One line, collapsed to a word until it is wanted. The live half of the screen
            // is already the tight part; a search bar that is always open costs a line of it
            // every day to save a tap now and then.
            if (searching) {
                SearchField(query, viewModel::setLogQuery) {
                    searching = false
                    viewModel.setLogQuery("")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { searching = true }) { Text("Search") }
                    TextButton(onClick = { viewModel.openGroup(GroupRef.ALL) }) { Text("Ask everything") }
                    TextButton(onClick = { exporting = true }) { Text("Export") }
                }
            }
        }

        if (query.isNotBlank()) {
            if (matches.isEmpty()) {
                item { EmptyState("Nothing matches \"$query\".") }
            } else {
                item { SectionLabel("${matches.size} match${if (matches.size == 1) "" else "es"}") }
                items(matches, key = { "m${it.id}" }) { segment ->
                    SearchResult(segment) { viewModel.openContaining(segment) }
                }
            }
            return@LazyColumn
        }

        // The last day stays flat. It is the part you actually scroll looking for something
        // you said this morning, and folding it away to save three rows would cost more
        // taps than it saves.
        if (hours.isNotEmpty()) {
            item { SectionLabel("Last 24 hours") }
            items(hours, key = { "h${it.bucket}" }) { hour ->
                val ref = GroupRef.hour(hour.firstTs)
                GroupRow(ref.title(), "${hour.count} lines") { viewModel.openGroup(ref) }
            }
        }

        // Everything older rolls up: months, then days, then hours. A flat list of every
        // hour ever recorded stops being navigable inside a week.
        if (months.isNotEmpty()) {
            item { SectionLabel("Earlier") }
            months.forEach { month ->
                val expanded = openMonth == month.monthKey
                item(key = "m${month.monthKey}") {
                    GroupRow(
                        title = monthTitle(month.monthKey),
                        detail = "${month.days.size} day(s) · ${month.lineCount} lines",
                        chevron = if (expanded) "−" else "+",
                    ) {
                        openMonth = if (expanded) null else month.monthKey
                        viewModel.openDay(null)
                    }
                }
                if (!expanded) return@forEach

                month.days.forEach { day ->
                    val dayOpen = openDay == day.dayKey
                    item(key = "d${day.dayKey}") {
                        val span = "${CLOCK_SHORT.format(Date(day.firstTs))}–" +
                            CLOCK_SHORT.format(Date(day.lastTs))
                        GroupRow(
                            title = GroupRef.day(day.dayKey).title(),
                            detail = "${day.count} lines · $span",
                            indent = 1,
                            chevron = if (dayOpen) "−" else "+",
                        ) { viewModel.openDay(day.dayKey) }
                    }
                    if (!dayOpen) return@forEach

                    if (openDayHours.isEmpty()) {
                        item(key = "dl${day.dayKey}") {
                            Text(
                                "Loading…",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(openDayHours, key = { "dh${day.dayKey}-${it.bucket}" }) { hour ->
                        val ref = GroupRef.hour(hour.firstTs)
                        GroupRow(ref.title(), "${hour.count} lines", indent = 2) {
                            viewModel.openGroup(ref)
                        }
                    }
                    // Whole day at once, for asking about it or exporting it.
                    item(key = "da${day.dayKey}") {
                        GroupRow("The whole day", "${day.count} lines", indent = 2) {
                            viewModel.openGroup(GroupRef.day(day.dayKey))
                        }
                    }
                }
            }
        }

        if (hours.isEmpty() && months.isEmpty()) {
            item { EmptyState("No logs yet. They group themselves as you talk.") }
        }
    }

    if (exporting) {
        RangeExportDialog(viewModel, days.map { it.dayKey }) { exporting = false }
    }
}

/** The search box: one line, with a way back out of it. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val compact = LocalCompact.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text("Search every log", fontSize = if (compact) 13.sp else 15.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (compact) 13.sp else 15.sp),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose) { Text("Close") }
    }
}

/** One found line: when it was said, and enough of it to recognise. Tapping opens its group. */
@Composable
private fun SearchResult(
    segment: com.recorder.core.storage.TranscriptSegment,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    val stamp = SimpleDateFormat(
        if (DayKey.of(segment.startTs) == DayKey.today()) "HH:mm" else "d MMM HH:mm",
        Locale.getDefault(),
    ).format(Date(segment.startTs))

    if (compact) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
            Text(stamp, color = CoverColors.dim, fontSize = 11.sp)
            Text(segment.text, color = Color.White, fontSize = 14.sp, maxLines = 3)
        }
    } else {
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick)) {
            Column(Modifier.padding(12.dp)) {
                Text(stamp, style = MaterialTheme.typography.labelSmall)
                Text(segment.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val compact = LocalCompact.current
    Text(
        text,
        color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

private val CLOCK_SHORT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** "September 2026" from a yyyyMM key. */
private fun monthTitle(monthKey: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.YEAR, monthKey / 100)
        set(java.util.Calendar.MONTH, monthKey % 100 - 1)
        set(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    return SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(calendar.time)
}

@Composable
private fun GroupRow(
    title: String,
    detail: String,
    indent: Int = 0,
    chevron: String? = null,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    val pad = (indent * if (compact) 10 else 16).dp
    if (compact) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(start = pad, top = 8.dp, bottom = 8.dp),
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(detail, color = CoverColors.dim, fontSize = 12.sp)
            chevron?.let { Text(" $it", color = CoverColors.dim, fontSize = 14.sp) }
        }
    } else {
        Card(
            Modifier.fillMaxWidth().padding(start = pad, top = 3.dp, bottom = 3.dp)
                .clickable(onClick = onClick),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(detail, style = MaterialTheme.typography.labelMedium)
                chevron?.let {
                    Text("  $it", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- one group

@Composable
private fun GroupDetail(viewModel: RecorderViewModel, group: GroupRef) {
    val compact = LocalCompact.current
    val askOpen by AppUiState.askOpen.collectAsState()
    var exporting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.openGroup(null) }) { Text("‹ All logs") }
            Row {
                if (group.kind != GroupKind.ALL) {
                    TextButton(onClick = { exporting = true }) { Text("Export") }
                }
                if (compact && group.kind != GroupKind.ALL) {
                    TextButton(onClick = { AppUiState.askOpen.value = !askOpen }) {
                        Text(if (askOpen) "Lines" else "Ask")
                    }
                }
            }
        }
        Text(
            group.title(),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = if (compact) Color.White else MaterialTheme.colorScheme.onBackground,
        )

        when {
            // "Everything" is too big to list; it is a place to ask.
            group.kind == GroupKind.ALL -> AskPanel(viewModel, group, Modifier.weight(1f))
            compact -> if (askOpen) AskPanel(viewModel, group, Modifier.weight(1f)) else Lines(viewModel, Modifier.weight(1f))
            else -> {
                Lines(viewModel, Modifier.weight(0.58f))
                HorizontalDivider()
                AskPanel(viewModel, group, Modifier.weight(0.42f))
            }
        }
    }

    if (exporting) {
        GroupExportDialog(viewModel, group) { exporting = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Lines(viewModel: RecorderViewModel, modifier: Modifier) {
    val compact = LocalCompact.current
    val lines by viewModel.groupLines.collectAsState()
    val mode by viewModel.textMode.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val group by viewModel.openGroup.collectAsState()
    val state = rememberSharedListState("group:${group?.id}")

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextMode.entries.forEach { entry ->
                FilterChip(selected = mode == entry, onClick = { viewModel.setTextMode(entry) }, label = { Text(entry.label) })
            }
            if (selection.isNotEmpty()) {
                TextButton(onClick = viewModel::clearSelection) { Text("${selection.size} selected · clear") }
            }
        }
        // The summary is its own view of the group, not a filter over the lines, so it comes
        // before the transcript rendering rather than inside it.
        if (mode == TextMode.SUMMARY) {
            group?.let { open ->
                Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                    SummaryList(viewModel, open)
                }
            }
            return@Column
        }

        CorrectionSummary(lines)

        if (lines.isEmpty()) {
            EmptyState("Nothing in this group yet.")
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sideBySide = mode == TextMode.BOTH && maxWidth >= 560.dp
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(lines, key = { it.segment.id }) { line ->
                    val selected = line.segment.id in selection
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .combinedClickable(
                                onClick = { if (selection.isNotEmpty()) viewModel.toggleSelected(line.segment.id) },
                                onLongClick = { viewModel.toggleSelected(line.segment.id) },
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            CLOCK.format(Date(line.segment.startTs)) + if (line.changed) "  · corrected" else "",
                            color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
                            fontSize = 11.sp,
                        )
                        LineText(line, mode, sideBySide)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineText(line: LineView, mode: TextMode, sideBySide: Boolean) {
    val compact = LocalCompact.current
    val main = if (compact) Color.White else MaterialTheme.colorScheme.onBackground
    val faded = if (compact) CoverColors.faint else MaterialTheme.colorScheme.outline
    when (mode) {
        TextMode.ORIGINAL -> Text(line.original, color = main, fontSize = 16.sp)
        TextMode.CORRECTED -> Text(line.corrected, color = main, fontSize = 16.sp)
        TextMode.BOTH -> if (sideBySide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(line.original, color = faded, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    if (line.changed) line.corrected else "—",
                    color = main,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column {
                Text(line.corrected, color = main, fontSize = 16.sp)
                if (line.changed) {
                    Text("was: ${line.original}", color = faded, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

/** Which passes produced the corrected text in view — shown rather than implied. */
@Composable
private fun CorrectionSummary(lines: List<LineView>) {
    if (lines.isEmpty()) return
    val compact = LocalCompact.current
    val corrections = lines.mapNotNull { it.correction }
    val changed = lines.count { it.changed }
    val text = if (corrections.isEmpty()) {
        "Not corrected yet — showing the original."
    } else {
        val by = corrections.groupingBy { "${CorrectionPass.label(it.pass)} by ${it.engine}" }.eachCount()
            .entries.sortedByDescending { it.value }.joinToString("; ") { it.key }
        "${corrections.size} of ${lines.size} lines checked, $changed changed · $by"
    }
    Text(
        text,
        color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- ask, scoped to the group

@Composable
fun AskPanel(viewModel: RecorderViewModel, group: GroupRef, modifier: Modifier) {
    val compact = LocalCompact.current
    val conversations by viewModel.conversations.collectAsState()
    val turns = conversations[group.id].orEmpty()
    val drafts by AppUiState.drafts.collectAsState()
    val input = drafts[group.id].orEmpty()
    val state = rememberSharedListState("ask:${group.id}")
    val busy = turns.lastOrNull()?.pending == true

    androidx.compose.runtime.LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) state.animateScrollToItem(turns.lastIndex)
    }

    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        if (group.kind == GroupKind.ALL) "Ask anything about everything recorded." else
                            "Ask about just this group, or tap an action below.",
                        color = if (compact) CoverColors.faint else MaterialTheme.colorScheme.outline,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            items(turns.size) { index -> TurnView(viewModel, turns[index]) }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (group.kind != GroupKind.ALL) {
                ActionChip("Summarise", !busy) { viewModel.summarize(group) }
                ActionChip("Action items", !busy) { viewModel.actionItems(group) }
                ActionChip("Draft follow-up", !busy) {
                    viewModel.draftFollowUp(group, input)
                    AppUiState.setDraft(group.id, "")
                }
                ActionChip("Re-correct", !busy) { viewModel.recorrect(group) }
            }
            ActionChip("Find mentions", !busy && input.isNotBlank()) {
                viewModel.find(group, input)
                AppUiState.setDraft(group.id, "")
            }
            ActionChip("What can it do?", true) { AppUiState.showCapabilities.value = true }
            if (turns.isNotEmpty()) ActionChip("Clear", !busy) { viewModel.clearConversation(group) }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = input,
                onValueChange = { AppUiState.setDraft(group.id, it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (compact) "Ask…" else "Ask a question, or type a topic / a name") },
                singleLine = true,
            )
            androidx.compose.material3.Button(
                onClick = {
                    viewModel.ask(group, input)
                    AppUiState.setDraft(group.id, "")
                },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.padding(start = 6.dp),
            ) { Text("Ask") }
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun TurnView(viewModel: RecorderViewModel, turn: ChatTurn) {
    val compact = LocalCompact.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            turn.question,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
            color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
        )
        Text(
            if (turn.pending) "Working… (on this phone this can take a minute)" else turn.answer,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (compact) Color.White else MaterialTheme.colorScheme.onBackground,
        )
        if (!turn.pending && turn.answer.isNotBlank()) {
            Row {
                TextButton(onClick = { viewModel.copy(turn.answer) }) { Text("Copy") }
                if (turn.isDraft) TextButton(onClick = { viewModel.shareText(turn.answer) }) { Text("Share…") }
                if (turn.sourceIds.isNotEmpty()) {
                    TextButton(onClick = { viewModel.flagSources(turn) }) { Text("Flag these lines") }
                }
            }
        }
    }
}
