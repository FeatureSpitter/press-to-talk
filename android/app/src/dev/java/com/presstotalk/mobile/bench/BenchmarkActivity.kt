package com.presstotalk.mobile.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.presstotalk.mobile.asr.LanguageMode
import com.presstotalk.mobile.asr.ModelStore
import com.presstotalk.mobile.asr.SpeechRecognizer
import com.presstotalk.mobile.asr.WhisperRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Measures Whisper decode speed on the actual device. Dev flavor only.
 *
 * No published Whisper-on-Android numbers exist, so the model choice has to come
 * from measurement rather than extrapolation from someone's Raspberry Pi.
 *
 * Speed is what this settles. Transcription *quality* in Portuguese is a
 * judgement call, best made by switching models in Settings and listening to the
 * difference - not something a number decides.
 *
 *   adb shell am start -n com.presstotalk.mobile.dev/com.presstotalk.mobile.bench.BenchmarkActivity
 *   adb logcat -s Benchmark:I
 */
class BenchmarkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val models = intent.getStringExtra("models")?.split(",") ?: DEFAULT_MODELS
        val threads = intent.getIntExtra("threads", 4)

        CoroutineScope(Dispatchers.Default).launch {
            runCatching { benchmark(models, threads) }
                .onFailure { Log.e(TAG, "Benchmark failed", it) }
            finish()
        }
    }

    private fun benchmark(models: List<String>, threads: Int) {
        val clips = loadClips()
        if (clips.isEmpty()) {
            Log.e(TAG, "No .wav files in ${benchDir().absolutePath} - push some first")
            return
        }

        Log.i(TAG, "================ BENCHMARK ================")
        Log.i(TAG, "device=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT} threads=$threads")
        clips.forEach { Log.i(TAG, "clip ${it.name}: ${"%.2f".format(it.durationSeconds)}s") }

        val store = ModelStore(this)

        for (model in models) {
            val paths = runCatching { store.prepare(model) }
                .onFailure { Log.w(TAG, "Skipping '$model': ${it.message?.lineSequence()?.first()}") }
                .getOrNull() ?: continue

            val recognizer = WhisperRecognizer(paths, LanguageMode.AUTO, threads)
            try {
                val loadStart = System.nanoTime()
                recognizer.load()
                val loadMs = (System.nanoTime() - loadStart) / 1_000_000

                // First decode pays one-off lazy allocation inside ONNX Runtime,
                // so it is run and discarded rather than averaged into the result.
                recognizer.recognize(clips.first().samples)

                var totalAudio = 0.0
                var totalDecode = 0.0

                for (clip in clips) {
                    val start = System.nanoTime()
                    val result = recognizer.recognize(clip.samples)
                    val decodeSeconds = (System.nanoTime() - start) / 1e9

                    totalAudio += clip.durationSeconds
                    totalDecode += decodeSeconds

                    Log.i(
                        TAG,
                        "%-6s %-8s audio=%5.2fs decode=%6.2fs rtf=%.3f lang=%s | %s".format(
                            model,
                            clip.name,
                            clip.durationSeconds,
                            decodeSeconds,
                            decodeSeconds / clip.durationSeconds,
                            result?.language ?: "-",
                            result?.text?.take(60) ?: "(no speech)",
                        ),
                    )
                }

                val rtf = totalDecode / totalAudio
                Log.i(
                    TAG,
                    "RESULT %-6s load=%5.1fs rtf=%.3f -> %.0fs of audio per minute of processing"
                        .format(model, loadMs / 1000.0, rtf, 60.0 / rtf),
                )
            } finally {
                recognizer.close()
            }
        }
        Log.i(TAG, "================ DONE ================")
    }

    private class Clip(val name: String, val samples: FloatArray) {
        val durationSeconds: Double get() = samples.size.toDouble() / SpeechRecognizer.SAMPLE_RATE
    }

    private fun benchDir(): File = File(getExternalFilesDir(null), "bench")

    private fun loadClips(): List<Clip> =
        benchDir().listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { Clip(file.name, WavReader.readMono16k(file)) }
                    .onFailure { Log.w(TAG, "Skipping ${file.name}: ${it.message}") }
                    .getOrNull()
            }
            .orEmpty()

    private companion object {
        const val TAG = "Benchmark"
        val DEFAULT_MODELS = listOf("tiny", "base", "small")
    }
}
