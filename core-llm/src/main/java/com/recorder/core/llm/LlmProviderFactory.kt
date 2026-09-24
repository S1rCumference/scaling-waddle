package com.recorder.core.llm

import android.content.Context
import com.recorder.core.llm.local.LocalModelChoice
import com.recorder.core.llm.local.LocalModelProvider
import com.recorder.core.llm.providers.ClaudeProvider
import com.recorder.core.llm.providers.GeminiProvider
import com.recorder.core.llm.providers.OpenAiCompatibleProvider
import com.recorder.core.storage.ModelChoice
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

    /**
     * The always-available small model used by the cover-screen chat. Never touches the
     * network. Memoised because callers ask per question, and a fresh provider per question
     * used to mean a fresh model load per question.
     */
    private val smallModel: LocalModelProvider by lazy { LocalModelProvider(context, heavy = false) }

    fun onDeviceSmallModel(): LocalModelProvider = smallModel

    private val localByChoice = mutableMapOf<Pair<LocalModelChoice, Int>, LocalModelProvider>()

    /** One provider per choice, so each keeps a single idle-unload timer. */
    @Synchronized
    fun local(choice: LocalModelChoice, maxTokens: Int = 512): LocalModelProvider =
        if (choice == LocalModelChoice.Small && maxTokens == 512) smallModel
        else localByChoice.getOrPut(choice to maxTokens) {
            LocalModelProvider(context, choice, ProviderIds.LOCAL_ON_DEVICE, maxTokens)
        }

    /**
     * The cloud provider, but only while the heavy tier is switched on — that switch is the
     * user's consent for transcript text to leave the phone at all. Null otherwise.
     */
    suspend fun consentedCloudProvider(): LlmProvider? {
        if (!settings.heavyTierEnabled.first()) return null
        val id = settings.activeProvider.first()
        if (id.isBlank() || id == ProviderIds.LOCAL_ON_DEVICE) return null
        return activeProvider()
    }

    /** What answers questions and runs the Ask actions, per Settings. */
    suspend fun askProvider(): Chosen {
        val choice = settings.askModel.first()
        if (choice == ModelChoice.CLOUD) {
            consentedCloudProvider()?.let { return Chosen(it, cloudLabel(), cloud = true) }
        }
        val local = local(localChoice(choice, LocalModelChoice.Small), maxTokens = 768)
        return Chosen(local, localLabel(local), cloud = false)
    }

    /**
     * What corrects transcripts. Local by default — the strongest model the phone can hold
     * beside recording. The cloud is used only when chosen *and* consented to.
     */
    suspend fun correctionProvider(): Chosen {
        if (settings.correctionEngine.first() == ModelChoice.CLOUD) {
            consentedCloudProvider()?.let { return Chosen(it, cloudLabel(), cloud = true) }
        }
        val choice = localChoice(settings.correctionModel.first(), LocalModelChoice.Strongest)
        val local = local(choice, maxTokens = CORRECTION_MAX_TOKENS)
        return Chosen(local, localLabel(local), cloud = false)
    }

    private fun localChoice(stored: String, auto: LocalModelChoice): LocalModelChoice = when (stored) {
        ModelChoice.AUTO, ModelChoice.LOCAL, ModelChoice.CLOUD, "" -> auto
        else -> LocalModelChoice.File(stored)
    }

    private fun localLabel(provider: LocalModelProvider): String =
        (provider.plannedModelLabel() ?: "on-device model") + " (on this phone)"

    private suspend fun cloudLabel(): String {
        val id = settings.activeProvider.first()
        val model = settings.providerModel.first().ifBlank { ProviderIds.defaultModel(id) }
        return "${ProviderIds.label(id)} · $model (cloud)"
    }

    /** A provider plus the name to show next to what it produced. */
    data class Chosen(val provider: LlmProvider, val label: String, val cloud: Boolean)

    companion object {
        /** Enough for a correction window of ~25 segments echoed back line by line. */
        const val CORRECTION_MAX_TOKENS = 1_600
    }
}
