package com.recorder.core.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Speech / not-speech decision for one [AudioFrame]. */
interface VoiceActivityDetector : Closeable {
    /** Probability in [0, 1] that the frame contains speech. */
    fun speechProbability(frame: FloatArray, sampleRate: Int): Float

    fun reset()

    override fun close() {}
}

/**
 * Silero VAD through ONNX Runtime. The model file is not shipped in the APK — see
 * `scripts/fetch_models.sh`. Use [tryLoad] so a missing or unreadable model degrades to
 * [EnergyVad] instead of taking the recording pipeline down with it.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val layout: Layout,
) : VoiceActivityDetector {

    /** Silero v5 carries one packed `state` tensor; v4 carries separate `h` and `c`. */
    private enum class Layout { V5_STATE, V4_HC }

    private var state = FloatArray(2 * 1 * 128)
    private var h = FloatArray(2 * 1 * 64)
    private var c = FloatArray(2 * 1 * 64)

    override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
        val inputs = HashMap<String, OnnxTensor>()
        return try {
            inputs["input"] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(frame),
                longArrayOf(1, frame.size.toLong()),
            )
            inputs["sr"] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(sampleRate.toLong())),
                longArrayOf(1),
            )
            when (layout) {
                Layout.V5_STATE -> inputs["state"] =
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))

                Layout.V4_HC -> {
                    inputs["h"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64))
                    inputs["c"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64))
                }
            }

            session.run(inputs).use { result ->
                val probability = (result[0].value as Array<*>).firstProbability()
                when (layout) {
                    Layout.V5_STATE -> state = (result[1].value as Array<*>).flattenFloats(state.size)
                    Layout.V4_HC -> {
                        h = (result[1].value as Array<*>).flattenFloats(h.size)
                        c = (result[2].value as Array<*>).flattenFloats(c.size)
                    }
                }
                probability
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Silero inference failed, treating frame as silence", t)
            0f
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    override fun reset() {
        state = FloatArray(state.size)
        h = FloatArray(h.size)
        c = FloatArray(c.size)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SileroVad"

        fun tryLoad(modelFile: File): SileroVad? {
            if (!modelFile.isFile) {
                Log.i(TAG, "No Silero model at ${modelFile.absolutePath}")
                return null
            }
            return runCatching {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(modelFile.absolutePath, options)
                val layout = if (session.inputNames.contains("state")) Layout.V5_STATE else Layout.V4_HC
                SileroVad(env, session, layout)
            }.onFailure { Log.w(TAG, "Failed to load Silero model", it) }.getOrNull()
        }

        private fun Array<*>.firstProbability(): Float {
            val row = this.firstOrNull() ?: return 0f
            return when (row) {
                is FloatArray -> row.firstOrNull() ?: 0f
                is Array<*> -> (row.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f
                is Float -> row
                else -> 0f
            }
        }

        private fun Array<*>.flattenFloats(expected: Int): FloatArray {
            val out = FloatArray(expected)
            var i = 0
            fun walk(node: Any?) {
                when (node) {
                    is FloatArray -> for (v in node) { if (i < expected) out[i++] = v }
                    is Array<*> -> node.forEach(::walk)
                    is Float -> if (i < expected) out[i++] = node
                }
            }
            walk(this)
            return out
        }
    }
}

/**
 * RMS gate with a slowly-adapting noise floor. Not as sharp as Silero, but it needs no
 * model file, so a freshly flashed phone still records and transcribes on first boot.
 */
class EnergyVad(
    private val marginDb: Float = 9f,
) : VoiceActivityDetector {

    private var noiseFloorDb = -55f

    override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
        var sum = 0.0
        for (s in frame) sum += (s * s).toDouble()
        val rms = sqrt(sum / frame.size).toFloat()
        val db = 20f * kotlin.math.log10(rms.coerceAtLeast(1e-7f))

        val isSpeech = db > noiseFloorDb + marginDb
        // Track the floor only on quiet frames so a long sentence can't drag it upward.
        if (!isSpeech) noiseFloorDb += (db - noiseFloorDb) * FLOOR_ADAPT
        return if (isSpeech) 1f else 0f
    }

    override fun reset() {
        noiseFloorDb = -55f
    }

    private companion object {
        const val FLOOR_ADAPT = 0.05f
    }
}
