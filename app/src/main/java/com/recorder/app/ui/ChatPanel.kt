package com.recorder.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Ask-your-own-transcripts chat. Runs entirely on the local model, which is why the same
 * composable serves both the full app and the cover screen — it works with no network.
 */
@Composable
fun ChatPanel(viewModel: RecorderViewModel, compact: Boolean) {
    val turns by viewModel.chat.collectAsState()
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 12.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(turns) { turn ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        turn.question,
                        style = if (compact) MaterialTheme.typography.labelMedium
                        else MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (turn.pending) "…" else turn.answer,
                        style = if (compact) MaterialTheme.typography.bodySmall
                        else MaterialTheme.typography.bodyMedium,
                        maxLines = if (compact) 8 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (compact) "Ask…" else "Ask about your transcripts") },
                singleLine = true,
            )
            Button(
                onClick = {
                    viewModel.ask(question)
                    question = ""
                },
                modifier = Modifier.padding(start = 6.dp),
            ) { Text("Ask") }
        }
    }
}
