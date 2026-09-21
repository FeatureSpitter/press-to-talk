package com.presstotalk.mobile.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.presstotalk.mobile.asr.SpeechRecognizer
import kotlin.math.min

/**
 * Decodes any audio file Android supports into the mono 16 kHz float frames
 * Whisper and the VAD expect.
 *
 * Files (and WhatsApp voice notes) arrive in whatever container and codec the
 * sender used - Opus, AAC, MP3, M4A, WAV - so the pipeline needs a decoder
 * before the recognizer. MediaExtractor + MediaCodec decode everything the
 * platform ships with, and both accept SAF `content://` URIs, which keeps this
 * free of storage permissions.
 */
class AudioDecoder(private val context: Context) {

    class DecodeException(message: String) : Exception(message)

    /**
     * Decodes [uri] to mono 16 kHz PCM in [-1, 1]. Runs to completion - the
     * caller decides the dispatcher. Returns empty when the file held no audio.
     */
    fun decode(uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw DecodeException("Could not read this file: ${e.message}")
        }

        val trackIndex = findAudioTrack(extractor)
        if (trackIndex < 0) {
            extractor.release()
            return FloatArray(0)
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        Log.i(TAG, "decoding ${format.getString(MediaFormat.KEY_MIME)} $channels ch @ ${sampleRate}Hz")

        val t0 = System.currentTimeMillis()
        val samples = decodeToFloat(extractor, format)
        extractor.release()
        Log.i(TAG, "codec decoded ${samples.size} samples in ${System.currentTimeMillis() - t0} ms")

        var out = samples
        if (channels > 1) out = downmix(out, channels)
        if (sampleRate != SpeechRecognizer.SAMPLE_RATE) out = resample(out, sampleRate)

        Log.i(TAG, "final: ${out.size} samples (%.1fs at 16kHz)".format(out.size.toFloat() / SpeechRecognizer.SAMPLE_RATE))
        if (out.isEmpty()) throw DecodeException("This file contained no audio")
        return out
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun decodeToFloat(extractor: MediaExtractor, format: MediaFormat): FloatArray {
        val codec = MediaCodec.createDecoderByType(
            format.getString(MediaFormat.KEY_MIME) ?: error("no MIME type"),
        )
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<FloatArray>(256)
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        try {
            while (!sawOutputEOS) {
                // Feed input until the source is exhausted.
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain available output.
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        // Decoded PCM is 16-bit signed little-endian (the only
                        // format MediaCodec audio decoders emit).
                        val floats = FloatArray(info.size / 2)
                        val bytes = ByteArray(info.size)
                        outBuf!!.position(info.offset)
                        outBuf.get(bytes)
                        for (i in floats.indices) {
                            val raw = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                            val signed = if (raw >= 0x8000) raw - 0x10000 else raw
                            floats[i] = signed / PCM16_FULL_SCALE
                        }
                        out += floats
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore - format is already known
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        val total = out.sumOf { it.size }
        val result = FloatArray(total)
        var offset = 0
        for (chunk in out) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    /** Averaging downmix to mono - never a single channel, which clips. */
    private fun downmix(samples: FloatArray, channels: Int): FloatArray {
        val frameCount = samples.size / channels
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            mono[f] = sum / channels
        }
        return mono
    }

    /** Linear interpolation from [fromRate] to 16 kHz. */
    private fun resample(samples: FloatArray, fromRate: Int): FloatArray {
        val ratio = fromRate.toDouble() / SpeechRecognizer.SAMPLE_RATE
        val outSize = (samples.size / ratio).toInt()
        val out = FloatArray(outSize)
        for (i in out.indices) {
            val pos = i * ratio
            val a = pos.toInt()
            val b = min(a + 1, samples.size - 1)
            val frac = (pos - a).toFloat()
            out[i] = samples[a] * (1f - frac) + samples[b] * frac
        }
        return out
    }

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 50_000L
        private const val PCM16_FULL_SCALE = 32768.0f
    }
}
