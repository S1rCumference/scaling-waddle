package com.recorder.app.models

import android.content.Context
import com.recorder.core.asr.AsrModels
import com.recorder.core.llm.local.LocalModelRuntime
import com.recorder.core.llm.local.RamTier
import java.io.File
import org.json.JSONObject

enum class ModelRole {
    /** Voice activity detection — gates whether ASR runs at all. */
    VAD,

    /** Speech recognition. Without this the app records but produces no text. */
    ASR,

    /** The always-available on-device chat model. */
    SMALL_CHAT,

    /** The heavier local model, only offered on phones with the RAM for it. */
    HEAVY,
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
    val minRamTier: RamTier,
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
        ModelRole.SMALL_CHAT, ModelRole.HEAVY -> LocalModelRuntime.modelDir(context)
    }

    /** The in-progress download for this model, which is never evidence of an install. */
    fun partFile(context: Context): File = File(destinationDir(context), "$fileName.part")

    /**
     * True when this model is actually usable on disk.
     *
     * The archive case used to accept "any non-empty file in the directory", and the
     * in-progress `.part` download lives in exactly that directory — so a half-downloaded
     * speech model reported itself installed, the wizard showed a tick, and transcription
     * produced nothing. An unpacked sherpa model is a set of .onnx files plus tokens.txt, so
     * that is what gets checked.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = destinationDir(context)
        if (archive == null) {
            return File(dir, fileName).let { it.isFile && it.length() > 0 }
        }
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty()
        return files.any { it.name.endsWith(".onnx") } && files.any { it.name == "tokens.txt" }
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
                minRamTier = RamTier.entries.firstOrNull {
                    it.name.equals(o.text("minRamTier"), true)
                } ?: RamTier.LOW_8GB,
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
     * What this phone should install: everything required, plus one model per role.
     *
     * The pick per role is the entry for the highest tier this phone qualifies for — not the
     * largest file. Those happen to coincide today, but the intent is the tier mapping in
     * models.json, and a future entry that is bigger without being the tier's choice should
     * not quietly win.
     */
    fun recommended(all: List<ModelEntry>, tier: RamTier): List<ModelEntry> {
        fun rank(t: RamTier) = TIER_ORDER.indexOf(t)
        fun fits(entry: ModelEntry) = rank(entry.minRamTier) <= rank(tier)

        return buildList {
            addAll(all.filter { it.required && fits(it) })
            listOf(ModelRole.SMALL_CHAT, ModelRole.HEAVY).forEach { role ->
                all.filter { it.role == role && fits(it) && !it.required }
                    .maxByOrNull { rank(it.minRamTier) }
                    ?.let(::add)
            }
        }
    }

    private val TIER_ORDER = listOf(RamTier.LOW_8GB, RamTier.MID_12GB, RamTier.HIGH_16GB_PLUS)
}
