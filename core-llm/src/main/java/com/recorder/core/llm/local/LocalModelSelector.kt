package com.recorder.core.llm.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * What a caller wants loaded. [Auto] picks from RAM tier and free memory; [Strongest] prefers
 * the heavy model and falls back to the best chat model; [File] is a choice made in Settings.
 */
sealed interface LocalModelChoice {
    data object Small : LocalModelChoice
    data object Heavy : LocalModelChoice
    data object Strongest : LocalModelChoice

    /**
     * The smallest model installed that will run. For work that is mechanical rather than
     * clever, where finishing quickly is the whole value.
     */
    data object Smallest : LocalModelChoice
    data class File(val fileName: String) : LocalModelChoice
}

/** One candidate GGUF, with the headroom it needs beyond the ASR pipeline already resident. */
data class LocalModelSpec(
    val label: String,
    val fileName: String,
    val requiredFreeMb: Long,
    val path: String,
) {
    /**
     * Null when the file is there and complete, otherwise why it will not be loaded.
     *
     * "The file is there" is not enough. A GGUF whose download was interrupted is a real file
     * of the right name, and memory-mapping a truncated one is a native fault, not an
     * exception — so whether the install actually finished is asked before the load, through
     * the verifier the app installs on [LocalModelRuntime].
     */
    val problem: String?
        get() {
            val file = File(path)
            if (!file.isFile) return "not downloaded"
            return LocalModelRuntime.fileVerifier?.invoke(file)
        }

    val exists: Boolean get() = problem == null
}

/**
 * Picks the largest model that both exists on disk and fits in memory *right now*,
 * alongside the recording pipeline. Deliberately runtime-evaluated rather than a compile
 * time constant: the same APK has to behave on an 8 GB Razr and on a 16 GB phone, and the
 * honest answer on the small one is sometimes "none of them".
 */
class LocalModelSelector(private val context: Context) {

    /**
     * The all-day model for this phone's tier, then smaller fallbacks.
     *
     * Three tiers, and nothing that needs more than a 12 GB phone:
     *
     *   LOW    (6-8 GB)  Gemma 3 1B, 0.81 GB
     *   MEDIUM (12 GB)   Qwen 3 1.7B, 1.11 GB   <- the default on this project's Razr+
     *   HIGH   (12 GB)   Qwen 3 4B, 2.50 GB, charging and idle only ([heavyModelCandidate])
     *
     * The MEDIUM choice is deliberately not the biggest model a 12 GB phone can hold. This
     * one runs all day beside the recorder: it is loaded and unloaded every few minutes by
     * the correction pass, so what matters is that 1.11 GB memory-maps in about a second and
     * leaves the ASR pipeline's working set alone, not how it scores on a benchmark. The
     * bigger model is still there for when the phone is plugged in.
     *
     * Filenames and headroom figures match app/src/main/assets/models.json, whose sizes were
     * read from the Hub rather than estimated. Headroom is roughly the file plus KV cache and
     * runtime overhead, so a model that would be OOM-killed beside the recorder is never
     * chosen.
     */
    fun smallModelCandidates(): List<LocalModelSpec> {
        val dir = LocalModelRuntime.modelDir(context)
        val medium = spec("Qwen 3 1.7B (Q4_K_M)", "qwen3-1.7b-q4.gguf", 1_700, dir)
        val low = spec("Gemma 3 1B Instruct (Q4_K_M)", "gemma-3-1b-q4.gguf", 1_300, dir)
        return when (DeviceCapabilities.ramTier(context)) {
            RamTier.LOW_8GB -> listOf(low)
            // 16 GB phones get the same models: nothing in this build needs more than 12 GB.
            RamTier.MID_12GB, RamTier.HIGH_16GB_PLUS -> listOf(medium, low)
        }
    }

    /**
     * The HIGH tier: the biggest model that loads reliably on a 12 GB phone. Nothing is
     * offered on 8 GB, and nothing larger is offered at all — 5 GB of weights does not load
     * beside the recorder on 12 GB, so it is left out rather than offered and then killed.
     */
    fun heavyModelCandidate(): LocalModelSpec? {
        val dir = LocalModelRuntime.modelDir(context)
        return when (DeviceCapabilities.ramTier(context)) {
            RamTier.LOW_8GB -> null
            RamTier.MID_12GB, RamTier.HIGH_16GB_PLUS ->
                spec("Qwen 3 4B (Q4_K_M)", "qwen3-4b-q4.gguf", 3_400, dir)
        }
    }

