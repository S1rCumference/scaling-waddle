package com.recorder.core.llm

import com.recorder.core.storage.Folder
import com.recorder.core.storage.TranscriptSegment

/**
 * First-pass folder assignment. Runs on the local small model when one is loaded, and on a
 * keyword heuristic when it is not, so segments still land somewhere sensible on a phone
 * with no model installed. The Phase 4 heavy tier can reassign anything this gets wrong.
 */
class FolderClassifier(private val provider: LlmProvider) {

    suspend fun classify(segment: TranscriptSegment, folders: List<Folder>): String {
        val names = folders.map { it.name }
        heuristicFolder(segment.text)?.let { return it }

        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(
                    Role.USER,
                    buildString {
                        append("Existing folders: ")
                        append(if (names.isEmpty()) "(none yet)" else names.joinToString(", "))
                        append("\n\nTranscript: ")
                        append(segment.text.take(1_200))
                        append("\n\nFolder name:")
                    },
                ),
            ),
        )
        if (response.isError) return DEFAULT_FOLDER

        val answer = response.text.lineSequence().firstOrNull().orEmpty()
            .trim()
            .trim('"', '.', '*', '-', ' ')
            .take(40)

        if (answer.isBlank()) return DEFAULT_FOLDER
        // Prefer an existing folder when the model effectively named one, so folder lists
        // don't fragment into near-duplicates.
        return names.firstOrNull { it.equals(answer, ignoreCase = true) } ?: answer
    }

    companion object {

        /**
         * Pure string matching, safe to run on the recording hot path: no model, no
         * allocation beyond a lowercase copy. Returns null when nothing obvious matches, and
         * the batch job sorts those out later with the model.
         */
        fun heuristicFolder(text: String): String? {
            val lower = text.lowercase()
            return HEURISTICS.entries.firstOrNull { (_, cues) -> cues.any { it in lower } }?.key
        }

        const val DEFAULT_FOLDER = "Unsorted"

        private const val SYSTEM_PROMPT =
            "You sort transcript snippets into folders. Reply with a single short folder " +
                "name and nothing else. Reuse one of the existing folders whenever it fits; " +
                "only invent a new name when none of them do."

        private val HEURISTICS = linkedMapOf(
            "Business Ideas" to listOf("business idea", "startup idea", "we could sell", "new product"),
            "Follow Ups" to listOf("follow up", "remind me", "get back to", "chase up"),
            "Meetings" to listOf("meeting", "call with", "standup", "agenda"),
            "Suppliers" to listOf("supplier", "vendor", "invoice", "purchase order", "quote"),
        )
    }
}
