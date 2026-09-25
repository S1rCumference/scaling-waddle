package com.recorder.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recorder.app.ServiceLocator
import com.recorder.app.models.InstallProgress
import com.recorder.app.models.ModelCatalog
import com.recorder.app.models.ModelDownloadService
import com.recorder.app.models.ModelEntry
import com.recorder.app.models.ModelInstallStore
import com.recorder.app.models.ModelInstaller
import com.recorder.app.models.ModelRole
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.local.DeviceCapabilities
import com.recorder.core.llm.local.LocalModelRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    /** The catalogue plus what the user has ticked. Progress comes from [ModelInstallStore]. */
    private data class Plan(val entry: ModelEntry, val selected: Boolean)

    private val _plan = MutableStateFlow<List<Plan>>(emptyList())

    /**
     * What the models step renders. Install state is read from the store rather than held
     * here: it used to be kept in this ViewModel and then overwritten by [reload], which
     * threw away the reason a download had failed and left the model looking merely
     * un-ticked. Now a failure stays visible until it is retried or succeeds.
     */
    val models: StateFlow<List<ModelUiState>> =
        combine(_plan, ModelInstallStore.states) { plan, states ->
            val context = getApplication<Application>()
            plan.map { (entry, selected) ->
                val installed = entry.isInstalled(context)
                ModelUiState(
                    entry = entry,
                    selected = selected,
                    installed = installed,
                    progress = when {
                        installed -> InstallProgress.Done
                        else -> states[entry.id] ?: InstallProgress.Idle
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True while the download service is working, wherever it was started from. */
    val installing: StateFlow<Boolean> = ModelInstallStore.running

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val ramGb = DeviceCapabilities.totalRamGb(application)

    /** False on a phone too small to run the correction model; it still records. */
    val enoughRam = DeviceCapabilities.enoughRamForAModel(application)

    init {
        reload()
    }

    /**
     * Re-reads the catalogue and reconciles the store with what is actually on disk. Safe to
     * call at any time: it does not touch in-flight or failed states.
     */
    fun reload() {
        val context = getApplication<Application>()
        val all = runCatching { ModelCatalog.load(context) }
            .onFailure { _message.value = "Could not read the model list: ${it.message}" }
            .getOrDefault(emptyList())

        val recommended = ModelCatalog.recommended(all).map { it.id }.toSet()
        val previous = _plan.value.associate { it.entry.id to it.selected }
        _plan.value = all.map { entry ->
            Plan(entry, previous[entry.id] ?: (entry.id in recommended))
        }
        ModelInstallStore.reconcile(context, all)
    }

    fun toggle(id: String) {
        var nowSelected = false
        _plan.update { list ->
            list.map { plan ->
                // Required models are not optional; the app produces no text without them.
                if (plan.entry.id == id && !plan.entry.required) {
                    nowSelected = !plan.selected
                    plan.copy(selected = nowSelected)
                } else {
                    plan
                }
            }
        }
        // Ticking a box while the queue is already running adds to it there and then, so
        // "select them all and it downloads them" means exactly that.
        if (nowSelected && ModelInstallStore.running.value) {
            ModelDownloadService.start(getApplication(), listOf(id))
        }
    }

    /** Ticks everything in the catalogue and starts downloading it. */
    fun selectAllAndDownload() {
        _plan.update { list -> list.map { it.copy(selected = true) } }
        startInstall()
    }

    /** Everything this app needs, installed or not: the one honest total. */
    fun everythingBytes(): Long = ModelCatalog.everythingBytes(models.value.map { it.entry })

    /** Total download size of everything selected and not yet installed. */
    fun pendingBytes(): Long =
        models.value.filter { it.selected && !it.installed }.sumOf { it.entry.sizeBytes }

    fun freeBytes(): Long = installer.freeBytes()

    fun onUnmeteredNetwork(): Boolean = installer.onUnmeteredNetwork()

    /**
     * Hands the selected models to [ModelDownloadService]. The work deliberately does not run
     * in this ViewModel's scope: a multi-gigabyte download has to survive the wizard being
     * closed and the screen turning off, which is exactly what used to kill it.
     */
    fun startInstall() {
        val context = getApplication<Application>()
        val queue = models.value.filter { it.selected && !it.installed }
        if (queue.isEmpty()) {
            _message.value = "Everything selected is already installed."
            return
        }
        // Already running: the service queues the extras rather than starting a second pass.
        installer.spaceProblem(queue.map { it.entry })?.let { problem ->
            _message.value = problem
            return
        }
        if (!installer.hasNetwork()) {
            _message.value = "No network connection. Connect to Wi-Fi and try again."
            return
        }
        ModelDownloadService.start(context, queue.map { it.entry.id })
    }

    fun cancelInstall() {
        ModelDownloadService.stop(getApplication())
        _message.value = "Download stopped. Part-downloaded files are kept and will resume."
    }

    /** Retries one model, and only that one. */
    fun retry(id: String) {
        ModelDownloadService.start(getApplication(), listOf(id))
    }

    /** Retries every model whose last attempt failed in a way worth retrying. */
    fun retryFailed() {
        val ids = models.value
            .filter { (it.progress as? InstallProgress.Failed)?.retryable == true }
            .map { it.entry.id }
        if (ids.isEmpty()) {
            _message.value = "Nothing to retry that a retry would fix."
            return
        }
        ModelDownloadService.start(getApplication(), ids)
    }

    fun remove(id: String) = viewModelScope.launch {
        models.value.firstOrNull { it.entry.id == id }?.let { installer.uninstall(it.entry) }
        reload()
    }

    fun allowMetered() = viewModelScope.launch { settings.setAllowMeteredDownloads(true) }

    /** True when everything required is installed, which is what the wizard gates on. */
    fun requiredInstalled(): Boolean =
        models.value.filter { it.entry.required }.all { it.installed }

    fun transcriptionReady(): Boolean {
        val context = getApplication<Application>()
        return AsrEngineFactory.sherpaBundled && AsrEngineFactory.modelsInstalled(context)
    }

    fun localChatReady(): Boolean =
        LocalModelRuntime.available &&
            models.value.any { it.entry.role == ModelRole.SMALL_CHAT && it.installed }

    /** Why local chat is unavailable, or null when it is ready. */
    fun localChatBlocker(): String? = when {
        !LocalModelRuntime.available -> LocalModelRuntime.unavailableReason
        models.value.none { it.entry.role == ModelRole.SMALL_CHAT } ->
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
