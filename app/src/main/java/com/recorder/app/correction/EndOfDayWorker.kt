package com.recorder.app.correction

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.core.storage.DayKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The end-of-day pass. Runs only while charging and idle — in practice overnight — and
 * re-corrects yesterday and today in full, each only if it has speech the last pass did not
 * cover. The result is stored as the newest correction; originals and earlier passes stay.
 */
class EndOfDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!ServiceLocator.settings.endOfDayEnabled.first()) return Result.success()
        val today = DayKey.today()
        for (day in listOf(DayKey.previous(today), today)) {
            if (!CorrectionRunner.dayNeedsPass(day)) continue
            val corrected = CorrectionRunner.runDay(day)
            Log.i(TAG, "overnight pass on $day corrected $corrected lines")
        }
        // Not holding the weights once the phone is left alone for the night.
        ServiceLocator.providers.correctionProvider().provider
            .let { it as? com.recorder.core.llm.local.LocalModelProvider }?.unload()
        return Result.success()
    }

    companion object {
        private const val TAG = "EndOfDayWorker"
        private const val UNIQUE = "end-of-day-correction"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<EndOfDayWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
