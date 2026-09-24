package com.recorder.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.core.storage.SummaryItem

/**
 * What the AI understood, as a list a person will actually go through.
 *
 * The thing this replaces was a corrected transcript, which nobody proofreads — so its
 * mistakes survived forever and the correction pass never learned anything. A dozen short
 * statements, each of which can be marked wrong, fixed in place or sent on, is reviewable
 * in a minute, and every fix is kept and fed back into later passes.
 */
@Composable
fun SummaryList(viewModel: RecorderViewModel, group: GroupRef) {
    val compact = LocalCompact.current
    val items by viewModel.summaryItems.collectAsState()

    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "Nothing read back yet for this stretch.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (compact) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "The AI turns what was said into a short list of what it amounts to. It runs " +
                    "on its own while the phone is charging with the screen off.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { viewModel.summarise(group) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Read it back now")
            }
        }
        return
    }

    items.forEach { item -> SummaryRow(viewModel, item) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TextButton(onClick = { viewModel.summarise(group) }) { Text("Read it back again") }
    }
}

@Composable
private fun SummaryRow(viewModel: RecorderViewModel, item: SummaryItem) {
    val compact = LocalCompact.current
    val context = LocalContext.current
    var editing by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id, item.edited) { mutableStateOf(item.display) }

    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        viewModel.editItem(item, draft)
                        editing = false
                    }) { Text("Save") }
                    TextButton(onClick = {
                        draft = item.display
                        editing = false
                    }) { Text("Cancel") }
                }
                return@Column
            }

            Text(
                item.display,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    item.flaggedWrong -> MaterialTheme.colorScheme.error
                    compact -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (item.edited != null) {
                Text("Your wording", style = MaterialTheme.typography.labelSmall)
            }
            if (item.flaggedWrong) {
                Text("Marked wrong — the AI is told about this", style = MaterialTheme.typography.labelSmall)
            }
            // Only shown when there is something to warn about, so it means something when
            // it appears rather than being furniture on every row.
            item.uncertainPhrases.takeIf { it.isNotEmpty() }?.let { unsure ->
                Text(
                    "Unsure about: ${unsure.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.flagItem(item) }) {
                    Text(if (item.flaggedWrong) "Not wrong" else "Wrong", fontSize = 13.sp)
                }
                TextButton(onClick = { editing = true }) { Text("Edit", fontSize = 13.sp) }
                TextButton(onClick = { context.shareText(item.display) }) {
                    Text("Share", fontSize = 13.sp)
                }
            }
        }
    }
}

/** One item out to whatever the user picks. Nothing is sent anywhere without this tap. */
private fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
