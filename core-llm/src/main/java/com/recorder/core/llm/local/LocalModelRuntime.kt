package com.recorder.core.llm.local

import android.content.Context
import android.os.Build
import android.util.Log
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.RunningTasks
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
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        maxTokens: Int = 512,
        /** Wall-clock ceiling; the text produced so far is returned when it is reached. */
        deadlineMs: Long = 60_000,
        /** Which job asked, for the progress bar and the self-diagnostic report. */
        label: String = "pass",
    ): String

    /**
     * Runs the backend's own benchmark, if it has one, and returns its raw report.
     * Null when the backend cannot benchmark itself — better than inventing numbers.
     */
    suspend fun benchmark(promptTokens: Int = 128, generateTokens: Int = 64): String? = null

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

    /**
     * An extra gate the app installs at start-up: given a model file, null when the app's own
     * install records say it is complete, otherwise the reason it is not.
     *
     * A seam rather than a dependency, for the same reason the speech engine has one. This
     * module can see that a file exists; only the app knows how many bytes it was supposed to
     * be, and a truncated GGUF is a native crash rather than a catchable failure.
     */
    @Volatile
    var fileVerifier: ((File) -> String?)? = null

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
     * Why the last load failed, in a sentence, or null if the last one worked. Shown in the
     * UI instead of "Model runtime failed to load <file>", which named the model and told
     * you nothing about the actual fault.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Checks that llama.cpp's CPU kernels are where it will look for them, or says what is
     * wrong. Null means fine.
     *
     * The bundled AAR is built with GGML_BACKEND_DL=ON and GGML_CPU_ALL_VARIANTS=ON, so the
     * kernels are separate `libggml-cpu-*.so` files that llama.cpp dlopen()s at start-up by
     * scanning ApplicationInfo.nativeLibraryDir. If the APK does not extract its native
     * libraries at install time that directory is empty, no backend is registered, and every
     * model fails to load with no other symptom — which is exactly what happened. The APK now
     * sets useLegacyPackaging, and this is the check that says so out loud if it ever stops.
     */
    fun backendProblem(context: Context): String? {
        if (!available) return null
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val names = dir.listFiles()?.map { it.name }.orEmpty()
        return when {
            names.isEmpty() ->
                "The app's native libraries are not on disk ($dir is empty), so llama.cpp " +
                    "cannot load its CPU kernels. This build needs extracted native libraries."

            names.none { it.startsWith("libggml-cpu") } ->
                "llama.cpp's CPU kernels (libggml-cpu-*.so) are missing from $dir. Found: " +
                    names.sorted().joinToString(", ").take(300)

            else -> null
        }
    }

    /** What is on disk next to the app, for the diagnostics screen. */
    fun nativeLibrarySummary(context: Context): String {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val names = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        if (names.isEmpty()) return "No native libraries extracted to $dir"
        return "${names.size} native libraries in $dir: ${names.joinToString(", ")}"
    }

    /**
     * Loads [model], evicting whatever was resident. Returns null when the runtime is not
     * bundled or the load fails — never throws, because a failed model load must not take
     * the recorder down with it.
     */
    suspend fun load(context: Context, model: LocalModelSpec, contextSize: Int = 4096): LocalLlm? =
        lock.withLock {
            if (!available) {
                Diagnostics.i(TAG, "llama.cpp runtime not bundled; skipping ${model.fileName}")
                return@withLock null
            }
            resident?.let { existing ->
                if (residentPath == model.path) return@withLock existing
                Log.i(TAG, "evicting ${existing.modelName} to load ${model.fileName}")
                evict()
            }

            backendProblem(context)?.let { problem ->
                lastError = problem
                Diagnostics.e(TAG, problem)
                return@withLock null
            }

            runCatching {
                RunningTasks.track("llm-load", "Loading ${model.label}") {
                    val plugin = Class.forName(PLUGIN)
                        .getDeclaredConstructor()
                        .newInstance() as LocalLlmPlugin
                    plugin.load(context, model.path, contextSize)
                }
            }.onFailure {
                lastError = it.message ?: it.javaClass.simpleName
                Diagnostics.w(TAG, "failed to load ${model.fileName}", it)
            }
                .getOrNull()
                ?.also {
                    resident = it
                    residentPath = model.path
                    loadCount++
                    lastError = null
                }
        }

    /** Frees the resident model. Called when the app goes idle, and before loading another. */
    suspend fun unload() = usage.withLock { lock.withLock { evict() } }

    /**
     * Held for the whole of one load-and-generate, so a question on the cover screen cannot
     * evict the model halfway through a correction batch (or the reverse). Without it the
     * second caller's load would unload the weights mid-generation, and the first caller
     * would get truncated text back that looks like a real answer.
     */
    private val usage = Mutex()

    /** Loads [model] if needed and runs [block] with exclusive use of it. Null if it can't load. */
    suspend fun <T> withModel(context: Context, model: LocalModelSpec, block: suspend (LocalLlm) -> T): T? =
        usage.withLock {
            val llm = load(context, model) ?: return@withLock null
            block(llm)
        }

    private fun evict() {
        resident?.let {
            runCatching { it.close() }.onFailure { e -> Log.w(TAG, "unload failed", e) }
            unloadCount++
        }
        resident = null
        residentPath = null
    }
}
