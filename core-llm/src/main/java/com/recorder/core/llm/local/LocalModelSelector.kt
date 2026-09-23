package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import java.io.File

/** One candidate GGUF, with the headroom it needs beyond the ASR pipeline already resident. */
data class LocalModelSpec(
    val label: String,
    val fileName: String,
    val requiredFreeMb: Long,
    val path: String,
) {
    val exists: Boolean get() = File(path).isFile
}

/**
 * Picks the largest model that both exists on disk and fits in memory *right now*,
 * alongside the recording pipeline. Deliberately runtime-evaluated rather than a compile
 * time constant: the same APK has to behave on an 8 GB Razr and on a 16 GB phone, and the
 * honest answer on the small one is sometimes "none of them".
 */
class LocalModelSelector(private val context: Context) {

    /**
     * The always-available chat model, largest first.
     *
     * Filenames and headroom figures match app/src/main/assets/models.json, whose sizes were
     * read from the Hub rather than estimated. Headroom is roughly the file plus KV cache and
     * runtime overhead, so a model that would be OOM-killed beside the ASR pipeline is never
     * chosen.
     */
    fun smallModelCandidates(): List<LocalModelSpec> {
        val dir = LocalModelRuntime.modelDir(context)
        return listOf(
            // 2.49 GB on disk; only offered from the 12 GB tier upward.
            spec("Phi-4-mini Instruct (Q4_K_M)", "phi-4-mini-q4.gguf", 3_400, dir),
            // 1.11 GB — the sensible default on an 8 GB phone.
            spec("Qwen 3 1.7B (Q4_K_M)", "qwen3-1.7b-q4.gguf", 1_700, dir),
            // 0.81 GB — last resort when memory is tight.
            spec("Gemma 3 1B Instruct (Q4_K_M)", "gemma-3-1b-q4.gguf", 1_300, dir),
        )
    }

    /** The heavy tier, gated on RAM tier rather than only on free memory. */
    fun heavyModelCandidate(): LocalModelSpec? {
        val dir = LocalModelRuntime.modelDir(context)
        return when (DeviceCapabilities.ramTier(context)) {
            // Nothing heavy fits beside ASR and a chat model in 8 GB.
            RamTier.LOW_8GB -> null
            // 2.50 GB. Whether the 8B fits here instead is for the benchmark to answer.
            RamTier.MID_12GB -> spec("Qwen 3 4B (Q4_K_M)", "qwen3-4b-q4.gguf", 3_400, dir)
            // 5.03 GB.
            RamTier.HIGH_16GB_PLUS -> spec("Qwen 3 8B (Q4_K_M)", "qwen3-8b-q4.gguf", 6_600, dir)
        }
    }

    fun selectSmallModel(): LocalModelSpec? {
        if (!LocalModelRuntime.available) return null
        if (DeviceCapabilities.isLowMemory(context)) {
            Log.w(TAG, "device reports low memory; not loading a local model")
            return null
        }
        val free = DeviceCapabilities.availableRamMb(context)
        return smallModelCandidates()
            .firstOrNull { it.exists && free >= it.requiredFreeMb }
            .also { chosen ->
                if (chosen == null) Log.i(TAG, "no small model fits (${free}MB free)")
                else Log.i(TAG, "selected ${chosen.label} with ${free}MB free")
            }
    }

    fun selectHeavyModel(): LocalModelSpec? {
        if (!LocalModelRuntime.available) return null
        val candidate = heavyModelCandidate() ?: return null
        val free = DeviceCapabilities.availableRamMb(context)
        return candidate.takeIf { it.exists && free >= it.requiredFreeMb }
    }

    /** Why the heavy tier is off, phrased for the settings screen. */
    fun heavyUnavailableReason(): String? = when {
        DeviceCapabilities.ramTier(context) == RamTier.LOW_8GB ->
            "Not enough RAM for a local heavy model on this device " +
                "(${DeviceCapabilities.marketedRamGb(context)} GB). " +
                "Use a cloud provider, or a 12 GB+ phone."

        !LocalModelRuntime.available -> "llama.cpp runtime not bundled in this build."
        heavyModelCandidate()?.exists != true ->
            "Model file not installed: ${heavyModelCandidate()?.fileName}"

        else -> null
    }

    private fun spec(label: String, fileName: String, requiredFreeMb: Long, dir: File) =
        LocalModelSpec(label, fileName, requiredFreeMb, File(dir, fileName).absolutePath)

    companion object {
        private const val TAG = "LocalModelSelector"

        /**
         * The filenames this selector looks for on disk. The download manifest must write
         * exactly these names, or a model downloads successfully and is then never found —
         * a failure with no visible symptom beyond the assistant staying unavailable. A unit
         * test asserts the manifest and this list agree.
         */
        val SMALL_MODEL_FILES = listOf(
            "phi-4-mini-q4.gguf",
            "qwen3-1.7b-q4.gguf",
            "gemma-3-1b-q4.gguf",
        )

        val HEAVY_MODEL_FILES = listOf(
            "qwen3-4b-q4.gguf",
            "qwen3-8b-q4.gguf",
        )
    }
}
