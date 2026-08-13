package com.presstotalk.mobile.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.presstotalk.mobile.asr.SpeechRecognizer
import com.presstotalk.mobile.asr.VadSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Microphone capture as mono 16 kHz float frames.
 *
 * The format is dictated from both ends: Whisper wants 16 kHz mono, and Silero
 * VAD only accepts windows of 512/1024/1536 samples at that rate. Emitting
 * exactly [VadSegmenter.WINDOW_SIZE] samples per frame means no downstream
 * rebuffering.
 *
 * AudioRecord rather than MediaRecorder because we need raw PCM, not an encoded
 * container; and not Oboe, because batch dictation has no low-latency
 * requirement that would justify a JNI layer.
 */
class AudioRecorder(
    context: Context? = null,
    private val sampleRate: Int = SpeechRecognizer.SAMPLE_RATE,
    private val frameSize: Int = VadSegmenter.WINDOW_SIZE,
) {

    private val audioManager: AudioManager? =
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    class RecorderInitException(message: String) : Exception(message)

    /**
     * Emits frames until the collector stops. Blocking reads, so it runs on the
     * IO dispatcher; cancelling the collection stops and releases the recorder.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<FloatArray> = flow {
        val record = open()
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not start; the microphone may be held by another app"
            }

            val buffer = ShortArray(frameSize)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, frameSize)
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                    if (read < 0) throw RecorderInitException("AudioRecord.read failed with $read")
                    continue
                }
                emit(FloatArray(read) { buffer[it] / PCM16_FULL_SCALE })
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission") // enforced by @RequiresPermission on frames()
    private fun open(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw RecorderInitException("This device cannot record ${sampleRate}Hz mono PCM16")
        }

        // Generous headroom: Whisper hogs the CPU, and an undersized buffer
        // drops audio when the reader is late.
        val bufferBytes = maxOf(minBuffer, frameSize * Short.SIZE_BYTES * BUFFER_FRAMES)

        val source = preferredSource()
        Log.i(TAG, "opening mic: source=${sourceName(source)} buffer=${bufferBytes}B min=$minBuffer")

        val record = AudioRecord(source, sampleRate, CHANNEL, ENCODING, bufferBytes)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw RecorderInitException("AudioRecord failed to initialise")
        }
        return record
    }

    /**
     * Picks the least-processed microphone this device offers.
     *
     * VOICE_RECOGNITION still runs noise suppression and gain control that ramp
     * over roughly the first second, which can hold early speech below the VAD's
     * threshold - the words are captured but arrive attenuated enough to be
     * treated as silence, so the opening of a recording goes missing.
     *
     * UNPROCESSED is the raw capture path with that chain disabled. It is
     * optional, so the device is asked before it is used.
     */
    private fun preferredSource(): Int {
        val supportsUnprocessed = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBoolean() == true

        return if (supportsUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "source-$source"
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_FRAMES = 16
        private const val PCM16_FULL_SCALE = 32768.0f

        /** Root-mean-square level of a frame, for the amplitude ring. */
        fun rms(frame: FloatArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (sample in frame) sum += sample.toDouble() * sample
            return kotlin.math.sqrt(sum / frame.size).toFloat()
        }
    }
}
