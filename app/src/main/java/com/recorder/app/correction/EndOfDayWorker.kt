package com.recorder.app.correction

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.core.storage.Diagnostics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The end-of-day pass. Runs only while charging and idle — in practice overnight — and
 * re-corrects, in full, every day that still has speech the last pass did not cover. The
 * result is stored as the newest correction; originals and earlier passes stay.
 *
 * It catches up rather than keeping a window. A pass that only looked at yesterday and
 * today lost a day for good the moment two nights went by off the charger, and said
 * nothing about it.
 */
class EndOfDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching { correctEverythingOutstanding() }
        .getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Diagnostics.w(TAG, "overnight pass failed; it will be retried", error)
            // Retry, not success. A pass that reports success is never re-run, and the work
            // it did not do has no other way back.
            Result.retry()
        }

    private suspend fun correctEverythingOutstanding(): Result {
        if (!ServiceLocator.settings.endOfDayEnabled.first()) return Result.success()

        // Every day that still has uncorrected speech, oldest first — not just yesterday
        // and today. The old version looked at exactly two days, so a couple of nights off
        // the charger and a day fell out of the window permanently, with nothing to say so.
        val outstanding = ServiceLocator.database.transcripts().daySummaries().first()
            .map { it.dayKey }
            .sorted()
            .takeLast(CATCH_UP_DAYS)
            .filter { CorrectionRunner.dayNeedsPass(it) }

        if (outstanding.isEmpty()) return Result.success()
        Diagnostics.i(TAG, "overnight pass: ${outstanding.size} day(s) to catch up on")

        val deadline = System.currentTimeMillis() + BUDGET_MS
        for ((index, day) in outstanding.withIndex()) {
            if (System.currentTimeMillis() > deadline) {
                // Out of time rather than out of work. Stopping cleanly leaves the remaining
                // days needing a pass, so the next run picks up exactly where this left off.
                val left = outstanding.size - index
                Diagnostics.i(TAG, "overnight pass out of time; $left day(s) left for next time")
                break
            }
            val corrected = CorrectionRunner.runDay(day)
            Diagnostics.i(TAG, "overnight pass on $day corrected $corrected line(s)")
        }

        // Not holding the weights once the phone is left alone for the night.
        ServiceLocator.providers.correctionProvider().provider
            .let { it as? com.recorder.core.llm.local.LocalModelProvider }?.unload()
        return Result.success()
    }

    companion object {
        private const val TAG = "EndOfDayWorker"
        private const val UNIQUE = "end-of-day-correction"

        /** How far back a catch-up reaches. Two weeks of nights off the charger. */
        private const val CATCH_UP_DAYS = 14

        /** One run's share of the night. What it cannot finish, the next run continues. */
        private const val BUDGET_MS = 25 * 60 * 1000L

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
