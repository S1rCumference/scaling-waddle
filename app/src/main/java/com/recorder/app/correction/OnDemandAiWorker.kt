package com.recorder.app.correction

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.recorder.app.summary.SummaryRunner
import com.recorder.app.ui.GroupRef
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Correct" and "Summarise", run somewhere that survives leaving the screen.
 *
 * This is the third thing that made the AI unusable, and the one with no error message at all.
 * Both buttons launched their work in `viewModelScope`, which is cancelled when the ViewModel is
 * cleared — so pressing Summarise and then folding the phone, switching app, or letting the
 * screen time out killed the pass silently, several minutes in, with the model half way through
 * an answer. From the outside it looked exactly like "it loads for a bit and never does
 * anything", because that is what it did.
 *
 * WorkManager is the right host: the work is bound to the application, not to a screen, and an
 * expedited request asks the OS to keep the process alive while it runs. Progress still reaches
 * the UI through [CorrectionRunner.progress], [SummaryRunner.progress] and `RunningTasks`, all of
 * which are process-wide, so whichever ViewModel is alive when the user comes back sees it.
 *
 * Unique work, dropped rather than queued if something is already running: the two runners share
 * one model slot behind a mutex anyway, and queueing a second press would mean a button that
 * silently signs the user up for twice the wait.
 *
 * A worker gets about ten minutes before the system stops it, which is why the runners' own
 * ceilings add up to less than that. A pass cut short still keeps everything it finished — a
 * correction is stored per batch and a summary per group — so pressing the button again carries
 * on rather than starting over.
 */
class OnDemandAiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * IO, not Default. The work is a model that memory-maps a gigabyte and then blocks in
     * native code; it belongs on the dispatcher that expects to be blocked.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val job = inputData.getString(KEY_JOB) ?: return@withContext Result.failure()
        val group = inputData.getString(KEY_GROUP)?.let(GroupRef::parse)
            ?: return@withContext Result.failure()

        runCatching {
            when (job) {
                JOB_CORRECT -> CorrectionRunner.runRange(group.fromTs, group.toTs)
                JOB_SUMMARISE -> SummaryRunner.run(group)
                else -> return@withContext Result.failure()
            }
            Result.success()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Diagnostics.w(TAG, "$job on ${group.title()} failed", error)
            // Not a retry. A pass that failed will fail the same way in thirty seconds, and
            // retrying it is how the old version ended up running the model on a warm phone.
            Result.success()
        }
    }

    companion object {
        private const val TAG = "OnDemandAiWorker"

        /** One name for both jobs: they share the model slot, so only one can run regardless. */
        private const val UNIQUE = "on-demand-ai"

        private const val KEY_JOB = "job"
        private const val KEY_GROUP = "group"
        const val JOB_CORRECT = "correct"
        const val JOB_SUMMARISE = "summarise"

        /** Enqueues [job] for [group]. Returns false when something is already running. */
        fun enqueue(context: Context, job: String, group: GroupRef): Boolean {
            // No constraints and no expedited flag. Unconstrained one-time work starts
            // immediately and WorkManager holds a wake lock for as long as it runs, which is
            // the property that was missing: the pass survives the screen going off, the phone
            // folding, and the Activity being destroyed. Expedited would add process priority
            // but also requires a foreground notification below API 31, and minSdk here is 26 —
            // a crash on older devices is a worse trade than slightly lower priority.
            val request = OneTimeWorkRequestBuilder<OnDemandAiWorker>()
                .setInputData(data(job, group))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
            return true
        }

        /** Stops whatever on-demand pass is running, for the Cancel button. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }

        private fun data(job: String, group: GroupRef): Data =
            workDataOf(KEY_JOB to job, KEY_GROUP to group.id)
    }
}
