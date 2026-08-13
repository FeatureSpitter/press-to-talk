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
import kotlin.math.max
import kotlin.math.min

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
     * Every sample captured this session, so utterances can be re-sliced with
     * padding the VAD did not include.
     *
     * A 10 minute recording is ~38 MB of float - cheap next to a 375 MB model,
     * and it buys back the speech onsets that VAD trims.
     */
    private class RecordingBuffer {
        private val chunks = ArrayList<FloatArray>()
        var size: Int = 0
            private set

        fun append(frame: FloatArray) {
            chunks += frame
            size += frame.size
        }

        fun slice(from: Int, to: Int): FloatArray {
            val start = from.coerceIn(0, size)
            val end = to.coerceIn(start, size)
            val out = FloatArray(end - start)
            var copied = 0
            var offset = 0
            for (chunk in chunks) {
                if (copied >= out.size) break
                val chunkEnd = offset + chunk.size
                if (chunkEnd > start) {
                    val srcFrom = max(0, start - offset)
                    val srcTo = min(chunk.size, end - offset)
                    if (srcTo > srcFrom) {
                        chunk.copyInto(out, copied, srcFrom, srcTo)
                        copied += srcTo - srcFrom
                    }
                }
                offset = chunkEnd
            }
            return out
        }
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
        val buffer = RecordingBuffer()

        val recognition = launch(Dispatchers.Default) {
            for (segment in pending) {
                val startedAt = System.nanoTime()
                val recognised = runCatching { recognizer.recognize(segment.samples) }
                    .onFailure { Log.e(TAG, "Recognition failed for a segment", it) }
                    .getOrNull()

                val audioSeconds = segment.samples.size.toFloat() / SpeechRecognizer.SAMPLE_RATE
                val decodeSeconds = (System.nanoTime() - startedAt) / 1e9
                Log.d(
                    TAG,
                    "segment %.2f-%.2fs (%.2fs audio) decoded in %.2fs -> %s".format(
                        segment.startSeconds, segment.endSeconds, audioSeconds,
                        decodeSeconds, recognised?.text?.take(40) ?: "(nothing)",
                    ),
                )

                if (recognised == null) continue
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

        // Per-frame levels, used to choose a quiet place to cut long speech.
        val frameLevels = ArrayList<Float>()

        /** Samples already handed to recognition; nothing before this is re-sent. */
        var emittedThrough = 0

        /**
         * Sends [from]..[to] for recognition, clipped so audio is never sent
         * twice. VAD segments and our own forced cuts overlap by design - the
         * VAD eventually reports the whole utterance including the part already
         * transcribed - so every emission goes through here.
         */
        suspend fun emitRange(from: Int, to: Int) {
            var start = max(from, emittedThrough)
            val end = min(to, buffer.size)
            if (end - start < MIN_EMIT_SAMPLES) return

            // Hard guard against Whisper's 30s ceiling. sherpa-onnx does not
            // report going over it - it truncates and logs a warning - so an
            // oversized chunk would silently lose its tail.
            if (end - start > MAX_DECODE_SAMPLES) {
                Log.w(
                    TAG,
                    "chunk of %.1fs exceeds the decoder limit; keeping the last %.1fs".format(
                        (end - start).toFloat() / SpeechRecognizer.SAMPLE_RATE,
                        MAX_DECODE_SAMPLES.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                    ),
                )
                start = end - MAX_DECODE_SAMPLES
            }

            pending.send(
                VadSegmenter.Segment(
                    samples = buffer.slice(start, end),
                    startSample = start,
                    startSeconds = start.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                    endSeconds = end.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                ),
            )
            emittedThrough = end
        }

        /**
         * Quietest frame boundary within the last [CUT_SEARCH_SAMPLES], so a
         * forced cut lands in a breath or a gap between words rather than
         * through the middle of one.
         */
        fun quietestBoundaryBefore(end: Int): Int {
            val searchStart = max(emittedThrough, end - CUT_SEARCH_SAMPLES)
            val firstFrame = searchStart / VadSegmenter.WINDOW_SIZE
            val lastFrame = min(frameLevels.size, end / VadSegmenter.WINDOW_SIZE)
            if (lastFrame <= firstFrame) return end

            var quietest = firstFrame
            for (i in firstFrame until lastFrame) {
                if (frameLevels[i] < frameLevels[quietest]) quietest = i
            }
            return (quietest + 1) * VadSegmenter.WINDOW_SIZE
        }

        segmenter.reset()
        var samplesRead = 0L
        val maxSamples = (maxDurationSeconds * SpeechRecognizer.SAMPLE_RATE).toLong()
        var hitCap = false

        val openedAt = System.nanoTime()
        var firstFrameLogged = false
        var loudestOpening = 0f

        try {
            recorder.frames().collect { frame ->
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(TAG, "first audio frame ${(System.nanoTime() - openedAt) / 1_000_000} ms after start")
                }

                buffer.append(frame)
                segmenter.accept(frame)
                samplesRead += frame.size

                val rms = AudioRecorder.rms(frame)
                frameLevels += rms

                // Distinguishes a microphone that is still warming up (level near
                // zero while the user is already talking) from a VAD that is
                // simply slow to trigger. Only the opening matters here.
                if (samplesRead <= OPENING_DIAGNOSTIC_SAMPLES) {
                    loudestOpening = max(loudestOpening, rms)
                    if (samplesRead % (SpeechRecognizer.SAMPLE_RATE / 2) < frame.size) {
                        Log.d(
                            TAG,
                            "opening %.1fs: rms=%.4f peak-so-far=%.4f speech=%s".format(
                                samplesRead.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                                rms, loudestOpening, segmenter.isSpeechDetected(),
                            ),
                        )
                    }
                }

                send(
                    Event.Level(
                        rms = rms,
                        elapsedSeconds = samplesRead.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                    ),
                )

                segmenter.drain().forEach { segment ->
                    emitRange(
                        segment.startSample - PRE_ROLL_SAMPLES,
                        segment.startSample + segment.samples.size + POST_ROLL_SAMPLES,
                    )
                }

                // Continuous speech never closes a VAD segment - measured: a 16.7s
                // clip produced nothing at all until flush, and maxSpeechDuration
                // does not hard-cut, it only raises the threshold and waits for a
                // quiet moment that may never arrive. Without cutting for
                // ourselves the transcript stays empty while the user is talking.
                if (buffer.size - emittedThrough >= LIVE_CHUNK_SAMPLES) {
                    if (segmenter.isSpeechDetected()) {
                        val cut = quietestBoundaryBefore(buffer.size)
                        if (cut > emittedThrough) {
                            Log.d(
                                TAG,
                                "forced cut at %.2fs (%.2fs of unsent speech)".format(
                                    cut.toFloat() / SpeechRecognizer.SAMPLE_RATE,
                                    (buffer.size - emittedThrough).toFloat() / SpeechRecognizer.SAMPLE_RATE,
                                ),
                            )
                            emitRange(emittedThrough, cut)
                        }
                    } else {
                        // Only silence is pending. Decoding it would waste a
                        // Whisper pass, and letting it pile up would eventually be
                        // prepended to real speech and push the chunk past the
                        // decoder limit. Drop it, keeping enough for the next onset.
                        emittedThrough = max(emittedThrough, buffer.size - PRE_ROLL_SAMPLES)
                    }
                }

                hitCap = samplesRead >= maxSamples
                if (hitCap || shouldStop()) {
                    // Ends collection of the microphone flow; the finally block
                    // below still flushes whatever the VAD is holding.
                    throw StopRecording()
                }
            }
        } catch (_: StopRecording) {
            // expected: stop requested or the cap was reached
        } finally {
            // Without this the last utterance stays stuck in the VAD buffer.
            runCatching { segmenter.flush() }
                .onFailure { Log.e(TAG, "VAD flush failed", it) }
                .getOrDefault(emptyList())
                .forEach { segment ->
                    emitRange(
                        segment.startSample - PRE_ROLL_SAMPLES,
                        segment.startSample + segment.samples.size + POST_ROLL_SAMPLES,
                    )
                }
            // Anything after the last VAD segment (trailing speech it never
            // closed) would otherwise be dropped.
            emitRange(emittedThrough, buffer.size)
            pending.close()
        }

        recognition.join() // let the tail finish before the flow completes
        if (hitCap) send(Event.CapReached)
    }

    private class StopRecording : Exception(null, null, false, false)

    private companion object {
        const val TAG = "RecordingPipeline"

        /** ~0.3s of audio before VAD's reported onset, to keep the first word intact. */
        val PRE_ROLL_SAMPLES = (0.30f * SpeechRecognizer.SAMPLE_RATE).toInt()

        /** ~0.2s after, so trailing consonants are not clipped. */
        val POST_ROLL_SAMPLES = (0.20f * SpeechRecognizer.SAMPLE_RATE).toInt()

        /** How much of the opening to profile when diagnosing lost speech. */
        val OPENING_DIAGNOSTIC_SAMPLES = 4 * SpeechRecognizer.SAMPLE_RATE

        /**
         * Longest run of unsent audio before the pipeline cuts for itself.
         * Bounds how long the live transcript can sit unchanged; also roughly
         * the decode size, so it trades latency against per-chunk context.
         */
        val LIVE_CHUNK_SAMPLES = (7.0f * SpeechRecognizer.SAMPLE_RATE).toInt()

        /** Window searched for a quiet frame when forcing a cut. */
        val CUT_SEARCH_SAMPLES = (0.7f * SpeechRecognizer.SAMPLE_RATE).toInt()

        /** Below this, a slice is not worth a decode. */
        val MIN_EMIT_SAMPLES = (0.3f * SpeechRecognizer.SAMPLE_RATE).toInt()

        /**
         * Whisper's encoder cannot see past 30s and sherpa-onnx truncates
         * silently rather than erroring, so chunks are kept safely under it.
         */
        val MAX_DECODE_SAMPLES = (25f * SpeechRecognizer.SAMPLE_RATE).toInt()
    }
}
