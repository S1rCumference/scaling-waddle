package com.recorder.app.correction

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.recorder.app.ServiceLocator
import com.recorder.app.service.TranscriptPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wakes every few minutes, and corrects new lines if there are any.
 *
 * Lives in the recording service's scope because that is the one thing alive all day, but
 * shares nothing with the audio path: it only reads and writes text rows. It waits for a
 * pause in speech before starting, so the correction model and the speech decoder are not
 * competing for the CPU while someone is talking.
 */
class CorrectionLoop(private val context: Context) {

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val settings = ServiceLocator.settings
            while (isActive) {
                val charging = context.isCharging()
                val minutes = if (charging) settings.correctionIntervalChargingMin.first()
                else settings.correctionIntervalMin.first()
                delay(minutes * 60_000L)

                if (!settings.correctionEnabled.first()) continue
                if (!charging && context.batteryPercent() in 0..LOW_BATTERY_PERCENT) continue

                // Wait for a lull, but not forever: a long meeting still gets corrected.
                var waited = 0L
                while (System.currentTimeMillis() - TranscriptPipeline.lastSegmentAt < QUIET_MS && waited < MAX_WAIT_MS) {
                    delay(QUIET_MS)
                    waited += QUIET_MS
                }

                runCatching { CorrectionRunner.runBatch(drain = charging) }
                    .onSuccess { if (it > 0) Log.i(TAG, "corrected $it lines") }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "batch failed", it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "CorrectionLoop"
        const val QUIET_MS = 15_000L
        const val MAX_WAIT_MS = 5 * 60_000L
        const val LOW_BATTERY_PERCENT = 20
    }
}

internal fun Context.isCharging(): Boolean = batteryIntent()?.let {
    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
} ?: false

internal fun Context.batteryPercent(): Int = batteryIntent()?.let {
    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level >= 0 && scale > 0) level * 100 / scale else -1
} ?: -1

private fun Context.batteryIntent(): Intent? =
    runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
