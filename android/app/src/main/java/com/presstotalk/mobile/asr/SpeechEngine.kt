package com.presstotalk.mobile.asr

import android.content.Context
import android.util.Log
import com.presstotalk.mobile.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the loaded Whisper recognizer + VAD, shared by the record screen and the
 * WhatsApp/file transcription flows.
 *
 * One recognizer is loaded per app process (guarded by the lock in
 * [WhisperRecognizer]); keeping it here means the two screens never load two
 * copies of a 375 MB model at once. [ensure] reloads only when the requested
 * signature (model/language/threads) actually changes.
 */
class SpeechEngine(private val context: Context) : AutoCloseable {

    private val modelStore = ModelStore(context)

    @Volatile
    var state: ModelState = ModelState.Loading
        private set

    var recognizer: SpeechRecognizer? = null
        private set

    var segmenter: VadSegmenter? = null
        private set

    private var loadedSignature: String? = null

    val availableModels: List<String>
        get() = modelStore.installedModels(KNOWN_MODELS)

    suspend fun ensure(settings: AppSettings) {
        val installed = availableModels
        if (installed.isNotEmpty() && settings.modelName !in installed) {
            val fallback = installed.last() // largest available is the most accurate
            Log.w(TAG, "Model '${settings.modelName}' is not installed; falling back to '$fallback'")
            ensure(AppSettings().copy(modelName = fallback))
            return
        }

        val signature = "${settings.modelName}/${settings.languageMode}/${settings.numThreads}"
        if (signature == loadedSignature && recognizer?.isLoaded == true) return

        state = ModelState.Loading
        withContext(Dispatchers.IO) {
            release()
            try {
                val paths = modelStore.prepare(settings.modelName)
                val whisper = WhisperRecognizer(
                    paths = paths,
                    languageMode = settings.languageMode,
                    numThreads = settings.numThreads,
                )
                whisper.load()
                recognizer = whisper
                segmenter = VadSegmenter(paths.vad)
                loadedSignature = signature
                state = ModelState.Ready
            } catch (missing: ModelStore.ModelMissingException) {
                Log.w(TAG, "Model '${settings.modelName}' unavailable: ${missing.message}")
                state = ModelState.Missing(missing.message ?: "Model not found")
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to load model '${settings.modelName}'", failure)
                state = ModelState.Failed(failure.message ?: failure.toString())
            }
        }
    }

    override fun close() {
        release()
    }

    private fun release() {
        runCatching { recognizer?.close() }
        runCatching { segmenter?.close() }
        recognizer = null
        segmenter = null
        loadedSignature = null
    }

    companion object {
        private const val TAG = "SpeechEngine"
        /** Everything the app knows how to load; pickers show the subset present. */
        val KNOWN_MODELS = listOf("tiny", "base", "small")
    }
}
