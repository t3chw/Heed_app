package com.bol.app.ai.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * EmbeddingGemma-300M, 4-bit ONNX, on ONNX Runtime (CPU), set up as in the study's phone benchmark.
 * The model's `sentence_embedding` output is already pooled, projected and normalised (768-d, unit length).
 * Only [com.bol.app.ai.model.ModelManager] creates and calls this.
 */
class GemmaEmbedder(dir: File, threads: Int = 4) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: GemmaTokenizer
    /** Load time of each part, for the log. */
    val tokenizerLoadMs: Long
    val sessionLoadMs: Long

    init {
        var t0 = System.nanoTime()
        tokenizer = GemmaTokenizer.loadCached(File(dir, TOKENIZER_FILE), File(dir, TOKENIZER_CACHE))
        tokenizerLoadMs = (System.nanoTime() - t0) / 1_000_000
        t0 = System.nanoTime()
        session = OrtSession.SessionOptions().use { so ->
            so.setIntraOpNumThreads(threads)
            so.setInterOpNumThreads(1)
            env.createSession(File(dir, MODEL_FILE).absolutePath, so)
        }
        sessionLoadMs = (System.nanoTime() - t0) / 1_000_000
    }

    /** One unit vector per text, with the model card's prompts: queries cut at 64 tokens, documents at 128. */
    fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val batch = texts.map { if (isQuery) tokenizer.encode(QUERY_PROMPT + it, QUERY_MAX_TOKENS) else tokenizer.encode(DOC_PROMPT + it, DOC_MAX_TOKENS) }
        val b = batch.size
        val len = batch.maxOf { it.size }
        val ids = LongArray(b * len); val am = LongArray(b * len)
        for (r in 0 until b) for (c in batch[r].indices) { ids[r * len + c] = batch[r][c].toLong(); am[r * len + c] = 1 }
        val shape = longArrayOf(b.toLong(), len.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = mapOf(
                "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).also { tensors.add(it) },
                "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(am), shape).also { tensors.add(it) },
            )
            session.run(inputs, setOf(OUTPUT)).use { res ->
                val fb = (res[0] as OnnxTensor).floatBuffer
                return List(b) { r -> FloatArray(DIM).also { o -> fb.position(r * DIM); fb.get(o) } }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Folder under filesDir/models; the `.onnx` names `model_q4.onnx_data` next to it. */
        const val DIR = "egemma300m_q4"
        const val MODEL_FILE = "model_q4.onnx"
        const val DATA_FILE = "model_q4.onnx_data"
        const val TOKENIZER_FILE = "tokenizer.json"
        /** Written next to the json on the first load (see [GemmaTokenizer.loadCached]). */
        private const val TOKENIZER_CACHE = "tokenizer.bin"
        const val DIM = 768
        /** Stored with every vector; bump when the model, prompts or truncation change so the index re-embeds. */
        const val VERSION = "egemma300m_q4/doc128"
        private const val OUTPUT = "sentence_embedding"
        private const val QUERY_PROMPT = "task: search result | query: "
        private const val DOC_PROMPT = "title: none | text: "
        private const val QUERY_MAX_TOKENS = 64
        private const val DOC_MAX_TOKENS = 128
    }
}
