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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.recorder.core.audio.SpeakerChange
import com.recorder.core.audio.VoicePrint
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



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
    var searching by remember { mutableStateOf(false) }
    val query by viewModel.logQuery.collectAsState()
    val matches by viewModel.logMatches.collectAsState()
    // One index for every visible row's name, rather than a query per row per scroll.
    val summaries by viewModel.summaries.collectAsState()
    val state = rememberSharedListState("logs")
    var pendingDelete by remember { mutableStateOf<GroupRef?>(null) }

    fun topicOf(group: GroupRef): String? =
        summaries[viewModel.spanKey(group.fromTs, group.toTs)]?.title

    pendingDelete?.let { group ->
        ConfirmGroupDelete(
            viewModel = viewModel,
            group = group,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteGroup(group)
            },
            onDismiss = { pendingDelete = null },
        )
    }

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
                    TextButton(onClick = { viewModel.openExport() }) { Text("Export") }
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
            // Said rather than left to be discovered. A long-press that deletes an hour is not
            // a gesture anyone tries on the off-chance.
            item { Hint("Long-press any group to delete that whole stretch.") }
            items(hours, key = { "h${it.bucket}" }) { hour ->
                val ref = GroupRef.hour(hour.firstTs)
                GroupRow(
                    ref.title(),
                    "${hour.count} lines",
                    topic = topicOf(ref),
                    onDelete = { pendingDelete = ref },
                ) { viewModel.openGroup(ref) }
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
                        topic = topicOf(GroupRef.month(month.monthKey)),
                        onDelete = { pendingDelete = GroupRef.month(month.monthKey) },
                    ) {
                        openMonth = if (expanded) null else month.monthKey
                        viewModel.openDay(null)
                    }
                }
                if (!expanded) return@forEach

                month.days.forEach { day ->
                    val dayOpen = openDay == day.dayKey
                    item(key = "d${day.dayKey}") {
                        val span = "${Clocks.shortTime(day.firstTs)}–" +
                            Clocks.shortTime(day.lastTs)
                        GroupRow(
                            title = GroupRef.day(day.dayKey).title(),
                            detail = "${day.count} lines · $span",
                            indent = 1,
                            chevron = if (dayOpen) "−" else "+",
                            topic = topicOf(GroupRef.day(day.dayKey)),
                            onDelete = { pendingDelete = GroupRef.day(day.dayKey) },
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
                        GroupRow(
                            ref.title(),
                            "${hour.count} lines",
                            indent = 2,
                            topic = topicOf(ref),
                            onDelete = { pendingDelete = ref },
                        ) { viewModel.openGroup(ref) }
                    }
                    // Whole day at once, for summarising or exporting it.
                    item(key = "da${day.dayKey}") {
                        GroupRow(
                            "The whole day",
                            "${day.count} lines",
                            indent = 2,
                            onDelete = { pendingDelete = GroupRef.day(day.dayKey) },
                        ) { viewModel.openGroup(GroupRef.day(day.dayKey)) }
                    }
                }

                // The month as one group: the widest zoom-out, where the days' topics roll
                // into what the month was about.
                item(key = "ma${month.monthKey}") {
                    GroupRow(
                        "The whole month",
                        "${month.lineCount} lines",
                        indent = 1,
                        onDelete = { pendingDelete = GroupRef.month(month.monthKey) },
                    ) { viewModel.openGroup(GroupRef.month(month.monthKey)) }
                }
            }
        }

        if (hours.isEmpty() && months.isEmpty()) {
            item { EmptyState("No logs yet. They group themselves as you talk.") }
        }
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
    segment: TranscriptSegment,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    val stamp = if (DayKey.of(segment.startTs) == DayKey.today()) {
        Clocks.shortTime(segment.startTs)
    } else {
        Clocks.dayAndTime(segment.startTs)
    }

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

/**
 * Whether two consecutive lines probably came from different people.
 *
 * Pure and one call deep on purpose: replacing this with real diarization is replacing this
 * function. Segments recorded before the app measured anything have a zero voice print and
 * never produce a marker, which is the right answer for them.
 */
