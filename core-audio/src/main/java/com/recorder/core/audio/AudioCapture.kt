package com.recorder.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.buffer
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.thread

/**
 * Continuous microphone capture. One [AudioRecord] is opened and never closed while the
 * recording service is alive: re-opening the mic is what usually loses audio around
 * screen-off and fold transitions, so the read loop stays running and downstream
 * components decide what to do with the frames.
 */
class AudioCapture(
    val sampleRate: Int = SAMPLE_RATE,
    val frameSamples: Int = FRAME_SAMPLES,
    /**
     * Told when Android starts or stops silencing this recording because another app has
     * taken the microphone (Android 10+ concurrent-capture policy). Metadata only.
     */
    private val onSilencedChanged: ((Boolean) -> Unit)? = null,
) {

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<AudioFrame> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord reports no usable buffer size at ${sampleRate}Hz" }

        // A generous buffer keeps a slow ASR pass from dropping audio on the floor.
        val bufferBytes = maxOf(minBuffer, frameSamples * 2 * BUFFER_FRAMES)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            close(IllegalStateException("AudioRecord failed to initialise"))
            return@callbackFlow
        }

        record.startRecording()
        val watcher = Executors.newSingleThreadExecutor()
        val silenceWatch = watchSilencing(record, watcher)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val reader = thread(name = "audio-capture", isDaemon = true) {
            val pcm = ShortArray(frameSamples)
            val floats = FloatArray(frameSamples)
            while (running.get()) {
                val read = record.read(pcm, 0, frameSamples)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "AudioRecord read error $read")
                        break
                    }
                    continue
                }
                val ts = System.currentTimeMillis()
                for (i in 0 until read) floats[i] = pcm[i] / 32768f
                if (read < frameSamples) java.util.Arrays.fill(floats, read, frameSamples, 0f)
                trySend(AudioFrame(floats.copyOf(), sampleRate, ts))
            }
        }

        awaitClose {
            silenceWatch?.let { runCatching { record.unregisterAudioRecordingCallback(it) } }
            watcher.shutdown()
            running.set(false)
            reader.join(500)
            runCatching { record.stop() }
            record.release()
        }
    }
        // Up to a minute of audio can queue here if decoding falls behind — for instance
        // while the correction model has the CPU. A short default buffer would silently drop
        // frames instead, and dropped audio is the one loss this app cannot recover from.
        .buffer(capacity = BACKLOG_FRAMES)
        .flowOn(Dispatchers.IO)

    private fun watchSilencing(record: AudioRecord, executor: Executor): AudioManager.AudioRecordingCallback? {
        val listener = onSilencedChanged ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        var last: Boolean? = null
        fun report(silenced: Boolean) {
            if (silenced != last) {
                last = silenced
                listener(silenced)
            }
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                report(mine?.isClientSilenced == true)
            }
        }
        record.registerAudioRecordingCallback(executor, callback)
        record.activeRecordingConfiguration?.let { report(it.isClientSilenced) }
        return callback
    }

    companion object {
        private const val TAG = "AudioCapture"

        /** Both Silero VAD and the ASR models expect 16 kHz mono. */
        const val SAMPLE_RATE = 16_000

        /** 32 ms at 16 kHz — the frame size Silero VAD is trained on. */
        const val FRAME_SAMPLES = 512

        private const val BUFFER_FRAMES = 32

        /** 60 s of 32 ms frames, about 3.8 MB at worst. */
        private const val BACKLOG_FRAMES = 1_875
    }
}

/** One fixed-size window of mono float PCM in [-1, 1], stamped when it left the mic. */
data class AudioFrame(
    val samples: FloatArray,
    val sampleRate: Int,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioFrame && timestampMs == other.timestampMs && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * samples.contentHashCode() + timestampMs.hashCode()
}
