package com.recorder.core.llm

import com.recorder.core.storage.FtsQuery
import com.recorder.core.storage.TranscriptDao
import com.recorder.core.storage.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Retrieval over the transcript database. FTS rather than embeddings on purpose: it costs
 * nothing at write time, survives a model swap, and answers "what did I say about the
 * supplier deal" well enough on a corpus this size.
 */
class TranscriptRag(private val transcripts: TranscriptDao) {

    suspend fun retrieve(question: String, limit: Int = 24): List<TranscriptSegment> {
        val match = FtsQuery.sanitize(question)
        if (match.isBlank()) return emptyList()
        return transcripts.search(match, limit)
    }

    fun buildContext(segments: List<TranscriptSegment>): String =
        segments.sortedBy { it.startTs }.joinToString("\n") { segment ->
            "[${TIMESTAMP.format(Date(segment.startTs))}] ${segment.text}"
        }

    private companion object {
        val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}

/**
 * Question answering over your own transcripts. Runs against whichever provider it is
 * handed — the cover screen passes the on-device model so answers work with the radio off.
 */
class TranscriptAssistant(
    private val provider: LlmProvider,
    private val rag: TranscriptRag,
) {

    suspend fun ask(question: String): AssistantAnswer {
        val sources = rag.retrieve(question)
        if (sources.isEmpty()) {
            return AssistantAnswer("Nothing in your transcripts matches that yet.", emptyList())
        }

        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(
                    Role.USER,
                    buildString {
                        append("Transcript excerpts:\n")
                        append(rag.buildContext(sources))
                        append("\n\nQuestion: ")
                        append(question)
                    },
                ),
            ),
        )

        val text = when {
            response.isError -> response.error ?: "The assistant is unavailable."
            response.text.isBlank() -> "No answer came back."
            else -> response.text
        }
        return AssistantAnswer(text, sources)
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You answer questions using only the transcript excerpts provided. They are " +
                "recordings of the user's own conversations. Be brief and concrete. Quote " +
                "the relevant line when it helps. If the excerpts do not contain the answer, " +
                "say so plainly instead of guessing."
    }
}

data class AssistantAnswer(
    val text: String,
    val sources: List<TranscriptSegment>,
)