private fun speakerChanged(previous: TranscriptSegment, current: TranscriptSegment): Boolean =
    SpeakerChange.between(
        previous = VoicePrint(previous.levelDb, previous.zeroCrossingRate),
        current = VoicePrint(current.levelDb, current.zeroCrossingRate),
        gapMs = current.startTs - previous.endTs,
    )

@Composable
private fun SpeakerChangeMarker() {
    val compact = LocalCompact.current
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  someone else  ",
            color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
            fontSize = 11.sp,
        )
        HorizontalDivider(Modifier.weight(1f))
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



/** "September 2026" from a yyyyMM key. */
private fun monthTitle(monthKey: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.YEAR, monthKey / 100)
        set(java.util.Calendar.MONTH, monthKey % 100 - 1)
        set(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    return SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(calendar.time)
}

/**
 * One calendar row: the span, how much is in it, and what it was about.
 *
 * [topic] is the name the AI gave this stretch of time, and it is the whole point of the
 * grouping — a month of "14:00–15:00 · 41 lines" is unreadable, a month of named hours is a
 * list of topics you can scan. It is a second line rather than squeezed onto the first,
 * because the four-inch screen has room for two short lines and not for one long one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    title: String,
    detail: String,
    indent: Int = 0,
    chevron: String? = null,
    topic: String? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    val pad = (indent * if (compact) 10 else 16).dp
    val named = topic?.takeIf { it.isNotBlank() }
    // Long-press a row to throw that whole stretch away. The same gesture as selecting a line,
    // on the thing that represents a stretch of time, which is the unit people want gone.
    val press = if (onDelete == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onDelete)
    }
    if (compact) {
        Column(
            Modifier.fillMaxWidth().then(press)
                .padding(start = pad, top = 8.dp, bottom = 8.dp),
        ) {
            Row {
                Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(detail, color = CoverColors.dim, fontSize = 12.sp)
                chevron?.let { Text(" $it", color = CoverColors.dim, fontSize = 14.sp) }
            }
            named?.let {
                Text(it, color = CoverColors.live, fontSize = 13.sp, maxLines = 2)
            }
        }
    } else {
        Card(
            Modifier.fillMaxWidth().padding(start = pad, top = 3.dp, bottom = 3.dp).then(press),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(detail, style = MaterialTheme.typography.labelMedium)
                    chevron?.let {
                        Text("  $it", style = MaterialTheme.typography.titleSmall)
                    }
                }
                named?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- one group

@Composable
private fun GroupDetail(viewModel: RecorderViewModel, group: GroupRef) {
    val compact = LocalCompact.current
    var confirmGroupDelete by remember { mutableStateOf(false) }

    if (confirmGroupDelete) {
        ConfirmGroupDelete(
            viewModel = viewModel,
            group = group,
            onConfirm = {
                confirmGroupDelete = false
                viewModel.deleteGroup(group)
            },
            onDismiss = { confirmGroupDelete = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { viewModel.openGroup(null) }) { Text("‹ All logs") }
            TextButton(onClick = { viewModel.openExport(group) }) { Text("Export") }
            TextButton(onClick = { viewModel.recorrect(group) }) { Text("Correct") }
            if (viewModel.canSummarise(group)) {
                TextButton(onClick = { viewModel.summarise(group) }) { Text("Summarise") }
            }
            TextButton(onClick = { confirmGroupDelete = true }) {
                Text("Delete all", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            group.title(),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = if (compact) Color.White else MaterialTheme.colorScheme.onBackground,
        )
        GroupTopic(viewModel, group)

        Lines(viewModel, Modifier.weight(1f))
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
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && selection.isNotEmpty()) {
        ConfirmDelete(
            count = selection.size,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelected()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextMode.entries.forEach { entry ->
                FilterChip(selected = mode == entry, onClick = { viewModel.setTextMode(entry) }, label = { Text(entry.label) })
            }
            if (selection.isNotEmpty()) {
                // A long-press selection opens the same Export screen, pre-filled with it.
                TextButton(onClick = { viewModel.openExport(onlyIds = selection) }) {
                    Text("Export ${selection.size}")
                }
                // Bulk delete: the reason for selecting several lines at once is usually that
                // a stretch of the day should not have been recorded. One confirmation,
                // because this is the one destructive thing in the app and a mis-tap here
                // costs lines rather than a screen.
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete ${selection.size}", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = viewModel::clearSelection) { Text("Clear") }
            }
        }
        CorrectionSummary(lines)

        if (lines.isEmpty()) {
            EmptyState("Nothing in this group yet.")
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sideBySide = mode == TextMode.BOTH && maxWidth >= 560.dp
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                itemsIndexed(lines, key = { _, it -> it.segment.id }) { index, line ->
                    val selected = line.segment.id in selection
                    // A generic divider, never a name: nothing here knows who anybody is.
                    if (index > 0 && speakerChanged(lines[index - 1].segment, line.segment)) {
                        SpeakerChangeMarker()
                    }
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
                            Clocks.time(line.segment.startTs) + if (line.changed) "  · corrected" else "",
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
        // Summary never reaches here — it is a different view of the group, handled before
        // the lines are rendered at all — but it is spelled out rather than swept into an
        // else, so the next mode added has to come back and decide.
        TextMode.CORRECTED,
        -> Text(line.corrected, color = main, fontSize = 16.sp)
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


/**
 * The one confirmation in the app.
 *
 * Deleting is real — the lines, their corrections and their flags all go — so it asks once,
 * and says how many. Undo still exists afterwards, from the status line, but only while the
 * app is running: this is the sentence that has to be right.
 */
