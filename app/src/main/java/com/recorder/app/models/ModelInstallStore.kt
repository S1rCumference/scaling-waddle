package com.recorder.app.models

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where one model has got to. Exactly one model is ever [Downloading] at a time. */
sealed interface InstallProgress {

    /** Nothing is happening and nothing is planned. */
    data object Idle : InstallProgress

    /** Accepted into the queue, waiting its turn. Shows as "waiting", never as progress. */
    data object Queued : InstallProgress

    data class Downloading(val bytes: Long, val total: Long) : InstallProgress {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent: Int get() = (fraction * 100).toInt()
    }

    data object Verifying : InstallProgress
    data object Extracting : InstallProgress
    data object Done : InstallProgress
    data class Failed(val reason: String, val retryable: Boolean) : InstallProgress
}

/**
 * The one place per-model install state lives, shared by the download service that writes it
 * and by every screen that reads it.
 *
 * It exists because the state used to live in the wizard's ViewModel, which had two
 * consequences on a real phone. A failure was written into that state and then immediately
 * overwritten by a reload that recomputed "installed or pending" from disk — so a model that
 * failed to download showed up as neither installed nor failed, just blank, with the reason
 * discarded. And the download itself ran in `viewModelScope`, so leaving the wizard (or the
 * screen turning off during a multi-gigabyte download) cancelled it silently. Now the service
 * owns the work and this object owns the state, and neither is tied to a screen being open.
 */
object ModelInstallStore {

    private val _states = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val states: StateFlow<Map<String, InstallProgress>> = _states.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)

    /** The model currently being worked on, if any. */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** True while the download service is working through a queue. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun state(id: String): InstallProgress = _states.value[id] ?: InstallProgress.Idle

    fun enqueue(ids: Collection<String>) = _states.update { current ->
        current + ids.associateWith { InstallProgress.Queued }
    }

    fun set(id: String, progress: InstallProgress) = _states.update { it + (id to progress) }

    fun setActive(id: String?) {
        _activeId.value = id
    }

    fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _activeId.value = null
    }

    /**
     * Drops queued-but-unstarted entries after a stop. Failures are deliberately kept: their
     * reason is the only explanation the user gets, and losing it is the bug this replaced.
     */
    fun clearQueued() = _states.update { current ->
        current.filterValues { it !is InstallProgress.Queued }
    }

    /**
     * Reconciles with disk: anything present becomes [InstallProgress.Done], anything absent
     * that this store thought was done goes back to [InstallProgress.Idle]. Failures, queued
     * entries and in-flight work are left alone.
     */
    fun reconcile(context: Context, entries: List<ModelEntry>) = _states.update { current ->
        val updated = current.toMutableMap()
        entries.forEach { entry ->
            val installed = entry.isInstalled(context)
            val known = current[entry.id]
            when {
                installed -> updated[entry.id] = InstallProgress.Done
                known is InstallProgress.Done -> updated[entry.id] = InstallProgress.Idle
                else -> Unit
            }
        }
        updated
    }
}
