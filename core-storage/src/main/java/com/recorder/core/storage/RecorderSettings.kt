package com.recorder.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
     * Speech probability above which the VAD opens a segment. Higher misses quiet speech;
     * lower wakes the decoder for background noise, which is the expensive mistake.
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

    suspend fun setVadThreshold(threshold: Float) = edit {
        it[Keys.VAD_THRESHOLD] = threshold.coerceIn(0.1f, 0.95f)
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
        val CORRECTION_ENABLED = booleanPreferencesKey("correction_enabled")
        val CORRECTION_INTERVAL = intPreferencesKey("correction_interval_min")
        val CORRECTION_INTERVAL_CHARGING = intPreferencesKey("correction_interval_charging_min")
        val END_OF_DAY_ENABLED = booleanPreferencesKey("end_of_day_enabled")
        val CORRECTION_ENGINE = stringPreferencesKey("correction_engine")
        val CORRECTION_MODEL = stringPreferencesKey("correction_model")
        val ASK_MODEL = stringPreferencesKey("ask_model")
        val EXPORT_CONTENT = stringPreferencesKey("export_content")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
    }

    companion object {
        const val DEFAULT_ASR_THREADS = 2
        const val DEFAULT_VAD_THRESHOLD = 0.5f

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
}
