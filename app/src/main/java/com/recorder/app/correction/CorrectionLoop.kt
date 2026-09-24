package com.recorder.app.correction

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.recorder.app.ServiceLocator
import com.recorder.app.service.TranscriptPipeline
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches for a moment when the phone can afford to think, and then does all of it at once.
 *
 * Lives in the recording service's scope because that is the one thing alive all day, but
 * shares nothing with the audio path: it only reads and writes text rows.
 *
 * The change from the old version is what it waits for. It used to wait for a timer; now it
 * waits for [CorrectionGate] — charging, screen off, not hot, not saving power — and when
 * that arrives it drains the whole backlog in one pass rather than taking a slice every
 * quarter of an hour. Outside those conditions nothing runs unless it is asked for by hand.
 */
class CorrectionLoop(private val context: Context) {

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val settings = ServiceLocator.settings
            while (isActive) {
                delay(POLL_MS)
                if (!settings.correctionEnabled.first()) continue

                val verdict = CorrectionGate.check(context)
                if (verdict is CorrectionGate.Verdict.Blocked) {
                    // Logged at most once per reason: a line a minute saying "not charging"
                    // would bury everything else in Diagnostics.
                    if (verdict.reason != lastBlockReason) {
                        lastBlockReason = verdict.reason
                        Diagnostics.i(TAG, "automatic passes paused: ${verdict.reason}")
                    }
                    continue
                }
                lastBlockReason = null

                // Wait for a lull, but not forever: a long meeting still gets corrected.
                var waited = 0L
                while (System.currentTimeMillis() - TranscriptPipeline.lastSegmentAt < QUIET_MS &&
                    waited < MAX_WAIT_MS
                ) {
                    delay(QUIET_MS)
                    waited += QUIET_MS
                }

                CorrectionRunner.runAllPending(context, "Overnight correction")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @Volatile
    private var lastBlockReason: String? = null

    private companion object {
        const val TAG = "CorrectionLoop"

        /** How often the conditions are re-checked. Cheap; it is three system calls. */
        const val POLL_MS = 2 * 60_000L
        const val QUIET_MS = 15_000L
        const val MAX_WAIT_MS = 5 * 60_000L
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
