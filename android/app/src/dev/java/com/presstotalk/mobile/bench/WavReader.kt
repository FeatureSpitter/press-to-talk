package com.presstotalk.mobile.bench

import com.presstotalk.mobile.asr.SpeechRecognizer
import java.io.DataInputStream
import java.io.File

/**
 * Minimal 16-bit PCM WAV reader for the dev-only tools.
 *
 * Chunks are walked rather than assumed at fixed offsets, because some encoders
 * insert LIST or fact chunks before the data chunk.
 */
object WavReader {

    fun readMono16k(file: File): FloatArray {
        DataInputStream(file.inputStream().buffered()).use { input ->
            fun readIntLe(): Int =
                input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or
                    (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 24)

            fun readShortLe(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)

            fun readTag(): String = ByteArray(4).also { input.readFully(it) }.decodeToString()

            require(readTag() == "RIFF") { "not a RIFF file" }
            readIntLe()
            require(readTag() == "WAVE") { "not a WAVE file" }

            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0

            while (true) {
                val tag = readTag()
                val size = readIntLe()
                when (tag) {
                    "fmt " -> {
                        readShortLe() // audio format
                        channels = readShortLe()
                        sampleRate = readIntLe()
                        readIntLe() // byte rate
                        readShortLe() // block align
                        bitsPerSample = readShortLe()
                        repeat(size - 16) { input.readUnsignedByte() }
                    }

                    "data" -> {
                        require(channels == 1) { "expected mono, got $channels channels" }
                        require(bitsPerSample == 16) { "expected 16-bit, got $bitsPerSample" }
                        require(sampleRate == SpeechRecognizer.SAMPLE_RATE) {
                            "expected ${SpeechRecognizer.SAMPLE_RATE}Hz, got ${sampleRate}Hz"
                        }
                        val samples = FloatArray(size / 2)
                        for (i in samples.indices) {
                            val value = readShortLe()
                            val signed = if (value >= 0x8000) value - 0x10000 else value
                            samples[i] = signed / 32768.0f
                        }
                        return samples
                    }

                    else -> repeat(size) { input.readUnsignedByte() }
                }
            }
        }
    }
}
