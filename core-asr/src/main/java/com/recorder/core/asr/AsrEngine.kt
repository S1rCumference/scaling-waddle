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
    fun create(modelDir: File): AsrEngine?
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
}

object AsrEngineFactory {
    private const val TAG = "AsrEngineFactory"
    private const val SHERPA_PLUGIN = "com.recorder.core.asr.SherpaAsrEnginePlugin"

    /** Whether the sherpa-onnx AAR was bundled into this build at all. */
    val sherpaBundled: Boolean get() = BuildConfig.SHERPA_AVAILABLE

    /** Version of the bundled sherpa-onnx AAR, or "none". */
    val sherpaVersion: String get() = BuildConfig.SHERPA_VERSION

    /** True once the Parakeet model files are present alongside the runtime. */
    fun modelsInstalled(context: Context): Boolean =
        AsrModels.asrDir(context).listFiles()?.isNotEmpty() == true

    /**
     * Picks the best engine actually available on this device: the sherpa-onnx Parakeet
     * engine when both its AAR and model files are present, otherwise a no-op engine.
     * Never throws — a missing model must degrade the app, not stop recording.
     */
    fun create(context: Context): AsrEngine {
        val modelDir = AsrModels.asrDir(context)
        if (!BuildConfig.SHERPA_AVAILABLE) {
            return NoopAsrEngine("sherpa-onnx AAR not bundled")
        }
        if (!modelDir.isDirectory || modelDir.listFiles().isNullOrEmpty()) {
            return NoopAsrEngine("no ASR model in ${modelDir.absolutePath}")
        }
        return runCatching {
            val plugin = Class.forName(SHERPA_PLUGIN)
                .getDeclaredConstructor()
                .newInstance() as AsrEnginePlugin
            plugin.create(modelDir)
        }.onFailure { Log.w(TAG, "sherpa engine unavailable", it) }
            .getOrNull() ?: NoopAsrEngine("engine failed to load")
    }
}