@Composable
private fun ConfirmDelete(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this line?" else "Delete $count lines?") },
        text = {
            Text(
                "They are removed for good, along with any corrections and flags. " +
                    "Undo is offered for a moment afterwards.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}


/**
 * What this group was about: the name, and a paragraph under it.
 *
 * The date is already the heading above, so this is the other two thirds of what was asked
 * for — the name and the context. Collapsed to the name after the first read, because on the
 * cover screen the paragraph is most of the screen and the name is what you came for.
 */
@Composable
private fun GroupTopic(viewModel: RecorderViewModel, group: GroupRef) {
    val compact = LocalCompact.current
    val summary by viewModel.openGroupSummary.collectAsState()
    val progress by viewModel.summaryProgress.collectAsState()
    var expanded by remember(group.id) { mutableStateOf(!compact) }
    val current = summary

    if (current == null) {
        // Said rather than implied. A group with no summary and no explanation reads like a
        // feature that is broken rather than one that has not run yet.
        if (viewModel.canSummarise(group)) {
            Text(
                progress ?: "No summary yet — the overnight pass writes one, or press Summarise.",
                color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        return
    }

    val body = current.body
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = body.isNotBlank()) { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        if (current.title.isNotBlank()) {
            Text(
                current.title,
                color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            )
        }
        if (body.isNotBlank()) {
            Text(
                body,
                color = if (compact) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (compact) 13.sp else 15.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )
        }
        progress?.let {
            Text(
                it,
                color = if (compact) CoverColors.dim else MaterialTheme.colorScheme.outline,
                fontSize = 11.sp,
            )
        }
    }
}


/** A one-line note in the list, for a gesture that would otherwise never be found. */
@Composable
private fun Hint(text: String) {
    val compact = LocalCompact.current
    Text(
        text,
        color = if (compact) CoverColors.faint else MaterialTheme.colorScheme.outline,
        fontSize = if (compact) 11.sp else 12.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * The confirmation for throwing a whole stretch of recording away.
 *
 * It counts the lines first and says the number. "Delete this hour?" is not enough information
 * to answer: an hour with four lines in it and an hour with four hundred are different decisions,
 * and the second one is most of an afternoon.
 */
@Composable
private fun ConfirmGroupDelete(
    viewModel: RecorderViewModel,
    group: GroupRef,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count by androidx.compose.runtime.produceState<Int?>(null, group.id) {
        value = viewModel.groupSize(group)
    }
    val lines = count

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${group.title()}?") },
        text = {
            Text(
                when (lines) {
                    null -> "Counting what is in it…"
                    0 -> "There is nothing recorded in this group."
                    1 -> "One line, and any correction, flag or summary belonging to it, removed " +
                        "for good. Undo is offered for a moment afterwards."
                    else -> "All $lines lines, and any corrections, flags and summaries " +
                        "belonging to them, removed for good. Undo is offered for a moment " +
                        "afterwards."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = lines != null && lines > 0) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}
