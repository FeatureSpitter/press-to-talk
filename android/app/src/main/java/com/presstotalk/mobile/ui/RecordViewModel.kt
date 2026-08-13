package com.presstotalk.mobile.ui

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.presstotalk.mobile.asr.ModelStore
import com.presstotalk.mobile.asr.RecordingPipeline
import com.presstotalk.mobile.asr.SpeechRecognizer
import com.presstotalk.mobile.asr.TranscriptFormatter
import com.presstotalk.mobile.asr.Utterance
import com.presstotalk.mobile.asr.VadSegmenter
import com.presstotalk.mobile.asr.WhisperRecognizer
import com.presstotalk.mobile.audio.AudioRecorder
import com.presstotalk.mobile.data.AppSettings
import com.presstotalk.mobile.data.AppStore
import com.presstotalk.mobile.data.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface ModelState {
    data object Loading : ModelState
    data object Ready : ModelState
    /** No model on disk - actionable, with instructions. */
    data class Missing(val message: String) : ModelState
    data class Failed(val message: String) : ModelState
}

data class RecordUiState(
    val isRecording: Boolean = false,
    /** Stop was requested; the trailing utterance is still being recognised. */
    val isFinishing: Boolean = false,
    val liveText: String = "",
    val elapsedSeconds: Float = 0f,
    val level: Float = 0f,
    val modelState: ModelState = ModelState.Loading,
    /** Only models actually present on this device - see ModelStore.installedModels. */
    val availableModels: List<String> = emptyList(),
    val history: List<Transcript> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val message: String? = null,
) {
    val canRecord: Boolean get() = modelState is ModelState.Ready && !isFinishing
    val remainingSeconds: Float get() = (settings.maxRecordingSeconds - elapsedSeconds).coerceAtLeast(0f)
    /** Drives the timer turning amber near the cap. */
    val isNearCap: Boolean get() = isRecording && remainingSeconds <= CAP_WARNING_SECONDS

    companion object {
        const val CAP_WARNING_SECONDS = 30f
    }
}

