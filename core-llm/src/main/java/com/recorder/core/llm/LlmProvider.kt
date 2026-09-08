package com.recorder.core.llm

/**
 * The single seam every heavy-tier backend implements — Claude, OpenAI, Gemini, an
 * OpenAI-compatible local server, or an on-device llama.cpp model. Callers never learn
 * which one they got.
 */
interface LlmProvider {
    val id: String

    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList()): LlmResponse
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
