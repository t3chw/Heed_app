package com.bol.app.ai.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavConverter {

    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    /** Write raw PCM bytes to a WAV file with proper 44-byte RIFF header. */
    fun pcmToWav(pcmData: ByteArray, outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                val dataSize = pcmData.size
                val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
                val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

                val header = ByteBuffer.allocate(44).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    put("RIFF".toByteArray())
                    putInt(36 + dataSize)
                    put("WAVE".toByteArray())
                    put("fmt ".toByteArray())
                    putInt(16)
                    putShort(1) // PCM format
                    putShort(CHANNELS.toShort())
                    putInt(SAMPLE_RATE)
                    putInt(byteRate)
                    putShort(blockAlign.toShort())
                    putShort(BITS_PER_SAMPLE.toShort())
                    put("data".toByteArray())
                    putInt(dataSize)
                }

                fos.write(header.array())
                fos.write(pcmData)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Build a WAV byte array (header + PCM data) entirely in memory — no
     * temp file. Used by [the modelAudioTranscriber] to wrap chunked audio for
     * `Content.AudioBytes(...)` without round-tripping through disk.
     */
    fun pcmToWavBytes(pcmData: ByteArray): ByteArray {
        val dataSize = pcmData.size
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val buffer = ByteBuffer.allocate(44 + dataSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM format
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(dataSize)
            put(pcmData)
        }
        return buffer.array()
    }

    /**
     * Convert a FloatArray of normalized samples [-1.0, 1.0] (the format
     * [AudioChunker] produces) into in-memory WAV bytes ready for
     * `Content.AudioBytes(...)`. Clamps out-of-range samples to int16 range.
     */
    fun floatArrayToWavBytes(samples: FloatArray): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        val pcmBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val pcmValue = (clamped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            pcmBuffer.putShort(pcmValue)
        }
        return pcmToWavBytes(pcm)
    }

    /** Read a WAV file and return samples as FloatArray normalized to [-1.0, 1.0] for sherpa-onnx. */
    fun wavToFloatArray(wavFile: File): FloatArray {
        val bytes = wavFile.readBytes()
        val pcmBytes = bytes.copyOfRange(44, bytes.size) // skip 44-byte header
        val shortBuffer = ByteBuffer.wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val samples = FloatArray(shortBuffer.remaining())
        for (i in samples.indices) {
            samples[i] = shortBuffer.get(i).toFloat() / 32768.0f
        }
        return samples
    }
}
