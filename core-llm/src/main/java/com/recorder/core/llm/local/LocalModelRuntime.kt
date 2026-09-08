package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import com.recorder.core.llm.BuildConfig
import java.io.Closeable
import java.io.File

/** A loaded on-device model. Implemented by the optional llama.cpp source set. */
interface LocalLlm : Closeable {
    val modelName: String

    suspend fun generate(prompt: String, maxTokens: Int = 512): String

    override fun close() {}
}

/** Contract for the optional runtime, resolved reflectively so the APK builds without it. */
interface LocalLlmPlugin {
    fun load(modelPath: String, contextSize: Int): LocalLlm?
}

object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"
    private const val PLUGIN = "com.recorder.core.llm.local.LlamaCppPlugin"

    val available: Boolean get() = BuildConfig.LLAMA_AVAILABLE

    /** Where `.gguf` files are pushed by `scripts/fetch_models.sh`. */
    fun modelDir(context: Context): File = File(context.filesDir, "models/llm").apply { mkdirs() }

    fun load(model: LocalModelSpec, contextSize: Int = 4096): LocalLlm? {
        if (!available) {
            Log.i(TAG, "llama.cpp runtime not bundled; skipping ${model.fileName}")
            return null
        }
        return runCatching {
            val plugin = Class.forName(PLUGIN).getDeclaredConstructor().newInstance() as LocalLlmPlugin
            plugin.load(model.path, contextSize)
        }.onFailure { Log.w(TAG, "failed to load ${model.fileName}", it) }.getOrNull()
    }
}
