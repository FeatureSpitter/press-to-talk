package com.presstotalk.mobile.asr

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.presstotalk.mobile.audio.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Ties microphone, VAD and recognizer into one recording session.
 *
 * Two stages run concurrently: the audio side feeds frames to the VAD and
 * publishes levels, while a separate coroutine recognises completed utterances.
 * Recognition is far slower than real time in the worst case, so it must never
 * block the reader - a stalled reader drops audio.
 *
 * Utterances are emitted as they are recognised, which is why the transcript
 * fills in while the user is still speaking.
 */
class RecordingPipeline(
    private val recorder: AudioRecorder,
    private val segmenter: VadSegmenter,
    private val recognizer: SpeechRecognizer,
) {

    sealed interface Event {
        /** Microphone level, roughly 31 times a second. Drives the amplitude ring. */
        data class Level(val rms: Float, val elapsedSeconds: Float) : Event

        /** A recognised stretch of speech. */
        data class Text(val utterance: Utterance) : Event

        /** The cap was reached and capture stopped on its own. */
        data object CapReached : Event
    }

    /**
     * Records until [shouldStop] returns true or [maxDurationSeconds] elapses,
     * then flushes the VAD and recognises whatever is left before completing.
     *
     * Stopping must go through [shouldStop], never through cancelling the
     * collecting job: a cancelled scope cannot deliver the flushed tail, which
     * would silently drop the last thing the user said - precisely the failure
     * `flush()` exists to prevent.
     *
     * [shouldStop] is polled once per audio frame, so it takes effect within
     * about 32 ms.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(
        maxDurationSeconds: Float,
        shouldStop: () -> Boolean = { false },
    ): Flow<Event> = channelFlow {
        val pending = Channel<VadSegmenter.Segment>(Channel.UNLIMITED)

        val recognition = launch(Dispatchers.Default) {
            for (segment in pending) {
                val recognised = runCatching { recognizer.recognize(segment.samples) }
                    .onFailure { Log.e(TAG, "Recognition failed for a segment", it) }
                    .getOrNull()
                    ?: continue

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
        }

        segmenter.reset()
        var samplesRead = 0L
        val maxSamples = (maxDurationSeconds * SpeechRecognizer.SAMPLE_RATE).toLong()
        var hitCap = false

        try {
            recorder.frames().collect { frame ->
                segmenter.accept(frame)
                samplesRead += frame.size

                send(
                    Event.Level(
                        rms = AudioRecorder.rms(frame),
                        elapsedSeconds = samplesRead.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                    ),
                )

                segmenter.drain().forEach { pending.send(it) }

                hitCap = samplesRead >= maxSamples
                if (hitCap || shouldStop()) {
                    // Ends collection of the microphone flow; the finally block
                    // below still flushes whatever the VAD is holding.
                    throw StopRecording()
                }
            }
        } catch (_: StopRecording) {
            // expected: the cap was reached
        } finally {
            // Without this the last utterance stays stuck in the VAD buffer.
            runCatching { segmenter.flush() }
                .onFailure { Log.e(TAG, "VAD flush failed", it) }
                .getOrDefault(emptyList())
                .forEach { pending.send(it) }
            pending.close()
        }

        recognition.join() // let the tail finish before the flow completes
        if (hitCap) send(Event.CapReached)
    }

    private class StopRecording : Exception(null, null, false, false)

    private companion object {
        const val TAG = "RecordingPipeline"
    }
}
