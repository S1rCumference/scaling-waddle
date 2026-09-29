package com.recorder.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.recorder.core.storage.Diagnostics

/**
 * Wakes the recorder at the schedule's next edge.
 *
 * An exact, allow-while-idle alarm, because the phone will be in Doze at 9:00 on a Monday with
 * the lid shut, and an inexact one can be deferred by the better part of an hour — an hour of a
 * meeting not recorded. One alarm per edge is a handful a day, which costs nothing.
 *
 * USE_EXACT_ALARM covers Android 13+ without asking; SCHEDULE_EXACT_ALARM covers 12. If exact
 * alarms are refused anyway, an inexact one is still better than none, and the supervisor's own
 * timer catches the edge whenever the phone is awake.
 */
object ScheduleAlarm {

    private const val TAG = "ScheduleAlarm"
    private const val REQUEST_CODE = 41

    fun set(context: Context, atMs: Long?) {
        if (atMs == null) return cancel(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        }.recoverCatching {
            // SecurityException if the exact-alarm grant was withdrawn between the check and
            // the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }.onFailure { Diagnostics.w(TAG, "could not set the schedule alarm", it) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /**
     * A plain service start, not a foreground one: the service is normally already running and
     * only needs the tick. If it is not, the alarm's own exemption lets it start and promote
     * itself, and it decides from the settings whether it should be running at all.
     */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            REQUEST_CODE,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_SCHEDULE_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
