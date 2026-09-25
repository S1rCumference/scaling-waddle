package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The model, singular. One file, one role, no tiers and no picking.
 *
 * 3.0 runs exactly one language model, for exactly one job: correcting transcript lines. The
 * app used to carry three, sorted into tiers by how much RAM the phone had, with a different
 * one chosen per role and per charging state — which meant the overnight correction pass
 * loaded 2.5 GB of weights to substitute misheard words, and every question about why the AI
 * was slow had to start by working out which model had actually run.
 *
 * Gemma 3 1B Q4 is the one, and the reason is not its size. It has no reasoning mode, so it
 * cannot emit think tokens: the failure that produced 1151 tokens and two minutes of output
 * for a one-line correction is not available to it.
 */
object OnDeviceModel {

    private const val TAG = "OnDeviceModel"

    const val FILE_NAME = "gemma-3-1b-q4.gguf"
    const val LABEL = "Gemma 3 1B Instruct (Q4_K_M)"

    /** Weights plus KV cache and runtime overhead, beside the recorder's own working set. */
    const val REQUIRED_FREE_MB = 1_300L

    fun file(context: Context): File = File(LocalModelRuntime.modelDir(context), FILE_NAME)

    fun spec(context: Context): LocalModelSpec = LocalModelSpec(
        label = LABEL,
        fileName = FILE_NAME,
        requiredFreeMb = REQUIRED_FREE_MB,
        path = file(context).absolutePath,
    )

    /** Null when the model can be loaded right now, otherwise why not, for a person to read. */
    fun problem(context: Context): String? {
        if (!LocalModelRuntime.available) return LocalModelRuntime.unavailableReason
        spec(context).problem?.let { return it }
        if (DeviceCapabilities.isLowMemory(context)) {
            return "the phone is short of memory right now"
        }
        val free = DeviceCapabilities.availableRamMb(context)
        if (free < REQUIRED_FREE_MB) {
            return "only ${free} MB of memory free, and it needs about $REQUIRED_FREE_MB MB"
        }
        return null
    }

    fun ready(context: Context): Boolean = problem(context) == null

    /** The model to load, or null with the reason logged. */
    fun selected(context: Context): LocalModelSpec? {
        val problem = problem(context)
        if (problem != null) {
            Log.i(TAG, "not loading $LABEL: $problem")
            return null
        }
        return spec(context)
    }
}

/**
 * One GGUF on disk, with the headroom it needs.
 *
 * Kept as a type rather than a path because [problem] is the load-bearing part: a GGUF whose
 * download was interrupted is a real file of the right name, and memory-mapping a truncated
 * one is a native fault rather than an exception, so whether the install actually finished is
 * asked before the load through the verifier the app installs on [LocalModelRuntime].
 */
data class LocalModelSpec(
    val label: String,
    val fileName: String,
    val requiredFreeMb: Long,
    val path: String,
) {
    val problem: String?
        get() {
            val file = File(path)
            if (!file.isFile) return "not downloaded yet"
            return LocalModelRuntime.fileVerifier?.invoke(file)
        }

    val exists: Boolean get() = problem == null
}
