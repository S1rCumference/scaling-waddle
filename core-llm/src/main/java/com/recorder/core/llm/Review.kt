package com.recorder.core.llm

/**
 * Turning a corrected stretch of transcript into a handful of things a person will read.
 *
 * A corrected transcript is not a reviewable object. Nobody proofreads an hour of their own
 * speech, so errors survive in it indefinitely and the correction pass gets no feedback.
 * A dozen short statements, each of which can be marked wrong or fixed in place, is
 * something a person will actually go through — and every fix becomes context for the next
 * pass.
 */
object SummaryPrompt {

    const val SYSTEM =
        "You are turning a stretch of one person's day, transcribed from a microphone, into " +
            "a short list of the things that actually happened or were decided. Write each one " +
            "as a single plain sentence someone could read a week later and still understand. " +
            "Include commitments, decisions, numbers, names and anything asked for. Leave out " +
            "small talk, filler and anything you are only guessing at. Do not invent detail " +
            "that is not in the text. Answer immediately without reasoning first."

    /**
     * [taught] is what the user has already corrected by hand. Kept short and put first:
     * a small model follows a handful of concrete examples far better than an instruction.
     */
    fun build(lines: List<String>, taught: List<Pair<String, String>>): String = buildString {
        if (taught.isNotEmpty()) {
            append("You have been corrected on these before. Do not repeat these mistakes:\n")
            taught.forEach { (wrong, right) ->
                if (right.isBlank()) {
                    append("- not true: \"").append(wrong).append("\"\n")
                } else {
                    append("- \"").append(wrong).append("\" should be \"").append(right).append("\"\n")
                }
            }
            append('\n')
        }
        append("What was said:\n")
        lines.forEach { append("- ").append(it.replace('\n', ' ')).append('\n') }
        append("\nReply with at most ").append(MAX_ITEMS).append(" lines, each starting with \"- \". ")
        append("One thing per line. Output nothing else.")
    }

    /** At most this many; a list longer than a screen is not a summary. */
    const val MAX_ITEMS = 12

    private val bullet = Regex("""^\s*[-*•]\s+(.*)$""")

    /** The items out of a model's reply, tolerant of the ways small models decorate a list. */
    fun parse(output: String): List<String> =
        output.lineSequence()
            .mapNotNull { line -> bullet.find(line)?.groupValues?.get(1)?.trim() }
            .map { it.trim('*', '"').trim() }
            .filter { it.length > 8 }
            .distinct()
            .take(MAX_ITEMS)
            .toList()
}
