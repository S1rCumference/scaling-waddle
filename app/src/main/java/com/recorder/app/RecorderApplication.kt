package com.recorder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.recorder.app.service.RecordingService
import com.recorder.app.service.ResumeNotifier
import com.recorder.app.work.FolderFilingWorker
import com.recorder.app.work.HeavySyncScheduler
import com.recorder.app.work.RecordingWatchdog

class RecorderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ServiceLocator.init(this)
        HeavySyncScheduler.ensureScheduled(this)
        RecordingWatchdog.ensureScheduled(this)
        FolderFilingWorker.ensureScheduled(this)
    }

    private fun createNotificationChannels() {
        val recording = NotificationChannel(
            RecordingService.CHANNEL_ID,
            getString(R.string.recording_channel_name),
            // Low importance: this notification must be permanent but silent.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }

        // Deliberately high importance: it means the phone has stopped listening and only a
        // tap will fix it. Silently low would defeat the purpose.
        val resume = NotificationChannel(
            ResumeNotifier.CHANNEL_ID,
            getString(R.string.resume_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.resume_channel_description)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(recording, resume))
    }
}
