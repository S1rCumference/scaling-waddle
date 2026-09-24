package com.recorder.core.llm

import com.recorder.core.storage.TranscriptSegment

/**
 * One slice of transcript to correct. [before] and [after] are there so the model can use
 * the conversation around a line to decide what was actually said; only [targets] are
 * rewritten. [vocabulary] is the day's recurring names and terms, used by the end-of-day
 * pass so the whole day informs every window without the whole day fitting in one prompt.
 */
data class CorrectionWindow(
    val before: List<String>,
    val targets: List<TranscriptSegment>,
    val after: List<String>,
    val vocabulary: List<String> = emptyList(),
)

/**
 * Prompt building and answer parsing for the correction pass. Pure, so it is unit tested
 * without a model: the parsing is where a small model's sloppiness has to be absorbed.
 */
object CorrectionPrompt {

    /** How the draft pass flags a word it guessed at. */
    const val OPEN_MARK = "<<"
    const val CLOSE_MARK = ">>"

    /**
     * The draft pass. Fast, and told to admit what it does not know rather than guess.
     *
     * Marking is the whole point of splitting the work: whatever comes back marked is the
     * only thing the second pass has to look at, so the expensive careful reading happens on
     * a handful of phrases instead of the whole transcript.
     */
    const val SYSTEM_DRAFT =
        "You fix speech-recognition errors in transcripts of the user's own conversations. " +
            "Some words were misheard as similar-sounding ones. Use the surrounding conversation " +
            "to decide what was really said, and fix only those words. Example: in a conversation " +
            "about videos someone posted, \"go through my contacts\" should be \"go through my " +
            "content\". Never rephrase, summarise, shorten, translate or add anything. Keep filler " +
            "words and the speaker's own grammar. If a line is already right, repeat it exactly. " +
            "Wrap any word or phrase you are not sure about in $OPEN_MARK and $CLOSE_MARK, like " +
            "$OPEN_MARK this $CLOSE_MARK. Mark generously: a marked guess is useful, a confident " +
            "wrong answer is not. Answer immediately without reasoning first."

    /** The repair pass. Sees only what the draft marked, with the lines either side of it. */
    const val SYSTEM_REPAIR =
        "You are resolving the uncertain parts of an almost-finished transcript. Each line " +
            "contains one or more phrases wrapped in $OPEN_MARK and $CLOSE_MARK that the first " +
            "pass was unsure about. Use the surrounding conversation to decide what was really " +
            "said and replace each marked phrase with your best answer, removing the marks. If " +
            "you still cannot tell, leave that phrase marked exactly as it is. Change nothing " +
            "outside the marks. Answer immediately without reasoning first."

