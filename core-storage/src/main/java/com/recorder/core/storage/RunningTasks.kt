package com.recorder.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app is doing right now, and how long each thing took.
 *
 * Two jobs at once. The UI shows a progress bar while anything is running, because work
 * that takes a minute with no sign of life is indistinguishable from work that is not
 * happening — which is exactly how "the correcting doesn't work" and "the correcting takes
 * a long time" look from the outside. And every task writes its duration to [Diagnostics]
 * when it ends, so a slow step can be named from the phone rather than guessed at.
 *
 * Process-wide on purpose: the recorder, the correction loop and the UI live in one process
 * but not one object graph, and all three have work worth showing.
 */
object RunningTasks {

    private const val TAG = "RunningTasks"

    data class Task(
        val id: String,
        val label: String,
        val startedAt: Long,
        /** Optional live detail, e.g. "window 3 of 12". */
        val detail: String? = null,
        /**
         * Stops the work for real, rather than hiding the indicator. Null when the task did
         * not register one; the UI hides the button in that case rather than offering a
         * cancel that does nothing.
         */
        val cancel: (() -> Unit)? = null,
    ) {
        val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())

    /** Everything in flight, oldest first. */
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)

    /** One line to put beside a progress bar, or null when nothing is running. */
    val summary: StateFlow<String?> = _summary.asStateFlow()

    fun start(id: String, label: String, cancel: (() -> Unit)? = null) {
        _tasks.update { list ->
            list.filterNot { it.id == id } + Task(id, label, System.currentTimeMillis(), cancel = cancel)
        }
        publish()
        Diagnostics.i(TAG, "started: $label")
    }

    fun update(id: String, detail: String) {
        _tasks.update { list -> list.map { if (it.id == id) it.copy(detail = detail) else it } }
        publish()
    }

    /** Ends [id] and records how long it took. */
    fun finish(id: String, outcome: String? = null) {
        val task = _tasks.value.firstOrNull { it.id == id }
        _tasks.update { list -> list.filterNot { it.id == id } }
        publish()
        if (task != null) {
            val seconds = "%.1f".format(task.elapsedMs / 1000.0)
            Diagnostics.i(TAG, "${task.label} took ${seconds}s${outcome?.let { " — $it" }.orEmpty()}")
        }
    }

    /**
     * Cancels [id] if it registered a way to be cancelled. Safe to call for anything.
     */
    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        val stop = task.cancel
        if (stop == null) {
            Diagnostics.w(TAG, "${task.label} cannot be cancelled")
            return
        }
        Diagnostics.i(TAG, "cancelling ${task.label}")
        runCatching { stop() }
    }

    /** Cancels everything cancellable. What the one button in the UI does. */
    fun cancelAll() = _tasks.value.forEach { cancel(it.id) }

    /**
     * Runs [block] as a tracked task, and closes the task whatever it does or throws.
     *
     * The running coroutine's own job is registered as the cancel action, so pressing cancel
     * unwinds the generation rather than just clearing the indicator.
     */
    suspend fun <T> track(id: String, label: String, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        start(id, label, cancel = job?.let { { it.cancel() } })
        try {
            val result = block()
            finish(id)
            return result
        } catch (t: CancellationException) {
            finish(id, "cancelled")
            throw t
        } catch (t: Throwable) {
            finish(id, "failed: ${t.message ?: t.javaClass.simpleName}")
            throw t
        }
    }

    private fun publish() {
        val running = _tasks.value
        _summary.value = when (running.size) {
            0 -> null
            1 -> running.first().let { task -> task.detail?.let { "${task.label} · $it" } ?: task.label }
            else -> "${running.first().label} (+${running.size - 1} more)"
        }
    }
}
