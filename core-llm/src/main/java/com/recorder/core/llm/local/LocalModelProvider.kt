package com.recorder.core.llm.local

import android.content.Context
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.LlmResponse
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.Role
import com.recorder.core.llm.ToolSpec

/**
 * An on-device model behind the same [LlmProvider] interface as Claude/OpenAI/Gemini, so
 * it shows up as one more entry in the provider picker instead of a parallel system.
 *
 * Residency is not owned here: [LocalModelRuntime] holds the single model slot, so the
 * small chat model and the heavy tier can both be constructed without fighting over the
 * underlying singleton. On a device that cannot host a model, [complete] returns
 * [LlmResponse.unavailable] with the reason rather than attempting a load that would be
 * OOM-killed.
 */
class LocalModelProvider(
    private val context: Context,
    private val heavy: Boolean,
    override val id: String = ProviderIds.LOCAL_ON_DEVICE,
) : LlmProvider {

    private val selector = LocalModelSelector(context)

    val modelLabel: String? get() = LocalModelRuntime.current

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
        val spec = (if (heavy) selector.selectHeavyModel() else selector.selectSmallModel())
            ?: return LlmResponse.unavailable(unavailableReason())

        val model = LocalModelRuntime.load(context, spec)
            ?: return LlmResponse.unavailable("Model runtime failed to load ${spec.fileName}.")

        // Local models here summarise and file transcripts rather than call tools, so tool
        // specs are ignored outright instead of being half-supported.
        return runCatching {
            LlmResponse(
                text = model.generate(
                    prompt = messages.userContent(),
                    systemPrompt = messages.systemContent(),
                ).trim(),
            )
        }.getOrElse { LlmResponse.failed(it.message ?: "local inference failed") }
    }

    /** Frees the weights. Called when the app goes idle so the model is not resident all day. */
    suspend fun unload() = LocalModelRuntime.unload()

    private fun unavailableReason(): String =
        if (heavy) {
            selector.heavyUnavailableReason() ?: "No local heavy model available."
        } else {
            when {
                !LocalModelRuntime.available ->
                    "Local AI unavailable: ${LocalModelRuntime.unavailableReason}."

                selector.smallModelCandidates().none { it.exists } ->
                    "No local model installed yet. Finish setup to download one."

                else -> "Not enough free memory to load a local model right now."
            }
        }

    private fun List<ChatMessage>.systemContent(): String? =
        filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }

    /**
     * Everything that is not the system prompt, flattened into one turn. The backend takes
     * a single user message, so prior turns are labelled inline rather than lost.
     */
    private fun List<ChatMessage>.userContent(): String = buildString {
        this@userContent.filterNot { it.role == Role.SYSTEM }.forEach { message ->
            when (message.role) {
                Role.USER -> append(message.content)
                Role.ASSISTANT -> append("Earlier answer: ").append(message.content)
                Role.TOOL -> append("Tool result: ").append(message.content)
                Role.SYSTEM -> Unit
            }
            append("\n\n")
        }
    }.trim()
}