    fun build(window: CorrectionWindow): String = buildString {
        if (window.vocabulary.isNotEmpty()) {
            append("Names and terms that come up in this person's day: ")
            append(window.vocabulary.joinToString(", "))
            append("\n\n")
        }
        if (window.before.isNotEmpty()) {
            append("Earlier in the conversation (context only, do not output):\n")
            window.before.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Lines to correct:\n")
        window.targets.forEachIndexed { i, segment ->
            append(i + 1).append("| ").append(segment.text.replace('\n', ' ')).append('\n')
        }
        if (window.after.isNotEmpty()) {
            append("\nLater in the conversation (context only, do not output):\n")
            window.after.forEach { append("- ").append(it).append('\n') }
        }
        append("\nReply with exactly one line for each numbered line, in the same form: ")
        append("the number, a | and the corrected text. Output nothing else.")
    }

    /** The repair prompt: only the lines the draft left marked, with their neighbours. */
    fun buildRepair(marked: List<String>, context: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("The conversation around these lines (context only, do not output):\n")
            context.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Lines with uncertain parts:\n")
        marked.forEachIndexed { i, text ->
            append(i + 1).append("| ").append(text.replace('\n', ' ')).append('\n')
        }
        append("\nReply with exactly one line for each numbered line, in the same form: ")
        append("the number, a | and the line with each marked phrase resolved. Output nothing else.")
    }

    private val markPattern = Regex(
        Regex.escape(OPEN_MARK) + "(.*?)" + Regex.escape(CLOSE_MARK),
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The phrases the model admitted it guessed at. */
    fun marks(text: String): List<String> =
        markPattern.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    fun hasMarks(text: String): Boolean = markPattern.containsMatchIn(text)

    /** The same line with the marks taken off, which is what gets stored and read. */
    fun stripMarks(text: String): String =
        markPattern.replace(text) { it.groupValues[1].trim() }
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private val numbered = Regex("""^\s*\**\s*(\d{1,3})\s*[|:.)\]]\s?(.*)$""")

    /**
     * Maps target index → text the model returned, for lines it actually returned. Lines it
     * skipped are absent. The first answer for a number wins; the model sometimes repeats
     * the list.
     */
    fun parse(output: String, targetCount: Int): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        output.lineSequence().forEach { line ->
            val match = numbered.find(line) ?: return@forEach
            val index = match.groupValues[1].toInt() - 1
            // Models decorate: "**3.** text", "3| \"text\"". Keep only the text.
            val text = match.groupValues[2].trim().trim('*', '"').trim()
            if (index in 0 until targetCount && index !in result && text.isNotEmpty()) {
                result[index] = text
            }
        }
        return result
    }

    /**
     * Guards the original against a model that paraphrased, summarised or invented instead
     * of correcting. A correction keeps roughly the same length and mostly the same words;
     * anything else is rejected and the line is kept as spoken.
     */
    fun plausible(original: String, corrected: String): Boolean {
        val o = words(original)
        val c = words(corrected)
        if (c.isEmpty()) return false
        if (o.size < 4) return kotlin.math.abs(c.size - o.size) <= 2
        val ratio = c.size.toDouble() / o.size
        if (ratio < 0.6 || ratio > 1.5) return false
        val originalSet = o.toSet()
        val kept = c.count { it in originalSet }.toDouble() / c.size
        return kept >= 0.5
    }

    /**
     * What to store per target, given the model's answer. Lines the model skipped *between*
     * lines it answered were looked at and left alone, so they count as unchanged. Lines
     * after the last one it answered were probably cut off by the token limit and are left
     * for the next batch rather than being marked done.
     */
    fun resolve(targets: List<TranscriptSegment>, parsed: Map<Int, String>): Map<Long, String> {
        if (parsed.isEmpty()) return emptyMap()
        val last = parsed.keys.max()
        val out = linkedMapOf<Long, String>()
        for (i in 0..last) {
            val original = targets[i].text
            val candidate = parsed[i]
            out[targets[i].id] = if (candidate != null && plausible(original, candidate)) candidate else original
        }
        return out
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }
}

/** The recurring names and terms in a day, cheaply, without a model. */
object DayVocabulary {

    private val STOP = setOf(
        "about", "after", "again", "also", "because", "before", "being", "could", "doing",
        "going", "gonna", "having", "just", "know", "like", "maybe", "might", "really",
        "right", "should", "something", "still", "that's", "their", "there", "these", "thing",
        "things", "think", "those", "through", "today", "want", "we're", "what's", "where",
        "which", "while", "would", "yeah", "you're", "actually", "basically", "everything",
        "people", "little", "okay", "there's", "they're", "about", "other", "never", "always",
    )

