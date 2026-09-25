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
) {
    companion object {
        /** The output ceiling for every correction call, whatever the batch. */
        const val CORRECTION_MAX_TOKENS = 256

        /** The wall-clock ceiling for one batch. */
        const val CORRECTION_DEADLINE_MS = 45_000L

        /**
         * Correcting n lines produces about n lines back. The ceiling is the smaller of what
         * the lines need and what a batch is ever allowed, so a large batch cannot buy itself
         * a larger budget.
         */
        fun forLines(lines: Int): TokenBudget = TokenBudget(
            maxTokens = (lines * TOKENS_PER_LINE + 64).coerceIn(128, CORRECTION_MAX_TOKENS),
            deadlineMs = CORRECTION_DEADLINE_MS,
            label = "correction draft",
        )

        /** Repairing only the marked spans is a fraction of the work of a full pass. */
        fun forRepair(spans: Int): TokenBudget = TokenBudget(
            maxTokens = (spans * TOKENS_PER_LINE + 48).coerceIn(96, CORRECTION_MAX_TOKENS),
            deadlineMs = CORRECTION_DEADLINE_MS,
            label = "repair",
        )

        /** A transcript line is short; 40 tokens covers a long one with room to spare. */
        private const val TOKENS_PER_LINE = 40
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
