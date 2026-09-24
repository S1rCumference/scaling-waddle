package com.recorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recorder.core.storage.PendingAction
import com.recorder.core.storage.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WHEN = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

/**
 * Flags: every trigger-phrase hit and every line flagged from a question. Drafts waiting for
 * approval sit at the top — nothing is ever sent without a tap here.
 */
@Composable
fun FlagsTab(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val flags by viewModel.flagged.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val segments by produceState(emptyMap<Long, TranscriptSegment>(), flags) {
        value = viewModel.segmentsByIds(flags.map { it.segmentId })
    }
    val state = rememberSharedListState("flags")

    if (flags.isEmpty() && drafts.isEmpty()) {
        EmptyState("Nothing flagged yet. Trigger phrases are in Settings; you can also flag lines from any answer in Logs.")
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp), state = state) {
        if (drafts.isNotEmpty()) {
            item {
                Text(
                    "Waiting for your approval",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            items(drafts, key = { "draft${it.id}" }) { draft -> DraftCard(draft, viewModel) }
        }
        items(flags, key = { "flag${it.id}" }) { flag ->
            val segment = segments[flag.segmentId]
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = segment != null) {
                        segment?.let { viewModel.openGroup(GroupRef.hour(it.startTs)) }
                    }
                    .padding(vertical = 6.dp),
            ) {
                Row {
                    Text(
                        "\"${flag.keyword}\"",
                        color = if (compact) CoverColors.live else MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(WHEN.format(Date(flag.createdTs)), color = CoverColors.dim, fontSize = 11.sp)
                }
                Text(
                    segment?.text ?: "(line no longer stored)",
                    color = if (compact) Color.White else MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                )
                TextButton(onClick = { viewModel.dismissFlag(flag.id) }) { Text("Dismiss") }
            }
        }
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
