package com.presstotalk.mobile.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.presstotalk.mobile.asr.ModelStore
import com.presstotalk.mobile.asr.SpeechRecognizer
import com.presstotalk.mobile.asr.VadSegmenter
import com.presstotalk.mobile.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Replays a WAV through the VAD exactly as the live pipeline does, with no
 * microphone involved. Dev flavor only.
 *
 * The live transcript only updates when the VAD closes a segment *during*
 * recording. If segments only appear at flush time, the transcript stays empty
 * until the user stops - which is indistinguishable, from the outside, from
 * recognition being broken. This isolates that question from microphone levels,
 * room noise and timing, which a live test cannot.
 *
 *   adb shell am start -n com.presstotalk.mobile.dev/com.presstotalk.mobile.bench.VadReplayActivity
 *   adb logcat -s VadReplay:I
 */
class VadReplayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { replay() }.onFailure { Log.e(TAG, "Replay failed", it) }
            finish()
        }
    }

    private fun replay() {
        val benchDir = File(getExternalFilesDir(null), "bench")
        val wavs = benchDir.listFiles { f -> f.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (wavs.isEmpty()) {
            Log.e(TAG, "No .wav in ${benchDir.absolutePath}")
            return
        }

        val paths = ModelStore(this).prepare("small")
        val segmenter = VadSegmenter(paths.vad)

        try {
            for (wav in wavs) {
                val samples = WavReader.readMono16k(wav)
                Log.i(TAG, "================================================")
                Log.i(
                    TAG,
                    "%s: %.2fs, threshold=%.2f minSilence=%.2fs maxSpeech=%.1fs".format(
                        wav.name,
                        samples.size.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                        VadSegmenter.SPEECH_THRESHOLD,
                        VadSegmenter.MIN_SILENCE_SECONDS,
                        VadSegmenter.MAX_SPEECH_SECONDS,
                    ),
                )

                segmenter.reset()
                var fed = 0
                var midStream = 0

                // Same frame size and drain cadence as the live pipeline.
                while (fed + VadSegmenter.WINDOW_SIZE <= samples.size) {
                    val frame = samples.copyOfRange(fed, fed + VadSegmenter.WINDOW_SIZE)
                    segmenter.accept(frame)
                    fed += VadSegmenter.WINDOW_SIZE

                    segmenter.drain().forEach { segment ->
                        midStream++
                        Log.i(
                            TAG,
                            "  MID-STREAM at %.2fs: segment %.2f-%.2fs (%.2fs) rms=%.4f".format(
                                fed.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                                segment.startSeconds,
                                segment.endSeconds,
                                segment.samples.size.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                                AudioRecorder.rms(segment.samples),
                            ),
                        )
                    }
                }

                val flushed = segmenter.flush()
                flushed.forEach { segment ->
                    Log.i(
                        TAG,
                        "  AT-FLUSH: segment %.2f-%.2fs (%.2fs)".format(
                            segment.startSeconds,
                            segment.endSeconds,
                            segment.samples.size.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                        ),
                    )
                }

                Log.i(TAG, "  => $midStream mid-stream, ${flushed.size} at flush")
                if (midStream == 0) {
                    Log.w(TAG, "  !! nothing emitted while streaming: live transcript would stay empty")
                }
            }
        } finally {
            segmenter.close()
        }
        Log.i(TAG, "================ DONE ================")
    }

    private companion object {
        const val TAG = "VadReplay"
    }
}
