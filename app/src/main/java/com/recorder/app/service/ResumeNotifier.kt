package com.recorder.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.recorder.app.R

/**
 * The "tap to resume recording" notification.
 *
 * This exists because of a specific Android rule: `RECORD_AUDIO` is a while-in-use
 * permission, so starting a `microphone` foreground service while the app is in the
 * background throws SecurityException — even when the app is otherwise exempt from
 * background-start restrictions, and even with battery optimisation turned off.
 *
 * Android's documented exemptions to that rule are short. Two are reachable here:
 * device owner mode, and "the service starts by interacting with a notification". This
 * notification is the second one, so its tap target starts the service directly rather
 * than routing through an activity.
 */
object ResumeNotifier {

    const val CHANNEL_ID = "resume"
    private const val NOTIFICATION_ID = 2

    fun show(context: Context, reason: String) {
        // A PendingIntent that starts the service IS the exemption: the resulting start is
        // attributed to the user's interaction with this notification.
        val resume = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.resume_title))
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentIntent(resume)
            .addAction(0, context.getString(R.string.resume_action), resume)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        if (canNotify(context)) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun canNotify(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
