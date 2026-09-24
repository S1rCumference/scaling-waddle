package com.recorder.core.llm

/**
 * The single seam every heavy-tier backend implements — Claude, OpenAI, Gemini, an
 * OpenAI-compatible local server, or an on-device llama.cpp model. Callers never learn
 * which one they got.
 */
interface LlmProvider {
    val id: String

    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList()): LlmResponse

    /**
     * Same as [complete], with a ceiling on how much the model may produce and how long it
     * may take. Providers that cannot enforce one ignore it and answer as usual.
     */
    suspend fun complete(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse =
        complete(messages)
}

/**
 * What one task is allowed to spend.
 *
 * On-device generation has no natural stopping point: a 1.7B model asked a small question
 * produced 1151 tokens and ran for two minutes, because nothing in the path said when to
 * stop. Every call now carries a ceiling sized to its job, so the worst case is a truncated
 * answer rather than a phone that is busy for minutes.
 */
data class TokenBudget(
    val maxTokens: Int,
    /** Wall-clock ceiling. Hit this and the answer so far is used. */
    val deadlineMs: Long,
) {
    companion object {
        /** Correcting n lines produces about n lines back, plus room for the model to be untidy. */
        fun forLines(lines: Int): TokenBudget =
            TokenBudget(
                maxTokens = (lines * TOKENS_PER_LINE + 64).coerceIn(128, 768),
                deadlineMs = 20_000,
            )

        /** Repairing only the marked spans is a fraction of the work of a full pass. */
        fun forRepair(spans: Int): TokenBudget =
            TokenBudget(
                maxTokens = (spans * TOKENS_PER_LINE + 48).coerceIn(96, 384),
                deadlineMs = 15_000,
            )

        /** An open question deserves a real answer, but not an unbounded one. */
        val ANSWER = TokenBudget(maxTokens = 512, deadlineMs = 45_000)

        /** A dozen one-sentence items, and no room to ramble past them. */
        val SUMMARY = TokenBudget(maxTokens = 384, deadlineMs = 25_000)

        /** A transcript line is short; 40 tokens covers a long one with room to spare. */
        private const val TOKENS_PER_LINE = 40
    }
}

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: Role,
    val content: String,
    /** Set on [Role.TOOL] messages to tie a result back to the call that asked for it. */
    val toolCallId: String? = null,
    val toolName: String? = null,
)

/**
 * A tool the model may call. [parametersJsonSchema] is a JSON Schema object as a string,
 * which is the shape all three vendor APIs and MCP agree on.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val available: Boolean = true,
    val error: String? = null,
) {
    val isError: Boolean get() = !available || error != null

    companion object {
        fun unavailable(reason: String) = LlmResponse(text = "", available = false, error = reason)
        fun failed(reason: String) = LlmResponse(text = "", error = reason)
    }
}

data class ProviderConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
)

/** Provider ids as stored in settings. Kept as constants so settings and factory can't drift. */
object ProviderIds {
    const val OPENAI = "openai"
    const val CLAUDE = "claude"
    const val GEMINI = "gemini"
    const val LOCAL_SERVER = "local_server"
    const val LOCAL_ON_DEVICE = "local_on_device"

    val all = listOf(OPENAI, CLAUDE, GEMINI, LOCAL_SERVER, LOCAL_ON_DEVICE)

    fun defaultEndpoint(id: String): String = when (id) {
        OPENAI -> "https://api.openai.com/v1/chat/completions"
        CLAUDE -> "https://api.anthropic.com/v1/messages"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models"
        LOCAL_SERVER -> "http://192.168.1.10:11434/v1/chat/completions"
        else -> ""
    }

    fun defaultModel(id: String): String = when (id) {
        OPENAI -> "gpt-4.1-mini"
        CLAUDE -> "claude-sonnet-4-5"
        GEMINI -> "gemini-2.0-flash"
        LOCAL_SERVER -> "qwen2.5:7b"
        else -> ""
    }

    fun label(id: String): String = when (id) {
        OPENAI -> "OpenAI"
        CLAUDE -> "Claude (Anthropic)"
        GEMINI -> "Gemini (Google)"
        LOCAL_SERVER -> "Local server (OpenAI-compatible)"
        LOCAL_ON_DEVICE -> "On-device model"
        else -> id
    }
}