class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppStore(application)
    private val modelStore = ModelStore(application)
    private val recorder = AudioRecorder(application)

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var segmenter: VadSegmenter? = null

    /** Identifies the loaded model so settings changes can force a reload. */
    private var loadedSignature: String? = null

    private var recordingJob: Job? = null

    /**
     * Stopping goes through this rather than cancelling [recordingJob]: a
     * cancelled scope cannot deliver the VAD's flushed tail, so the last thing
     * said would be lost.
     */
    @Volatile
    private var stopRequested = false

    private var interruptedByBackground = false

    init {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                modelStore.installedModels(KNOWN_MODELS)
            }
            _state.value = _state.value.copy(availableModels = installed)

            combine(store.settings, store.history) { settings, history -> settings to history }
                .collect { (settings, history) ->
                    _state.value = _state.value.copy(settings = settings, history = history)
                    ensureRecognizer(settings)
                }
        }
    }

    // --- model ---------------------------------------------------------------

    private suspend fun ensureRecognizer(settings: AppSettings) {
        // A stored model name can outlive the model itself - the build changed,
        // or someone picked one that was never installed. Fall back to something
        // real and persist it, rather than leaving the app permanently unusable.
        val installed = _state.value.availableModels
        if (installed.isNotEmpty() && settings.modelName !in installed) {
            val fallback = installed.last() // largest available is the most accurate
            Log.w(TAG, "Model '${settings.modelName}' is not installed; falling back to '$fallback'")
            store.updateSettings { it.copy(modelName = fallback) }
            _state.value = _state.value.copy(
                message = "${settings.modelName} isn't installed - using $fallback",
            )
            return // the settings flow re-emits and this runs again with the fallback
        }

        val signature = "${settings.modelName}/${settings.languageMode}/${settings.numThreads}"
        if (signature == loadedSignature && recognizer?.isLoaded == true) return
        if (_state.value.isRecording) return // never swap the model mid-recording

        _state.value = _state.value.copy(modelState = ModelState.Loading)

        withContext(Dispatchers.IO) {
            releaseEngines()
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
                _state.value = _state.value.copy(modelState = ModelState.Ready)
            } catch (missing: ModelStore.ModelMissingException) {
                Log.w(TAG, "Model '${settings.modelName}' unavailable: ${missing.message}")
                _state.value = _state.value.copy(
                    modelState = ModelState.Missing(missing.message ?: "Model not found"),
                )
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to load model '${settings.modelName}'", failure)
                _state.value = _state.value.copy(
                    modelState = ModelState.Failed(failure.message ?: failure.toString()),
                )
            }
        }
    }

    // --- recording -----------------------------------------------------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleRecording() {
        if (_state.value.isRecording) requestStop() else startRecording()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val engine = recognizer
        val vad = segmenter
        if (engine == null || vad == null || !_state.value.canRecord) return
        if (recordingJob?.isActive == true) return

        stopRequested = false
        interruptedByBackground = false
        _state.value = _state.value.copy(
            isRecording = true,
            isFinishing = false,
            liveText = "",
            elapsedSeconds = 0f,
            level = 0f,
            message = null,
        )

        val settings = _state.value.settings
        val startedAt = System.currentTimeMillis()
        val utterances = mutableListOf<Utterance>()

        recordingJob = viewModelScope.launch {
            val pipeline = RecordingPipeline(recorder, vad, engine)
            try {
                pipeline.run(settings.maxRecordingSeconds) { stopRequested }.collect { event ->
                    when (event) {
                        is RecordingPipeline.Event.Level ->
                            _state.value = _state.value.copy(
                                level = event.rms,
                                elapsedSeconds = event.elapsedSeconds,
                            )

                        is RecordingPipeline.Event.Text -> {
                            utterances += event.utterance
                            _state.value = _state.value.copy(
                                liveText = TranscriptFormatter.join(utterances),
                                // Stop is only truly done once the tail is in.
                                isFinishing = stopRequested,
                            )
                        }

                        RecordingPipeline.Event.CapReached ->
                            _state.value = _state.value.copy(
                                message = "Reached the ${settings.maxRecordingMinutes} minute limit",
                            )
                    }
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Recording failed", failure)
                _state.value = _state.value.copy(
                    message = failure.message ?: "Recording failed",
                )
            } finally {
                finishRecording(utterances, System.currentTimeMillis() - startedAt)
            }
        }
    }

    fun requestStop() {
        if (!_state.value.isRecording) return
        stopRequested = true
        _state.value = _state.value.copy(isFinishing = true)
    }

    /** Backgrounding stops capture, but keeps whatever was already transcribed. */
    fun onMovedToBackground() {
        if (!_state.value.isRecording) return
        interruptedByBackground = true
        requestStop()
    }

    private suspend fun finishRecording(utterances: List<Utterance>, durationMs: Long) {
        val text = TranscriptFormatter.join(utterances)
        val wasInterrupted = interruptedByBackground

        _state.value = _state.value.copy(
            isRecording = false,
            isFinishing = false,
            level = 0f,
            liveText = "",
        )
        stopRequested = false
        interruptedByBackground = false

        if (text.isBlank()) {
            _state.value = _state.value.copy(message = "No speech detected")
            return
        }

        store.addTranscript(
            Transcript(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                text = text,
                durationMs = durationMs,
                language = utterances.firstNotNullOfOrNull { it.language },
                interrupted = wasInterrupted,
            ),
        )
    }

    // --- settings ------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.updateSettings(transform) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun releaseEngines() {
        runCatching { recognizer?.close() }
        runCatching { segmenter?.close() }
        recognizer = null
        segmenter = null
        loadedSignature = null
    }

    override fun onCleared() {
        recordingJob?.cancel()
        releaseEngines()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RecordViewModel"
        /** Everything the app knows how to load; the picker shows the subset present. */
        val KNOWN_MODELS = listOf("tiny", "base", "small")
    }
}
