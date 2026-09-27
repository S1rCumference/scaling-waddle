package com.recorder.app.summary

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.recorder.app.ui.GroupRef
import com.recorder.core.storage.Diagnostics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The scheduled pass: a few times a day, summarise whatever has no summary yet.
 *
 * Replaces the overnight correction worker, and is a different shape because the work is. That
 * one had to wait for the phone to be charging, idle and cool, because it ran a gigabyte of
 * weights on the CPU for minutes at a time. This makes a handful of HTTPS requests, so the only
 * thing it needs is a network — and Wi-Fi rather than mobile data by default, since transcripts
 * are the payload.
 *
 * Every six hours rather than once at midnight, so a long day is caught up in pieces and an hour
 * gets a name while it is still worth reading. [BATCH] is what stops one run from spending a whole
 * free-tier allowance in a burst: the rest waits for the next run, and the ordering in
 * [SummaryRunner.pendingSpans] means the oldest unnamed hour goes first.
 */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { SummaryRunner.catchUp(BATCH) }
            .map { Result.success() }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Diagnostics.w(TAG, "scheduled summary pass failed", error)
                // Not a retry. The next scheduled run is the retry, and a failure that repeats
                // every few minutes is how the old version kept the phone warm.
                Result.success()
            }
    }

    companion object {
        private const val TAG = "SummaryWorker"
        private const val UNIQUE = "summary-catch-up"

        /**
         * Spans per run. Sized so a day's worth of hours is caught up over a day of runs rather
         * than all at once, which keeps a per-minute rate limit out of the way and makes a
         * free-tier allowance last.
         */
        const val BATCH = 8

        /** Four runs a day. "A few times a day", and WorkManager's floor is 15 minutes anyway. */
        private const val EVERY_HOURS = 6L

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<SummaryWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered, not merely connected: this sends the user's transcripts, and
                        // doing that over mobile data without being asked would be a surprise.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Stops the schedule, for when summaries are switched off. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}

/**
 * "Summarise", pressed on a group.
 *
 * A worker rather than a viewModelScope launch, because the work must survive leaving the screen:
 * pressing the button and then folding the phone used to cancel the pass silently, part way
 * through. Progress reaches the UI through [SummaryRunner.progress] and `RunningTasks`, which are
 * process-wide, so whichever screen is alive when the user comes back sees it.
 */
class SummariseGroupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val group = inputData.getString(KEY_GROUP)?.let(GroupRef::parse)
            ?: return@withContext Result.failure()
        runCatching { SummaryRunner.run(group) }
            .map { Result.success() }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Diagnostics.w(TAG, "summarising ${group.title()} failed", error)
                Result.success()
            }
    }

    companion object {
        private const val TAG = "SummariseGroupWorker"

        /** One name, because the runner holds a lock: a second press would only queue a wait. */
        private const val UNIQUE = "summarise-group"
        private const val KEY_GROUP = "group"

        fun enqueue(context: Context, group: GroupRef) {
            val request = OneTimeWorkRequestBuilder<SummariseGroupWorker>()
                .setInputData(workDataOf(KEY_GROUP to group.id))
                .setConstraints(
                    // Connected, not unmetered: the user pressed a button and is waiting, so
                    // mobile data is their call to make in that moment.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
