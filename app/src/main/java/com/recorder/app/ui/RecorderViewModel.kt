package com.recorder.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recorder.app.ServiceLocator
import com.recorder.app.service.RecordingService
import com.recorder.app.work.HeavySyncScheduler
import com.recorder.core.connectors.RefreshTokenGoogleAuth
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.TranscriptAssistant
import com.recorder.core.llm.local.DeviceCapabilities
import com.recorder.core.llm.local.LocalModelSelector
import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.Folder
import com.recorder.core.storage.PendingAction
import com.recorder.core.storage.PendingActionStatus
import com.recorder.core.storage.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatTurn(val question: String, val answer: String, val pending: Boolean = false)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ServiceLocator.database
    private val settings = ServiceLocator.settings

    val transcripts: StateFlow<List<TranscriptSegment>> =
        db.transcripts().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val flagged: StateFlow<List<FlaggedItem>> =
        db.flagged().active().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> =
        db.folders().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val drafts: StateFlow<List<PendingAction>> =
        db.pendingActions().byStatus(PendingActionStatus.DRAFT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recorderState: StateFlow<RecordingService.RecorderState> = RecordingService.state

    val triggerKeywords: StateFlow<Set<String>> =
        settings.triggerKeywords.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val activeProvider: StateFlow<String> =
        settings.activeProvider.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val heavyTierEnabled: StateFlow<Boolean> =
        settings.heavyTierEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _chat = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chat: StateFlow<List<ChatTurn>> = _chat.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Answers questions with the on-device model only — this must work with the radio off. */
    fun ask(question: String) {
        if (question.isBlank()) return
        _chat.value = _chat.value + ChatTurn(question, "", pending = true)
        viewModelScope.launch {
            val assistant = TranscriptAssistant(
                provider = ServiceLocator.providers.onDeviceSmallModel(),
                rag = ServiceLocator.rag,
            )
            val answer = assistant.ask(question)
            _chat.value = _chat.value.dropLast(1) + ChatTurn(question, answer.text)
        }
    }

    fun clearChat() {
        _chat.value = emptyList()
    }

    fun setRecording(enabled: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch { settings.setRecordingEnabled(enabled) }
        if (enabled) RecordingService.start(context) else RecordingService.stop(context)
    }

    fun dismissFlag(id: Long) = viewModelScope.launch { db.flagged().dismiss(id) }

    fun saveTriggers(keywords: Set<String>) = viewModelScope.launch {
        settings.setTriggerKeywords(keywords)
    }

    fun saveProvider(id: String, endpoint: String, model: String, apiKey: String) =
        viewModelScope.launch {
            settings.setProvider(id, endpoint, model)
            if (apiKey.isNotBlank()) ServiceLocator.apiKeys.setKey(id, apiKey)
            _status.value = "Saved ${ProviderIds.label(id)}"
        }

    fun setHeavyTierEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setHeavyTierEnabled(enabled)
    }

    fun saveGoogleCredentials(clientId: String, clientSecret: String, refreshToken: String) {
        val keys = ServiceLocator.apiKeys
        if (clientId.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_CLIENT_ID, clientId)
        if (clientSecret.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_CLIENT_SECRET, clientSecret)
        if (refreshToken.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_REFRESH_TOKEN, refreshToken)
        _status.value = "Google credentials stored"
    }

    fun syncNow() {
        HeavySyncScheduler.runNow(getApplication())
        _status.value = "Heavy sync queued"
    }

    fun approveDraft(id: Long) = viewModelScope.launch {
        val result = ServiceLocator.connectorGateway.approve(id)
        _status.value = result.fold(
            onSuccess = { "Sent: $it" },
            onFailure = { "Failed: ${it.message}" },
        )
    }

    fun rejectDraft(id: Long) = viewModelScope.launch {
        ServiceLocator.connectorGateway.reject(id)
    }

    fun clearStatus() {
        _status.value = null
    }

    suspend fun providerSettings(): Triple<String, String, String> = Triple(
        settings.activeProvider.first(),
        settings.providerEndpoint.first(),
        settings.providerModel.first(),
    )

    /** Reported in settings so it is obvious why a local heavy model is or isn't offered. */
    fun deviceSummary(): String {
        val context = getApplication<Application>()
        val selector = LocalModelSelector(context)
        val tier = DeviceCapabilities.ramTier(context)
        val ram = "%.1f".format(DeviceCapabilities.totalRamGb(context))
        val heavy = selector.heavyUnavailableReason() ?: "Available: ${selector.heavyModelCandidate()?.label}"
        return "RAM ${ram} GB · tier $tier\nLocal heavy model — $heavy"
    }
}
