package com.recorder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.recorder.app.correction.EndOfDayWorker
import com.recorder.app.models.ModelDownloadService
import com.recorder.app.models.ModelHealth
import com.recorder.app.service.RecordingService
import com.recorder.app.ui.AppUiState
import com.recorder.app.service.ResumeNotifier
import com.recorder.app.work.FolderFilingWorker
import com.recorder.app.work.HeavySyncScheduler
import com.recorder.app.work.RecordingWatchdog
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.local.LocalModelRuntime
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
        // Before anything is started or loaded: if the last two starts died, this one comes
        // up doing nothing, so the screen can be reached and the fault repaired from it.
        StartupGuard.begin(this)
        ServiceLocator.init(this)
        // The recorder must never hand a part-written model to onnxruntime, which aborts the
        // process rather than throwing. This is where the app's own install records get to
        // veto a load, and in safe mode nothing is loadable at all.
        AsrEngineFactory.installVerifier = { context ->
            if (StartupGuard.safeMode) SAFE_MODE_REASON else ModelHealth.asrProblem(context)
        }
        // The same gate for the GGUF chat models, which llama.cpp memory-maps.
        LocalModelRuntime.fileVerifier = { file ->
            if (StartupGuard.safeMode) SAFE_MODE_REASON else ModelHealth.ggufProblem(this, file)
        }
        // One clock for the whole app, read once and kept current. Every timestamp anywhere
        // goes through Clocks, so the preference cannot end up applied to only some of them.
        applicationScope.launch {
            ServiceLocator.settings.use24HourClock.collect { Clocks.set(it) }
        }
        AppUiState.init(this)
        if (StartupGuard.safeMode) {
            // Including the watchdog: its whole job is to start the recorder again, which is
            // the opposite of what a start-up that keeps dying needs.
            Diagnostics.w("RecorderApplication", "safe mode: background work is not scheduled")
            return
        }
        HeavySyncScheduler.ensureScheduled(this)
        RecordingWatchdog.ensureScheduled(this)
        FolderFilingWorker.ensureScheduled(this)
        EndOfDayWorker.ensureScheduled(this)
    }

    private companion object {
        const val SAFE_MODE_REASON =
            "safe mode: the last two starts did not survive, so no model is being loaded"
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
