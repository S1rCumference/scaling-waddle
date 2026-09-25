package com.recorder.core.llm

import com.recorder.core.storage.TranscriptSegment

/**
 * One slice of transcript to correct, with the day's recurring names and terms.
 *
 * There is no longer a `before`/`after` split. The old design asked the model to rewrite a
 * handful of target lines while reading the lines around them; the new one reads a whole
 * stretch at once and reports only the words it believes were misheard, so every line in the
 * window is both context and target.
 */
data class CorrectionWindow(
    val targets: List<TranscriptSegment>,
    val vocabulary: List<String> = emptyList(),
)

/**
 * One word or short phrase that speech recognition got wrong, and what was really said.
 *
 * This is the whole output of a correction pass now, and the reason the pass is usable at all.
 *
 * The old design had the model echo every line back with its corrections applied. On this
 * phone the model generates about 8 tokens a second, so echoing an hour of transcript — around
 * four thousand tokens — is eight and a half minutes of generation for a pass that usually
 * changes a dozen words. It never finished inside any honest ceiling, and when the ceiling cut
 * it off mid-answer the whole batch was thrown away.
 *
 * Reporting only substitutions makes the output proportional to the number of mistakes rather
 * than the length of the transcript: a dozen of these is under fifty tokens, or about six
 * seconds. It also matches how recognition actually fails. A name or a piece of jargon is
 * misheard the same way every time it is said, so one substitution fixes every occurrence
 * instead of the model having to get the same line right once per batch.
 */
data class Mishearing(val wrong: String, val right: String) {

    /** Whole-word, case-insensitive. Built once per fix rather than per line. */
    internal val pattern: Regex by lazy {
        // \b does not work at a boundary that is not a word character, and transcript text is
        // full of those, so the edges are asserted by hand.
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wrong) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    fun appliesTo(text: String): Boolean = pattern.containsMatchIn(text)

    fun applyTo(text: String): String = pattern.replace(text, Regex.escapeReplacement(right))
}

/** One line after correction: what to store beside the original. */
data class CorrectedLine(
    val segmentId: Long,
    val text: String,
    /** Which substitutions changed this line, for the log and the diagnostic report. */
    val applied: List<Mishearing> = emptyList(),
)

/**
 * Prompt building and answer parsing for the correction pass. Pure, so it is unit tested
 * without a model: the parsing and the guards are where a 1B model's sloppiness has to be
 * absorbed, and they are the only thing standing between a hallucination and the record.
 */
object CorrectionPrompt {

    /** What the model writes between the misheard text and the real text. */
    const val ARROW = ">"

    /** What it writes when it found nothing, so "no answer" and "nothing wrong" differ. */
    const val NOTHING = "NONE"

    /**
     * Told to find substitutions, not to rewrite anything.
     *
     * "Copied exactly" is load-bearing: [parse] throws away any substitution whose left side
     * does not literally appear in the transcript, which is what makes a hallucinated fix
     * impossible rather than merely unlikely. Saying so in the prompt means the model usually
     * produces usable fixes instead of having most of them discarded.
     */
    val SYSTEM: String = buildString {
        append("You find words that speech recognition heard wrong in a transcript of the ")
        append("user's own conversations. Some words were misheard as similar-sounding ones. ")
        append("Use the surrounding conversation to work out what was really said.\n\n")
        append("Reply with one line for each mistake, in exactly this form:\n")
        append("wrong $ARROW right\n\n")
        append("The left side must be text copied exactly from the transcript. The right side ")
        append("is what was really said. Both sides are just the words — no line numbers, no ")
        append("quotes, no explanation, no bullet points.\n")
        append("Example: contacts $ARROW content\n\n")
        append("Only list a word when you are confident it is wrong, and only when the right ")
        append("version sounds like the wrong one. A mistake you miss costs nothing. A word you ")
        append("change wrongly corrupts the record.\n")
        append("Do not fix grammar, filler words, punctuation or capitalisation. Do not rewrite ")
        append("or shorten anything. Only misheard words.\n")
        append("If nothing was misheard, reply with the single word $NOTHING.")
    }

    fun build(window: CorrectionWindow): String = buildString {
        if (window.vocabulary.isNotEmpty()) {
            append("Names and terms that come up in this person's day: ")
            append(window.vocabulary.joinToString(", "))
            append("\n\n")
        }
        append("Transcript:\n")
        window.targets.forEach { segment ->
            append("- ").append(segment.text.replace('\n', ' ').trim()).append('\n')
        }
        append("\nList the misheard words now, one per line, as: wrong $ARROW right\n")
        append("Reply $NOTHING if there are none.")
    }

    /** Characters either side of the arrow that a model likes to decorate its answers with. */
    private const val DECORATION = "*`\"'•-–—: \t"

