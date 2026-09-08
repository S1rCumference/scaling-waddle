package com.recorder.core.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Parakeet-TDT (INT8) through sherpa-onnx. Compiled only when the sherpa-onnx AAR is in
 * `core-asr/libs/` — see that directory's README.
 *
 * Model files expected in [modelDir]: `encoder.onnx`, `decoder.onnx`, `joiner.onnx`,
 * `tokens.txt`. Filenames may carry the usual `.int8` infix; the loader matches by prefix.
 */
class SherpaAsrEngine(private val recognizer: OfflineRecognizer) : AsrEngine {

    // sherpa's recognizer is not safe to decode on from two threads at once.
    private val lock = Mutex()

    override val name: String get() = "sherpa-onnx / Parakeet-TDT"

    override suspend fun transcribe(audioChunk: FloatArray, sampleRate: Int): String =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(audioChunk, sampleRate)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }

    override fun close() {
        runCatching { recognizer.release() }
    }
}

class SherpaAsrEnginePlugin : AsrEnginePlugin {
    override fun create(modelDir: File): AsrEngine? = runCatching {
        val encoder = modelDir.matching("encoder") ?: error("encoder model missing")
        val decoder = modelDir.matching("decoder") ?: error("decoder model missing")
        val joiner = modelDir.matching("joiner") ?: error("joiner model missing")
        val tokens = modelDir.matching("tokens") ?: error("tokens.txt missing")

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                modelType = "nemo_transducer",
                numThreads = 2,
            ),
        )
        SherpaAsrEngine(OfflineRecognizer(assetManager = null, config = config))
    }.onFailure { Log.w("SherpaAsrEnginePlugin", "could not build recognizer", it) }.getOrNull()

    private fun File.matching(prefix: String): File? =
        listFiles()?.firstOrNull { it.name.startsWith(prefix) }
}
