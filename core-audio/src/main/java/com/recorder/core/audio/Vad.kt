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

    /**
     * Something the detector has noticed about itself worth putting in front of a person,
     * or null. Read rather than logged, because core-audio has no idea how the app records
     * anything and logcat is unreadable on a phone with no computer attached.
     */
    fun note(): String? = null

    override fun close() {}
}

/**
 * Silero VAD through ONNX Runtime. The model file is not shipped in the APK — see
 * `scripts/fetch_models.sh`. Use [tryLoad] so a missing or unreadable model degrades to
 * [EnergyVad] instead of taking the recording pipeline down with it.
 *
 * [speechProbability] lets inference failures propagate on purpose. It used to catch
 * everything and return 0, which is indistinguishable from a silent room: a model that
 * loaded but could not run made the whole app look like a dead microphone, with only a
 * logcat line to say otherwise and no way to read logcat on the phone. Wrap this in
 * [ResilientVad], which counts failures and switches to the energy detector for good.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val layout: Layout,
) : VoiceActivityDetector {

    /**
     * Outputs are read by name, never by position.
     *
     * Reading `result[0]` assumed the model lists its speech probability first. On a real
     * phone Silero scored 0.001 on every frame of every recording, in a quiet room and in a
     * car, which is not what a conservative detector looks like — it is what reading the
     * wrong tensor looks like. A first element taken out of the recurrent state is a small
     * number near zero, every time, and the state was then being overwritten with the
     * probability, so it could never recover either.
     */
    private val stateOutput: String? =
        session.outputNames.firstOrNull { it.contains("state", ignoreCase = true) || it == "stateN" }

    private val hOutput: String? = session.outputNames.firstOrNull { it.equals("hn", true) || it == "h" }
    private val cOutput: String? = session.outputNames.firstOrNull { it.equals("cn", true) || it == "c" }

    private val probabilityOutput: String =
        session.outputNames.firstOrNull { it == "output" }
            ?: session.outputNames.firstOrNull { it != stateOutput && it != hOutput && it != cOutput }
            ?: session.outputNames.first()

    /**
     * What this model actually declares, shapes included, for the diagnostics screen.
     *
     * The shapes are the point. "input,state,sr" told us the names matched and nothing
     * about whether the tensor being handed over is the shape the model wants, which is
     * the difference between a detector that is being fed properly and one that is being
     * fed a 512-sample window it cannot use.
     */
    val layoutSummary: String
        get() = "inputs ${describe(session.inputInfo)} → outputs ${describe(session.outputInfo)} " +
            "(probability from \"$probabilityOutput\", state from \"${stateOutput ?: hOutput}\")"

    private fun describe(info: Map<String, ai.onnxruntime.NodeInfo>): String =
        info.entries.joinToString(", ") { (name, node) ->
            val shape = (node.info as? ai.onnxruntime.TensorInfo)
                ?.shape?.joinToString("x") { if (it < 0) "?" else it.toString() }
            if (shape == null) name else "$name[$shape]"
        }

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
                val probability = result.value(probabilityOutput).firstProbability()
                when (layout) {
                    Layout.V5_STATE ->
                        stateOutput?.let {
                            state = result.value(it).flattenFloats(state.size)
                            checkStateIsAlive()
                        }

                    Layout.V4_HC -> {
                        hOutput?.let { h = result.value(it).flattenFloats(h.size) }
                        cOutput?.let { c = result.value(it).flattenFloats(c.size) }
                    }
                }
                probability
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Whether the recurrent state is actually coming back, checked once per session.
     *
     * A detector that scores near zero on every frame looks identical whether it is right
     * about the room or its memory is being silently dropped, and the two have completely
     * different fixes. A state that is still all zeros after real audio has gone through it,
     * or that contains anything non-finite, is the second case and says so out loud rather
     * than leaving it to be inferred from suspiciously flat scores.
     *
     * Runs on the first few frames only; after that the answer cannot change.
     */
    private fun checkStateIsAlive() {
        if (stateChecked) return
        framesSeen++
        if (framesSeen < STATE_CHECK_AFTER) return
        stateChecked = true

        val allZero = state.all { it == 0f }
        val broken = state.any { !it.isFinite() }
        stateNote = when {
            broken -> "Silero's state contains NaN or infinity — its memory is corrupt"
            allZero ->
                "Silero's state is still all zeros after $framesSeen frames — it is not " +
                    "remembering anything between frames"

            else -> "Silero's state is updating (${state.count { it != 0f }} of ${state.size} non-zero)"
        }
        Log.i(TAG, stateNote.orEmpty())
    }

    override fun note(): String? = stateNote

    @Volatile
    private var stateNote: String? = null
    private var stateChecked = false
    private var framesSeen = 0

    override fun reset() {
        state = FloatArray(state.size)
        h = FloatArray(h.size)
        c = FloatArray(c.size)
        stateChecked = false
        framesSeen = 0
        stateNote = null
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SileroVad"

        /** Enough frames for real audio to have moved the state, without waiting around. */
        private const val STATE_CHECK_AFTER = 30

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
                SileroVad(env, session, layout).also {
                    Log.i(TAG, "Silero loaded: ${it.layoutSummary}")
                }
            }.onFailure { Log.w(TAG, "Failed to load Silero model", it) }.getOrNull()
        }

        /** One output by name. Throws rather than guessing, so a wrong name is visible. */
        private fun OrtSession.Result.value(name: String): Any =
            get(name).orElseThrow { IllegalStateException("model has no output named \"$name\"") }.value

        private fun Any?.firstProbability(): Float = (this as? Array<*>)?.firstProbabilityImpl() ?: 0f

        private fun Any?.flattenFloats(expected: Int): FloatArray {
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

        private fun Array<*>.firstProbabilityImpl(): Float {
            val row = this.firstOrNull() ?: return 0f
            return when (row) {
                is FloatArray -> row.firstOrNull() ?: 0f
                is Array<*> -> (row.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f
                is Float -> row
                else -> 0f
            }
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
        val db = rmsDb(frame)
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

/** Frame loudness in dBFS. Shared so the energy detector and its auditor agree. */
internal fun rmsDb(frame: FloatArray): Float {
    if (frame.isEmpty()) return -120f
    var sum = 0.0
    for (s in frame) sum += (s * s).toDouble()
    val rms = sqrt(sum / frame.size).toFloat()
    return 20f * kotlin.math.log10(rms.coerceAtLeast(1e-7f))
}
