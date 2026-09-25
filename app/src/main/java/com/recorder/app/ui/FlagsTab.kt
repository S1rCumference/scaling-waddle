package com.recorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.TranscriptSegment


/**
 * Flags: every line a trigger phrase matched.
 *
 * The drafts-awaiting-approval section went with the connectors in 3.0. Nothing in this app
 * sends anything anywhere any more, so there is nothing left to approve.
 */
@Composable
fun FlagsTab(viewModel: RecorderViewModel) {
    val compact = LocalCompact.current
    val flags by viewModel.flagged.collectAsState()
    val segments by produceState(emptyMap<Long, TranscriptSegment>(), flags) {
        value = viewModel.segmentsByIds(flags.map { it.segmentId })
    }
    val state = rememberSharedListState("flags")

    if (flags.isEmpty()) {
        EmptyState("Nothing flagged yet. Trigger phrases are in Settings → Recording.")
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp), state = state) {
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
                    Text(Clocks.dayAndTime(flag.createdTs), color = CoverColors.dim, fontSize = 11.sp)
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
