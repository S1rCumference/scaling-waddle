package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.isModelLoaded
import com.recorder.core.storage.AiPasses
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
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

    /** The system prompt currently in the native context, or null if none was set. */
    private var residentSystemPrompt: String? = null

    /** Turns sent since the last load, so the context window cannot quietly overflow. */
    private var turnsSinceLoad = 0

    /**
     * Reuses the loaded context when it is safe to, and reloads when it is not.
     *
     * The wrapper keeps chat history in its native context and accepts a system prompt only
     * immediately after a load (InferenceEngineImpl.setSystemPrompt checks
     * `_readyForSystemPrompt`). The old code took the simple way out and reloaded before
     * every call after the first, which is where "resetting the engine from ModelReady"
     * came from on nearly every run — a full model load, per question, per correction
     * window, all day.
     *
     * So a reload now happens only when one is actually needed:
     *  - the system prompt is different from the one already in the context, because there
     *    is no other way to replace it;
     *  - [MAX_TURNS_PER_LOAD] turns have gone by, because the history is never cleared and
     *    an overflowing context returns worse answers than a reload costs;
     *  - the engine is in an error state, which [readyToLoad] handles.
     *
     * The trade is that consecutive turns under one load see each other's history. For the
     * correction pass, where consecutive windows are neighbouring minutes of the same day,
     * that is context rather than contamination — but it is a trade, and the turn bound is
     * what keeps it from growing without limit.
     */
    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        maxTokens: Int,
        deadlineMs: Long,
        label: String,
        freshContext: Boolean,
    ): String =
        turnLock.withLock {
            RunningTasks.track("llm-generate", "Thinking · $label") {
                val wanted = systemPrompt?.takeIf { it.isNotBlank() }
                val why = when {
                    !dirty -> null
                    // A summary of one hour must not be written with the previous hour still
                    // in the context; the model blends them and names the wrong thing.
                    freshContext -> "this task must not see the last one"
                    wanted != null && wanted != residentSystemPrompt -> "the task changed"
                    turnsSinceLoad >= MAX_TURNS_PER_LOAD ->
                        "$turnsSinceLoad turns of history would crowd the context"

                    else -> null
                }
                if (why != null) reload(why)

                dirty = true
                turnsSinceLoad++
                if (wanted != null && wanted != residentSystemPrompt) {
                    engine.setSystemPrompt(wanted)
                    residentSystemPrompt = wanted
                }

                val out = StringBuilder()
                var tokens = 0
                var stoppedBy: String? = null
                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + deadlineMs

                // The ceiling is enforced here, not only asked for.
                //
                // predictLength is handed to the native side, and a pass that was given 512
                // produced 1151 tokens and ran for two minutes, so it is plainly not a
                // guarantee. takeWhile is what stops it: abandoning the flow unwinds the
                // generation, and it unwinds as a cancellation, which is the one path the
                // wrapper puts back to ModelReady instead of leaving the engine in Error —
                // so a capped pass costs nothing on the next turn.
                engine.sendUserPrompt(prompt, maxTokens)
                    .takeWhile { chunk ->
                        out.append(chunk)
                        tokens++
                        stoppedBy = when {
                            tokens >= maxTokens -> "the ${maxTokens}-token ceiling"
                            System.currentTimeMillis() > deadline -> "the ${deadlineMs / 1000}s deadline"
                            // A reasoning model would spend the whole budget thinking. Gemma
                            // 3 has no reasoning mode, which is most of why it was chosen, but
                            // the check stays: it costs nothing and the next model might.
                            tokens % THINK_CHECK_EVERY == 0 && out.isStillThinking() ->
                                "an unclosed <think> block"

                            else -> null
                        }
                        if (tokens % TOKEN_REPORT_EVERY == 0) {
                            RunningTasks.update("llm-generate", "$tokens tokens")
                        }
                        stoppedBy == null
                    }
                    .collect { }

                val elapsed = System.currentTimeMillis() - startedAt
                Diagnostics.i(
                    TAG,
                    "turn $turnsSinceLoad ($label): $tokens token(s) in " +
                        "${"%.1f".format(elapsed / 1000.0)}s" +
                        (stoppedBy?.let { ", cut off by $it" } ?: "") +
                        (why?.let { ", after a reload because $it" } ?: ", context reused"),
                )
                // The same facts as numbers, for the self-diagnostic report. A log line can
                // be read; it cannot be averaged.
                AiPasses.record(
                    label = label,
                    tokens = tokens,
                    ms = elapsed,
                    stoppedBy = stoppedBy,
                    reloadedBecause = why,
                )
                stripThinking(out.toString())
            }
        }

    private suspend fun reload(why: String) {
        val startedAt = System.currentTimeMillis()
        RunningTasks.update("llm-generate", "reloading the model")
        engine.readyToLoad()
        engine.loadModel(modelPath)
        val state = engine.state.first { it is State.ModelReady || it is State.Error }
        if (state is State.Error) error("reload failed: ${describe(state.exception)}")
        dirty = false
        turnsSinceLoad = 0
        residentSystemPrompt = null
        // Timed separately from generation, because "the model is slow to load" and "the
        // model is slow to answer" need completely different fixes.
        Diagnostics.i(
            TAG,
            "reloaded $modelName in ${"%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)}s — $why",
        )
    }

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

/**
 * Turns to run under one load before starting again.
 *
 * The native context is 8192 tokens and the wrapper never clears it, so this is the guard
 * against a silent overflow — and an overflowed context is not an error, it is worse answers
 * with no sign of why. A correction window is now up to about 1500 tokens in and 160 out, so
 * three turns sits inside 8192 with room for a long one and a fourth starts fresh.
 *
 * This was six when a window was 400 tokens in. It came down with the window size going up:
 * reading more transcript per call is what makes the pass fast, and the arithmetic has to
 * follow it rather than stay at a number chosen for the old shape.
 */
private const val MAX_TURNS_PER_LOAD = 3

/** Scanning the buffer on every token would be quadratic; every sixteenth is plenty. */
private const val THINK_CHECK_EVERY = 16

/** True when the text has opened a reasoning block it has not closed. */
private fun StringBuilder.isStillThinking(): Boolean {
    val opened = indexOf("<think>")
    return opened >= 0 && indexOf("</think>", opened) < 0
}

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
