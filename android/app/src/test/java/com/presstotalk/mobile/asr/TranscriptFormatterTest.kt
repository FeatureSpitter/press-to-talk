package com.presstotalk.mobile.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatterTest {

    private fun utterance(text: String, start: Float, end: Float) =
        Utterance(text = text, startSeconds = start, endSeconds = end)

    @Test
    fun `empty input produces empty string`() {
        assertEquals("", TranscriptFormatter.join(emptyList()))
    }

    @Test
    fun `single utterance is trimmed`() {
        val result = TranscriptFormatter.join(listOf(utterance("  hello there  ", 0f, 2f)))
        assertEquals("hello there", result)
    }

    @Test
    fun `short gap joins with a space`() {
        val result = TranscriptFormatter.join(
            listOf(
                utterance("first sentence", 0f, 2f),
                utterance("second sentence", 2.4f, 4f), // 0.4s gap
            ),
        )
        assertEquals("first sentence second sentence", result)
    }

    @Test
    fun `long gap starts a new paragraph`() {
        val result = TranscriptFormatter.join(
            listOf(
                utterance("first thought", 0f, 2f),
                utterance("second thought", 4f, 6f), // 2.0s gap
            ),
        )
        assertEquals("first thought\n\nsecond thought", result)
    }

    @Test
    fun `gap exactly at the threshold breaks the paragraph`() {
        val result = TranscriptFormatter.join(
            listOf(
                utterance("before", 0f, 2f),
                utterance("after", 3.5f, 5f), // exactly 1.5s
            ),
        )
        assertEquals("before\n\nafter", result)
    }

    @Test
    fun `blank utterances are dropped without leaving separators`() {
        val result = TranscriptFormatter.join(
            listOf(
                utterance("   ", 0f, 1f),
                utterance("real text", 1.1f, 2f),
                utterance("", 2.1f, 3f),
                utterance("more text", 2.2f, 4f),
            ),
        )
        assertEquals("real text more text", result)
    }

    @Test
    fun `a leading blank utterance does not indent the result`() {
        val result = TranscriptFormatter.join(
            listOf(
                utterance("", 0f, 1f),
                utterance("actual start", 5f, 6f), // large gap, but nothing precedes it
            ),
        )
        assertEquals("actual start", result)
    }

    @Test
    fun `gap is measured from previous end, not previous start`() {
        // A long utterance followed closely: 10.2 - 10.0 = 0.2s, so no paragraph break.
        // Measuring from the previous *start* would give 10.2s and wrongly split.
        val result = TranscriptFormatter.join(
            listOf(
                utterance("a very long stretch of speech", 0f, 10f),
                utterance("continuing right after", 10.2f, 12f),
            ),
        )
        assertEquals("a very long stretch of speech continuing right after", result)
    }
}
