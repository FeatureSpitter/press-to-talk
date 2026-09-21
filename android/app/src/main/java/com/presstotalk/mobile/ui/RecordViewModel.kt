package com.presstotalk.mobile.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.presstotalk.mobile.asr.FileTranscriptionPipeline
import com.presstotalk.mobile.asr.ModelState
import com.presstotalk.mobile.asr.RecordingPipeline
import com.presstotalk.mobile.asr.SpeechEngine
import com.presstotalk.mobile.asr.TranscriptFormatter
import com.presstotalk.mobile.asr.Utterance
import com.presstotalk.mobile.audio.AudioDecoder
import com.presstotalk.mobile.audio.AudioRecorder
import com.presstotalk.mobile.data.AppSettings
import com.presstotalk.mobile.data.AppStore
import com.presstotalk.mobile.data.Transcript
import com.presstotalk.mobile.data.TranscriptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class RecordUiState(
    val isRecording: Boolean = false,
    /** Stop was requested; the trailing utterance is still being recognised. */
    val isFinishing: Boolean = false,
    val liveText: String = "",
    val elapsedSeconds: Float = 0f,
    val level: Float = 0f,
    val modelState: ModelState = ModelState.Loading,
    /** Only models actually present on this device - see SpeechEngine.availableModels. */
    val availableModels: List<String> = emptyList(),
    val history: List<Transcript> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val message: String? = null,

    // --- file transcription --------------------------------------------------
    /** A picked audio file is being decoded + transcribed. */
    val isTranscribingFile: Boolean = false,
    /** 0..1 across decode + recognition, for the inline progress bar. */
    val fileProgress: Float = 0f,
    /** Name of the file being transcribed, shown next to the progress. */
    val transcribingFileName: String? = null,
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
    private val engine = SpeechEngine(application)
    private val recorder = AudioRecorder(application)
    private val decoder = AudioDecoder(application)

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var recordingJob: Job? = null
    private var fileJob: Job? = null

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
            _state.value = _state.value.copy(availableModels = engine.availableModels)

            combine(store.settings, store.history) { settings, history -> settings to history }
                .collect { (settings, history) ->
                    _state.value = _state.value.copy(settings = settings, history = history)
                    engine.ensure(settings)
                    _state.value = _state.value.copy(modelState = engine.state)
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
        val engineRec = engine.recognizer
        val vad = engine.segmenter
        if (engineRec == null || vad == null || !_state.value.canRecord) return
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
            val pipeline = RecordingPipeline(recorder, vad, engineRec)
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

    // --- file transcription --------------------------------------------------

    /**
     * Decodes [uri] and transcribes it with the same engine as the mic, saving
     * the result to history. Reusable by the WhatsApp screen via [transcribeSamples].
     */
    fun transcribeFile(
        uri: Uri,
        displayName: String? = null,
        source: TranscriptSource = TranscriptSource.FILE,
    ) {
        if (_state.value.isTranscribingFile) return
        val engineRec = engine.recognizer
        val vad = engine.segmenter
        if (engineRec == null || vad == null || _state.value.modelState !is ModelState.Ready) {
            _state.value = _state.value.copy(message = "Wait for the model to load first")
            return
        }

        _state.value = _state.value.copy(
            isTranscribingFile = true,
            fileProgress = 0f,
            transcribingFileName = displayName,
            message = null,
        )
        fileJob = viewModelScope.launch {
            try {
                val samples = withContext(Dispatchers.IO) {
                    _state.value = _state.value.copy(fileProgress = 0.05f)
                    decoder.decode(uri)
                }
                transcribeSamples(
                    samples = samples,
                    durationMs = (samples.size.toLong() / 16_000L) * 1000L,
                    source = source,
                    sourceLabel = displayName,
                )
            } catch (failure: Exception) {
                Log.e(TAG, "File transcription failed", failure)
                _state.value = _state.value.copy(message = failure.message ?: "Could not transcribe this file")
            } finally {
                _state.value = _state.value.copy(isTranscribingFile = false, fileProgress = 0f, transcribingFileName = null)
            }
        }
    }

    /** Shared by the file picker and the WhatsApp browser. */
    suspend fun transcribeSamples(
        samples: FloatArray,
        durationMs: Long,
        source: TranscriptSource,
        sourceLabel: String? = null,
    ) {
        val engineRec = engine.recognizer ?: return
        val vad = engine.segmenter ?: return
        if (samples.isEmpty()) {
            _state.value = _state.value.copy(message = "This file contained no audio")
            return
        }

        val pipeline = FileTranscriptionPipeline(vad, engineRec)
        val utterances = mutableListOf<Utterance>()
        withContext(Dispatchers.Default) {
            pipeline.transcribe(samples).collect { event ->
                when (event) {
                    is FileTranscriptionPipeline.Event.Progress ->
                        _state.value = _state.value.copy(fileProgress = event.fraction)
                    is FileTranscriptionPipeline.Event.Text -> utterances += event.utterance
                }
            }
        }

        val text = TranscriptFormatter.join(utterances)
        if (text.isBlank()) {
            _state.value = _state.value.copy(message = "No speech detected in this file")
            return
        }
        store.addTranscript(
            Transcript(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                text = text,
                durationMs = durationMs,
                language = utterances.firstNotNullOfOrNull { it.language },
                source = source,
                sourceLabel = sourceLabel,
            ),
        )
    }

    // --- settings ------------------------------------------------------------

    fun deleteTranscript(id: String) {
        viewModelScope.launch { store.deleteTranscript(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { store.clearHistory() }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.updateSettings(transform) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        recordingJob?.cancel()
        fileJob?.cancel()
        engine.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RecordViewModel"
    }
}
