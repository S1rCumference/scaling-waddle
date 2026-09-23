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

    /**
     * True when this model looks installed. Archives are judged by their directory having
     * contents, since their inner filenames come from the archive itself.
     */
    fun isInstalled(context: Context): Boolean {
        val dir = destinationDir(context)
        return if (archive != null) {
            dir.listFiles()?.any { it.length() > 0 } == true
        } else {
            File(dir, fileName).let { it.isFile && it.length() > 0 }
        }
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
                revision = o.optString("revision"),
                sizeBytes = o.getLong("sizeBytes"),
                sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
                license = o.optString("license", "unknown"),
                licenseUrl = o.optString("licenseUrl").takeIf { it.isNotBlank() },
                minRamTier = RamTier.entries.firstOrNull {
                    it.name.equals(o.optString("minRamTier"), true)
                } ?: RamTier.LOW_8GB,
                required = o.optBoolean("required", false),
                archive = o.optString("archive").takeIf { it.isNotBlank() },
                hashVerified = o.optBoolean("hashVerified", false),
                notes = o.optString("notes").takeIf { it.isNotBlank() },
            )
        }
    }

    /** Models this phone should install, largest-capable first within each role. */
    fun recommended(all: List<ModelEntry>, tier: RamTier): List<ModelEntry> {
        val order = listOf(RamTier.LOW_8GB, RamTier.MID_12GB, RamTier.HIGH_16GB_PLUS)
        fun fits(entry: ModelEntry) = order.indexOf(entry.minRamTier) <= order.indexOf(tier)

        return buildList {
            addAll(all.filter { it.required && fits(it) })
            // One chat model per role: the largest this tier can host.
            listOf(ModelRole.SMALL_CHAT, ModelRole.HEAVY).forEach { role ->
                all.filter { it.role == role && fits(it) && !it.required }
                    .maxByOrNull { it.sizeBytes }
                    ?.let(::add)
            }
        }
    }
}
