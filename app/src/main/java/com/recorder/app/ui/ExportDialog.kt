package com.recorder.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.recorder.app.export.Exporter
import com.recorder.app.export.ExportTarget
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.ExportDefaults
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export for the open group — the whole group, or only the lines long-pressed in it. */
@Composable
fun GroupExportDialog(viewModel: RecorderViewModel, group: GroupRef, onDismiss: () -> Unit) {
    val selection by viewModel.selection.collectAsState()
    var onlySelected by remember { mutableStateOf(selection.isNotEmpty()) }
    ExportOptionsDialog(
        viewModel = viewModel,
        heading = "Export ${group.title()}",
        extra = {
            if (selection.isNotEmpty()) {
                Choice(
                    "Lines",
                    listOf("All in this group" to false, "Only the ${selection.size} selected" to true),
                    onlySelected,
                ) { onlySelected = it }
            } else {
                Text(
                    "Tip: long-press lines to export only those.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        onDismiss = onDismiss,
    ) { content, format, target ->
        val title = "Recorder — ${group.title()}" + if (onlySelected) " (selected lines)" else ""
        viewModel.export(title, group.fromTs, group.toTs, selection.takeIf { onlySelected }, content, format, target)
    }
}

/** Export any span of days, chosen from the days that have recordings. */
@Composable
fun RangeExportDialog(viewModel: RecorderViewModel, availableDays: List<Int>, onDismiss: () -> Unit) {
    val days = remember(availableDays) { availableDays.sorted() }
    if (days.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            text = { Text("There is nothing recorded to export yet.") },
        )
        return
    }
    var from by remember { mutableIntStateOf((days.size - 7).coerceAtLeast(0)) }
    var to by remember { mutableIntStateOf(days.lastIndex) }
    val label = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

    ExportOptionsDialog(
        viewModel = viewModel,
        heading = "Export a date range",
        extra = {
            Stepper("From", label.format(Date(DayKey.startOf(days[from]))), from > 0, from < to,
                onPrev = { from-- }, onNext = { from++ })
            Stepper("To", label.format(Date(DayKey.startOf(days[to]))), to > from, to < days.lastIndex,
                onPrev = { to-- }, onNext = { to++ })
        },
        onDismiss = onDismiss,
    ) { content, format, target ->
        val start = DayKey.startOf(days[from])
        val end = DayKey.endOf(days[to])
        val title = "Recorder — " + GroupRef(GroupKind.RANGE, start, end).title()
        viewModel.export(title, start, end, null, content, format, target)
    }
}

@Composable
private fun ExportOptionsDialog(
    viewModel: RecorderViewModel,
    heading: String,
    extra: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onExport: (content: String, format: String, target: ExportTarget) -> Unit,
) {
    val defaultContent by viewModel.exportContent.collectAsState()
    val defaultFormat by viewModel.exportFormat.collectAsState()
    var content by remember { mutableStateOf(defaultContent) }
    var format by remember { mutableStateOf(defaultFormat) }
    var target by remember { mutableStateOf(ExportTarget.SHARE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extra()
                Choice(
                    "Text",
                    listOf(
                        "Corrected" to ExportDefaults.CONTENT_CORRECTED,
                        "Original" to ExportDefaults.CONTENT_ORIGINAL,
                        "Both" to ExportDefaults.CONTENT_BOTH,
                    ),
                    content,
                ) { content = it }
                Choice(
                    "Format",
                    listOf("Markdown" to ExportDefaults.FORMAT_MARKDOWN, "Plain text" to ExportDefaults.FORMAT_TEXT),
                    format,
                ) { format = it }
                Choice(
                    "Send to",
                    listOf("Share…" to ExportTarget.SHARE, "Download/${Exporter.FOLDER}" to ExportTarget.DOWNLOADS),
                    target,
                ) { target = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onExport(content, format, target)
                onDismiss()
            }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, value) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 6.dp))
        TextButton(onClick = onPrev, enabled = canPrev) { Text("‹") }
        Text(value, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onNext, enabled = canNext) { Text("›") }
    }
}
