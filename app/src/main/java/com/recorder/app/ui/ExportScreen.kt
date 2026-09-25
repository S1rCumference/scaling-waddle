package com.recorder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.recorder.app.export.ExportGrouping
import com.recorder.app.export.ExportRange
import com.recorder.app.export.TimeWindow
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.ExportDestinations

/**
 * One screen for every export, replacing the four-control dialog.
 *
 * The order is the order someone thinks in: which days, which part of the day, which lines,
 * then what the file should look like and where it goes. The match count and size sit above the
 * button and update as the filters change, because the failure this screen is built against is
 * exporting blind and finding out afterwards that the file was empty or enormous.
 */
@Composable
fun ExportScreen(viewModel: RecorderViewModel, onClose: () -> Unit) {
    val query by viewModel.exportQuery.collectAsState()
    val preview by viewModel.exportPreview.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("‹ Back") }
            TextButton(onClick = viewModel::resetExport) { Text("Reset") }
        }
        Text("Export", style = MaterialTheme.typography.titleMedium)

        if (query.isSelection) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${query.onlyIds.size} selected line(s)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only the lines you picked will be exported. The date range and the " +
                            "time window are ignored for a selection; keywords still apply.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = viewModel::clearExportSelection) { Text("Use a date range instead") }
                }
            }
        } else {
            Group("Date range") {
                ChipRow(ExportRange.all, query.range, ExportRange::label, viewModel::setExportRange)
                if (query.range == ExportRange.CUSTOM) {
                    Text(
                        "Both days are included. Written as yyyymmdd, so 20260925 is " +
                            "25 September 2026.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        DayField("From", query.customFromDay) { viewModel.setExportCustomFrom(it) }
                        DayField("To", query.customToDay) { viewModel.setExportCustomTo(it) }
                    }
                }
            }

            Group("Time of day") {
                val window = query.timeWindow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = window != null,
                        onCheckedChange = { on -> viewModel.setExportWindowEnabled(on) },
                    )
                    Text(
                        window?.describe() ?: "Any time of day",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (window != null) {
                    Row {
                        MinuteField("From", window.startMinute) { viewModel.setExportWindowStart(it) }
                        MinuteField("To", window.endMinute) { viewModel.setExportWindowEnd(it) }
                    }
                    Text(
                        if (window.crossesMidnight) {
                            "This window runs over midnight, so it takes the late evening of " +
                                "each day and the early hours of the next."
                        } else {
                            "Applied to every day in the range."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Group("Keywords") {
            OutlinedTextField(
                value = query.includeField,
                onValueChange = viewModel::setExportInclude,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Include lines containing") },
                placeholder = { Text("invoice, Marguerite") },
                singleLine = true,
            )
            if (query.keywords.include.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = query.keywords.includeAll,
                        onCheckedChange = viewModel::setExportIncludeAll,
                    )
                    Text(
                        if (query.keywords.includeAll) "All of these" else "Any of these",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            OutlinedTextField(
                value = query.excludeField,
                onValueChange = viewModel::setExportExclude,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Leave out lines containing") },
                singleLine = true,
            )
            Text(
                "Comma separated, case insensitive. A term anywhere in a line keeps or drops " +
                    "the whole line. Anything in the second field wins over the first.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Group("What to write") {
            ChipRow(
                ExportDefaults.contents,
                query.content,
                ExportDefaults::contentLabel,
                viewModel::setExportContent,
            )
            ChipRow(
                ExportDefaults.formats,
                query.format,
                ExportDefaults::formatLabel,
                viewModel::setExportFormat,
            )
            Text(
                when (query.format) {
                    ExportDefaults.FORMAT_CSV ->
                        "Columns: date, time, text, source, flagged. Times are 24-hour and " +
                            "dates are ISO, so a spreadsheet reads them without being told."

                    ExportDefaults.FORMAT_JSONL ->
                        "One JSON object per line: at, epochMs, text, source, flagged."

                    ExportDefaults.FORMAT_MARKDOWN ->
                        "Headings per day or hour, one bullet per line, times in front."

                    else -> "One line each, time in brackets."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (query.format == ExportDefaults.FORMAT_MARKDOWN || query.format == ExportDefaults.FORMAT_TEXT) {
                ChipRow(
                    ExportGrouping.all,
                    query.grouping,
                    ExportGrouping::label,
                    viewModel::setExportGrouping,
                )
            }
        }

        Group("Where it goes") {
            ChipRow(
                ExportDestinations.all,
                query.destination,
                ExportDestinations::label,
                viewModel::setExportDestination,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        val counted = preview
        Text(
            when {
                counted == null -> "Counting…"
                counted.lines == 0 -> "Nothing matches these filters."
                else -> "${counted.lines} line(s) · about ${counted.size}"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "These settings are remembered as the next export's defaults.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { viewModel.runExport(onDone = onClose) },
            enabled = (counted?.lines ?: 0) > 0,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(ExportDestinations.label(query.destination))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    content()
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** A day as yyyymmdd. Typed rather than picked: a text field cannot be half-dismissed. */
@Composable
private fun DayField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value <= 0) "" else value.toString(),
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(8).toIntOrNull() ?: 0) },
        modifier = Modifier.padding(end = 8.dp),
        label = { Text(label) },
        placeholder = { Text("20260925") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** A time as HH:mm, stored as minutes from midnight. */
@Composable
private fun MinuteField(label: String, minute: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = TimeWindow.clock(minute),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(4).padEnd(4, '0')
            val hours = digits.take(2).toInt().coerceIn(0, 23)
            val minutes = digits.drop(2).toInt().coerceIn(0, 59)
            onChange(hours * 60 + minutes)
        },
        modifier = Modifier.padding(end = 8.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
