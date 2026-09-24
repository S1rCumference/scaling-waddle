package com.recorder.core.asr

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * The one seam the whole transcription stack hangs off. Nothing outside this module may
 * reference Parakeet, sherpa-onnx or any other runtime by name, so swapping models later
 * is a change to [AsrEngineFactory] and nothing else.
 */
interface AsrEngine : Closeable {
    suspend fun transcribe(audioChunk: FloatArray, sampleRate: Int): String

    /** Human-readable engine name, shown in settings so it is obvious what is running. */
    val name: String

    override fun close() {}
}

/** Contract for an optional engine implementation compiled in only when its runtime is present. */
interface AsrEnginePlugin {
    fun create(modelDir: File, threads: Int): AsrEngine?
}

/** Records audio but yields no text — the honest default before models are installed. */
class NoopAsrEngine(private val reason: String) : AsrEngine {
    override val name: String get() = "none ($reason)"
    override suspend fun transcribe(audioChunk: FloatArray, sampleRate: Int): String = ""
}

object AsrModels {
    /** Model files live in app-private storage, pushed by `scripts/fetch_models.sh`. */
    fun modelDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun sileroVadFile(context: Context): File = File(modelDir(context), "silero_vad.onnx")

    /** Directory holding the Parakeet encoder/decoder/joiner/tokens files. */
    fun asrDir(context: Context): File = File(modelDir(context), "asr")

    /** What the transducer needs, by filename prefix. The `.int8` infix is allowed. */
    private val REQUIRED_PARTS = listOf("encoder", "decoder", "joiner")

    /**
     * Below this, a file is not a model however it is named — the smallest of the three
     * Parakeet graphs is several megabytes. This is what a part-written file looks like.
     */
    private const val MIN_PART_BYTES = 64L * 1024

    /**
     * Null when [asrDir] holds something that can be loaded, otherwise why not.
     *
     * Loading a malformed .onnx aborts the process from inside onnxruntime, which no Kotlin
     * `runCatching` can catch, so the check has to happen before the file is handed over.
     * "The directory is not empty" was the old check, and a directory containing only an
     * interrupted download satisfied it.
     */
    fun asrProblem(context: Context): String? = asrProblem(asrDir(context))

    /** The same check against a plain directory, so it can be tested without a device. */
    fun asrProblem(dir: File): String? {
        if (!dir.isDirectory) return "no model directory at ${dir.absolutePath}"
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return "no ASR model in ${dir.absolutePath}"
        files.firstOrNull { it.name.endsWith(".part") }?.let {
            return "a download is still in progress (${it.name})"
        }
        REQUIRED_PARTS.forEach { part ->
            val match = files.firstOrNull { it.name.startsWith(part) && it.name.endsWith(".onnx") }
                ?: return "$part model file is missing"
            if (match.length() < MIN_PART_BYTES) {
                return "${match.name} is only ${match.length()} bytes, so it is incomplete"
            }
        }
        val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return "tokens.txt is missing"
        if (tokens.length() <= 0) return "tokens.txt is empty"
        return null
    }
}

object AsrEngineFactory {
    private const val TAG = "AsrEngineFactory"
    private const val SHERPA_PLUGIN = "com.recorder.core.asr.SherpaAsrEnginePlugin"

    /**
     * Two threads is the default on purpose. Decoding is the single largest CPU cost in an
     * all-day recorder, and adding threads buys latency that nobody is waiting on while
     * costing battery that matters.
     */
    const val DEFAULT_THREADS = 2

    /** Whether the sherpa-onnx AAR was bundled into this build at all. */
    val sherpaBundled: Boolean get() = BuildConfig.SHERPA_AVAILABLE

    /** Version of the bundled sherpa-onnx AAR, or "none". */
    val sherpaVersion: String get() = BuildConfig.SHERPA_VERSION

    /**
     * An extra gate the app installs at start-up, so the recorder only ever loads a model the
     * app's own install records vouch for.
     *
     * A seam rather than a dependency: this module knows what a usable directory looks like,
     * but only the app knows whether the install that produced it actually finished. Returns
     * null when the directory is good, or the reason it is not.
     */
    @Volatile
    var installVerifier: ((Context) -> String?)? = null

    /** True once the Parakeet model files are complete alongside the runtime. */
    fun modelsInstalled(context: Context): Boolean = modelProblem(context) == null

    /** Null when a model can be loaded, otherwise why not. */
    fun modelProblem(context: Context): String? =
        AsrModels.asrProblem(context) ?: installVerifier?.invoke(context)

    /**
     * Picks the best engine actually available on this device: the sherpa-onnx Parakeet
     * engine when both its AAR and model files are present, otherwise a no-op engine.
     * Never throws — a missing model must degrade the app, not stop recording.
     */
    fun create(context: Context, threads: Int = DEFAULT_THREADS): AsrEngine {
        val modelDir = AsrModels.asrDir(context)
        if (!BuildConfig.SHERPA_AVAILABLE) {
            return NoopAsrEngine("sherpa-onnx AAR not bundled")
        }
        modelProblem(context)?.let { problem ->
            // Refusing here is the whole point: handing a part-written model to onnxruntime
            // takes the process down with it, which is not a failure the app can report.
            Log.w(TAG, "not loading the speech model: $problem")
            return NoopAsrEngine(problem)
        }
        return runCatching {
            val plugin = Class.forName(SHERPA_PLUGIN)
                .getDeclaredConstructor()
                .newInstance() as AsrEnginePlugin
            plugin.create(modelDir, threads.coerceIn(1, 8))
        }.onFailure { Log.w(TAG, "sherpa engine unavailable", it) }
            .getOrNull() ?: NoopAsrEngine("engine failed to load")
    }
}