    fun extract(texts: List<String>, limit: Int = 40): List<String> {
        val counts = HashMap<String, Int>()
        val display = HashMap<String, String>()
        texts.forEach { text ->
            text.split(Regex("[^\\p{L}\\p{N}'-]+")).forEachIndexed { i, raw ->
                val word = raw.trim('\'', '-')
                if (word.length < 4) return@forEachIndexed
                val key = word.lowercase()
                if (key in STOP) return@forEachIndexed
                // Capitalised mid-sentence words are names; long repeated words are topics.
                val capitalised = i > 0 && word[0].isUpperCase()
                val weight = if (capitalised) 3 else 1
                counts[key] = (counts[key] ?: 0) + weight
                if (capitalised || key !in display) display[key] = word
            }
        }
        return counts.entries
            .filter { it.value >= 3 && it.key.length >= 5 || it.value >= 6 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { display[it.key] ?: it.key }
    }
}

/** One line after correction: what to store, and what the model would not commit to. */
data class CorrectedLine(
    val segmentId: Long,
    val text: String,
    /** Phrases the model marked and the repair pass could not resolve either. */
    val uncertain: List<String> = emptyList(),
)

/**
 * Runs one correction window through a provider as two short passes.
 *
 * One long pass was the problem: a 1.7B model asked to do careful work on twenty lines
 * reasons about all of them at once, and on a phone that is two minutes of saturated CPU for
 * a result nobody is waiting on. Splitting it means the first pass is quick and admits what
 * it guessed, and the second pass only ever sees those admissions — usually a couple of
 * phrases — so the careful expensive reading is spent where it changes the answer.
 *
 * The second pass is skipped entirely when the first marked nothing, which is the common case.
 */
class TranscriptCorrector(private val provider: LlmProvider) {

    suspend fun correct(window: CorrectionWindow): Result<List<CorrectedLine>> {
        if (window.targets.isEmpty()) return Result.success(emptyList())

        // --- pass 1: fast draft, marking anything it is unsure about ---------------------
        val draft = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, CorrectionPrompt.SYSTEM_DRAFT),
                ChatMessage(Role.USER, CorrectionPrompt.build(window)),
            ),
            TokenBudget.forLines(window.targets.size),
        )
        if (draft.isError) {
            return Result.failure(IllegalStateException(draft.error ?: "model unavailable"))
        }
        val drafted = CorrectionPrompt.resolve(
            window.targets,
            CorrectionPrompt.parse(draft.text, window.targets.size),
        )
        if (drafted.isEmpty()) {
            return Result.failure(IllegalStateException("the model's answer had no numbered lines"))
        }

        // --- pass 2: only the marked lines, if there are any -----------------------------
        val markedIds = drafted.filterValues { CorrectionPrompt.hasMarks(it) }.keys.toList()
        val repaired: Map<Long, String> = if (markedIds.isEmpty()) {
            emptyMap()
        } else {
            repair(markedIds.map { it to drafted.getValue(it) }, window)
        }

        return Result.success(
            drafted.map { (id, draftText) ->
                val finalText = repaired[id] ?: draftText
                CorrectedLine(
                    segmentId = id,
                    text = CorrectionPrompt.stripMarks(finalText),
                    uncertain = CorrectionPrompt.marks(finalText),
                )
            },
        )
    }

    private suspend fun repair(
        marked: List<Pair<Long, String>>,
        window: CorrectionWindow,
    ): Map<Long, String> {
        val texts = marked.map { it.second }
        val spans = texts.sumOf { CorrectionPrompt.marks(it).size }
        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, CorrectionPrompt.SYSTEM_REPAIR),
                ChatMessage(Role.USER, CorrectionPrompt.buildRepair(texts, window.before + window.after)),
            ),
            TokenBudget.forRepair(spans),
        )
        // A failed repair is not a failed correction: the draft still stands, marks and all.
        if (response.isError) return emptyMap()
        val parsed = CorrectionPrompt.parse(response.text, texts.size)
        return parsed.mapNotNull { (index, text) ->
            val (id, draftText) = marked.getOrNull(index) ?: return@mapNotNull null
            // The repair may only resolve marks, never rewrite the line.
            if (CorrectionPrompt.plausible(CorrectionPrompt.stripMarks(draftText), CorrectionPrompt.stripMarks(text))) {
                id to text
            } else {
                null
            }
        }.toMap()
    }
}
