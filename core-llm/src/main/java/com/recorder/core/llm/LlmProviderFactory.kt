package com.recorder.core.llm

import android.content.Context
import com.recorder.core.llm.local.LocalModelProvider
import com.recorder.core.llm.providers.ClaudeProvider
import com.recorder.core.llm.providers.GeminiProvider
import com.recorder.core.llm.providers.OpenAiCompatibleProvider
import com.recorder.core.storage.RecorderSettings
import kotlinx.coroutines.flow.first

/**
 * Builds whichever provider settings currently point at. Adding a backend means adding a
 * branch here and an id in [ProviderIds] — nothing else in the app changes.
 */
class LlmProviderFactory(
    private val context: Context,
    private val settings: RecorderSettings = RecorderSettings(context),
    private val keys: ApiKeyStore = ApiKeyStore(context),
) {

    suspend fun activeProvider(): LlmProvider? {
        val id = settings.activeProvider.first().ifBlank { return null }
        val config = ProviderConfig(
            endpoint = settings.providerEndpoint.first().ifBlank { ProviderIds.defaultEndpoint(id) },
            apiKey = keys.key(id),
            model = settings.providerModel.first().ifBlank { ProviderIds.defaultModel(id) },
        )
        return create(id, config)
    }

    fun create(id: String, config: ProviderConfig): LlmProvider = when (id) {
        ProviderIds.CLAUDE -> ClaudeProvider(config)
        ProviderIds.GEMINI -> GeminiProvider(config)
        ProviderIds.LOCAL_ON_DEVICE -> LocalModelProvider(context, heavy = true)
        // OpenAI and any OpenAI-compatible local server share one adapter.
        else -> OpenAiCompatibleProvider(config, id)
    }

    /** The always-available small model used by the cover-screen chat. Never touches the network. */
    fun onDeviceSmallModel(): LocalModelProvider = LocalModelProvider(context, heavy = false)
}
