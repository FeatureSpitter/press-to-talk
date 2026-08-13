package com.presstotalk.mobile.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whisper via sherpa-onnx, CPU only.
 *
 * Android has no usable acceleration for this: there is no GPU backend, and
 * NNAPI is deprecated as of Android 15 and a poor fit for Whisper's
 * dynamic-shape decoder.
 *
 * The recognizer holds one loaded model guarded by a lock, mirroring the
 * desktop app's single-model-plus-lock arrangement. Decoding is greedy-only and
 * unbatched - sherpa-onnx hard-errors on any other decoding method for Whisper.
 */
class WhisperRecognizer(
    private val paths: ModelStore.ModelPaths,
    private val languageMode: LanguageMode = LanguageMode.AUTO,
    private val numThreads: Int = DEFAULT_THREADS,
) : SpeechRecognizer {

    private val lock = ReentrantLock()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override val isLoaded: Boolean get() = recognizer != null

    override fun load() {
        lock.withLock {
            if (recognizer != null) return

            val language = languageMode.whisperCode
            require(LanguageMode.isSafeWhisperCode(language)) {
                "Refusing to pass '$language' to sherpa-onnx: an unrecognised language " +
                    "code terminates the process instead of throwing."
            }

            val started = System.currentTimeMillis()
            recognizer = OfflineRecognizer(
                assetManager = null, // load from a filesystem path, never from assets
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = paths.encoder.absolutePath,
                            decoder = paths.decoder.absolutePath,
                            // "" means Whisper detects the language itself. The Kotlin
                            // default is "en", which would force English onto Portuguese.
                            language = language,
                            task = "transcribe",
                            // Upstream guidance: 50 for .en models, 300 for multilingual.
                            // Too little padding and Whisper never emits its end token.
                            tailPaddings = MULTILINGUAL_TAIL_PADDINGS,
                        ),
                        tokens = paths.tokens.absolutePath,
                        modelType = "whisper",
                        numThreads = numThreads,
                        provider = "cpu",
                    ),
                    // Whisper supports nothing else; any other method hard-errors.
                    decodingMethod = "greedy_search",
                ),
            )
            Log.i(
                TAG,
                "Loaded '${paths.name}' in ${System.currentTimeMillis() - started} ms " +
                    "(threads=$numThreads, language='${language.ifEmpty { "auto" }}')",
            )
        }
    }

    override fun recognize(samples: FloatArray): RecognizedText? {
        if (samples.isEmpty()) return null

        return lock.withLock {
            val engine = recognizer ?: error("recognize() called before load()")

            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, SpeechRecognizer.SAMPLE_RATE)
                engine.decode(stream)
                val result = engine.getResult(stream)
                val text = result.text.trim()
                if (text.isEmpty()) null else RecognizedText(text, result.lang.takeIf { it.isNotBlank() })
            } finally {
                stream.release()
            }
        }
    }

    override fun close() {
        lock.withLock {
            recognizer?.release()
            recognizer = null
        }
    }

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val MULTILINGUAL_TAIL_PADDINGS = 300

        /**
         * The Pixel 8 Pro has 9 cores, but Whisper's greedy decoder scales poorly
         * and the audio thread must stay responsive. Revisit with the benchmark.
         */
        const val DEFAULT_THREADS = 4
    }
}
