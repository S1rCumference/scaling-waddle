package com.recorder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.recorder.app.service.RecordingService
import com.recorder.app.work.HeavySyncScheduler

class RecorderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceLocator.init(this)
        HeavySyncScheduler.ensureScheduled(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            RecordingService.CHANNEL_ID,
            getString(R.string.recording_channel_name),
            // Low importance: this notification must be permanent but silent.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
