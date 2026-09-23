package com.recorder.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recorder.app.ServiceLocator
import com.recorder.app.models.InstallProgress
import com.recorder.app.models.ModelCatalog
import com.recorder.app.models.ModelEntry
import com.recorder.app.models.ModelInstaller
import com.recorder.app.models.ModelRole
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.local.DeviceCapabilities
import com.recorder.core.llm.local.LocalModelRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { WELCOME, PERMISSIONS, MODELS, COVER_SCREEN, TEST, DONE }

data class ModelUiState(
    val entry: ModelEntry,
    val selected: Boolean,
    val installed: Boolean,
    val progress: InstallProgress,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val installer = ModelInstaller(application)
    private val settings = ServiceLocator.settings

    private val _step = MutableStateFlow(SetupStep.WELCOME)
    val step: StateFlow<SetupStep> = _step.asStateFlow()

    private val _models = MutableStateFlow<List<ModelUiState>>(emptyList())
    val models: StateFlow<List<ModelUiState>> = _models.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var installJob: Job? = null

    val ramTier = DeviceCapabilities.ramTier(application)
    val ramGb = DeviceCapabilities.totalRamGb(application)

    init {
        reload()
    }

    fun reload() {
        val context = getApplication<Application>()
        val all = runCatching { ModelCatalog.load(context) }
            .onFailure { _message.value = "Could not read the model list: ${it.message}" }
            .getOrDefault(emptyList())

        val recommended = ModelCatalog.recommended(all, ramTier).map { it.id }.toSet()
        _models.value = all.map { entry ->
            ModelUiState(
                entry = entry,
                selected = entry.id in recommended,
                installed = entry.isInstalled(context),
                progress = if (entry.isInstalled(context)) InstallProgress.Done else InstallProgress.Pending,
            )
        }
    }

    fun toggle(id: String) {
        _models.update { list ->
            list.map { state ->
                // Required models are not optional; the app produces no text without them.
                if (state.entry.id == id && !state.entry.required) {
                    state.copy(selected = !state.selected)
                } else {
                    state
                }
            }
        }
    }

    /** Total download size of everything selected and not yet installed. */
    fun pendingBytes(): Long =
        _models.value.filter { it.selected && !it.installed }.sumOf { it.entry.sizeBytes }

    fun freeBytes(): Long = installer.freeBytes()

    fun onUnmeteredNetwork(): Boolean = installer.onUnmeteredNetwork()

    fun startInstall() {
        if (_installing.value) return
        _installing.value = true
        installJob = viewModelScope.launch {
            val allowMetered = settings.allowMeteredDownloads.first()
            val queue = _models.value.filter { it.selected && !it.installed }

            for (target in queue) {
                installer.install(target.entry, allowMetered) { progress ->
                    _models.update { list ->
                        list.map { if (it.entry.id == target.entry.id) it.copy(progress = progress) else it }
                    }
                }
            }
            reload()
            _installing.value = false
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        _installing.value = false
        _message.value = "Download stopped. Partly downloaded files are kept and will resume."
    }

    fun retry(id: String) {
        _models.update { list ->
            list.map { if (it.entry.id == id) it.copy(progress = InstallProgress.Pending) else it }
        }
        startInstall()
    }

    fun remove(id: String) = viewModelScope.launch {
        _models.value.firstOrNull { it.entry.id == id }?.let { installer.uninstall(it.entry) }
        reload()
    }

    fun allowMetered() = viewModelScope.launch { settings.setAllowMeteredDownloads(true) }

    /** True when everything required is installed, which is what the wizard gates on. */
    fun requiredInstalled(): Boolean =
        _models.value.filter { it.entry.required }.all { it.installed }

    fun transcriptionReady(): Boolean {
        val context = getApplication<Application>()
        return AsrEngineFactory.sherpaBundled && AsrEngineFactory.modelsInstalled(context)
    }

    fun localChatReady(): Boolean =
        LocalModelRuntime.available &&
            _models.value.any { it.entry.role == ModelRole.SMALL_CHAT && it.installed }

    /** Why local chat is unavailable, or null when it is ready. */
    fun localChatBlocker(): String? = when {
        !LocalModelRuntime.available -> LocalModelRuntime.unavailableReason
        _models.value.none { it.entry.role == ModelRole.SMALL_CHAT } ->
            "No chat model is listed in this build's catalogue yet"

        !localChatReady() -> "No chat model installed"
        else -> null
    }

    fun next() {
        _step.value = when (_step.value) {
            SetupStep.WELCOME -> SetupStep.PERMISSIONS
            SetupStep.PERMISSIONS -> SetupStep.MODELS
            SetupStep.MODELS -> SetupStep.COVER_SCREEN
            SetupStep.COVER_SCREEN -> SetupStep.TEST
            SetupStep.TEST -> SetupStep.DONE
            SetupStep.DONE -> SetupStep.DONE
        }
    }

    fun back() {
        _step.value = when (_step.value) {
            SetupStep.DONE, SetupStep.TEST -> SetupStep.COVER_SCREEN
            SetupStep.COVER_SCREEN -> SetupStep.MODELS
            SetupStep.MODELS -> SetupStep.PERMISSIONS
            SetupStep.PERMISSIONS, SetupStep.WELCOME -> SetupStep.WELCOME
        }
    }

    fun finish() = viewModelScope.launch { settings.setSetupComplete(true) }

    fun clearMessage() {
        _message.value = null
    }
}
