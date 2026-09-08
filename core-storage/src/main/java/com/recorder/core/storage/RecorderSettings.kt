package com.recorder.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    }

    companion object {
        val DEFAULT_TRIGGERS = setOf(
            "business idea",
            "remind me",
            "follow up",
            "email this",
            "meeting",
        )
    }
}
