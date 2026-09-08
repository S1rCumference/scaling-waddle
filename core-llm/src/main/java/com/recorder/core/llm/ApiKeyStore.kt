package com.recorder.core.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provider API keys, held in Keystore-backed encrypted preferences rather than DataStore.
 * Keys are the only secret this app holds; transcripts stay local, so losing this file to
 * a backup or an adb pull must not hand anyone an account.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "provider_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun key(providerId: String): String = prefs.getString(providerId, "").orEmpty()

    fun setKey(providerId: String, value: String) {
        prefs.edit().apply {
            if (value.isBlank()) remove(providerId) else putString(providerId, value)
        }.apply()
    }

    fun hasKey(providerId: String): Boolean = key(providerId).isNotBlank()
}
