package com.recorder.core.llm

/**
 * The seam the on-device model sits behind.
 *
 * It was once an abstraction over five backends — Claude, OpenAI, Gemini, an
 * OpenAI-compatible local server and llama.cpp. 3.0 has one, because nothing derived from the
 * microphone leaves the phone any more. The seam stays because it is what keeps prompt
 * building and response parsing testable without a model, not because another backend is
 * coming.
 */
interface LlmProvider {
    val id: String

    /**
     * Completes [messages] within [budget]. A provider that cannot enforce a budget is not
     * acceptable here: an unbounded pass on a phone is a flat battery.
     */
    suspend fun complete(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse
}

/**
 * What one task is allowed to spend.
 *
 * On-device generation has no natural stopping point: a 1.7B model asked a small question
 * produced 1151 tokens and ran for two minutes, because nothing in the path said when to
 * stop. Every call carries a ceiling sized to its job, so the worst case is a truncated
 * answer rather than a phone that is busy for minutes.
 */
data class TokenBudget(
    val maxTokens: Int,
    /** Wall-clock ceiling. Hit this and the text produced so far is used. */
    val deadlineMs: Long,
    /** Which job this is, for the progress bar and the self-diagnostic report. */
    val label: String = "pass",
    /**
     * Whether this call must start from an empty context.
     *
     * The backend keeps chat history in its native context and never clears it, so
     * consecutive calls see each other. For correction that is useful: consecutive windows are
     * neighbouring minutes of the same conversation. For a summary it is actively wrong — hour
     * two would be summarised with hour one still in the context, and the model blends them —
     * so every summary asks for a reload.
     */
    val freshContext: Boolean = false,
) {
    companion object {
        /**
         * The output ceiling for a correction call.
         *
         * A correction produces a short list of `wrong > right` substitutions, so this is a
         * ceiling on the number of mistakes reported, not on the length of the transcript
         * read. At roughly 8 tokens a second on this phone that is about twenty seconds in the
         * worst case, against eight and a half minutes for the rewrite-every-line design it
         * replaced.
         */
        const val CORRECTION_MAX_TOKENS = 160

        /** The wall-clock ceiling for one correction call. */
        const val CORRECTION_DEADLINE_MS = 40_000L

        /**
         * One correction call. Fixed rather than scaled by batch size: the answer is a list of
         * mistakes, and a longer stretch of transcript does not contain proportionally more of
         * them — it just gives the model more to read, which costs prefill, not generation.
         */
        fun forFixes(): TokenBudget = TokenBudget(
            maxTokens = CORRECTION_MAX_TOKENS,
            deadlineMs = CORRECTION_DEADLINE_MS,
            label = "correction",
        )

        /**
         * A group summary: a name and two or three sentences, so the ceiling is small and
         * fixed. It does not scale with the span — a month is not allowed a longer answer than
         * an hour, because the point of the roll-up is that it stays readable as the span grows.
         */
        const val SUMMARY_MAX_TOKENS = 200

        /**
         * Longer than a correction call. A summary pays for a reload (it demands a fresh
         * context) and for reading its whole source before it writes anything.
         */
        const val SUMMARY_DEADLINE_MS = 90_000L

        fun forSummary(label: String): TokenBudget = TokenBudget(
            maxTokens = SUMMARY_MAX_TOKENS,
            deadlineMs = SUMMARY_DEADLINE_MS,
            label = "summary of one $label",
            freshContext = true,
        )
    }
}

enum class Role { SYSTEM, USER }

data class ChatMessage(val role: Role, val content: String)

data class LlmResponse(
    val text: String,
    val available: Boolean = true,
    val error: String? = null,
) {
    val isError: Boolean get() = !available || error != null

    companion object {
        fun unavailable(reason: String) = LlmResponse(text = "", available = false, error = reason)
        fun failed(reason: String) = LlmResponse(text = "", error = reason)
    }
}
