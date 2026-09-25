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

/**
 * The tail of the previous frame, which Silero v5 and v6 require in front of the current one.
 *
 * Their ONNX graph does not take a bare analysis window. It takes the window with a fixed
 * amount of preceding audio attached — 64 samples at 16 kHz, 32 at 8 kHz — and the app is
 * responsible for carrying that across calls. The `input` dimension is dynamic, so handing it
 * a bare 512-sample window is accepted without any error at all, and the model returns
 * approximately 0.001 for every frame of everything: silence, a quiet room, loud white noise,
 * a tone, and strong clear speech alike. Measured against the shipped v6.2.3 model, the same
 * synthetic voiced signal scores 0.0007 fed bare and 0.86 fed with its context.
 *
 * That is what "the detector best score was 0.00" meant in this app's diagnostics for weeks,
 * and why every window ended up rescued by over-capture as a twenty-second block of room
 * noise that the speech model then correctly transcribed as nothing.
 */
internal class FrameContext(contextSamples: Int) {

    private var tail = FloatArray(contextSamples)

    /** The window to feed: the carried tail, then [frame]. Remembers this frame's own tail. */
    fun window(frame: FloatArray): FloatArray {
        val out = FloatArray(tail.size + frame.size)
        tail.copyInto(out, 0)
        frame.copyInto(out, tail.size)
        // The last n samples of (tail + frame) are the last n samples of the stream, which is
        // the definition regardless of whether a frame is shorter than the context itself.
        tail = out.copyOfRange(out.size - tail.size, out.size)
        return out
    }

    fun reset() {
        tail = FloatArray(tail.size)
    }

    companion object {
        /** 64 samples at 16 kHz, 32 at 8 kHz — what the v5/v6 graph was exported with. */
        fun sizeFor(sampleRate: Int): Int = (sampleRate / 250).coerceAtLeast(0)
    }
}

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

    /** Only v5/v6 carry context; v4 takes a bare 1536-sample window. */
    private var context: FrameContext? = null

    override fun speechProbability(frame: FloatArray, sampleRate: Int): Float {
        val window = when (layout) {
            Layout.V5_STATE -> {
                val carried = context
                    ?: FrameContext(FrameContext.sizeFor(sampleRate)).also { context = it }
                carried.window(frame)
            }

            Layout.V4_HC -> frame
        }
        val inputs = HashMap<String, OnnxTensor>()
        return try {
            inputs["input"] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(window),
                longArrayOf(1, window.size.toLong()),
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

    override fun note(): String? =
        listOfNotNull(selfTestNote, stateNote).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /**
     * Asks the model, once at load, whether it can recognise speech at all.
     *
     * This exists because the failure it catches was invisible for weeks. A detector fed the
     * wrong shape of window does not throw, does not log, and does not look broken: it returns
     * a small number for every frame, which is exactly what a correct detector returns in a
     * quiet room. The only way to tell them apart is to hand it something that is definitely
     * speech-shaped and see whether the number moves.
     *
     * The probe is half a second of a synthesised voiced sound — a 120 Hz harmonic series with
     * the first three formants emphasised and a four-per-second syllable envelope. Against the
     * shipped v6.2.3 model it scores 0.86 fed correctly and 0.0007 fed as a bare window, so any
     * threshold between those settles the question; [SELF_TEST_MIN] sits far from both.
     *
     * A failure here is reported, not acted on: the energy fallback is [ResilientVad]'s
     * decision to make, and a probe is weaker evidence than the real audio that follows it.
     */
    private fun runSelfTest(sampleRate: Int): String {
        val probe = speechProbe(sampleRate)
        var best = 0f
        var frame = 0
        while ((frame + 1) * PROBE_FRAME <= probe.size) {
            val slice = probe.copyOfRange(frame * PROBE_FRAME, (frame + 1) * PROBE_FRAME)
            val p = runCatching { speechProbability(slice, sampleRate) }.getOrElse { error ->
                reset()
                return "Silero could not run its own speech probe: ${error.message ?: error.javaClass.simpleName}"
            }
            if (p > best) best = p
            frame++
        }
        reset()
        val score = "%.2f".format(best)
        return if (best >= SELF_TEST_MIN) {
            "Silero scored $score on its built-in speech probe, so it is scoring speech"
        } else {
            "Silero scored only $score on its built-in speech probe (expected above " +
                "$SELF_TEST_MIN) — it is loaded and running but not recognising speech"
        }
    }

    @Volatile
    private var selfTestNote: String? = null

    @Volatile
    private var stateNote: String? = null
    private var stateChecked = false
    private var framesSeen = 0

    override fun reset() {
        state = FloatArray(state.size)
        h = FloatArray(h.size)
        c = FloatArray(c.size)
        context?.reset()
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

        /** Comfortably between the 0.86 a correctly fed model scores and the 0.0007 it does not. */
        const val SELF_TEST_MIN = 0.2f

        private const val SELF_TEST_RATE = 16_000
        private const val PROBE_FRAME = 512
        private const val PROBE_SECONDS = 0.5

        /**
         * Half a second of something unmistakably voice-shaped: a 120 Hz harmonic series with
         * the first three formants emphasised, under a four-per-second syllable envelope.
         * Synthetic on purpose — bundling a speech recording to test a speech detector would
         * add a licence and a megabyte to answer a question arithmetic can answer.
         */
        internal fun speechProbe(sampleRate: Int): FloatArray {
            val n = (sampleRate * PROBE_SECONDS).toInt()
            val raw = FloatArray(n)
            var peak = 0f
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                var v = 0.0
                for (k in 1..25) {
                    val f = 120.0 * k
                    var gain = 1.0 / k
                    gain += 3.0 * kotlin.math.exp(-((f - 700.0) * (f - 700.0)) / (2 * 120.0 * 120.0)) / k
                    gain += 2.0 * kotlin.math.exp(-((f - 1200.0) * (f - 1200.0)) / (2 * 150.0 * 150.0)) / k
                    gain += 1.2 * kotlin.math.exp(-((f - 2600.0) * (f - 2600.0)) / (2 * 200.0 * 200.0)) / k
                    v += gain * kotlin.math.sin(2 * Math.PI * f * t + k)
                }
                raw[i] = v.toFloat()
                val a = kotlin.math.abs(raw[i])
                if (a > peak) peak = a
            }
            if (peak <= 0f) return raw
            for (i in 0 until n) {
                val envelope = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 4.0 * i / sampleRate)
                raw[i] = (0.3f * raw[i] / peak) * (envelope * envelope).toFloat()
            }
            return raw
        }

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
                    it.selfTestNote = it.runSelfTest(SELF_TEST_RATE)
                    Log.i(TAG, it.selfTestNote.orEmpty())
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
