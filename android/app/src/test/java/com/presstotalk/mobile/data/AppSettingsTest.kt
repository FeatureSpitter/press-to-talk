package com.presstotalk.mobile.data

import com.presstotalk.mobile.asr.LanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `defaults match the agreed behaviour`() {
        val settings = AppSettings()
        assertEquals(25, settings.historyCap)
        assertEquals(10, settings.maxRecordingMinutes)
        assertEquals(LanguageMode.AUTO, settings.languageMode)
        assertEquals(600f, settings.maxRecordingSeconds)
    }

    @Test
    fun `sanitize clamps a stored history cap`() {
        assertEquals(HistoryPolicy.MAX_CAP, AppSettings(historyCap = 5000).sanitized().historyCap)
        assertEquals(HistoryPolicy.MIN_CAP, AppSettings(historyCap = 0).sanitized().historyCap)
    }

    @Test
    fun `sanitize clamps the recording limit`() {
        assertEquals(
            AppSettings.MIN_MAX_MINUTES,
            AppSettings(maxRecordingMinutes = 0).sanitized().maxRecordingMinutes,
        )
        assertEquals(
            AppSettings.MAX_MAX_MINUTES,
            AppSettings(maxRecordingMinutes = 9999).sanitized().maxRecordingMinutes,
        )
    }

    @Test
    fun `sanitize clamps thread count`() {
        assertEquals(1, AppSettings(numThreads = 0).sanitized().numThreads)
        assertEquals(8, AppSettings(numThreads = 64).sanitized().numThreads)
    }

    /**
     * Guards the one failure mode that is not merely a wrong answer: sherpa-onnx
     * validates the language string in native code and calls SHERPA_ONNX_EXIT(-1)
     * on anything unexpected, killing the process rather than throwing.
     */
    @Test
    fun `every language mode maps to a code sherpa-onnx accepts`() {
        LanguageMode.entries.forEach { mode ->
            assertTrue(
                "LanguageMode.$mode produced an unsafe code '${mode.whisperCode}'",
                LanguageMode.isSafeWhisperCode(mode.whisperCode),
            )
        }
    }

    @Test
    fun `unknown language codes are rejected`() {
        listOf("fr", "PT", "auto", "xx", " ").forEach { code ->
            assertTrue(
                "'$code' must not be treated as safe",
                !LanguageMode.isSafeWhisperCode(code),
            )
        }
    }

    @Test
    fun `auto detection is the empty string, not en`() {
        // The Kotlin API defaults this field to "en"; leaving it would force
        // English onto Portuguese audio.
        assertEquals("", LanguageMode.AUTO.whisperCode)
    }
}
