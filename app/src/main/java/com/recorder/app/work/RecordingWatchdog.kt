package com.recorder.app.work

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recorder.app.R
import com.recorder.app.ServiceLocator
import com.recorder.app.admin.DeviceOwner
import com.recorder.app.service.RecordingService
import com.recorder.app.service.ResumeAction
import com.recorder.app.service.ResumeDecision
import com.recorder.app.service.ResumeNotifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Notices when recording has stopped without anyone asking it to — a low-memory kill, an
 * OEM battery manager, a crash — and gets it going again.
 *
 * Checks hourly. Anything more frequent would cost more battery than the problem it
 * solves, and WorkManager will not schedule periodic work more often than every 15 minutes
 * anyway. This is a safety net, not the primary mechanism: the primary mechanisms are
 * START_STICKY and the boot receiver.
 */
class RecordingWatchdog(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        val decision = ResumeDecision.onWatchdogCheck(
            recordingEnabled = ServiceLocator.settings.recordingEnabled.first(),
            serviceRunning = isServiceRunning(context),
            isDeviceOwner = DeviceOwner(context).isActive,
        )

        when (decision) {
            ResumeAction.NOTHING -> Unit

            ResumeAction.START_DIRECTLY -> {
                Log.i(TAG, "recording had stopped; restarting as device owner")
                RecordingService.start(context).onFailure { error ->
                    Log.w(TAG, "device owner start refused", error)
                    ResumeNotifier.show(context, context.getString(R.string.resume_reason_stopped))
                }
            }

            ResumeAction.ASK_WITH_NOTIFICATION -> {
                Log.i(TAG, "recording had stopped; asking for a tap")
                ResumeNotifier.show(context, context.getString(R.string.resume_reason_stopped))
            }
        }
        return Result.success()
    }

    /**
     * On API 26+ this only ever returns the calling app's own services, which is exactly
     * what is being asked. A static flag would not survive the process death this is
     * meant to detect.
     */
    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val target = RecordingService::class.java.name
        return runCatching {
            @Suppress("DEPRECATION")
            am.getRunningServices(MAX_SERVICES).any { it.service.className == target }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "RecordingWatchdog"
        private const val UNIQUE = "recording-watchdog"
        private const val MAX_SERVICES = 64

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecordingWatchdog>(1, TimeUnit.HOURS)
                // No network or charging constraint: the point is to notice a dead recorder
                // on an idle phone on battery, which is exactly when those would block it.
                .setConstraints(Constraints.NONE)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
