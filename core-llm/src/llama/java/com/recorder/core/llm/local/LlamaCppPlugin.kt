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
    override val modelName: String,
) : LocalLlm {

    // sendUserPrompt drives one shared native context; overlapping calls would interleave.
    private val turnLock = Mutex()
    private var lastSystemPrompt: String? = null

    override suspend fun generate(prompt: String, systemPrompt: String?, maxTokens: Int): String =
        turnLock.withLock {
            // Re-sending an unchanged system prompt would re-process it every question.
            if (systemPrompt != null && systemPrompt != lastSystemPrompt) {
                engine.setSystemPrompt(systemPrompt)
                lastSystemPrompt = systemPrompt
            }

            val out = StringBuilder()
            engine.sendUserPrompt(prompt, maxTokens).collect { chunk -> out.append(chunk) }
            out.toString()
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

            LlamaCppEngine(engine, file.name)
        }.onFailure { Log.w(TAG, "load failed for ${file.name}", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "LlamaCppPlugin"
    }
}
