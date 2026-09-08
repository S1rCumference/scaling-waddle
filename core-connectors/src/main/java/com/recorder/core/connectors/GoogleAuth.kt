package com.recorder.core.connectors

import com.recorder.core.llm.ApiKeyStore
import com.recorder.core.llm.http.HttpJson
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/** Supplies a bearer token for the Google REST APIs. */
interface GoogleAuth {
    val isConfigured: Boolean

    /** Returns a valid access token, refreshing if needed, or null when not connected. */
    suspend fun accessToken(): String?
}

/**
 * OAuth installed-app flow, holding only a refresh token on the device.
 *
 * The one-time consent (which produces the refresh token) happens on a computer via
 * `scripts/google_oauth.sh`; the phone then only ever exchanges the refresh token for
 * short-lived access tokens. That keeps a browser and a Google sign-in library off the
 * phone entirely, which matters on a device that has had most of its Google stack
 * disabled by `provision.sh`.
 */
class RefreshTokenGoogleAuth(private val keys: ApiKeyStore) : GoogleAuth {

    override val isConfigured: Boolean
        get() = keys.hasKey(KEY_REFRESH_TOKEN) && keys.hasKey(KEY_CLIENT_ID)

    private var cachedToken: String? = null
    private var expiresAtMs: Long = 0

    override suspend fun accessToken(): String? {
        if (!isConfigured) return null
        cachedToken?.let { if (System.currentTimeMillis() < expiresAtMs - SKEW_MS) return it }

        val body = FormBody.Builder()
            .add("client_id", keys.key(KEY_CLIENT_ID))
            .add("client_secret", keys.key(KEY_CLIENT_SECRET))
            .add("refresh_token", keys.key(KEY_REFRESH_TOKEN))
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return runCatching {
            HttpJson.client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (!response.isSuccessful) return null
                val token = json.optString("access_token").ifBlank { return null }
                expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
                cachedToken = token
                token
            }
        }.getOrNull()
    }

    companion object {
        const val KEY_CLIENT_ID = "google_client_id"
        const val KEY_CLIENT_SECRET = "google_client_secret"
        const val KEY_REFRESH_TOKEN = "google_refresh_token"

        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SKEW_MS = 60_000L

        /** Scopes the one-time consent must request for all three connectors to work. */
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/drive.readonly",
        )
    }
}
