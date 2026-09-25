package com.recorder.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recorder_settings")

/**
 * Everything the user can change without a rebuild. Deliberately not a Room table:
 * the recording pipeline reads these on every segment and must never block on the DB.
 */
class RecorderSettings(private val context: Context) {

    val triggerKeywords: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.TRIGGERS] ?: DEFAULT_TRIGGERS }

    val recordingEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.RECORDING_ENABLED] ?: true }

    val activeProvider: Flow<String> =
        context.dataStore.data.map { it[Keys.ACTIVE_PROVIDER] ?: "" }

    val providerEndpoint: Flow<String> =
        context.dataStore.data.map { it[Keys.PROVIDER_ENDPOINT] ?: "" }

    val providerModel: Flow<String> =
        context.dataStore.data.map { it[Keys.PROVIDER_MODEL] ?: "" }

    val heavyTierEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.HEAVY_TIER_ENABLED] ?: false }

    /** False until the first-run wizard has been completed or explicitly skipped. */
    val setupComplete: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SETUP_COMPLETE] ?: false }

    /**
     * Decoder threads. The single biggest CPU knob in the app; more threads finish a
     * sentence sooner but cost battery nobody is waiting to spend.
     */
    val asrThreads: Flow<Int> =
        context.dataStore.data.map { it[Keys.ASR_THREADS] ?: DEFAULT_ASR_THREADS }

    /**
     * Speech probability above which the VAD opens a segment.
     *
     * The default is deliberately low. Waking the decoder for background noise costs some
     * battery and a line of nonsense that can be ignored; missing a conversation costs the
     * thing this app is for. Adjustable in Settings, with a live readout of what the
     * detector is actually scoring, because the right number depends on the room.
     */
    val vadThreshold: Flow<Float> =
        context.dataStore.data.map { it[Keys.VAD_THRESHOLD] ?: DEFAULT_VAD_THRESHOLD }

    /**
     * Packages the lockdown step suspended. Persisted rather than recomputed so Undo
     * restores exactly what was changed and nothing else.
     */
    val suspendedPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.SUSPENDED_PACKAGES] ?: emptySet() }

    /** Whether the user agreed to download models over a metered connection. */
    val allowMeteredDownloads: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ALLOW_METERED] ?: false }

    /** Whether the correction pass runs behind recording at all. */
    val correctionEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CORRECTION_ENABLED] ?: true }

    /** Minutes between correction batches on battery. */
    val correctionIntervalMin: Flow<Int> =
        context.dataStore.data.map { it[Keys.CORRECTION_INTERVAL] ?: DEFAULT_CORRECTION_INTERVAL_MIN }

    /** Minutes between correction batches while charging, when the energy is free. */
    val correctionIntervalChargingMin: Flow<Int> =
        context.dataStore.data.map {
            it[Keys.CORRECTION_INTERVAL_CHARGING] ?: DEFAULT_CORRECTION_INTERVAL_CHARGING_MIN
        }

    /** The overnight whole-day re-run. */
    val endOfDayEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.END_OF_DAY_ENABLED] ?: true }

    /**
     * [ModelChoice.LOCAL] (default) or [ModelChoice.CLOUD]. Cloud is only honoured while the
     * heavy tier is switched on, since that switch is the consent to send text anywhere.
     */
    val correctionEngine: Flow<String> =
        context.dataStore.data.map { it[Keys.CORRECTION_ENGINE] ?: ModelChoice.LOCAL }

    /** Which local model corrects: [ModelChoice.AUTO] or a model file name. */
    val correctionModel: Flow<String> =
        context.dataStore.data.map { it[Keys.CORRECTION_MODEL] ?: ModelChoice.AUTO }

    /** Which local model answers questions: [ModelChoice.AUTO], a file name, or cloud. */
    val askModel: Flow<String> =
        context.dataStore.data.map { it[Keys.ASK_MODEL] ?: ModelChoice.AUTO }

    /** [ExportDefaults] values. */
    val exportContent: Flow<String> =
        context.dataStore.data.map { it[Keys.EXPORT_CONTENT] ?: ExportDefaults.CONTENT_CORRECTED }

    val exportFormat: Flow<String> =
        context.dataStore.data.map { it[Keys.EXPORT_FORMAT] ?: ExportDefaults.FORMAT_MARKDOWN }

    suspend fun setCorrectionEnabled(enabled: Boolean) = edit { it[Keys.CORRECTION_ENABLED] = enabled }

    suspend fun setCorrectionIntervals(batteryMin: Int, chargingMin: Int) = edit {
        it[Keys.CORRECTION_INTERVAL] = batteryMin.coerceIn(1, 240)
        it[Keys.CORRECTION_INTERVAL_CHARGING] = chargingMin.coerceIn(1, 240)
    }

    suspend fun setEndOfDayEnabled(enabled: Boolean) = edit { it[Keys.END_OF_DAY_ENABLED] = enabled }

    suspend fun setCorrectionEngine(engine: String) = edit { it[Keys.CORRECTION_ENGINE] = engine }

    suspend fun setCorrectionModel(choice: String) = edit { it[Keys.CORRECTION_MODEL] = choice }

    suspend fun setAskModel(choice: String) = edit { it[Keys.ASK_MODEL] = choice }

    suspend fun setExportDefaults(content: String, format: String) = edit {
        it[Keys.EXPORT_CONTENT] = content
        it[Keys.EXPORT_FORMAT] = format
    }

    suspend fun triggerKeywordsNow(): Set<String> = triggerKeywords.first()

    suspend fun setTriggerKeywords(keywords: Set<String>) = edit {
        it[Keys.TRIGGERS] = keywords.map(String::lowercase).toSet()
    }

    suspend fun setRecordingEnabled(enabled: Boolean) = edit { it[Keys.RECORDING_ENABLED] = enabled }

    suspend fun setProvider(name: String, endpoint: String, model: String) = edit {
        it[Keys.ACTIVE_PROVIDER] = name
        it[Keys.PROVIDER_ENDPOINT] = endpoint
        it[Keys.PROVIDER_MODEL] = model
    }

    suspend fun setHeavyTierEnabled(enabled: Boolean) = edit { it[Keys.HEAVY_TIER_ENABLED] = enabled }

    suspend fun setSetupComplete(complete: Boolean) = edit { it[Keys.SETUP_COMPLETE] = complete }

    suspend fun setAllowMeteredDownloads(allow: Boolean) = edit { it[Keys.ALLOW_METERED] = allow }

    suspend fun setSuspendedPackages(packages: Set<String>) = edit {
        it[Keys.SUSPENDED_PACKAGES] = packages
    }

    suspend fun setAsrThreads(threads: Int) = edit { it[Keys.ASR_THREADS] = threads.coerceIn(1, 8) }

    /**
     * When the last AI pass ran, how long it took, and how much it did.
     *
     * Persisted rather than held in memory: the recorder's process is restarted often
     * enough that an in-memory figure would usually read "never", which is exactly the
     * question this is meant to answer.
     */
    val lastCorrectionRun: Flow<CorrectionRunRecord?> =
        context.dataStore.data.map { prefs ->
            val ts = prefs[Keys.LAST_RUN_TS] ?: return@map null
            CorrectionRunRecord(
                atTs = ts,
                durationMs = prefs[Keys.LAST_RUN_MS] ?: 0L,
                lines = prefs[Keys.LAST_RUN_LINES] ?: 0,
            )
        }

    /**
     * Whether times are shown on a 24-hour clock. Twelve-hour by default, which is what the
     * phone this was built for uses.
     */
    val use24HourClock: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.USE_24_HOUR] ?: false }

    suspend fun setUse24HourClock(use24: Boolean) = edit { it[Keys.USE_24_HOUR] = use24 }

    suspend fun recordCorrectionRun(durationMs: Long, lines: Int) = edit {
        it[Keys.LAST_RUN_TS] = System.currentTimeMillis()
        it[Keys.LAST_RUN_MS] = durationMs
        it[Keys.LAST_RUN_LINES] = lines
    }

    // --- the export screen's settings, which are the next export's defaults ---------------

    val exportRange: Flow<String> = context.dataStore.data.map { it[Keys.EXPORT_RANGE] ?: "today" }
    val exportCustomFrom: Flow<Int> = context.dataStore.data.map { it[Keys.EXPORT_FROM_DAY] ?: 0 }
    val exportCustomTo: Flow<Int> = context.dataStore.data.map { it[Keys.EXPORT_TO_DAY] ?: 0 }

    /** -1 means no time-of-day window, which is the default. */
    val exportWindowStart: Flow<Int> = context.dataStore.data.map { it[Keys.EXPORT_WINDOW_START] ?: -1 }
    val exportWindowEnd: Flow<Int> = context.dataStore.data.map { it[Keys.EXPORT_WINDOW_END] ?: -1 }
    val exportInclude: Flow<String> = context.dataStore.data.map { it[Keys.EXPORT_INCLUDE] ?: "" }
    val exportExclude: Flow<String> = context.dataStore.data.map { it[Keys.EXPORT_EXCLUDE] ?: "" }
    val exportIncludeAll: Flow<Boolean> = context.dataStore.data.map { it[Keys.EXPORT_INCLUDE_ALL] ?: false }
    val exportGrouping: Flow<String> = context.dataStore.data.map { it[Keys.EXPORT_GROUPING] ?: "day" }
    val exportDestination: Flow<String> = context.dataStore.data.map { it[Keys.EXPORT_DESTINATION] ?: ExportDestinations.SHARE }

    suspend fun saveExport(
        range: String,
        customFromDay: Int,
        customToDay: Int,
        windowStart: Int,
        windowEnd: Int,
        include: String,
        exclude: String,
        includeAll: Boolean,
        content: String,
        format: String,
        grouping: String,
        destination: String,
    ) = edit {
        it[Keys.EXPORT_RANGE] = range
        it[Keys.EXPORT_FROM_DAY] = customFromDay
        it[Keys.EXPORT_TO_DAY] = customToDay
        it[Keys.EXPORT_WINDOW_START] = windowStart
        it[Keys.EXPORT_WINDOW_END] = windowEnd
        it[Keys.EXPORT_INCLUDE] = include
        it[Keys.EXPORT_EXCLUDE] = exclude
        it[Keys.EXPORT_INCLUDE_ALL] = includeAll
        it[Keys.EXPORT_CONTENT] = content
        it[Keys.EXPORT_FORMAT] = format
        it[Keys.EXPORT_GROUPING] = grouping
        it[Keys.EXPORT_DESTINATION] = destination
    }

    /** Back to the shipped defaults, for the Reset button. */
    suspend fun resetExport() = edit {
        listOf(
            Keys.EXPORT_RANGE, Keys.EXPORT_FROM_DAY, Keys.EXPORT_TO_DAY,
            Keys.EXPORT_WINDOW_START, Keys.EXPORT_WINDOW_END,
            Keys.EXPORT_INCLUDE, Keys.EXPORT_EXCLUDE, Keys.EXPORT_INCLUDE_ALL,
            Keys.EXPORT_CONTENT, Keys.EXPORT_FORMAT, Keys.EXPORT_GROUPING,
            Keys.EXPORT_DESTINATION,
        ).forEach { key -> it.remove(key) }
    }

    suspend fun setVadThreshold(threshold: Float) = edit {
        it[Keys.VAD_THRESHOLD] = threshold.coerceIn(0.05f, 0.95f)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private object Keys {
        val TRIGGERS: Preferences.Key<Set<String>> = stringSetPreferencesKey("trigger_keywords")
        val RECORDING_ENABLED = booleanPreferencesKey("recording_enabled")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val PROVIDER_ENDPOINT = stringPreferencesKey("provider_endpoint")
        val PROVIDER_MODEL = stringPreferencesKey("provider_model")
        val HEAVY_TIER_ENABLED = booleanPreferencesKey("heavy_tier_enabled")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val ALLOW_METERED = booleanPreferencesKey("allow_metered_downloads")
        val SUSPENDED_PACKAGES: Preferences.Key<Set<String>> =
            stringSetPreferencesKey("suspended_packages")
        val ASR_THREADS = intPreferencesKey("asr_threads")
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val USE_24_HOUR = booleanPreferencesKey("use_24_hour_clock")
        val LAST_RUN_TS = longPreferencesKey("last_correction_run_ts")
        val LAST_RUN_MS = longPreferencesKey("last_correction_run_ms")
        val LAST_RUN_LINES = intPreferencesKey("last_correction_run_lines")
        val CORRECTION_ENABLED = booleanPreferencesKey("correction_enabled")
        val CORRECTION_INTERVAL = intPreferencesKey("correction_interval_min")
        val CORRECTION_INTERVAL_CHARGING = intPreferencesKey("correction_interval_charging_min")
        val END_OF_DAY_ENABLED = booleanPreferencesKey("end_of_day_enabled")
        val CORRECTION_ENGINE = stringPreferencesKey("correction_engine")
        val CORRECTION_MODEL = stringPreferencesKey("correction_model")
        val ASK_MODEL = stringPreferencesKey("ask_model")
        val EXPORT_CONTENT = stringPreferencesKey("export_content")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val EXPORT_RANGE = stringPreferencesKey("export_range")
        val EXPORT_FROM_DAY = intPreferencesKey("export_from_day")
        val EXPORT_TO_DAY = intPreferencesKey("export_to_day")
        val EXPORT_WINDOW_START = intPreferencesKey("export_window_start")
        val EXPORT_WINDOW_END = intPreferencesKey("export_window_end")
        val EXPORT_INCLUDE = stringPreferencesKey("export_include")
        val EXPORT_EXCLUDE = stringPreferencesKey("export_exclude")
        val EXPORT_INCLUDE_ALL = booleanPreferencesKey("export_include_all")
        val EXPORT_GROUPING = stringPreferencesKey("export_grouping")
        val EXPORT_DESTINATION = stringPreferencesKey("export_destination")
    }

    companion object {
        const val DEFAULT_ASR_THREADS = 2
        /** Biased toward over-capture. See [vadThreshold]. */
        const val DEFAULT_VAD_THRESHOLD = 0.3f

        /**
         * On battery the correction model is loaded at most this often. Each batch is a model
         * load plus a few seconds of full CPU, so this is the main battery knob for 2.1.
         */
        const val DEFAULT_CORRECTION_INTERVAL_MIN = 15
        const val DEFAULT_CORRECTION_INTERVAL_CHARGING_MIN = 3

        val DEFAULT_TRIGGERS = setOf(
            "business idea",
            "remind me",
            "follow up",
            "email this",
            "meeting",
        )
    }
}

