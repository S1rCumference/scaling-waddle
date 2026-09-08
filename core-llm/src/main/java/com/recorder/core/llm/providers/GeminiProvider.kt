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
 * Google's generateContent API. Tool calling is expressed as `functionDeclarations`, and
 * the assistant role is spelled "model", but everything else maps cleanly onto
 * [LlmProvider].
 */
class GeminiProvider(private val config: ProviderConfig) : LlmProvider {

    override val id: String = ProviderIds.GEMINI

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse =
        withContext(Dispatchers.IO) {
            runCatching {
                val system = messages.filter { it.role == Role.SYSTEM }
                    .joinToString("\n\n") { it.content }

                val body = JSONObject()
                    .put("contents", messages.filterNot { it.role == Role.SYSTEM }.toMessagesJson())
                if (system.isNotBlank()) {
                    body.put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
                    )
                }
                if (tools.isNotEmpty()) {
                    body.put(
                        "tools",
                        JSONArray().put(JSONObject().put("functionDeclarations", tools.toToolsJson())),
                    )
                }

                // Endpoint is the models base; the model name and method are appended here so
                // switching models in settings needs no endpoint edit.
                val url = "${config.endpoint.trimEnd('/')}/${config.model}:generateContent"
                val headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-goog-api-key" to config.apiKey,
                )

                parse(HttpJson.post(url, body, headers))
            }.getOrElse { LlmResponse.failed(it.message ?: "request failed") }
        }

    private fun List<ChatMessage>.toMessagesJson() = JSONArray().apply {
        this@toMessagesJson.forEach { message ->
            val part = when (message.role) {
                Role.TOOL -> JSONObject().put(
                    "functionResponse",
                    JSONObject()
                        .put("name", message.toolName ?: "tool")
                        .put("response", JSONObject().put("result", message.content)),
                )

                else -> JSONObject().put("text", message.content)
            }
            put(
                JSONObject()
                    .put("role", if (message.role == Role.ASSISTANT) "model" else "user")
                    .put("parts", JSONArray().put(part)),
            )
        }
    }

    private fun List<ToolSpec>.toToolsJson() = JSONArray().apply {
        this@toToolsJson.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", JSONObject(tool.parametersJsonSchema)),
            )
        }
    }

    private fun parse(json: JSONObject): LlmResponse {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return LlmResponse.failed(
                json.optJSONObject("error")?.optString("message") ?: "no candidates in response",
            )

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            part.optString("text").takeIf { it.isNotBlank() }?.let(text::append)
            part.optJSONObject("functionCall")?.let { call ->
                calls += ToolCall(
                    id = "call_$i",
                    name = call.optString("name"),
                    argumentsJson = call.optJSONObject("args")?.toString() ?: "{}",
                )
            }
        }
        return LlmResponse(text = text.toString().trim(), toolCalls = calls)
    }
}
