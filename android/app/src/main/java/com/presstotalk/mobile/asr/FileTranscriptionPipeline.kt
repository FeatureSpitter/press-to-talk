package com.presstotalk.mobile.asr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Transcribes a pre-decoded audio file - the file picker and the WhatsApp
 * browser both hand over a full [FloatArray] of 16 kHz mono samples.
 *
 * This is deliberately separate from [RecordingPipeline]. That one exists to
 * keep a *live* transcript moving while the microphone streams: it needs frame
 * levels, forced cuts on continuous speech, and opening diagnostics. A file is
 * finite, so there is nothing to keep up with - the work is just: run the VAD
 * over the samples, then recognise each utterance.
 *
 * The recognizer still runs on [Dispatchers.Default] while the VAD scan runs on
 * the caller's thread, so a slow Whisper pass never blocks segmenting.
 */
class FileTranscriptionPipeline(
    private val segmenter: VadSegmenter,
    private val recognizer: SpeechRecognizer,
) {

    sealed interface Event {
        /**
         * How far through the file the transcription has progressed, 0..1.
         * Tracks Whisper recognition (the slow part), not the VAD scan.
         */
        data class Progress(val fraction: Float) : Event

        /** One recognised stretch of speech. */
        data class Text(val utterance: Utterance) : Event
    }

    /**
     * @param samples entire file as mono 16 kHz PCM in [-1, 1].
     * @param onProgress called (possibly on any thread) as the VAD scan advances.
     */
    fun transcribe(samples: FloatArray): Flow<Event> = channelFlow {
        Log.i(TAG, "starting: ${samples.size} samples (%.1fs)".format(samples.size.toFloat() / SpeechRecognizer.SAMPLE_RATE))
        val t0 = System.currentTimeMillis()
        var emittedThrough = 0

        // --- Phase 1: VAD scan (fast, milliseconds) --------------------------
        // Collect all segments up front so we know the total count for progress.
        val allSegments = mutableListOf<VadSegmenter.Segment>()

        fun queueRange(from: Int, to: Int) {
            val start = maxOf(from, emittedThrough)
            val end = minOf(to, samples.size)
            if (end - start < MIN_EMIT_SAMPLES) return
            val cappedStart = if (end - start > MAX_DECODE_SAMPLES) end - MAX_DECODE_SAMPLES else start
            allSegments += VadSegmenter.Segment(
                samples = samples.copyOfRange(cappedStart, end),
                startSample = cappedStart,
                startSeconds = cappedStart.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                endSeconds = end.toFloat() / SpeechRecognizer.SAMPLE_RATE,
            )
            emittedThrough = end
        }

        segmenter.reset()
        var offset = 0
        while (offset + VadSegmenter.WINDOW_SIZE <= samples.size) {
            val frame = samples.copyOfRange(offset, offset + VadSegmenter.WINDOW_SIZE)
            segmenter.accept(frame)
            offset += VadSegmenter.WINDOW_SIZE
            segmenter.drain().forEach { seg ->
                queueRange(
                    seg.startSample - PRE_ROLL_SAMPLES,
                    seg.startSample + seg.samples.size + POST_ROLL_SAMPLES,
                )
            }
        }
        segmenter.flush().forEach { seg ->
            queueRange(
                seg.startSample - PRE_ROLL_SAMPLES,
                seg.startSample + seg.samples.size + POST_ROLL_SAMPLES,
            )
        }
        queueRange(emittedThrough, samples.size)

        Log.i(TAG, "VAD produced ${allSegments.size} segments, starting recognition")
        send(Event.Progress(0.05f)) // past VAD scan

        // --- Phase 2: Whisper recognition (slow, seconds per segment) --------
        val total = allSegments.size.coerceAtLeast(1)
        for ((i, segment) in allSegments.withIndex()) {
            val recognised = runCatching { recognizer.recognize(segment.samples) }
                .onFailure { Log.e(TAG, "Recognition failed for segment $i", it) }
                .getOrNull()
            if (recognised != null) {
                send(
                    Event.Text(
                        Utterance(
                            text = recognised.text,
                            startSeconds = segment.startSeconds,
                            endSeconds = segment.endSeconds,
                            language = recognised.language,
                        ),
                    ),
                )
            }
            // Progress tracks the slow part: 5% for decode+VAD, 95% for recognition.
            val fraction = 0.05f + 0.95f * (i + 1).toFloat() / total
            send(Event.Progress(fraction))
            Log.d(TAG, "recognised %d/%d (%.0f%%)".format(i + 1, total, fraction * 100))
        }
        Log.i(TAG, "done in ${System.currentTimeMillis() - t0} ms")
    }

    companion object {
        private const val TAG = "FileTranscriptionPipeline"

        /** Same pre/post-roll as the mic pipeline, to keep word onsets intact. */
        val PRE_ROLL_SAMPLES = (0.30f * SpeechRecognizer.SAMPLE_RATE).toInt()
        val POST_ROLL_SAMPLES = (0.20f * SpeechRecognizer.SAMPLE_RATE).toInt()
        val MIN_EMIT_SAMPLES = (0.30f * SpeechRecognizer.SAMPLE_RATE).toInt()
        val MAX_DECODE_SAMPLES = (25f * SpeechRecognizer.SAMPLE_RATE).toInt()
    }
}
