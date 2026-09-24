package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.isModelLoaded
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * llama.cpp through the Android library in `examples/llama.android` (ARM's AiChat wrapper),
 * built from a pinned commit by CI — see `.github/workflows/android.yml`.
 *
 * Two properties of that wrapper shape this file:
 *  - the engine is a process-wide singleton, so [LocalModelRuntime] owns the single slot
 *    and this class never tries to hold more than one model;
 *  - it exposes no thread count, context size, or explicit chat template. The
 *    [contextSize] argument is therefore accepted and ignored, and answers use whatever
 *    formatting the native layer applies. Both are the reason a purpose-built JNI wrapper
 *    is still on the roadmap.
 */
class LlamaCppEngine(
    private val engine: InferenceEngine,
    private val modelPath: String,
    override val modelName: String,
) : LocalLlm {

    // sendUserPrompt drives one shared native context; overlapping calls would interleave.
    private val turnLock = Mutex()

    /** True once a user prompt has gone into the native context since the last load. */
    private var dirty = false

    /**
     * Every call is a fresh conversation. The wrapper keeps chat history in its native
     * context and only accepts a system prompt *immediately* after a load
     * (InferenceEngineImpl.setSystemPrompt checks `_readyForSystemPrompt`), so a second call
     * with a different system prompt used to throw, and a second call with the same one
     * silently carried the previous task's text along. Reloading is cheap: the weights are
     * memory-mapped and still in the page cache.
     */
    override suspend fun generate(prompt: String, systemPrompt: String?, maxTokens: Int): String =
        turnLock.withLock {
            if (dirty) reload()
            dirty = true
            if (!systemPrompt.isNullOrBlank()) engine.setSystemPrompt(systemPrompt)

            val out = StringBuilder()
            engine.sendUserPrompt(prompt + thinkingSwitch(), maxTokens).collect { chunk -> out.append(chunk) }
            stripThinking(out.toString())
        }

    private suspend fun reload() {
        engine.cleanUp()
        engine.loadModel(modelPath)
        val state = engine.state.first { it is State.ModelReady || it is State.Error }
        if (state is State.Error) error("reload failed: ${state.exception.message}")
        dirty = false
    }

    /**
     * Qwen 3 reasons in a <think> block before answering unless told not to, which on a phone
     * spends the whole token budget on reasoning nobody reads. "/no_think" is Qwen 3's
     * documented switch; other models never see it.
     */
    private fun thinkingSwitch(): String =
        if (modelName.startsWith("qwen3", ignoreCase = true)) " /no_think" else ""

    /**
     * The wrapper ships its own benchmark, which is why this project does not hand-roll one:
     * pp is prompt processing, tg is token generation, pl is parallel sequences, nr repeats.
     */
    override suspend fun benchmark(promptTokens: Int, generateTokens: Int): String? =
        turnLock.withLock {
            runCatching { engine.bench(pp = promptTokens, tg = generateTokens, pl = 1, nr = 1) }
                .onFailure { Log.w(TAG, "bench failed", it) }
                .getOrNull()
        }

    /**
     * Unloads the weights but leaves the engine initialised, so the next question can load
     * a model again. Full teardown ([InferenceEngine.destroy]) would end the engine's
     * coroutine scope for the life of the process.
     */
    override fun close() {
        runCatching { engine.cleanUp() }
            .onFailure { Log.w(TAG, "cleanUp failed", it) }
    }

    private companion object {
        const val TAG = "LlamaCppEngine"
    }
}

/** Removes a leading reasoning block, including the empty one Qwen 3 emits under /no_think. */
internal fun stripThinking(text: String): String =
    text.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").trim()

class LlamaCppPlugin : LocalLlmPlugin {

    override suspend fun load(context: Context, modelPath: String, contextSize: Int): LocalLlm? {
        val file = File(modelPath)
        if (!file.isFile) {
            Log.w(TAG, "model not found at $modelPath")
            return null
        }

        return runCatching {
            val engine = AiChat.getInferenceEngine(context)
            engine.loadModel(modelPath)

            // loadModel reports failure through state rather than always throwing.
            val state = engine.state.first { it is State.ModelReady || it is State.Error }
            if (state is State.Error) error("engine error: ${state.exception.message}")
            check(engine.state.value.isModelLoaded) { "engine finished without a loaded model" }

            LlamaCppEngine(engine, modelPath, file.name)
        }.onFailure { Log.w(TAG, "load failed for ${file.name}", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "LlamaCppPlugin"
    }
}
