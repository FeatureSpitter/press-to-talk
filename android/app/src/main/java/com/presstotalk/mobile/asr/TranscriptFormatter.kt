package com.presstotalk.mobile.asr

/**
 * Joins recognized utterances into readable text, breaking paragraphs at pauses.
 *
 * Ported from the desktop app's `Transcriber._join_segments` (press_to_talk.py),
 * including its 1.5 second threshold. One deliberate difference: the desktop
 * relies on faster-whisper emitting segment text with a leading space and passes
 * it through unmodified. sherpa-onnx makes no such guarantee, so each utterance
 * is trimmed and the separator is inserted explicitly.
 */
object TranscriptFormatter {

    /** A gap at least this long reads as a new thought, not a new sentence. */
    const val PARAGRAPH_PAUSE_SECONDS = 1.5f

    fun join(
        utterances: List<Utterance>,
        pauseThreshold: Float = PARAGRAPH_PAUSE_SECONDS,
    ): String {
        val builder = StringBuilder()
        var previousEnd: Float? = null

        for (utterance in utterances) {
            val text = utterance.text.trim()
            if (text.isEmpty()) continue

            if (builder.isNotEmpty()) {
                val gap = previousEnd?.let { utterance.startSeconds - it } ?: 0f
                builder.append(if (gap >= pauseThreshold) "\n\n" else " ")
            }
            builder.append(text)
            previousEnd = utterance.endSeconds
        }

        return builder.toString().trim()
    }
}
