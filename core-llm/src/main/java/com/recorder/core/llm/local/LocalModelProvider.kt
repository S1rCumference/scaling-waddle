package com.recorder.core.llm.local

import android.content.Context
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.LlmResponse
import com.recorder.core.llm.Role
import com.recorder.core.llm.TokenBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The on-device model behind the [LlmProvider] seam. The only provider there is.
 *
 * There used to be five — three cloud vendors, a local server and this — resolved through a
 * factory that read settings to decide which. 3.0 has no cloud path at all: nothing derived
 * from the microphone leaves the phone, so the decision has no inputs left and the factory
 * had nothing to do.
 */
class LocalModelProvider(
    private val context: Context,
    override val id: String = ID,
) : LlmProvider {

    /** The label to store beside a correction, so a row says what produced it. */
    val modelLabel: String get() = OnDeviceModel.LABEL

    override suspend fun complete(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse =
        run(messages, budget)

    /**
     * Inference never runs on the caller's thread, whoever the caller is.
     *
     * This is the load-bearing line for "Recorder isn't responding". The wrapper's flow is
     * flowOn(its own dispatcher), so the *producer* was always off the main thread — but the
     * collector runs wherever the caller is, and a caller arriving from viewModelScope is on
     * Dispatchers.Main.immediate. So a few hundred tokens meant a few hundred callbacks,
     * string appends and state writes on the thread that is also supposed to be drawing.
     * Fixing it at this seam covers every path into the model at once.
     */
    private suspend fun run(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse =
        withContext(Dispatchers.IO) { generate(messages, budget) }

    private suspend fun generate(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse {
        val spec = OnDeviceModel.selected(context)
            ?: return LlmResponse.unavailable(
                OnDeviceModel.problem(context)?.let { "${OnDeviceModel.LABEL}: $it" }
                    ?: "No model installed.",
            )

        return runCatching {
            LocalModelRuntime.withModel(context, spec) { model ->
                LlmResponse(
                    text = model.generate(
                        prompt = messages.userContent(),
                        systemPrompt = messages.systemContent(),
                        maxTokens = budget.maxTokens,
                        deadlineMs = budget.deadlineMs,
                        label = budget.label,
                    ).trim(),
                )
            } ?: LlmResponse.unavailable(
                // The reason, not just the filename. "failed to load gemma-3-1b-q4.gguf" was
                // true and useless: it named the model when the fault was in the runtime
                // underneath it.
                LocalModelRuntime.lastError?.let { "Could not load ${spec.fileName}: $it" }
                    ?: "Could not load ${spec.fileName}. Settings -> Diagnostics has the detail.",
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            LlmResponse.failed(error.message ?: "on-device inference failed")
        }
    }

    /**
     * Frees the weights. Called the moment a correction pass finishes or is cancelled: this
     * model exists for one job, and holding a gigabyte resident between jobs that happen once
     * a night is the opposite of what it is for.
     */
    suspend fun unload() = LocalModelRuntime.unload()

    companion object {
        const val ID = "local_on_device"
    }

    private fun List<ChatMessage>.systemContent(): String? =
        filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }

    /** Everything that is not the system prompt, flattened: the backend takes one turn. */
    private fun List<ChatMessage>.userContent(): String =
        filterNot { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }.trim()
}
