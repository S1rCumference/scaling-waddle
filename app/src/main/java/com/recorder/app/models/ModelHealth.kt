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

    /** Null when the speech model is complete, otherwise why it will not be loaded. */
    fun asrProblem(context: Context): String? {
        val entry = runCatching { ModelCatalog.load(context) }
            .getOrNull()
            ?.firstOrNull { it.role == ModelRole.ASR }
            ?: return null // No catalogue entry to check against; the file checks still apply.
        return entry.installProblem(context)?.let { "${entry.displayName}: $it" }
    }

    /**
     * Null when [file] is a complete install of a catalogued model, otherwise why not.
     *
     * A file with no catalogue entry is left alone: it was put there deliberately (by the
     * fetch script, or by hand) and this is not the place to start refusing it.
     */
    fun ggufProblem(context: Context, file: java.io.File): String? {
        val entry = runCatching { ModelCatalog.load(context) }
            .getOrNull()
            ?.firstOrNull { it.fileName == file.name }
            ?: return null
        return entry.installProblem(context)
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
