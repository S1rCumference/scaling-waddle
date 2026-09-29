package com.recorder.app.models

import android.content.Context
import com.recorder.core.storage.Diagnostics

/**
 * One answer to "is this model actually safe to load", for everything that loads one.
 *
 * The check itself lives on [ModelEntry]; this is the seam that lets code with no idea what a
 * model catalogue is — the recorder, the speech engine factory — ask the question anyway.
 */
object ModelHealth {

    private const val TAG = "ModelHealth"

    /** Every model this phone knows about, with what is wrong with it or null. */
    fun survey(context: Context): List<Pair<ModelEntry, String?>> =
        runCatching { ModelCatalog.load(context) }
            .getOrDefault(emptyList())
            .map { it to it.installProblem(context) }

    /**
     * Speech models this app used to ship, newest first, that are still worth transcribing with
     * while their replacement downloads.
     *
     * 4.1 moved from Parakeet v2 (English only) to v3 (Russian, English and 23 more). The new one
     * is a 465 MB download, and without this a phone that had v2 fully installed would have
     * stopped writing anything down the moment the app updated — for however long the download
     * took, or forever if nobody pressed the button. v2's files and its install record stay on
     * disk untouched until v3's staged install moves over them, so v2 is loadable, and verified,
     * right up to that moment.
     */
    internal val LEGACY_ASR_IDS = listOf("parakeet-tdt-0.6b-v2-int8")

    /**
     * Which speech model's install record the files on disk satisfy: the catalogue's current one
     * if it is complete, otherwise the newest legacy one that still is, otherwise null.
     *
     * Pure over a directory so the fallback — the one thing between an app update and a silent
     * recorder — is tested on real files rather than trusted.
     */
    internal fun verifiedAsrId(dir: java.io.File, currentId: String, legacyIds: List<String>): String? =
        (listOf(currentId) + legacyIds).firstOrNull { InstallRecord.problem(dir, it) == null }

    /** The id of the speech model that will actually be loaded, or null if none will. */
    fun activeAsrId(context: Context): String? {
        val entry = asrEntry(context) ?: return null
        return verifiedAsrId(entry.destinationDir(context), entry.id, LEGACY_ASR_IDS)
    }

    /** Null when a speech model is complete, otherwise why none will be loaded. */
    fun asrProblem(context: Context): String? {
        val entry = asrEntry(context)
            ?: return null // No catalogue entry to check against; the file checks still apply.
        if (activeAsrId(context) != null) return null
        return entry.installProblem(context)?.let { "${entry.displayName}: $it" }
    }

    /** Whether the phone is still on an older speech model while the current one is missing. */
    fun asrIsLegacy(context: Context): Boolean {
        val entry = asrEntry(context) ?: return false
        val active = activeAsrId(context) ?: return false
        return active != entry.id
    }

    private fun asrEntry(context: Context): ModelEntry? =
        runCatching { ModelCatalog.load(context) }.getOrNull()?.firstOrNull { it.role == ModelRole.ASR }

    /**
     * Model files on disk that the manifest no longer mentions, with their sizes.
     *
     * 3.0 dropped two of the three language models, and an install over the top does not
     * remove their weights — they are app-private files nothing in the catalogue points at any
     * more. On this project's phone that is about 3.5 GB of dead space that no code would ever
     * touch again, so it is offered for removal rather than left to be discovered.
     */
    fun strayModelFiles(context: Context): List<java.io.File> {
        val known = runCatching { ModelCatalog.load(context) }
            .getOrDefault(emptyList())
            .map { it.fileName }
            .toSet()
        val dir = legacyLlmDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf") && it.name !in known }
            ?.sortedByDescending { it.length() }
            .orEmpty()
    }

    /**
     * Where the on-device language model used to live.
     *
     * Hard-coded rather than read from the runtime that owned it, because that runtime is gone.
     * The path has to stay right regardless: everybody upgrading from 3.2 or earlier has a 768 MB
     * Gemma sitting here that nothing will ever load again, and this is the only thing that will
     * ever offer to remove it.
     */
    private fun legacyLlmDir(context: Context): java.io.File =
        java.io.File(context.filesDir, "models/llm")

    /** Removes them, and says what was freed. Only ever from an explicit request. */
    fun removeStrayModelFiles(context: Context): String {
        val stray = strayModelFiles(context)
        if (stray.isEmpty()) return "No models from an earlier version are left on this phone."
        val bytes = stray.sumOf { it.length() }
        val names = stray.map { it.name }
        stray.forEach { file ->
            if (file.delete()) Diagnostics.i(TAG, "removed ${file.name}, freeing ${file.length()} bytes")
        }
        return "Removed ${names.size} model(s) no longer used — about " +
            "${bytes / (1024 * 1024)} MB: ${names.joinToString(", ")}"
    }

    /**
     * Deletes what is left of every unfinished install, and says what it removed.
     *
     * This is the repair: an unfinished install cannot be resumed into a working model, and
     * leaving it there is what makes the app look installed and behave broken.
     */
    fun discardUnfinished(context: Context): List<String> {
        val installer = ModelInstaller(context)
        return survey(context).mapNotNull { (entry, problem) ->
            if (problem == null) return@mapNotNull null
            if (!installer.discardIncomplete(entry)) return@mapNotNull null
            Diagnostics.w(TAG, "removed an unfinished install of ${entry.displayName}: $problem")
            "${entry.displayName} — $problem"
        }
    }
}
