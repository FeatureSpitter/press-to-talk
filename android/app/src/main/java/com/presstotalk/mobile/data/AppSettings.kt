package com.presstotalk.mobile.data

import com.presstotalk.mobile.asr.LanguageMode
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val historyCap: Int = HistoryPolicy.DEFAULT_CAP,
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val maxRecordingMinutes: Int = DEFAULT_MAX_MINUTES,
    /** Which model directory to load. Swapping it is the whole model-change story. */
    val modelName: String = DEFAULT_MODEL,
    val numThreads: Int = DEFAULT_THREADS,
) {
    /**
     * Clamps everything to a sane range. Applied on read as well as on write,
     * so a hand-edited or older stored value can never push a bad number into
     * the recognizer.
     */
    fun sanitized(): AppSettings = copy(
        historyCap = HistoryPolicy.clampCap(historyCap),
        maxRecordingMinutes = maxRecordingMinutes.coerceIn(MIN_MAX_MINUTES, MAX_MAX_MINUTES),
        numThreads = numThreads.coerceIn(1, 8),
    )

    val maxRecordingSeconds: Float get() = maxRecordingMinutes * 60f

    companion object {
        const val DEFAULT_MAX_MINUTES = 10
        const val MIN_MAX_MINUTES = 1
        const val MAX_MAX_MINUTES = 60
        const val DEFAULT_MODEL = "small"
        const val DEFAULT_THREADS = 4
    }
}
