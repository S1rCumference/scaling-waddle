package com.recorder.core.llm.local

import android.llama.cpp.LLamaAndroid
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

/**
 * llama.cpp backend. Compiled only when the llama.cpp Android AAR is in `core-llm/libs/`
 * — see that directory's README.
 */
class LlamaCppEngine(
    private val llama: LLamaAndroid,
    override val modelName: String,
) : LocalLlm {

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val out = StringBuilder()
        llama.send(prompt).take(maxTokens).collect { token -> out.append(token) }
        return out.toString()
    }

    override fun close() {
        runCatching { runBlocking { llama.unload() } }
    }
}

class LlamaCppPlugin : LocalLlmPlugin {
    override fun load(modelPath: String, contextSize: Int): LocalLlm? = runCatching {
        require(File(modelPath).isFile) { "model not found at $modelPath" }
        val llama = LLamaAndroid.instance()
        runBlocking { llama.load(modelPath) }
        LlamaCppEngine(llama, File(modelPath).name)
    }.onFailure { Log.w("LlamaCppPlugin", "load failed", it) }.getOrNull()
}
