package com.recorder.app.models

import android.content.Context
import com.recorder.core.asr.AsrModels
import com.recorder.core.llm.local.LocalModelRuntime
import java.io.File
import org.json.JSONObject

enum class ModelRole {
    /** Voice activity detection — gates whether ASR runs at all. */
    VAD,

    /** Speech recognition. Without this the app records but produces no text. */
    ASR,

    /** The one language model, which corrects transcript lines and nothing else. */
    SMALL_CHAT,

}

/**
 * One downloadable model.
 *
 * [sha256] is nullable on purpose. Every entry that could be verified at authoring time
 * carries a hash computed from the real download; entries whose host could not be reached
 * from the build environment are shipped with [hashVerified] false and a null hash, and the
 * installer treats them as size-checked only. That is weaker, and the UI says so, rather
 * than shipping a made-up digest that would fail every install.
 */
data class ModelEntry(
    val id: String,
    val role: ModelRole,
    val displayName: String,
    val fileName: String,
    val url: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val licenseUrl: String?,
    val required: Boolean,
    /** "tar.bz2" when the download is an archive to unpack, null for a plain file. */
    val archive: String?,
    val hashVerified: Boolean,
    val notes: String?,
) {
    val approxMb: Long get() = sizeBytes / (1024 * 1024)

    /** Where this model belongs once installed. */
    fun destinationDir(context: Context): File = when (role) {
        ModelRole.VAD -> AsrModels.modelDir(context)
        ModelRole.ASR -> AsrModels.asrDir(context)
        ModelRole.SMALL_CHAT -> LocalModelRuntime.modelDir(context)
    }

    /**
     * The in-progress download, kept outside the model directory entirely.
     *
     * It used to live beside the finished files, which is how a directory containing nothing
     * but a half-downloaded archive still answered "yes" to every "is there a model here"
     * check in the app. A partial download is not a model and now cannot be mistaken for one:
     * it sits in the cache directory, where the system may also reclaim it if space runs out.
     */
    fun partFile(context: Context): File =
        File(File(context.cacheDir, PART_DIR).apply { mkdirs() }, "$fileName.part")

    /** Where an archive is unpacked before anything is moved into place. */
    fun stagingDir(context: Context): File = File(destinationDir(context), ".staging-$id")

    /** True when this model is complete and safe to hand to a model runtime. */
    fun isInstalled(context: Context): Boolean = installProblem(context) == null

    /**
     * Null when the model is completely installed, otherwise why it is not — in a sentence
     * that can be shown to the user.
     *
     * A plain file is checked against the exact byte count in the manifest, which is the
     * cheapest complete answer there is: a truncated or resumed-and-corrupted download is
     * never the right length. An archive has no single expected size once it is unpacked, so
     * it is checked against [InstallRecord], written at the end of a successful install.
     *
     * Both used to be "a file exists and is not empty", which is true of a download that was
     * interrupted one byte in.
     */
    fun installProblem(context: Context): String? {
        val dir = destinationDir(context)
        if (archive == null) {
            val file = File(dir, fileName)
            if (!file.isFile) return "not downloaded yet"
            if (sizeBytes > 0 && file.length() != sizeBytes) {
                return "incomplete: ${file.length()} of $sizeBytes bytes"
            }
            return null
        }
        return InstallRecord.problem(dir, id)
    }

    companion object {
        /** Under cacheDir, so a partial download can never be read as an installed model. */
        const val PART_DIR = "model-downloads"
    }
}

/**
 * The shipped model manifest, read from assets rather than fetched, so a first run needs
 * no round trip before it can tell the user what it is about to download.
 */
object ModelCatalog {

    private const val ASSET = "models.json"

    fun load(context: Context): List<ModelEntry> {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return parse(json)
    }

    /** Separated from [load] so it can be unit tested without an Android context. */
    fun parse(json: String): List<ModelEntry> {
        val root = JSONObject(json)
        val models = root.getJSONArray("models")
        return (0 until models.length()).mapNotNull { i ->
            val o = models.optJSONObject(i) ?: return@mapNotNull null
            val role = ModelRole.entries.firstOrNull { it.name.equals(o.getString("role"), true) }
                ?: return@mapNotNull null

            ModelEntry(
                id = o.getString("id"),
                role = role,
                displayName = o.getString("displayName"),
                fileName = o.getString("fileName"),
                url = o.getString("url"),
                revision = o.text("revision").orEmpty(),
                sizeBytes = o.getLong("sizeBytes"),
                // Only a real digest counts. Anything else — absent, JSON null, or a value
                // that is not 64 hex characters — means size-checked only, which is what
                // hashVerified:false in the manifest already says.
                sha256 = o.text("sha256")?.lowercase()?.takeIf(::looksLikeSha256),
                license = o.text("license") ?: "unknown",
                licenseUrl = o.text("licenseUrl"),
                required = o.optBoolean("required", false),
                // An allowlist, not "is it non-blank": an unrecognised value means "not an
                // archive", so a plain file is never handed to the archive unpacker.
                archive = o.text("archive")?.takeIf { it in SUPPORTED_ARCHIVES },
                hashVerified = o.optBoolean("hashVerified", false),
                notes = o.text("notes"),
            )
        }
    }

    /** The archive formats the installer can actually unpack. */
    private val SUPPORTED_ARCHIVES = setOf("tar.bz2")

    private fun looksLikeSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * A string field, or null when it is absent, empty, or JSON null.
     *
     * Android's `org.json` is not the reference implementation: its `optString` coerces a
     * JSON null to the *four-character string* "null" rather than to an empty string, and
     * only a missing key gives "". Every nullable field in this manifest is written as JSON
     * null, so on a real phone `archive` came back as "null", the VAD was treated as an
     * archive and failed with "Unsupported archive type: null", and `sha256` came back as
     * "null", so every GGUF failed verification with "expected null, got <digest>" and was
     * deleted. Only Parakeet — the one entry with no JSON nulls in it — ever installed.
     *
     * It did not show up in tests because the unit tests run against the reference
     * `org.json` jar on the JVM, where the same code returns "" and behaves correctly.
     * `isNull` means the same thing in both, so it is what this uses.
     */
    private fun JSONObject.text(name: String): String? {
        if (isNull(name)) return null
        // Belt and braces: a manifest that literally contains the string "null" is a mistake
        // either way, and treating it as a value is how this bug behaved on the phone.
        return optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * What this phone should install: all of it.
     *
     * There is nothing to choose any more. The manifest is three required files — voice
     * detection, speech recognition, correction — and the only reason to leave one out is a
     * phone without the memory to run the last of them, which [everythingBytes] and the
     * wizard report rather than silently deciding.
     */
    fun recommended(all: List<ModelEntry>): List<ModelEntry> = all

    /** The whole download, in bytes, for the one figure the wizard has to be honest about. */
    fun everythingBytes(all: List<ModelEntry>): Long = all.sumOf { it.sizeBytes }
}
