package com.presstotalk.mobile.asr

/**
 * One VAD-delimited stretch of speech, after recognition.
 *
 * Timestamps are seconds from the start of the recording, taken from the VAD's
 * sample offsets rather than from Whisper - Whisper only ever sees the segment,
 * so it has no idea where in the recording it sits.
 */
data class Utterance(
    val text: String,
    val startSeconds: Float,
    val endSeconds: Float,
    /** Whisper's detected language, or null when it was pinned by the user. */
    val language: String? = null,
)
