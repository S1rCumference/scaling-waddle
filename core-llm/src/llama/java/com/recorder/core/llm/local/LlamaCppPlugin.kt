package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.isModelLoaded
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            RunningTasks.track("llm-generate", "Thinking") {
                if (dirty) reload()
                dirty = true
                if (!systemPrompt.isNullOrBlank()) engine.setSystemPrompt(systemPrompt)

                val out = StringBuilder()
                var tokens = 0
                engine.sendUserPrompt(prompt + thinkingSwitch(), maxTokens).collect { chunk ->
                    out.append(chunk)
                    // A count is the difference between "slow" and "stopped" when the only
                    // thing to look at is a progress bar.
                    if (++tokens % TOKEN_REPORT_EVERY == 0) {
                        RunningTasks.update("llm-generate", "$tokens tokens")
                    }
                }
                stripThinking(out.toString())
            }
        }

    private suspend fun reload() {
        engine.readyToLoad()
        engine.loadModel(modelPath)
        val state = engine.state.first { it is State.ModelReady || it is State.Error }
        if (state is State.Error) error("reload failed: ${describe(state.exception)}")
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

}

/** Removes a leading reasoning block, including the empty one Qwen 3 emits under /no_think. */
internal fun stripThinking(text: String): String =
    text.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").trim()

private const val TAG = "LlamaCppPlugin"

/** Often enough to look alive, rarely enough not to churn the UI. */
private const val TOKEN_REPORT_EVERY = 8

/** Loading the native library and registering backends, on a cold start. */
private const val STARTUP_TIMEOUT_MS = 30_000L

/** Long enough for an in-flight generation to notice the cancel flag and unwind. */
private const val RESET_TIMEOUT_MS = 10_000L

/**
 * Puts the shared engine into the one state [InferenceEngine.loadModel] accepts.
 *
 * The wrapper is a process-wide singleton with a strict state machine: loadModel starts
 * with `check(state is Initialized)`, so a previous failure that left it in `Error`, or a
 * model somebody forgot to unload leaving it in `ModelReady`, makes *every* later load
 * throw — permanently, for the life of the process. cleanUp() is what resets both, so it
 * is called here rather than hoping the state is already clean.
 *
 * The engine also starts up asynchronously (System.loadLibrary and backend registration
 * run on its own dispatcher), so a load attempted straight after the first
 * getInferenceEngine call can arrive while it is still Initializing.
 */
internal suspend fun InferenceEngine.readyToLoad() {
    val settled = withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
        state.first { it !is State.Uninitialized && it !is State.Initializing }
    } ?: error("the inference engine did not finish starting up")

    when (settled) {
        is State.Initialized -> Unit

        is State.Error, is State.ModelReady -> {
            Diagnostics.i(TAG, "resetting the engine from ${settled.label()} before loading")
            withContext(Dispatchers.IO) { cleanUp() }
        }

        // "Busy" here does not mean somebody else is using it. Callers reach this only
        // while holding LocalModelRuntime's lock, so nothing legitimate can be generating.
        // It means a previous turn died without the wrapper noticing: its catch takes
        // Exception, and the missing-stdlib-class failure that used to happen here arrives
        // as an Error, so the state machine sat in Generating for the life of the process
        // and every later correction was refused with "the engine is busy".
        else -> {
            Diagnostics.w(TAG, "engine left in ${settled.label()}; clearing it")
            // cleanUp sets its cancel flag first, so a generation that really is in flight
            // unwinds to ModelReady and this returns cleanly.
            runCatching { withContext(Dispatchers.IO) { cleanUp() } }
            val after = withTimeoutOrNull(RESET_TIMEOUT_MS) {
                state.first { it is State.Initialized || it is State.ModelReady || it is State.Error }
            }
            if (after is State.ModelReady || after is State.Error) {
                runCatching { withContext(Dispatchers.IO) { cleanUp() } }
            }
            if (state.value !is State.Initialized) {
                error(
                    "the engine is wedged in ${state.value.label()} and cannot be reset. " +
                        "Force-stop the app and start it again.",
                )
            }
            Diagnostics.i(TAG, "engine recovered")
        }
    }
}

private fun State.label(): String = when (this) {
    is State.Error -> "Error(${exception.javaClass.simpleName})"
    else -> javaClass.simpleName
}

/** A sentence worth putting in front of a person, from an exception that has none. */
internal fun describe(error: Throwable): String = when {
    error is UnsupportedArchitectureException ->
        "llama.cpp could not open this file. Either the GGUF is damaged or truncated, or " +
            "this build's llama.cpp does not know the model's architecture."

    !error.message.isNullOrBlank() -> error.message!!
    else -> error.javaClass.simpleName
}

class LlamaCppPlugin : LocalLlmPlugin {

    override suspend fun load(context: Context, modelPath: String, contextSize: Int): LocalLlm? {
        val file = File(modelPath)
        if (!file.isFile) {
            Diagnostics.w(TAG, "model not found at $modelPath")
            return null
        }
        LocalModelRuntime.backendProblem(context)?.let { problem ->
            // Worth its own message: this is a packaging fault, not a model fault, and it
            // makes every model fail identically with nothing else to go on.
            Diagnostics.e(TAG, problem)
            return null
        }

        return runCatching {
            val engine = AiChat.getInferenceEngine(context)
            engine.readyToLoad()
            engine.loadModel(modelPath)

            // loadModel reports failure through state rather than always throwing.
            val state = engine.state.first { it is State.ModelReady || it is State.Error }
            if (state is State.Error) error(describe(state.exception))
            check(engine.state.value.isModelLoaded) { "engine finished without a loaded model" }

            LlamaCppEngine(engine, modelPath, file.name)
        }.onFailure { error ->
            // This used to go to logcat only, which is unreadable on a phone with no computer
            // attached — the app just said "runtime failed to load" with no reason anywhere.
            Diagnostics.w(TAG, "could not load ${file.name}: ${describe(error)}", error)
        }.getOrNull()
    }
}
