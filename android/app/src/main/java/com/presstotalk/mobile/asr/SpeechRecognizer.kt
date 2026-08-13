package com.presstotalk.mobile.asr

/** What the user picked in settings, mapped to Whisper's language codes. */
enum class LanguageMode(val whisperCode: String) {
    /** Whisper detects the language itself, per utterance. */
    AUTO(""),
    PORTUGUESE("pt"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * sherpa-onnx validates the language string in native code and calls
         * `SHERPA_ONNX_EXIT(-1)` on anything it does not recognise - which kills
         * the process outright rather than throwing something catchable. Every
         * value that reaches OfflineWhisperModelConfig must come from here.
         */
        fun isSafeWhisperCode(code: String): Boolean = entries.any { it.whisperCode == code }
    }
}

data class RecognizedText(
    val text: String,
    /** Whisper's detected language, or null when it reported nothing. */
    val language: String?,
)

/**
 * Recognises one VAD-delimited stretch of speech.
 *
 * Deliberately narrow: implementations must not know about microphones, VAD, or
 * the recording session. That keeps the pipeline testable with a fake that
 * returns canned text and no model on disk.
 */
interface SpeechRecognizer : AutoCloseable {

    /**
     * Loads the model. Slow (seconds) and safe to call more than once.
     * Call off the main thread.
     */
    fun load()

    val isLoaded: Boolean

    /**
     * @param samples mono PCM in [-1, 1] at [SAMPLE_RATE].
     * @return recognised text, or null when the segment held no speech.
     */
    fun recognize(samples: FloatArray): RecognizedText?

    companion object {
        /** Whisper's fixed input rate. Everything upstream must match it. */
        const val SAMPLE_RATE = 16_000
    }
}
