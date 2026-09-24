package com.recorder.app.models

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.recorder.app.R
import com.recorder.app.ServiceLocator
import com.recorder.app.service.RecordingService
import com.recorder.app.ui.MainActivity
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Downloads models in a foreground service, with a progress notification.
 *
 * This exists because the downloads used to run in the setup wizard's `viewModelScope`. Three
 * and a half gigabytes over phone Wi-Fi takes long enough that the screen turns off and the
 * app gets backgrounded part-way through, and work owned by a screen dies with the screen —
 * silently, leaving a part-downloaded file and a wizard that showed nothing on return. The
 * biggest models are last in the queue, so they were the ones that "never arrived".
 *
 * State goes into [ModelInstallStore] rather than back to a caller, so the wizard and Settings
 * both see the same thing whether or not they were open when it happened.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ids = intent?.getStringArrayListExtra(EXTRA_IDS).orEmpty()
        if (ids.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(notification("Preparing…", null))
        ModelInstallStore.enqueue(ids)
        ModelInstallStore.setRunning(true)

        // One worker at a time: a second tap adds to the queue rather than starting a race
        // for the same .part files.
        if (work?.isActive == true) {
            Diagnostics.i(TAG, "already downloading; ${ids.size} model(s) added to the queue")
            return START_NOT_STICKY
        }

        work = scope.launch {
            runQueue()
            ModelInstallStore.setRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        val installer = ModelInstaller(this)
        val catalogue = runCatching { ModelCatalog.load(this) }.getOrElse { error ->
            Diagnostics.e(TAG, "could not read the model list", error)
            return
        }

        val allowMetered = ServiceLocator.settings.allowMeteredDownloads.first()

        // Space for the whole queue, checked once and reported against every model in it,
        // rather than per-model checks that each pass and then run the phone out on the last.
        val queuedIds = ModelInstallStore.states.value
            .filterValues { it is InstallProgress.Queued }.keys
        val queued = catalogue.filter { it.id in queuedIds }
        installer.spaceProblem(queued)?.let { problem ->
            Diagnostics.e(TAG, problem)
            queued.forEach { ModelInstallStore.set(it.id, InstallProgress.Failed(problem, false)) }
            return
        }

        var done = 0
        while (true) {
            val nextId = ModelInstallStore.states.value.entries
                .firstOrNull { it.value is InstallProgress.Queued }?.key ?: break
            val entry = catalogue.firstOrNull { it.id == nextId }
            if (entry == null) {
                ModelInstallStore.set(nextId, InstallProgress.Failed("Not in the model list.", false))
                continue
            }

            if (entry.isInstalled(this)) {
                ModelInstallStore.set(entry.id, InstallProgress.Done)
                continue
            }

            ModelInstallStore.setActive(entry.id)
            Diagnostics.i(TAG, "downloading ${entry.id} (${entry.approxMb} MB)")
            updateNotification(entry.displayName, null)

            installer.install(entry, allowMetered) { progress ->
                ModelInstallStore.set(entry.id, progress)
                updateNotification(
                    entry.displayName,
                    (progress as? InstallProgress.Downloading)?.percent,
                )
            }.onSuccess {
                done++
                Diagnostics.i(TAG, "installed ${entry.id}")
            }.onFailure { error ->
                // install() already recorded a Failed state with the reason; this is the
                // record that survives for Settings -> Diagnostics.
                Diagnostics.w(TAG, "download failed for ${entry.id}", error)
            }
            ModelInstallStore.setActive(null)
        }
        Diagnostics.i(TAG, "download queue finished, $done installed")
        if (done > 0) {
            // Put them to work now rather than the next time recording is started by hand.
            // Nothing restarts: the running recorder swaps the model in behind the mic.
            RecordingService.notifyModelsChanged(this)
        }
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        ModelInstallStore.setRunning(false)
        // Anything still queued is not happening, and must not keep claiming that it is.
        ModelInstallStore.clearQueued()
        super.onDestroy()
    }

    private fun notification(text: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_recording)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply {
                if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true)
            }
            .build()
    }

    private fun updateNotification(name: String, percent: Int?) {
        val text = if (percent != null) "$name · $percent%" else name
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text, percent))
    }

    /** dataSync is the type Android 14+ requires to be declared for work like this. */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        const val CHANNEL_ID = "model-downloads"
        private const val NOTIFICATION_ID = 3
        private const val EXTRA_IDS = "com.recorder.app.extra.MODEL_IDS"

        /**
         * Queues [ids] for download. Safe to call while a download is already running; the
         * ids are added to the queue.
         */
        fun start(context: Context, ids: Collection<String>) {
            if (ids.isEmpty()) return
            val intent = Intent(context, ModelDownloadService::class.java)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    Diagnostics.w(TAG, "could not start the download service", error)
                    ids.forEach {
                        ModelInstallStore.set(
                            it,
                            InstallProgress.Failed(
                                "Android refused to start the download in the background. " +
                                    "Open the app and try again with the screen on.",
                                true,
                            ),
                        )
                    }
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }
}
