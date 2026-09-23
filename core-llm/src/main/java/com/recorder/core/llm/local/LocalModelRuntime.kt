package com.recorder.core.llm.local

import android.content.Context
import android.os.Build
import android.util.Log
import com.recorder.core.llm.BuildConfig
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A loaded on-device model. Implemented by the optional llama.cpp source set. */
interface LocalLlm : Closeable {
    val modelName: String

    /**
     * [systemPrompt] is passed separately rather than glued onto [prompt] because the
     * backend applies the model's own chat template to each part.
     */
    suspend fun generate(prompt: String, systemPrompt: String? = null, maxTokens: Int = 512): String

    override fun close() {}
}

/** Contract for the optional runtime, resolved reflectively so the APK builds without it. */
interface LocalLlmPlugin {
    suspend fun load(context: Context, modelPath: String, contextSize: Int): LocalLlm?
}

/**
 * Owns the one on-device model slot.
 *
 * The llama.cpp binding hands out a per-process singleton, so two models cannot be
 * resident at once. Rather than let the small chat model and the heavy tier quietly
 * corrupt each other's state, every load goes through here and evicts the previous model
 * first. [current] is what the Settings screen reports.
 */
object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"
    private const val PLUGIN = "com.recorder.core.llm.local.LlamaCppPlugin"

    /**
     * The bundled llama.cpp wrapper needs Android 11: its logging header uses
     * `__android_log_is_loggable`, an API 30 symbol, so the native library cannot even be
     * loaded below that. Kept in step with LLAMA_MIN_SDK in scripts/ci/prepare_natives.sh.
     */
    const val MIN_SDK = Build.VERSION_CODES.R

    val available: Boolean
        get() = BuildConfig.LLAMA_AVAILABLE && Build.VERSION.SDK_INT >= MIN_SDK

    /** Why [available] is false, for the Settings screen. Null when it is available. */
    val unavailableReason: String?
        get() = when {
            !BuildConfig.LLAMA_AVAILABLE -> "not bundled in this build"
            Build.VERSION.SDK_INT < MIN_SDK ->
                "needs Android 11 or newer (this phone runs ${Build.VERSION.RELEASE})"

            else -> null
        }

    /** llama.cpp commit the bundled AAR was built from, or "none". */
    val commit: String get() = BuildConfig.LLAMA_COMMIT

    private val lock = Mutex()

    /** Counted so the power report can show whether the model is thrashing in and out. */
    @Volatile
    var loadCount: Int = 0
        private set

    @Volatile
    var unloadCount: Int = 0
        private set
    private var resident: LocalLlm? = null
    private var residentPath: String? = null

    /** The model currently in memory, if any. */
    val current: String? get() = resident?.modelName

    /** Where `.gguf` files are downloaded to by the first-run wizard. */
    fun modelDir(context: Context): File = File(context.filesDir, "models/llm").apply { mkdirs() }

    /**
     * Loads [model], evicting whatever was resident. Returns null when the runtime is not
     * bundled or the load fails — never throws, because a failed model load must not take
     * the recorder down with it.
     */
    suspend fun load(context: Context, model: LocalModelSpec, contextSize: Int = 4096): LocalLlm? =
        lock.withLock {
            if (!available) {
                Log.i(TAG, "llama.cpp runtime not bundled; skipping ${model.fileName}")
                return@withLock null
            }
            resident?.let { existing ->
                if (residentPath == model.path) return@withLock existing
                Log.i(TAG, "evicting ${existing.modelName} to load ${model.fileName}")
                evict()
            }

            runCatching {
                val plugin = Class.forName(PLUGIN)
                    .getDeclaredConstructor()
                    .newInstance() as LocalLlmPlugin
                plugin.load(context, model.path, contextSize)
            }.onFailure { Log.w(TAG, "failed to load ${model.fileName}", it) }
                .getOrNull()
                ?.also {
                    resident = it
                    residentPath = model.path
                    loadCount++
                }
        }

    /** Frees the resident model. Called when the app goes idle, and before loading another. */
    suspend fun unload() = lock.withLock { evict() }

    private fun evict() {
        resident?.let {
            runCatching { it.close() }.onFailure { e -> Log.w(TAG, "unload failed", e) }
            unloadCount++
        }
        resident = null
        residentPath = null
    }
}
