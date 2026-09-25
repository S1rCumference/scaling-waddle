package com.recorder.app.correction

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.ServiceLocator
import com.recorder.app.summary.SummaryRunner
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.Diagnostics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The overnight pass, and the only thing in 3.0 that starts a correction without being asked.
 *
 * It runs once a night, over the day that has just ended, and only when all four of these hold:
 * charging, screen off, battery above [CorrectionGate.MIN_BATTERY_PERCENT], and the phone not
 * already hot. WorkManager enforces charging and device-idle; the rest is checked here, because
 * a constraint that was true when the work was scheduled is not necessarily true when it runs.
 *
 * Everything else that used to start a correction is gone: the fifteen-minute timer, the
 * three-minute charging timer, the drain-the-backlog button.
 */
class EndOfDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * IO, not Default. The work is a model that memory-maps a gigabyte and then blocks in
     * native code; it belongs on the dispatcher that expects to be blocked.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { correctTheDayThatEnded() }.getOrElse { error ->
            if (error is CancellationException) throw error
            Diagnostics.w(TAG, "overnight pass failed", error)
            // Not a retry. A failed pass is re-attempted by the next night's run, which is
            // the cadence this is supposed to have; retrying inside the night is how the old
            // version ended up running the model repeatedly on a warm phone.
            Result.success()
        }
    }

    private suspend fun correctTheDayThatEnded(): Result {
        val settings = ServiceLocator.settings
        if (!settings.endOfDayEnabled.first()) return Result.success()

        when (val verdict = CorrectionGate.check(applicationContext)) {
            is CorrectionGate.Verdict.Blocked -> {
                Diagnostics.i(TAG, "overnight pass not run: ${verdict.reason}")
                return Result.success()
            }

            is CorrectionGate.Verdict.Ready -> Unit
        }

        val last = settings.lastCorrectionRun.first()
        if (last != null && System.currentTimeMillis() - last.atTs < ONCE_PER_MS) {
            Diagnostics.i(TAG, "overnight pass already ran within the last ${ONCE_PER_MS / 3_600_000} hours")
            return Result.success()
        }

        val day = dayToCorrect() ?: run {
            Diagnostics.i(TAG, "overnight pass: nothing to correct")
            return Result.success()
        }

        val startedAt = System.currentTimeMillis()
        val corrected = CorrectionRunner.runDay(day)
        val elapsed = System.currentTimeMillis() - startedAt
        settings.recordCorrectionRun(elapsed, corrected)
        Diagnostics.i(
            TAG,
            "overnight pass on $day: $corrected line(s) in ${"%.1f".format(elapsed / 1000.0)}s" +
                (CorrectionRunner.lastError?.let { " — $it" } ?: ""),
        )

        // Summaries come after correction, in the same sitting, so they are written from the
        // corrected text rather than raw recognition. Re-checking the gate first: correcting a
        // day can take minutes, and the phone may have come off the charger or got hot in them.
        when (val after = CorrectionGate.check(applicationContext)) {
            is CorrectionGate.Verdict.Blocked ->
                Diagnostics.i(TAG, "summaries not run: ${after.reason}")

            is CorrectionGate.Verdict.Ready -> {
                val summarised = SummaryRunner.runDay(day)
                Diagnostics.i(
                    TAG,
                    "overnight summaries on $day: $summarised written" +
                        (SummaryRunner.lastError?.let { " — $it" } ?: ""),
                )
            }
        }
        return Result.success()
    }

    /**
     * The day that just ended, or the newest earlier day that never got a pass.
     *
     * The fallback is deliberate and is a small departure from "the day just ended": a night
     * spent off the charger would otherwise lose that day permanently, with nothing anywhere
     * to say so. Still one day per night, so the cost does not grow.
     */
    private suspend fun dayToCorrect(): Int? {
        val yesterday = DayKey.of(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        if (CorrectionRunner.dayNeedsPass(yesterday)) return yesterday

        val today = DayKey.of(System.currentTimeMillis())
        return ServiceLocator.database.transcripts().daySummaries().first()
            .map { it.dayKey }
            .filter { it < today }
            .sortedDescending()
            .take(CATCH_UP_DAYS)
            .firstOrNull { CorrectionRunner.dayNeedsPass(it) }
    }

    companion object {
        private const val TAG = "EndOfDayWorker"
        private const val UNIQUE = "end-of-day-correction"

        /** How far back the fallback will reach for a day that never got a pass. */
        private const val CATCH_UP_DAYS = 14

        /** "Once a night", enforced against the recorded run rather than assumed from the schedule. */
        private const val ONCE_PER_MS = 20 * 60 * 60 * 1000L

        /**
         * Checked every four hours so that some part of the night qualifies whatever time the
         * phone goes on charge; the once-a-night guard above is what keeps it to one pass.
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<EndOfDayWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
