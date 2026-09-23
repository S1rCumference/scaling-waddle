package com.recorder.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.recorder.app.R
import com.recorder.app.ServiceLocator
import com.recorder.app.ui.MainActivity
import com.recorder.core.asr.AsrEngine
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.asr.AsrModels
import com.recorder.core.audio.AudioCapture
import com.recorder.core.audio.EnergyVad
import com.recorder.core.audio.SileroVad
import com.recorder.core.audio.SpeechSegmenter
import com.recorder.core.audio.VoiceActivityDetector
import com.recorder.core.audio.segmentSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The always-on recorder.
 *
 * Deliberately knows nothing about the UI, the fold state, or which display is active:
 * opening or closing the phone must not restart, pause, or even reach this service. Its
 * only inputs are the microphone and the settings flag; its only output is rows in the
 * database.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vad: VoiceActivityDetector? = null
    private var asr: AsrEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification(getString(R.string.notification_starting))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; stopping")
            updateNotification(getString(R.string.notification_no_permission))
            _state.value = RecorderState.NEEDS_PERMISSION
            stopSelf()
            return
        }

        startPipeline()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reaching here at all means the start was permitted, so clear any resume prompt.
        ResumeNotifier.clear(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A foreground service already survives the task being swiped away, and the old
        // self-restart here was both unnecessary and illegal: starting a microphone service
        // from the background throws SecurityException on Android 14+.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        vad?.close()
        asr?.close()
        _state.value = RecorderState.STOPPED
        super.onDestroy()
    }

    private fun startPipeline() {
        val detector = SileroVad.tryLoad(AsrModels.sileroVadFile(this)) ?: EnergyVad()
        val engine = AsrEngineFactory.create(this)
        vad = detector
        asr = engine

        val pipeline = TranscriptPipeline(
            asr = engine,
            transcripts = ServiceLocator.database.transcripts(),
            folders = ServiceLocator.database.folders(),
            keywordWatcher = KeywordWatcher(
                ServiceLocator.database.flagged(),
                ServiceLocator.settings,
            ),
            providers = ServiceLocator.providers,
        )

        _state.value = RecorderState.RECORDING
        updateNotification(
            getString(
                R.string.notification_recording,
                if (detector is SileroVad) "Silero" else "energy",
                engine.name,
            ),
        )

        scope.launch {
            AudioCapture().frames()
                .segmentSpeech(detector, SpeechSegmenter())
                .catch { error ->
                    Log.e(TAG, "capture pipeline failed", error)
                    _state.value = RecorderState.ERROR
                    updateNotification(getString(R.string.notification_error))
                }
                .collect { segment -> pipeline.process(segment) }
        }
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        androidx.core.app.NotificationManagerCompat.from(this)
            .takeIf {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            }
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_recording)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    enum class RecorderState { STOPPED, RECORDING, NEEDS_PERMISSION, ERROR }

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(RecorderState.STOPPED)

        /** Observable so the UI can show whether recording is actually running. */
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        const val ACTION_RESUME = "com.recorder.app.action.RESUME"

        /**
         * Starts recording, reporting refusal instead of crashing.
         *
         * A microphone foreground service can be refused for reasons the caller cannot
         * check in advance — the app being in the background at that instant, or an OEM
         * restriction. Callers decide what to do about it; most post the resume
         * notification, which is itself an exemption.
         */
        fun start(context: Context): Result<Unit> = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java),
            )
        }.onFailure { error ->
            val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error is android.app.ForegroundServiceStartNotAllowedException
            when {
                blocked -> Log.w(TAG, "foreground start not allowed from here", error)
                error is SecurityException ->
                    // The while-in-use case: the system does not consider the microphone
                    // permission held because the app is in the background.
                    Log.w(TAG, "microphone not available to a background start", error)

                else -> Log.w(TAG, "could not start recording", error)
            }
            _state.value = RecorderState.STOPPED
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