    /**
     * Reads substitutions out of whatever the model produced, and throws away everything it
     * cannot vouch for.
     *
     * [source] is the transcript the fixes have to apply to. Every guard here exists because
     * the alternative is a 1B model quietly rewriting somebody's record of a conversation:
     *
     *  - the left side must literally appear in [source], which makes an invented fix impossible;
     *  - both sides must be non-empty and different, case-insensitively;
     *  - the left side must be at least [MIN_WRONG_CHARS], so "a" $ARROW "the" cannot fire on
     *    every line in the window;
     *  - the two sides must be within a factor of [MAX_LENGTH_RATIO] of each other, because a
     *    mishearing sounds like what was said and a paraphrase does not;
     *  - neither side may span more than [MAX_WORDS] words, because that is a rewrite;
     *  - at most [MAX_FIXES] survive, so one confused answer cannot rewrite a whole hour.
     */
    fun parse(reply: String, source: String): List<Mishearing> {
        val trimmed = reply.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.equals(NOTHING, ignoreCase = true)) return emptyList()

        val seen = HashSet<String>()
        val fixes = mutableListOf<Mishearing>()
        for (raw in trimmed.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // The model sometimes answers NONE after a preamble, or mid-list.
            if (line.equals(NOTHING, ignoreCase = true)) continue
            val arrow = line.indexOf(ARROW)
            if (arrow <= 0) continue

            val wrong = line.substring(0, arrow).trim { it in DECORATION }
            val right = line.substring(arrow + ARROW.length).trim { it in DECORATION }
            if (!usable(wrong, right, source)) continue
            // The same substitution twice is the model repeating itself, not two fixes.
            if (!seen.add(wrong.lowercase())) continue

            fixes += Mishearing(wrong, right)
            if (fixes.size >= MAX_FIXES) break
        }
        return fixes
    }

    private fun usable(wrong: String, right: String, source: String): Boolean {
        if (wrong.length < MIN_WRONG_CHARS || right.isEmpty()) return false
        if (wrong.equals(right, ignoreCase = true)) return false
        if (words(wrong) > MAX_WORDS || words(right) > MAX_WORDS) return false
        val ratio = right.length.toDouble() / wrong.length
        if (ratio < 1.0 / MAX_LENGTH_RATIO || ratio > MAX_LENGTH_RATIO) return false
        // The one guard that makes a hallucinated fix impossible rather than unlikely.
        return Mishearing(wrong, right).appliesTo(source)
    }

    /**
     * Applies [fixes] to [targets] and returns only the lines that actually changed.
     *
     * A line nothing applied to is not stored: a correction row identical to the original is
     * noise in the log and in the "how many lines changed" figure.
     */
    fun apply(targets: List<TranscriptSegment>, fixes: List<Mishearing>): List<CorrectedLine> {
        if (fixes.isEmpty()) return emptyList()
        val out = mutableListOf<CorrectedLine>()
        for (segment in targets) {
            var text = segment.text
            val applied = mutableListOf<Mishearing>()
            for (fix in fixes) {
                if (!fix.appliesTo(text)) continue
                text = fix.applyTo(text)
                applied += fix
            }
            if (applied.isNotEmpty() && text != segment.text) {
                out += CorrectedLine(segmentId = segment.id, text = text, applied = applied)
            }
        }
        return out
    }

    /** The transcript as one string, which is what [parse] checks a fix against. */
    fun sourceText(targets: List<TranscriptSegment>): String =
        targets.joinToString("\n") { it.text }

    private fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /** Shorter than this and a substitution fires on half the transcript. */
    const val MIN_WRONG_CHARS = 3

    /** A mishearing is a word or two, not a clause. */
    const val MAX_WORDS = 4

    /** A mishearing sounds like what was said, so the two are a similar length. */
    const val MAX_LENGTH_RATIO = 2.5

    /** One answer may not rewrite a whole hour, however confident it sounds. */
    const val MAX_FIXES = 24
}

/**
 * Runs one window through a provider and returns the lines that changed.
 *
 * One call, not two. The old draft-then-repair pair existed to spend the careful pass only on
 * phrases the fast pass admitted to guessing at — a reasonable trade when the output was a
 * rewrite of every line, and pure cost now that the output is a short list of substitutions.
 */
class TranscriptCorrector(private val provider: LlmProvider) {

    suspend fun correct(window: CorrectionWindow): Result<List<CorrectedLine>> {
        if (window.targets.isEmpty()) return Result.success(emptyList())

        val response = provider.complete(
            listOf(
                ChatMessage(Role.SYSTEM, CorrectionPrompt.SYSTEM),
                ChatMessage(Role.USER, CorrectionPrompt.build(window)),
            ),
            TokenBudget.forFixes(),
        )
        if (response.isError) {
            return Result.failure(IllegalStateException(response.error ?: "model unavailable"))
        }

        val source = CorrectionPrompt.sourceText(window.targets)
        val fixes = CorrectionPrompt.parse(response.text, source)
        // No fixes is a success with nothing to store, not a failure. The model reading an
        // hour and finding nothing misheard is the common and correct outcome.
        return Result.success(CorrectionPrompt.apply(window.targets, fixes))
    }
}

/** The recurring names and terms in a day, cheaply, without a model. */
object DayVocabulary {

    private val STOP = setOf(
        "about", "after", "again", "also", "because", "before", "being", "could", "doing",
        "going", "gonna", "having", "just", "know", "like", "maybe", "might", "really",
        "right", "should", "something", "still", "that's", "their", "there", "these", "thing",
        "things", "think", "those", "through", "today", "want", "we're", "what's", "where",
        "which", "while", "would", "yeah", "you're", "actually", "basically", "everything",
        "people", "little", "okay", "there's", "they're", "other", "never", "always",
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
