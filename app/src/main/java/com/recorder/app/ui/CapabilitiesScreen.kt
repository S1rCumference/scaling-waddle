package com.recorder.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/** One thing the AI in this app can actually do. */
data class Capability(
    val name: String,
    val how: String,
    val example: String,
    /** Needs a cloud provider set up and the heavy tier switched on. */
    val needsCloud: Boolean = false,
    /** Works without any AI model at all. */
    val noModel: Boolean = false,
)

/**
 * The plain-language list, in one place so the screen and the code cannot drift apart:
 * every entry is wired to a real button (see [AskPanel] and Settings), and nothing is
 * listed that is not.
 */
val CAPABILITIES = listOf(
    Capability(
        "Answer a question about one hour or one day",
        "Logs → open an hour or a day → type in the Ask box → Ask.",
        "\"What did we decide about the delivery date?\"",
    ),
    Capability(
        "Answer a question about everything ever recorded",
        "Logs → Ask about everything → type the question.",
        "\"When did I last talk about the van insurance?\"",
    ),
    Capability(
        "Summarise an hour or a day",
        "Open the hour or day → Summarise.",
        "A handful of bullet points: who, what was decided, numbers mentioned.",
    ),
    Capability(
        "List action items and follow-ups",
        "Open the hour or day → Action items.",
        "\"- Call the supplier on Friday about the invoice\"",
    ),
    Capability(
        "Find everything said about a topic or a name",
        "Type the word in the Ask box → Find mentions. Every matching line with its time.",
        "Type \"Dave\" → every line that mentions Dave.",
        noModel = true,
    ),
    Capability(
        "Draft a follow-up message",
        "Open the conversation → optionally type who it is for → Draft follow-up. Then Copy or Share…",
        "A short recap of what was agreed and the next steps, ready to paste into a text or email.",
    ),
    Capability(
        "Fix misheard words (correction)",
        "Happens by itself every few minutes, and again overnight for the whole day. " +
            "To redo one hour or day now: open it → Re-correct.",
        "\"go through my contacts\" becomes \"go through my content\". The original is always kept.",
    ),
    Capability(
        "Flag lines from an answer",
        "Under any answer → Flag these lines. They appear in Flags, labelled with your question.",
        "Ask \"what did the customer complain about?\" → flag the lines it used.",
    ),
    Capability(
        "Use a cloud AI for answers or corrections",
        "Settings → Cloud AI → add a provider and key, switch on \"send text to the cloud\", then " +
            "pick Cloud under Models. Only text is sent, never audio.",
        "Claude or ChatGPT summarising a long day faster than the phone can.",
        needsCloud = true,
    ),
)

/** What it cannot do, said plainly so nobody waits for it. */
val LIMITS = listOf(
    "It never sends anything by itself. Drafts are shown for you to copy or share; email drafts from connectors wait in Flags for Approve.",
    "It cannot hear the audio again — it only reads the text. Recordings are not kept as sound.",
    "On the phone it is slow: a day's summary can take a few minutes. It keeps working if you fold the phone.",
    "It only knows what was said near the phone and transcribed.",
)

@Composable
fun CapabilitiesDialog(viewModel: RecorderViewModel) {
    val open by AppUiState.showCapabilities.collectAsState()
    if (!open) return
    AlertDialog(
        onDismissRequest = { AppUiState.showCapabilities.value = false },
        title = { Text("What the AI can do") },
        text = { CapabilitiesList(Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { AppUiState.showCapabilities.value = false }) { Text("Close") } },
    )
}

@Composable
fun CapabilitiesList(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Everything below works on this phone with no internet unless it says \"needs cloud\".",
            style = MaterialTheme.typography.bodySmall,
        )
        CAPABILITIES.forEach { item ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                item.name + when {
                    item.needsCloud -> "  · needs cloud"
                    item.noModel -> "  · no AI needed"
                    else -> ""
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text("How: ${item.how}", style = MaterialTheme.typography.bodySmall)
            Text("Example: ${item.example}", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("What it won't do", style = MaterialTheme.typography.titleSmall)
        LIMITS.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}
