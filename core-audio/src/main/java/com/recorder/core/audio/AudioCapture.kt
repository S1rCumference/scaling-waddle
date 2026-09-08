package com.recorder.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
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
            running.set(false)
            reader.join(500)
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "AudioCapture"

        /** Both Silero VAD and the ASR models expect 16 kHz mono. */
        const val SAMPLE_RATE = 16_000

        /** 32 ms at 16 kHz — the frame size Silero VAD is trained on. */
        const val FRAME_SAMPLES = 512

        private const val BUFFER_FRAMES = 32
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
