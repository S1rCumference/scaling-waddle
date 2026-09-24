package com.recorder.core.llm.local

import android.content.Context
import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.LlmProvider
import com.recorder.core.llm.LlmResponse
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.TokenBudget
import com.recorder.core.llm.Role
import com.recorder.core.llm.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val choice: LocalModelChoice,
    override val id: String = ProviderIds.LOCAL_ON_DEVICE,
    /** Upper bound on generated tokens per call; corrections need more than a chat answer. */
    private val maxTokens: Int = 512,
) : LlmProvider {

    constructor(context: Context, heavy: Boolean) :
        this(context, if (heavy) LocalModelChoice.Heavy else LocalModelChoice.Small)

    private val heavy: Boolean get() = choice == LocalModelChoice.Heavy

    private val selector = LocalModelSelector(context)

    /**
     * Unloading is debounced rather than immediate: someone asking a question usually asks
     * another one, and reloading several gigabytes between them would be worse than holding
     * it briefly. Holding it all day is what this avoids.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleUnload: Job? = null

    val modelLabel: String? get() = LocalModelRuntime.current

    override suspend fun complete(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse =
        run(messages, budget)

    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse =
        run(messages, TokenBudget(maxTokens, deadlineMs = 60_000))

    /**
     * Inference never runs on the caller's thread, whoever the caller is.
     *
     * This is the load-bearing line for "Recorder isn't responding". The wrapper's flow is
     * flowOn(its own dispatcher), so the *producer* was always off the main thread — but the
     * collector runs wherever the caller is, and every caller here arrives from
     * viewModelScope, which is Dispatchers.Main.immediate. So a thousand-token answer meant
     * a thousand callbacks, string appends and state writes on the main thread while it was
     * also supposed to be drawing. Fixing it at this seam covers every path into the model
     * at once, rather than hoping each call site remembers.
     */
    private suspend fun run(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse =
        withContext(Dispatchers.Default) { generate(messages, budget) }

    private suspend fun generate(messages: List<ChatMessage>, budget: TokenBudget): LlmResponse {
        val spec = selector.select(choice)
            ?: return LlmResponse.unavailable(unavailableReason())

        // Local models here summarise and file transcripts rather than call tools, so tool
        // specs are ignored outright instead of being half-supported.
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
                // The reason, not just the filename. "failed to load qwen3-1.7b-q4.gguf"
                // was true and completely useless: it named the model when the fault was in
                // the runtime underneath it.
                LocalModelRuntime.lastError?.let { "Could not load ${spec.fileName}: $it" }
                    ?: "Could not load ${spec.fileName}. Settings -> Diagnostics has the detail.",
            )
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            LlmResponse.failed(error.message ?: "local inference failed")
        }.also { scheduleIdleUnload() }
    }

    /** The label of the model this provider would load right now, for "corrected by …". */
    fun plannedModelLabel(): String? = selector.select(choice)?.label

    /** Frees the weights. Called when the app goes idle so the model is not resident all day. */
    suspend fun unload() {
        idleUnload?.cancel()
        LocalModelRuntime.unload()
    }

    private fun scheduleIdleUnload() {
        idleUnload?.cancel()
        idleUnload = scope.launch {
            delay(IDLE_UNLOAD_MS)
            LocalModelRuntime.unload()
        }
    }

    private fun unavailableReason(): String =
        if (heavy) {
            selector.heavyUnavailableReason() ?: "No local heavy model available."
        } else {
            when {
                !LocalModelRuntime.available ->
                    "Local AI unavailable: ${LocalModelRuntime.unavailableReason}."

                selector.allCandidates().none { it.exists } ->
                    "No local model installed yet. Finish setup to download one."

                choice is LocalModelChoice.File && selector.allCandidates()
                    .none { it.fileName == choice.fileName && it.exists } ->
                    "The model chosen in Settings (${choice.fileName}) is not installed."

                else -> "Not enough free memory to load a local model right now."
            }
        }

    private companion object {
        /**
         * Long enough to cover the gap between correction batches, short enough not to hold
         * the weights all day.
         *
         * Three minutes was shorter than the interval between runs, so the model was evicted
         * and loaded from cold almost every single time — the eviction cost more than the
         * residency it was avoiding. The weights are memory-mapped, so the pages the system
         * actually needs back it can take without asking.
         */
        const val IDLE_UNLOAD_MS = 10 * 60 * 1000L
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
