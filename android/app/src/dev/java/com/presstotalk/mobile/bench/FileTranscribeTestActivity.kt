package com.presstotalk.mobile.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.presstotalk.mobile.asr.FileTranscriptionPipeline
import com.presstotalk.mobile.asr.LanguageMode
import com.presstotalk.mobile.asr.ModelStore
import com.presstotalk.mobile.asr.VadSegmenter
import com.presstotalk.mobile.asr.WhisperRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Quick smoke test for the file transcription pipeline.
 *
 *   adb push .bench/test_wavs/1.wav /sdcard/Download/test_voice.wav
 *   adb shell am start -n com.presstotalk.mobile.dev/com.presstotalk.mobile.bench.FileTranscribeTestActivity
 *   adb logcat -s FileTranscribeTest:D
 */
class FileTranscribeTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { test() }
                .onFailure { Log.e(TAG, "Test failed", it) }
            finish()
        }
    }

    private suspend fun test() {
        Log.i(TAG, "=== FILE TRANSCRIPTION TEST ===")

        // 1. Load WAV directly (already 16kHz mono, no MediaCodec needed)
        val wavFile = File(getExternalFilesDir(null), "bench/1.wav")
        if (!wavFile.isFile) {
            Log.e(TAG, "No test file at ${wavFile.absolutePath}. Push one with: adb push .bench/test_wavs/1.wav ${wavFile.absolutePath}")
            return
        }
        Log.i(TAG, "Reading ${wavFile.name}...")
        val t0 = System.currentTimeMillis()
        val samples = WavReader.readMono16k(wavFile)
        Log.i(TAG, "Read: ${samples.size} samples (%.1fs) in ${System.currentTimeMillis() - t0} ms"
            .format(samples.size.toFloat() / 16_000))

        // 2. Load model
        val store = ModelStore(this)
        val paths = store.prepare("tiny") // fastest for testing
        val recognizer = WhisperRecognizer(paths, LanguageMode.AUTO, numThreads = 4)
        Log.i(TAG, "Loading model...")
        val t1 = System.currentTimeMillis()
        recognizer.load()
        val vad = VadSegmenter(paths.vad)
        Log.i(TAG, "Model loaded in ${System.currentTimeMillis() - t1} ms")

        // 3. Transcribe
        Log.i(TAG, "Running pipeline...")
        val pipeline = FileTranscriptionPipeline(vad, recognizer)
        val t2 = System.currentTimeMillis()
        var textCount = 0
        pipeline.transcribe(samples).collect { event ->
            when (event) {
                is FileTranscriptionPipeline.Event.Text -> {
                    textCount++
                    Log.i(TAG, "utterance #$textCount: ${event.utterance.text.take(80)}")
                }
                is FileTranscriptionPipeline.Event.Progress ->
                    Log.d(TAG, "progress: %.0f%%".format(event.fraction * 100))
            }
        }
        Log.i(TAG, "Pipeline done: $textCount utterances in ${System.currentTimeMillis() - t2} ms")
        Log.i(TAG, "=== DONE ===")

        recognizer.close()
        vad.close()
    }

    companion object {
        private const val TAG = "FileTranscribeTest"
    }
}
