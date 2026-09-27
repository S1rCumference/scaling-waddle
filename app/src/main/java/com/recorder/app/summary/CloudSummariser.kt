package com.recorder.app.summary

import com.recorder.core.llm.ChatMessage
import com.recorder.core.llm.Role
import com.recorder.core.llm.SummaryBudget
import com.recorder.core.llm.SummaryLevel
import com.recorder.core.llm.SummaryPrompt
import com.recorder.core.llm.SummaryResult
import com.recorder.core.llm.cloud.CloudProvider
import com.recorder.core.storage.AiPasses
import com.recorder.core.storage.Diagnostics
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends one group's text to a hosted model and reads a summary back.
 *
 * One client for every provider, because Google's OpenAI-compatible endpoint, Groq's and Hugging
 * Face's router all take the same request: `POST <base>/chat/completions`, an
 * `Authorization: Bearer` header, and a `{model, messages, max_tokens}` body. The base URL is
 * configuration rather than code, so a provider none of the presets name works too.
 *
 * **What leaves the phone.** Transcript text, and nothing else. No audio — recognition still runs
 * on the device and always will. No identifiers, no device information, no timestamps beyond the
 * span named in the prompt. It goes only when there is a key, and only to the URL in Settings.
 *
 * **Rate limits are discovered, not assumed.** The free tiers here are published as requests per
 * minute and per day, and the documentation for them was not reachable from the environment this
 * was written in — so rather than hard-code numbers from memory that would silently be wrong,
 * this reads HTTP 429 and its `Retry-After` header and hands the wait back to the caller. A limit
 * the server tells us about is a fact; one this file asserted would be a guess.
 */
class CloudSummariser(
    private val provider: CloudProvider,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(SummaryBudget.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Whether this is configured enough to try at all. */
    val configured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    suspend fun summarise(
        level: SummaryLevel,
        spanInWords: String,
        source: List<String>,
    ): SummaryResult = withContext(Dispatchers.IO) {
        if (!configured) {
            return@withContext SummaryResult.Refused(
                "No API key yet. Settings → Summaries has a field for one and a link to get it.",
            )
        }
        if (source.isEmpty()) return@withContext SummaryResult.Unusable("Nothing to summarise.")

        val budget = SummaryBudget.forLevel(level)
        val messages = listOf(
            ChatMessage(Role.SYSTEM, SummaryPrompt.SYSTEM),
            ChatMessage(Role.USER, SummaryPrompt.build(level, spanInWords, source)),
        )
        val startedAt = System.currentTimeMillis()

        val response = runCatching { post(messages, budget) }.getOrElse { error ->
            // A dropped connection on a phone is normal and temporary. It is not a reason to
            // stop summarising for the day.
            val why = if (error is IOException) {
                "network: ${error.message ?: error.javaClass.simpleName}"
            } else {
                error.message ?: error.javaClass.simpleName
            }
            Diagnostics.w(TAG, "summary request failed: $why")
            return@withContext SummaryResult.Backoff(why, retryAfterMs = null)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        when (val outcome = readBody(response, elapsed, budget)) {
            is SummaryResult.Ok -> {
                Diagnostics.i(
                    TAG,
                    "${level.label} summary \"${outcome.summary.title}\" from ${source.size} " +
                        "source(s) in ${"%.1f".format(elapsed / 1000.0)}s via ${provider.label}",
                )
                outcome
            }

            else -> outcome
        }
    }

    private fun post(messages: List<ChatMessage>, budget: SummaryBudget): okhttp3.Response {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", budget.maxTokens)
            // Low but not zero: a summary should be the same every time for the same text, and a
            // completely greedy decode on a small model tends to loop.
            put("temperature", TEMPERATURE)
            put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(
                            JSONObject()
                                .put("role", message.wireRole)
                                .put("content", message.content),
                        )
                    }
                },
            )
        }.toString()

        val request = Request.Builder()
            .url(provider.chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return http.newCall(request).execute()
    }

    private fun readBody(
        response: okhttp3.Response,
        elapsedMs: Long,
        budget: SummaryBudget,
    ): SummaryResult = response.use {
        val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")

        if (!response.isSuccessful) {
            return classify(response.code, response.header("Retry-After"), text)
        }

        val content = runCatching {
            JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse { error ->
            Diagnostics.w(TAG, "could not read the model's answer: ${error.message}")
            return SummaryResult.Unusable("The provider's answer was not in the expected shape.")
        }

        // The same facts as numbers, for the self-diagnostic report. Tokens come from the
        // provider when it reports them rather than being estimated here.
        val tokens = runCatching {
            JSONObject(text).getJSONObject("usage").getInt("completion_tokens")
        }.getOrDefault(0)
        AiPasses.record(label = budget.label, tokens = tokens, ms = elapsedMs, stoppedBy = null, reloadedBecause = null)

        val summary = SummaryPrompt.parse(content)
        if (summary.isBlank) {
            SummaryResult.Unusable("The model answered nothing usable.")
        } else {
            SummaryResult.Ok(summary)
        }
    }

    /**
     * Turns an HTTP failure into "wait and try again" or "stop asking".
     *
     * The distinction is the whole point: a rate limit means come back later and a bad key means
     * come back never, and a scheduled pass that treats them the same either gives up on a
     * working setup or hammers a broken one every few hours forever.
     */
    private fun classify(code: Int, retryAfter: String?, body: String): SummaryResult {
        val detail = providerMessage(body) ?: "HTTP $code"
        return when {
            // Told to wait, and often told how long. Seconds per the HTTP spec.
            code == 429 -> SummaryResult.Backoff(
                "rate limited: $detail",
                retryAfterMs = retryAfter?.trim()?.toLongOrNull()?.times(1_000),
            )

            // The key is wrong, missing, or not entitled to this model. Retrying cannot fix it.
            code == 401 || code == 403 -> SummaryResult.Refused(
                "the provider rejected the key ($detail). Check it in Settings → Summaries.",
            )

            // A bad model id or a malformed request — also not fixable by waiting.
            code == 400 || code == 404 -> SummaryResult.Refused(
                "the provider refused the request ($detail). Check the model name in Settings.",
            )

            // Out of credit. Refused rather than backed off: it will not come back this month.
            code == 402 -> SummaryResult.Refused("the account is out of credit ($detail).")

            // 5xx and anything unexpected: the provider's problem, so try later.
            else -> SummaryResult.Backoff("$detail", retryAfterMs = null)
        }
    }

    /** The provider's own error sentence, which is almost always more use than the status code. */
    private fun providerMessage(body: String): String? = runCatching {
        JSONObject(body).getJSONObject("error").getString("message").takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val TAG = "CloudSummariser"
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val TEMPERATURE = 0.2
    }
}
