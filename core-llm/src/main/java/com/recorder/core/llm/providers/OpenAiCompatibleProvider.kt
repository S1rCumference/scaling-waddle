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
 * OpenAI's chat-completions schema. Also covers Ollama, llama.cpp's server, vLLM and
 * LM Studio — pointing [ProviderConfig.endpoint] at a local box is the whole of the
 * "run it on my own server later" story, with no code change here.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    override val id: String = ProviderIds.OPENAI,
) : LlmProvider {

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("model", config.model)
                    .put("messages", messages.toJson())
                if (tools.isNotEmpty()) body.put("tools", tools.toJson())

                val headers = buildMap {
                    put("Content-Type", "application/json")
                    if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
                }

                parse(HttpJson.post(config.endpoint, body, headers))
            }.getOrElse { LlmResponse.failed(it.message ?: "request failed") }
        }

    private fun List<ChatMessage>.toJson() = JSONArray().apply {
        this@toJson.forEach { message ->
            put(
                JSONObject()
                    .put(
                        "role",
                        when (message.role) {
                            Role.SYSTEM -> "system"
                            Role.USER -> "user"
                            Role.ASSISTANT -> "assistant"
                            Role.TOOL -> "tool"
                        },
                    )
                    .put("content", message.content)
                    .apply { message.toolCallId?.let { put("tool_call_id", it) } },
            )
        }
    }

    private fun List<ToolSpec>.toJson() = JSONArray().apply {
        this@toJson.forEach { tool ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.parametersJsonSchema)),
                    ),
            )
        }
    }

    private fun parse(json: JSONObject): LlmResponse {
        val message = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: return LlmResponse.failed("no choices in response")

        val calls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                calls += ToolCall(
                    id = call.optString("id", "call_$i"),
                    name = function.optString("name"),
                    argumentsJson = function.optString("arguments", "{}"),
                )
            }
        }
        return LlmResponse(text = message.optString("content", ""), toolCalls = calls)
    }
}
