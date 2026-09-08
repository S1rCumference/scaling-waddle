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

    /** Phase 2's always-available chat model, largest first. */
    fun smallModelCandidates(): List<LocalModelSpec> {
        val dir = LocalModelRuntime.modelDir(context)
        return listOf(
            spec("Phi-4-mini 3.8B (Q4)", "phi-4-mini-q4.gguf", 3_000, dir),
            spec("Qwen 3 1.7B (Q4)", "qwen3-1.7b-q4.gguf", 1_600, dir),
            spec("Gemma 3 1B (Q4)", "gemma-3-1b-q4.gguf", 1_100, dir),
        )
    }

    /** Phase 4's heavy tier, gated on RAM tier rather than only on free memory. */
    fun heavyModelCandidate(): LocalModelSpec? {
        val dir = LocalModelRuntime.modelDir(context)
        return when (DeviceCapabilities.ramTier(context)) {
            RamTier.LOW_8GB -> null
            RamTier.MID_12GB -> spec("Gemma 3 4B (Q4)", "gemma-3-4b-q4.gguf", 4_000, dir)
            RamTier.HIGH_16GB_PLUS -> spec("Qwen 3 8B (Q4)", "qwen3-8b-q4.gguf", 6_500, dir)
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
                "(${"%.0f".format(DeviceCapabilities.totalRamGb(context))} GB). " +
                "Use a cloud provider, or a 12 GB+ phone."

        !LocalModelRuntime.available -> "llama.cpp runtime not bundled in this build."
        heavyModelCandidate()?.exists != true ->
            "Model file not installed: ${heavyModelCandidate()?.fileName}"

        else -> null
    }

    private fun spec(label: String, fileName: String, requiredFreeMb: Long, dir: File) =
        LocalModelSpec(label, fileName, requiredFreeMb, File(dir, fileName).absolutePath)

    private companion object {
        const val TAG = "LocalModelSelector"
    }
}
