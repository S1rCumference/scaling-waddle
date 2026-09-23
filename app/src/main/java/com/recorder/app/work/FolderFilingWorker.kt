package com.recorder.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.core.llm.FolderClassifier
import com.recorder.core.storage.ensure
import java.util.concurrent.TimeUnit

/**
 * Files the segments the cheap heuristic could not place, using the local model, in one
 * batch.
 *
 * This used to happen inline for every segment: a model call per sentence spoken, awaited
 * before the next segment could be processed. That is one of the most expensive things an
 * always-on recorder can do, and it ran all day.
 *
 * Now it runs while the phone is charging and idle, loads the model once, files whatever is
 * waiting, and unloads. If nothing is waiting it does nothing at all.
 */
class FolderFilingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val transcripts = ServiceLocator.database.transcripts()
        val folders = ServiceLocator.database.folders()

        val pending = transcripts.unfiled(BATCH_SIZE)
        if (pending.isEmpty()) return Result.success()

        val provider = ServiceLocator.providers.onDeviceSmallModel()
        val classifier = FolderClassifier(provider)
        val known = folders.allOnce()

        var filed = 0
        try {
            for (segment in pending) {
                val name = runCatching { classifier.classify(segment, known) }
                    .getOrElse { error ->
                        Log.w(TAG, "classification failed for ${segment.id}", error)
                        // A model that cannot answer will not answer for the rest either.
                        break
                    }
                transcripts.assignFolder(segment.id, folders.ensure(name))
                filed++
            }
        } finally {
            // The whole point is not to leave the weights resident afterwards.
            provider.unload()
        }

        Log.i(TAG, "filed $filed of ${pending.size} segments")
        return Result.success()
    }

    companion object {
        private const val TAG = "FolderFilingWorker"
        private const val UNIQUE = "folder-filing"
        private const val BATCH_SIZE = 200

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<FolderFilingWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
