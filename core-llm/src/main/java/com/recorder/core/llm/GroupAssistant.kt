package com.recorder.core.llm

import com.recorder.core.storage.FtsQuery
import com.recorder.core.storage.TranscriptDao
import com.recorder.core.storage.TranscriptSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A line of transcript as the assistant sees it: the corrected text when there is one. */
data class ScopedLine(val segment: TranscriptSegment, val text: String)

/**
 * The things the AI can do with one group of transcript — an hour, a day, a range. Every
 * action here is listed, with an example, on the "What the AI can do" screen; nothing is
 * listed there that is not implemented here.
 *
 * Long groups are handled by splitting into chunks that fit the local model's context,
 * answering per chunk, and combining. That is slow on a phone for a whole day, but honest:
 * nothing is silently dropped, and when a day is too long to cover in full the answer says
 * how much it covered.
 */
class GroupAssistant(
    private val provider: LlmProvider,
    private val transcripts: TranscriptDao,
    /** Characters of transcript per model call. Smaller for on-device models. */
    private val chunkChars: Int = LOCAL_CHUNK_CHARS,
    private val maxChunks: Int = LOCAL_MAX_CHUNKS,
) {

    suspend fun ask(question: String, lines: List<ScopedLine>, fromTs: Long, toTs: Long): AssistantAnswer {
        if (lines.isEmpty()) return AssistantAnswer("There is nothing in this group yet.", emptyList())

        val context = if (lines.charCount() <= chunkChars) {
            lines
        } else {
            // Too long to hand over whole: search inside the group, then fall back to its end.
            val byId = lines.associateBy { it.segment.id }
            val match = FtsQuery.sanitize(question)
            val hits = if (match.isBlank()) emptyList()
            else transcripts.searchInRange(match, fromTs, toTs, 40).mapNotNull { byId[it.id] }
            hits.ifEmpty { lines.takeLastWithin(chunkChars) }
        }

        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, ASK_SYSTEM),
                ChatMessage(Role.USER, "Transcript:\n${render(context)}\n\nQuestion: $question"),
            ),
        )
        return AssistantAnswer(response.textOr("No answer came back."), context.map { it.segment })
    }

    suspend fun summarize(lines: List<ScopedLine>): AssistantAnswer =
        mapReduce(
            lines,
            perChunk = "Summarise this part of the transcript in 3 to 6 short bullet points. " +
                "Keep names, numbers and decisions.",
            combine = "These are notes on consecutive parts of one stretch of the day. Merge them " +
                "into one summary of at most 8 bullet points, in time order.",
        )

    suspend fun actionItems(lines: List<ScopedLine>): AssistantAnswer =
        mapReduce(
            lines,
            perChunk = "List every action item, promise, reminder or follow-up mentioned in this " +
                "transcript, one per line starting with \"- \", with who and when if said. If there " +
                "are none, reply exactly: none",
            combine = "Merge these lists of action items into one list without duplicates, one per " +
                "line starting with \"- \". If all of them say none, reply exactly: No action items found.",
        )

    suspend fun draftFollowUp(lines: List<ScopedLine>, recipientHint: String): AssistantAnswer {
        val context = lines.takeLastWithin(chunkChars)
        val who = recipientHint.ifBlank { "the other person in this conversation" }
        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, ASK_SYSTEM),
                ChatMessage(
                    Role.USER,
                    "Transcript:\n${render(context)}\n\nDraft a short, friendly follow-up message to " +
                        "$who that recaps what was agreed and the next steps. Plain text, no subject line.",
                ),
            ),
        )
        return AssistantAnswer(response.textOr("Could not draft a message."), context.map { it.segment })
    }

    private suspend fun mapReduce(lines: List<ScopedLine>, perChunk: String, combine: String): AssistantAnswer {
        if (lines.isEmpty()) return AssistantAnswer("There is nothing in this group yet.", emptyList())
        val chunks = lines.chunkedByChars(chunkChars)
        val covered = chunks.evenlyPick(maxChunks)

        val notes = covered.map { chunk ->
            val response = provider.complete(
                listOf(
                    ChatMessage(Role.SYSTEM, ASK_SYSTEM),
                    ChatMessage(Role.USER, "Transcript:\n${render(chunk)}\n\n$perChunk"),
                ),
            )
            if (response.isError) return AssistantAnswer(response.textOr(""), emptyList())
            response.text
        }

        val text = if (notes.size == 1) {
            notes.single()
        } else {
            provider.complete(
                listOf(
                    ChatMessage(Role.SYSTEM, ASK_SYSTEM),
                    ChatMessage(Role.USER, notes.joinToString("\n\n---\n\n") + "\n\n" + combine),
                ),
            ).textOr(notes.joinToString("\n\n"))
        }

        val note = if (covered.size < chunks.size) {
            "\n\n(Covers ${covered.size} of ${chunks.size} parts of this group, spread across it — " +
                "it is too long to read in full on the phone. Narrow the range for the rest.)"
        } else {
            ""
        }
        return AssistantAnswer(text.trim() + note, covered.flatten().map { it.segment })
    }

    private fun LlmResponse.textOr(fallback: String): String = when {
        isError -> error ?: "The assistant is unavailable."
        text.isBlank() -> fallback
        else -> text.trim()
    }

    companion object {
        const val LOCAL_CHUNK_CHARS = 7_000
        const val LOCAL_MAX_CHUNKS = 8
        const val CLOUD_CHUNK_CHARS = 60_000
        const val CLOUD_MAX_CHUNKS = 6

        private const val ASK_SYSTEM =
            "You help someone with transcripts of their own conversations, recorded on their " +
                "phone. Use only the transcript given. Be brief and concrete. If the transcript " +
                "does not contain the answer, say so plainly instead of guessing."

        private val CLOCK = SimpleDateFormat("HH:mm", Locale.US)

        fun render(lines: List<ScopedLine>): String =
            lines.joinToString("\n") { "[${CLOCK.format(Date(it.segment.startTs))}] ${it.text}" }

        /** Lines with a keyword match, no model involved — "find everything about X". */
        fun find(topic: String, lines: List<ScopedLine>): List<ScopedLine> {
            val terms = topic.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length > 1 }
            if (terms.isEmpty()) return emptyList()
            return lines.filter { line -> val t = line.text.lowercase(); terms.any { it in t } }
        }
    }
}

internal fun List<ScopedLine>.charCount(): Int = sumOf { it.text.length + 10 }

internal fun List<ScopedLine>.takeLastWithin(chars: Int): List<ScopedLine> {
    var total = 0
    val out = ArrayList<ScopedLine>()
    for (line in asReversed()) {
        total += line.text.length + 10
        if (total > chars && out.isNotEmpty()) break
        out += line
    }
    return out.asReversed()
}

internal fun List<ScopedLine>.chunkedByChars(chars: Int): List<List<ScopedLine>> {
    val chunks = ArrayList<List<ScopedLine>>()
    var current = ArrayList<ScopedLine>()
    var size = 0
    for (line in this) {
        val cost = line.text.length + 10
        if (size + cost > chars && current.isNotEmpty()) {
            chunks += current
            current = ArrayList()
            size = 0
        }
        current += line
        size += cost
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

/** Up to [n] items spread evenly across the list, first and last included. */
internal fun <T> List<T>.evenlyPick(n: Int): List<T> {
    if (size <= n) return this
    if (n <= 1) return listOf(first())
    return (0 until n).map { this[it * (size - 1) / (n - 1)] }.distinct()
}
