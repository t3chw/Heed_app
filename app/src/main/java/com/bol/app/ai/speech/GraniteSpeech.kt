package com.bol.app.ai.speech

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Granite Speech 5.0 470M TurboCTC on the LiteRT interpreter, CPU only. One clip of at most 30 s at a time;
 * longer audio is split by [com.bol.app.ai.audio.AudioChunker]. Not thread-safe: [com.bol.app.ai.model.ModelManager] serialises calls.
 */
class GraniteSpeech(model: File, vocab: File, cacheDir: File) : AutoCloseable {
    private val compiled = CompiledModel.create(model.absolutePath, CompiledModel.Options(Accelerator.CPU).apply {
        cpuOptions = CompiledModel.CpuOptions(THREADS, null, File(cacheDir, "granite.xnnpack_cache").absolutePath)
    })
    private val tokens = vocab.readLines().map { hex -> ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }

    fun transcribe(samples: FloatArray): String {
        val seconds = WINDOWS.firstOrNull { samples.size <= it * SAMPLE_RATE } ?: error("clip longer than ${WINDOWS.last()} s")
        val padded = FloatArray(seconds * SAMPLE_RATE)
        samples.copyInto(padded)
        val signature = "transcribe_${seconds}s"
        val input = compiled.createInputBuffers(signature)
        val output = compiled.createOutputBuffers(signature)
        try {
            input[0].writeFloat(GraniteFeatures.compute(padded))
            compiled.run(input, output, signature)
            return decode(output[0].readInt())
        } finally {
            input.forEach { it.close() }
            output.forEach { it.close() }
        }
    }

    /** CTC: drop repeats and the blank (id 0). Tokens are raw bytes, because a piece can end inside a UTF-8 character. */
    private fun decode(ids: IntArray): String {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        for (id in ids) {
            if (id != previous && id != 0) bytes.write(tokens[id])
            previous = id
        }
        return bytes.toString("UTF-8").trim()
    }

    override fun close() = compiled.close()

    private companion object {
        const val SAMPLE_RATE = 16000
        const val THREADS = 4
        val WINDOWS = listOf(5, 10, 30)
    }
}