/** Values for the model-choice settings. Anything else stored there is a model file name. */
object ModelChoice {
    /** Let the app pick from RAM tier and free memory. */
    const val AUTO = "auto"
    const val LOCAL = "local"

    /** The configured cloud provider, only while the heavy tier is on. */
    const val CLOUD = "cloud"
}

object ExportDefaults {
    const val CONTENT_ORIGINAL = "original"
    const val CONTENT_CORRECTED = "corrected"
    const val CONTENT_BOTH = "both"
    const val FORMAT_MARKDOWN = "markdown"
    const val FORMAT_TEXT = "text"
    const val FORMAT_CSV = "csv"
    const val FORMAT_JSONL = "jsonl"

    val formats = listOf(FORMAT_MARKDOWN, FORMAT_TEXT, FORMAT_CSV, FORMAT_JSONL)
    val contents = listOf(CONTENT_CORRECTED, CONTENT_ORIGINAL, CONTENT_BOTH)

    fun formatLabel(value: String): String = when (value) {
        FORMAT_MARKDOWN -> "Markdown"
        FORMAT_TEXT -> "Plain text"
        FORMAT_CSV -> "CSV"
        else -> "JSON Lines"
    }

    fun contentLabel(value: String): String = when (value) {
        CONTENT_ORIGINAL -> "Original"
        CONTENT_BOTH -> "Both"
        else -> "Corrected"
    }
}

/** Where an export goes. Stored, so the last choice is the next default. */
object ExportDestinations {
    const val SHARE = "share"
    const val DOWNLOADS = "downloads"
    const val CLIPBOARD = "clipboard"

    val all = listOf(SHARE, DOWNLOADS, CLIPBOARD)

    fun label(value: String): String = when (value) {
        DOWNLOADS -> "Save to Downloads"
        CLIPBOARD -> "Copy to clipboard"
        else -> "Share…"
    }
}

/** The last automatic or on-demand AI pass, for the status line in Settings. */
data class CorrectionRunRecord(
    val atTs: Long,
    val durationMs: Long,
    val lines: Int,
)
