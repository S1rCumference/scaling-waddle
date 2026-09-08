package com.recorder.core.llm.local

import android.content.Context
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.LlmResponse
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.Role
import com.recorder.core.llm.ToolSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An on-device model behind the same [LlmProvider] interface as Claude/OpenAI/Gemini, so
 * it shows up as one more entry in the provider picker instead of a parallel system.
 *
 * The model is loaded lazily on first use and kept resident; on a device that cannot host
 * one, [complete] returns [LlmResponse.unavailable] with the reason rather than trying a
 * load that would be OOM-killed.
 */
class LocalModelProvider(
    private val context: Context,
    private val heavy: Boolean,
    override val id: String = ProviderIds.LOCAL_ON_DEVICE,
) : LlmProvider {

    private val selector = LocalModelSelector(context)
    private val lock = Mutex()
    private var engine: LocalLlm? = null

    val modelLabel: String? get() = engine?.modelName

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
        val model = lock.withLock {
            engine ?: run {
                val spec = (if (heavy) selector.selectHeavyModel() else selector.selectSmallModel())
                    ?: return LlmResponse.unavailable(unavailableReason())
                LocalModelRuntime.load(spec)?.also { engine = it }
                    ?: return LlmResponse.unavailable("Model runtime failed to load ${spec.fileName}.")
            }
        }

        // Local models here are used for summarising and folder assignment, not agentic
        // tool use; tool specs are ignored rather than silently half-supported.
        return runCatching {
            LlmResponse(text = model.generate(messages.toPrompt()).trim())
        }.getOrElse { LlmResponse.failed(it.message ?: "local inference failed") }
    }

    fun unload() {
        engine?.close()
        engine = null
    }

    private fun unavailableReason(): String =
        if (heavy) {
            selector.heavyUnavailableReason() ?: "No local heavy model available."
        } else {
            when {
                !LocalModelRuntime.available -> "llama.cpp runtime not bundled in this build."
                selector.smallModelCandidates().none { it.exists } ->
                    "No local model installed. Run scripts/fetch_models.sh to push one."

                else -> "Not enough free memory to load a local model right now."
            }
        }

    private fun List<ChatMessage>.toPrompt(): String = buildString {
        this@toPrompt.forEach { message ->
            when (message.role) {
                Role.SYSTEM -> append("System: ").append(message.content).append("\n\n")
                Role.USER -> append("User: ").append(message.content).append("\n\n")
                Role.ASSISTANT -> append("Assistant: ").append(message.content).append("\n\n")
                Role.TOOL -> append("Tool result: ").append(message.content).append("\n\n")
            }
        }
        append("Assistant: ")
    }
}
