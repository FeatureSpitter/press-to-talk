package com.presstotalk.mobile.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Splits a live microphone stream into utterances at natural pauses.
 *
 * This is not an optimisation, it is what makes long recordings work at all.
 * Whisper's encoder cannot see past 30 seconds, and sherpa-onnx does not chunk
 * for you - it truncates to ~29.5s and only logs a warning
 * (`offline-recognizer-whisper-impl.h`). Cutting at silence also means cuts
 * never land mid-word, which fixed 30-second chunking could not promise.
 */
class VadSegmenter(
    modelPath: File,
    private val sampleRate: Int = SpeechRecognizer.SAMPLE_RATE,
) : AutoCloseable {

    /** One detected stretch of speech, with its position in the recording. */
    data class Segment(
        val samples: FloatArray,
        val startSeconds: Float,
        val endSeconds: Float,
    ) {
        // FloatArray gives data classes reference equality; spell it out so
        // equality means what a reader expects.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Segment &&
                    startSeconds == other.startSeconds &&
                    endSeconds == other.endSeconds &&
                    samples.contentEquals(other.samples))

        override fun hashCode(): Int =
            (samples.contentHashCode() * 31 + startSeconds.hashCode()) * 31 + endSeconds.hashCode()
    }

    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath.absolutePath,
                threshold = SPEECH_THRESHOLD,
                // Upstream's 0.25 splits mid-sentence on ordinary speech pauses.
                minSilenceDuration = MIN_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                // Backstop for speech with no pause at all. Not a blind cut:
                // sherpa-onnx raises the threshold to 0.9 so the split lands at
                // the next quietest moment. Kept well under Whisper's 30s ceiling.
                maxSpeechDuration = MAX_SPEECH_SECONDS,
                // Silero accepts only 512/1024/1536 at 16 kHz.
                windowSize = WINDOW_SIZE,
            ),
            sampleRate = sampleRate,
            numThreads = 1, // the VAD is tiny; leave the cores for Whisper
            provider = "cpu",
        ),
    )

    /** Call before each recording so a previous session cannot leak into this one. */
    fun reset() = vad.reset()

    /** Feed exactly [WINDOW_SIZE] samples at a time. */
    fun accept(frame: FloatArray) = vad.acceptWaveform(frame)

    /** True while the user is mid-utterance; drives the "listening" affordance. */
    fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    /** Removes and returns every utterance completed so far. */
    fun drain(): List<Segment> {
        val segments = mutableListOf<Segment>()
        while (!vad.empty()) {
            val speech = vad.front()
            val start = speech.start.toFloat() / sampleRate
            segments += Segment(
                samples = speech.samples,
                startSeconds = start,
                endSeconds = start + speech.samples.size.toFloat() / sampleRate,
            )
            vad.pop()
        }
        return segments
    }

    /**
     * Forces out a trailing utterance and returns everything left.
     *
     * Mandatory when recording stops: without it the final utterance is stranded
     * in the VAD's buffer and silently lost. The official SherpaOnnxVadAsr
     * example omits this call.
     */
    fun flush(): List<Segment> {
        vad.flush()
        return drain()
    }

    override fun close() = vad.release()

    companion object {
        const val WINDOW_SIZE = 512
        const val SPEECH_THRESHOLD = 0.5f
        const val MIN_SILENCE_SECONDS = 0.5f
        const val MIN_SPEECH_SECONDS = 0.25f
        const val MAX_SPEECH_SECONDS = 20.0f
    }
}
