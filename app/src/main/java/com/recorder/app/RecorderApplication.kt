package com.recorder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.recorder.app.correction.EndOfDayWorker
import com.recorder.app.models.ModelDownloadService
import com.recorder.app.service.RecordingService
import com.recorder.app.ui.AppUiState
import com.recorder.app.service.ResumeNotifier
import com.recorder.app.work.FolderFilingWorker
import com.recorder.app.work.HeavySyncScheduler
import com.recorder.app.work.RecordingWatchdog
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RecorderApplication : Application() {

    /** Lives as long as the process; only used for settings that the whole app reads. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Diagnostics.init(this)
        Diagnostics.i("RecorderApplication", "process started, version ${BuildConfig.VERSION_NAME}")
        ServiceLocator.init(this)
        // One clock for the whole app, read once and kept current. Every timestamp anywhere
        // goes through Clocks, so the preference cannot end up applied to only some of them.
        applicationScope.launch {
            ServiceLocator.settings.use24HourClock.collect { Clocks.set(it) }
        }
        AppUiState.init(this)
        HeavySyncScheduler.ensureScheduled(this)
        RecordingWatchdog.ensureScheduled(this)
        FolderFilingWorker.ensureScheduled(this)
        EndOfDayWorker.ensureScheduled(this)
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

        // Low importance: a progress bar, not something to interrupt anyone for.
        val downloads = NotificationChannel(
            ModelDownloadService.CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(recording, resume, downloads))
    }
}