    /**
     * The smallest installed model that fits, ignoring tiers entirely.
     *
     * Correcting a transcript is not a task that rewards a bigger model: it is substituting
     * words the speech model misheard, with the surrounding lines as context. Asking a 2.5 GB
     * model to do it — which is what "the strongest that fits" meant while the phone was
     * plugged in, and the overnight pass only runs while it is plugged in — cost minutes per
     * batch to produce nearly the same text a 0.81 GB model produces in seconds.
     */
    fun selectSmallestModel(): LocalModelSpec? {
        if (!LocalModelRuntime.available) return null
        if (DeviceCapabilities.isLowMemory(context)) return null
        val free = DeviceCapabilities.availableRamMb(context)
        return smallModelCandidates()
            .filter { it.exists && free >= it.requiredFreeMb }
            .minByOrNull { it.requiredFreeMb }
            .also { chosen ->
                if (chosen == null) Log.i(TAG, "no model fits at all (${free}MB free)")
                else Log.i(TAG, "selected the smallest installed model: ${chosen.label}")
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

    /**
     * The HIGH tier model, or null. Requires the phone to be plugged in: this model exists
     * for overnight work, and loading it on battery is the thing the tiers are meant to stop.
     */
    fun selectHeavyModel(): LocalModelSpec? {
        if (!LocalModelRuntime.available) return null
        if (!DeviceCapabilities.isCharging(context)) return null
        val candidate = heavyModelCandidate() ?: return null
        val free = DeviceCapabilities.availableRamMb(context)
        return candidate.takeIf { it.exists && free >= it.requiredFreeMb }
    }

    /**
     * The strongest model available right now: the HIGH tier one while charging, otherwise
     * the all-day model. On battery this is the all-day model by design, not by accident.
     */
    fun selectStrongest(): LocalModelSpec? = selectHeavyModel() ?: selectSmallModel()

    /** Every model this build knows about, strongest first, for the Settings switchers. */
    fun allCandidates(): List<LocalModelSpec> =
        listOfNotNull(heavyModelCandidate()) + smallModelCandidates()

    /**
     * A specific model chosen in Settings, if it is installed and fits in memory now. The
     * charging rule still applies to the HIGH tier model however it was chosen.
     */
    fun selectFile(fileName: String): LocalModelSpec? {
        if (!LocalModelRuntime.available || DeviceCapabilities.isLowMemory(context)) return null
        val heavy = heavyModelCandidate()
        if (fileName == heavy?.fileName && !DeviceCapabilities.isCharging(context)) return null
        val known = allCandidates().firstOrNull { it.fileName == fileName } ?: return null
        val free = DeviceCapabilities.availableRamMb(context)
        return known.takeIf { it.exists && free >= it.requiredFreeMb }
    }

    fun select(choice: LocalModelChoice): LocalModelSpec? = when (choice) {
        LocalModelChoice.Small -> selectSmallModel()
        LocalModelChoice.Heavy -> selectHeavyModel()
        LocalModelChoice.Strongest -> selectStrongest()
        LocalModelChoice.Smallest -> selectSmallestModel()
        is LocalModelChoice.File -> selectFile(choice.fileName)
    }

    /** Which tier this phone is on, and what each role would use, for the Settings screen. */
    fun tierSummary(): String {
        val tier = when (DeviceCapabilities.ramTier(context)) {
            RamTier.LOW_8GB -> "LOW (6-8 GB)"
            RamTier.MID_12GB, RamTier.HIGH_16GB_PLUS -> "MEDIUM / HIGH (12 GB)"
        }
        val allDay = smallModelCandidates().firstOrNull()
        val heavy = heavyModelCandidate()
        return buildString {
            append("Tier: ").append(tier).append(" · ")
            append(DeviceCapabilities.marketedRamGb(context)).append(" GB, ")
            append(DeviceCapabilities.availableRamMb(context)).append(" MB free now\n")
            append("All day: ").append(allDay?.label ?: "none").append(' ')
            append(if (allDay?.exists == true) "· installed" else "· not installed").append('\n')
            append("Corrections: ").append(selectSmallestModel()?.label ?: "none that fits")
            append(" · smallest installed, because correction is mechanical\n")
            append("Charging only: ").append(heavy?.label ?: "none on this tier")
            if (heavy != null) {
                append(if (heavy.exists) " · installed" else " · not installed")
                if (!DeviceCapabilities.isCharging(context)) append(" · waiting for a charger")
            }
        }
    }

    /** Why the HIGH tier is unavailable, phrased for the settings screen. */
    fun heavyUnavailableReason(): String? = when {
        DeviceCapabilities.ramTier(context) == RamTier.LOW_8GB ->
            "This phone's tier (LOW, ${DeviceCapabilities.marketedRamGb(context)} GB) has no " +
                "charging-only model: nothing bigger than the all-day one fits. Use a cloud provider."

        !LocalModelRuntime.available -> "llama.cpp runtime not bundled in this build."
        heavyModelCandidate()?.exists != true ->
            "Not downloaded yet: ${heavyModelCandidate()?.fileName}"

        !DeviceCapabilities.isCharging(context) ->
            "Only runs while the phone is plugged in. Charge it and this becomes available."

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
            "qwen3-1.7b-q4.gguf",
            "gemma-3-1b-q4.gguf",
        )

        val HEAVY_MODEL_FILES = listOf(
            "qwen3-4b-q4.gguf",
        )
    }
}
