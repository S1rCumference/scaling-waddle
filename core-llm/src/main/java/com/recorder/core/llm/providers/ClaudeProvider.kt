package com.recorder.core.llm.providers

import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.LlmResponse
import com.recorder.core.llm.ProviderConfig
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.Role
import com.recorder.core.llm.ToolCall
import com.recorder.core.llm.ToolSpec
import com.recorder.core.llm.http.HttpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic's Messages API. Differs from the OpenAI shape in three ways that matter here:
 * the system prompt is a top-level field, tool schemas use `input_schema`, and the reply
 * is a list of content blocks rather than one string.
 */
class ClaudeProvider(private val config: ProviderConfig) : LlmProvider {

    override val id: String = ProviderIds.CLAUDE

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse =
        withContext(Dispatchers.IO) {
            runCatching {
                val system = messages.filter { it.role == Role.SYSTEM }
                    .joinToString("\n\n") { it.content }

                val body = JSONObject()
                    .put("model", config.model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("messages", messages.filterNot { it.role == Role.SYSTEM }.toMessagesJson())
                if (system.isNotBlank()) body.put("system", system)
                if (tools.isNotEmpty()) body.put("tools", tools.toToolsJson())

                val headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-api-key" to config.apiKey,
                    "anthropic-version" to ANTHROPIC_VERSION,
                )

                parse(HttpJson.post(config.endpoint, body, headers))
            }.getOrElse { LlmResponse.failed(it.message ?: "request failed") }
        }

    private fun List<ChatMessage>.toMessagesJson() = JSONArray().apply {
        this@toMessagesJson.forEach { message ->
            when (message.role) {
                Role.TOOL -> put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", message.toolCallId ?: "")
                                    .put("content", message.content),
                            ),
                        ),
                )

                else -> put(
                    JSONObject()
                        .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                        .put("content", message.content),
                )
            }
        }
    }

    private fun List<ToolSpec>.toToolsJson() = JSONArray().apply {
        this@toToolsJson.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("input_schema", JSONObject(tool.parametersJsonSchema)),
            )
        }
    }

    private fun parse(json: JSONObject): LlmResponse {
        val blocks = json.optJSONArray("content")
            ?: return LlmResponse.failed(json.optJSONObject("error")?.optString("message") ?: "empty response")

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> calls += ToolCall(
                    id = block.optString("id", "call_$i"),
                    name = block.optString("name"),
                    argumentsJson = block.optJSONObject("input")?.toString() ?: "{}",
                )
            }
        }
        return LlmResponse(text = text.toString().trim(), toolCalls = calls)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 4096
    }
}
