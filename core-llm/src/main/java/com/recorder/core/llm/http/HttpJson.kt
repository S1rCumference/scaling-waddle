package com.recorder.core.llm.http

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Shared JSON-over-HTTPS plumbing for the cloud providers.
 *
 * Only the heavy tier is ever allowed to reach the network, and it only ever sends
 * transcript *text* — raw audio never leaves the device, by construction: no code path
 * outside core-audio/core-asr can even see the PCM buffers.
 */
object HttpJson {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Performs a POST and returns the parsed body, or throws with the server's message. */
    fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${text.take(400)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
