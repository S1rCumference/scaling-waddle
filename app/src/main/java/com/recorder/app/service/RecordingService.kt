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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.recorder.app.BuildConfig
import com.recorder.app.R
import com.recorder.app.ServiceLocator
import com.recorder.app.correction.CorrectionLoop
import com.recorder.app.cover.CoverPresenter
import com.recorder.app.ui.MainActivity
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.asr.AsrModels
import com.recorder.core.asr.NoopAsrEngine
import com.recorder.core.audio.AudioCapture
import com.recorder.core.audio.OverCapture
import com.recorder.core.audio.ResilientVad
import com.recorder.core.audio.SileroVad
import com.recorder.core.audio.SpeechSegmenter
import com.recorder.core.audio.VoiceActivityDetector
import com.recorder.core.audio.segmentSpeech
import com.recorder.app.diag.DeviceWatch
import com.recorder.core.storage.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
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

    /**
     * Held behind a swappable handle so a model that finishes downloading can be put to work
     * without stopping the microphone. Restarting the service to pick up a new model would
     * mean a gap in the recording, and a gap is the one thing this app must not have.
     */
    private var asr: SwappableAsrEngine? = null

    /** Whether the last frame was dropped, so the detector is reset once per transition. */
    private var wasPaused = false

    /**
     * Present only so something is alive all day to watch for the phone being closed. The
     * audio pipeline never consults it, so fold state cannot disturb recording.
     */
    private var coverPresenter: CoverPresenter? = null

    /** Text-only correction behind recording. Never touches the audio path. */
    private val correctionLoop = CorrectionLoop(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification(getString(R.string.notification_starting))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Diagnostics.w(TAG, "RECORD_AUDIO not granted; stopping")
            updateNotification(getString(R.string.notification_no_permission))
            _state.value = RecorderState.NEEDS_PERMISSION
            stopSelf()
            return
        }

        Diagnostics.i(TAG, "recording service starting")
        startPipeline()
        correctionLoop.start(scope)

        if (BuildConfig.COVER_UI_ENABLED) {
            coverPresenter = CoverPresenter(this).also { it.start() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reaching here at all means the start was permitted, so clear any resume prompt.
        ResumeNotifier.clear(this)
        if (intent?.action == ACTION_MODELS_CHANGED) adoptNewModels()
        return START_STICKY
    }

    /**
     * Picks up models that were not installed when the pipeline started.
     *
     * The microphone keeps running throughout: only the detector's delegate and the ASR
     * engine behind the swappable handle are replaced. Before this, a model downloaded while
     * recording did nothing until the user noticed and toggled recording off and on.
     */
    private fun adoptNewModels() = scope.launch {
        val engine = asr ?: return@launch
        var changed = false

        if (engine.isNoop() && AsrEngineFactory.modelsInstalled(this@RecordingService)) {
            val threads = ServiceLocator.settings.asrThreads.first()
            val next = AsrEngineFactory.create(this@RecordingService, threads)
            if (next !is NoopAsrEngine) {
                engine.swap(next)
                changed = true
                Diagnostics.i(TAG, "speech model picked up without stopping: ${next.name}")
            }
        }

        (vad as? ResilientVad)?.let { detector ->
            if (detector.usingFallback) {
                SileroVad.tryLoad(AsrModels.sileroVadFile(this@RecordingService))?.let { silero ->
                    if (detector.adopt(silero)) {
                        changed = true
                        Diagnostics.i(TAG, "speech detector picked up without stopping: Silero")
                    } else {
                        silero.close()
                    }
                }
            }
        }

        if (changed) {
            updateNotification(getString(R.string.notification_recording, vadName(), engine.name))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A foreground service already survives the task being swiped away, and the old
        // self-restart here was both unnecessary and illegal: starting a microphone service
        // from the background throws SecurityException on Android 14+.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        coverPresenter?.stop()
        correctionLoop.stop()
        _micSilenced.value = false
        scope.cancel()
        vad?.close()
        asr?.close()
        _state.value = RecorderState.STOPPED
        super.onDestroy()
    }

    private fun startPipeline() {
        // The tunables live in DataStore, so the pipeline is assembled inside a coroutine
        // rather than blocking onCreate on a disk read.
        scope.launch {
            val settings = ServiceLocator.settings
            val threads = settings.asrThreads.first()
            val threshold = settings.vadThreshold.first()

            // Exactly what the detector is being handed, in the form it is handed. Loudness
            // was being reported from the same buffer, so "loud audio, no speech" could not
            // be separated from "audio in the wrong shape" without this line.
            Diagnostics.i(
                TAG,
                "capture: ${AudioCapture.SAMPLE_RATE} Hz mono, 16-bit PCM scaled to float " +
                    "-1..1, ${AudioCapture.FRAME_SAMPLES} samples per frame " +
                    "(${AudioCapture.FRAME_SAMPLES * 1000 / AudioCapture.SAMPLE_RATE} ms), " +
                    "threshold $threshold",
            )

            val silero = SileroVad.tryLoad(AsrModels.sileroVadFile(this@RecordingService))
            if (silero == null) {
                Diagnostics.w(TAG, "Silero VAD not installed; using the energy fallback")
            } else {
                // What the model actually declares, so a wrong tensor is visible from the
                // phone rather than inferred from suspiciously flat scores.
                Diagnostics.i(TAG, "Silero loaded: ${silero.layoutSummary}")
            }
            val detector = ResilientVad(
                primary = silero,
                threshold = threshold,
                onFallback = { reason -> Diagnostics.w(TAG, reason) },
            )
            val engine = SwappableAsrEngine(AsrEngineFactory.create(this@RecordingService, threads))
            if (!AsrEngineFactory.sherpaBundled) {
                Diagnostics.w(TAG, "sherpa-onnx not bundled in this build; recording produces no text")
            } else if (!AsrEngineFactory.modelsInstalled(this@RecordingService)) {
                Diagnostics.w(TAG, "speech model not installed yet; recording produces no text")
            } else {
                Diagnostics.i(TAG, "speech engine ready: ${engine.name}")
            }
            vad = detector
            detectorName = "${detector.activeName} at $threshold"
            asr = engine
            heartbeat()

            val pipeline = TranscriptPipeline(
                asr = engine,
                transcripts = ServiceLocator.database.transcripts(),
                folders = ServiceLocator.database.folders(),
                keywordWatcher = KeywordWatcher(
                    ServiceLocator.database.flagged(),
                    ServiceLocator.settings,
                ),
            )

            _state.value = RecorderState.RECORDING
            _recordingSince.value = System.currentTimeMillis()
            PowerMetrics.onRecordingStarted()
            updateNotification(getString(R.string.notification_recording, detector.activeName, engine.name))

            AudioCapture(onSilencedChanged = { silenced ->
                _micSilenced.value = silenced
                if (silenced) {
                    Diagnostics.w(TAG, "microphone is being given to another app; this recording is silent")
                } else {
                    Diagnostics.i(TAG, "microphone silencing cleared")
                }
            }).frames()
                // Pausing drops frames; it does not close the microphone. Reopening the mic
                // is how audio gets lost, and a paused recorder that has to be restarted by
                // hand cannot resume itself on Android 14+, where a microphone service
                // started from the background is refused outright.
                .transform { frame ->
                    if (pauseElapsed()) resumeNow()
                    if (_pausedUntil.value > 0L) {
                        if (!wasPaused) {
                            wasPaused = true
                            detector.reset()
                            Diagnostics.i(TAG, "recording paused until ${clock(_pausedUntil.value)}")
                        }
                        return@transform
                    }
                    if (wasPaused) {
                        wasPaused = false
                        detector.reset()
                    }
                    emit(frame)
                }
                .segmentSpeech(
                    vad = ObservedVad(detector, threshold, stats),
                    segmenter = SpeechSegmenter(threshold = threshold),
                    overCapture = OverCapture(speechThreshold = threshold),
                    onFallbackCapture = { stats.onFallbackCapture() },
                )
                .catch { error ->
                    Diagnostics.e(TAG, "capture pipeline failed", error)
                    _state.value = RecorderState.ERROR
                    updateNotification(getString(R.string.notification_error))
                }
                .collect { segment ->
                    val startedAt = System.currentTimeMillis()
                    val hadText = pipeline.process(segment)
                    stats.onSegment(segment.durationMs, System.currentTimeMillis() - startedAt, hadText)
                }
        }
    }

    /**
     * One line a minute about what the pipeline is doing. The whole point is that a phone
     * with no computer attached can answer "is it hearing me?" from Settings → Diagnostics
     * instead of from logcat, which is unreachable on this device.
     */
    private fun heartbeat() = scope.launch {
        var noted: String? = null
        while (isActive) {
            delay(HEARTBEAT_MS)
            runCatching { stats.heartbeat(TAG) }
            // Charging, heat and battery saver decide whether the AI may run at all, so a
            // change in any of them is worth a line with a time on it.
            runCatching { DeviceWatch.noteChanges(this@RecordingService) }
            // Whatever the detector worked out about itself, once, when it knows.
            vad?.note()?.takeIf { it != noted }?.let {
                noted = it
                detectorNote = it
                Diagnostics.i(TAG, it)
            }
        }
    }

    /** True when a pause has run out and recording should pick up again by itself. */
    private fun pauseElapsed(): Boolean {
        val until = _pausedUntil.value
        return until > 0L && System.currentTimeMillis() >= until
    }

    private fun clock(ts: Long): String = com.recorder.core.storage.Clocks.shortTime(ts)

    private fun vadName(): String = (vad as? ResilientVad)?.activeName ?: "energy"

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
        private const val HEARTBEAT_MS = 60_000L
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow(RecorderState.STOPPED)
        private val _recordingSince = MutableStateFlow<Long?>(null)

        /** When the current recording session started, for the cover screen's elapsed time. */
        val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()

        /** Observable so the UI can show whether recording is actually running. */
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        /**
         * Pipeline counters, on the companion so the self-diagnostic report can read them
         * without holding a reference to a service that may not be running.
         */
        val stats = PipelineStats()

        /** Which detector is live and at what threshold, e.g. "silero at 0.35". */
        @Volatile
        var detectorName: String? = null
            private set

        /** What the detector worked out about its own state, once it knows. */
        @Volatile
        var detectorNote: String? = null
            private set

        private val _pausedUntil = MutableStateFlow(0L)

        /**
         * When the current pause ends, or 0 when not paused.
         *
         * Deliberately not persisted. If the process dies while paused the pause is
         * forgotten and recording resumes, which is the safe direction to fail in: a
         * recorder that silently stays off is the one failure this app cannot recover from.
         */
        val pausedUntil: StateFlow<Long> = _pausedUntil.asStateFlow()

        /** Stops writing text for [minutes], keeping the microphone and service alive. */
        fun pauseFor(minutes: Int) {
            _pausedUntil.value = System.currentTimeMillis() + minutes * 60_000L
        }

        fun resumeNow() {
            if (_pausedUntil.value != 0L) {
                _pausedUntil.value = 0L
                Diagnostics.i(TAG, "recording resumed")
            }
        }

        private val _micSilenced = MutableStateFlow(false)

        /**
         * True while Android is feeding this recorder silence because another app holds the
         * microphone — typically the other installed version of Recorder. Android does not
         * fail the recording in that case, it quietly zeroes it, so this is the only signal.
         */
        val micSilenced: StateFlow<Boolean> = _micSilenced.asStateFlow()

        const val ACTION_RESUME = "com.recorder.app.action.RESUME"

        /** Tells a running recorder that a model has arrived since it started. */
        const val ACTION_MODELS_CHANGED = "com.recorder.app.action.MODELS_CHANGED"

        /**
         * Asks a *already running* recorder to pick up newly installed models. Deliberately
         * not a start: starting a microphone service from the background is refused on
         * Android 14+, so if recording is not running this does nothing and the models are
         * loaded the next time the user starts it.
         */
        fun notifyModelsChanged(context: Context) {
            if (_state.value != RecorderState.RECORDING) return
            runCatching {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(ACTION_MODELS_CHANGED),
                )
            }.onFailure { Diagnostics.w(TAG, "could not hand the new models to the recorder", it) }
        }

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
                blocked -> Diagnostics.w(TAG, "foreground start not allowed from here", error)
                error is SecurityException ->
                    // The while-in-use case: the system does not consider the microphone
                    // permission held because the app is in the background.
                    Diagnostics.w(TAG, "microphone not available to a background start", error)

                else -> Diagnostics.w(TAG, "could not start recording", error)
            }
            _state.value = RecorderState.STOPPED
        }

        fun stop(context: Context) {
            Diagnostics.i(TAG, "recording stopped")
            _pausedUntil.value = 0L
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
