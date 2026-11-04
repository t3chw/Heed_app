package com.bol.app.ai.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Parity with Hugging Face `AutoTokenizer.from_pretrained("google/embeddinggemma-300m")` on reference vectors (tokenization only,
 * truncation=True, add_special_tokens=True): documents and queries from the study's search benchmark, built from the public
 * synthetic notes, plus edge cases (emoji, digits, apostrophes, Hindi, empty, very long, special-token text).
 * `tokenizer.json` is a git-ignored asset next to the model; set EGEMMA_TOKENIZER_JSON to point elsewhere.
 * Set EGEMMA_TOKENIZER_VECTORS to run another vector file.
 */
class GemmaTokenizerTest {
    private fun tokenizerFile(): File? {
        val candidates = listOfNotNull(
            System.getenv("EGEMMA_TOKENIZER_JSON")?.let(::File),
            File("src/main/assets/models/egemma300m_q4/tokenizer.json"),
            File("app/src/main/assets/models/egemma300m_q4/tokenizer.json"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun vectors(): List<Triple<Int, String, IntArray>> {
        val env = System.getenv("EGEMMA_TOKENIZER_VECTORS")?.let(::File)
        val stream = if (env != null) GZIPInputStream(env.inputStream())
        else GZIPInputStream(javaClass.getResourceAsStream("/egemma_tokenizer_vectors.jsonl.gz")!!)
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val o = Json.parseToJsonElement(line).jsonObject
                Triple(o["m"]!!.jsonPrimitive.int, o["t"]!!.jsonPrimitive.content, o["i"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray())
            }.toList()
        }
    }

    @Test fun matchesHuggingFaceOnEveryVector() {
        val file = tokenizerFile()
        assumeTrue("tokenizer.json asset not present (git-ignored model assets)", file != null)
        val t0 = System.nanoTime()
        val tk = file!!.inputStream().use { GemmaTokenizer.load(it) }
        println("tokenizer.json read in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val all = vectors()
        assertTrue("expected thousands of vectors, got ${all.size}", all.size > 3000)
        val bad = all.filter { (m, t, ids) -> !tk.encode(t, m).contentEquals(ids) }
        if (bad.isNotEmpty()) {
            val (m, t, ids) = bad.first()
            println("MISMATCH ${bad.size}/${all.size}; first: max=$m text=${t.take(100).replace("\n", "\\n")}\n want=${ids.take(40)}\n got =${tk.encode(t, m).take(40)}")
        }
        println("tokenizer parity: ${all.size - bad.size}/${all.size} = ${100.0 * (all.size - bad.size) / all.size}%")
        assertEquals("tokenizer mismatches", 0, bad.size)
    }

    @Test fun binaryCopyGivesTheSameIds() {
        val file = tokenizerFile()
        assumeTrue("tokenizer.json asset not present (git-ignored model assets)", file != null)
        val fromJson = file!!.inputStream().use { GemmaTokenizer.load(it) }
        val bytes = java.io.ByteArrayOutputStream().also { fromJson.writeBinary(it, file.length()) }.toByteArray()
        assertNull("a copy made from another tokenizer.json must be refused", GemmaTokenizer.readBinary(bytes, file.length() + 1))
        val t0 = System.nanoTime()
        val fromCopy = GemmaTokenizer.readBinary(bytes, file.length())!!
        println("binary copy: ${bytes.size} bytes, read in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val all = vectors()
        val bad = all.count { (m, t, ids) -> !fromCopy.encode(t, m).contentEquals(ids) }
        assertEquals("mismatches after the binary round trip", 0, bad)
    }

    @Test fun loadCachedWritesThenReadsTheCopy() {
        val file = tokenizerFile()
        assumeTrue("tokenizer.json asset not present (git-ignored model assets)", file != null)
        val cache = java.io.File.createTempFile("tokenizer", ".bin").also { it.delete() }
        try {
            val first = GemmaTokenizer.loadCached(file!!, cache)
            assertTrue("the first load writes the copy", cache.isFile)
            val second = GemmaTokenizer.loadCached(file, cache)
            for (t in listOf("buy milk (buy)", "task: search result | query: what did I say about the vet?", "", "नमस्ते 🙂")) {
                assertEquals(t, first.encode(t, 64).toList(), second.encode(t, 64).toList())
            }
        } finally {
            cache.delete()
        }
    }

    @Test fun bosAndEosOnEmptyAndTruncation() {
        val file = tokenizerFile()
        assumeTrue("tokenizer.json asset not present (git-ignored model assets)", file != null)
        val tk = file!!.inputStream().use { GemmaTokenizer.load(it) }
        assertEquals(listOf(2, 1), tk.encode("", 128).toList())
        val long = tk.encode("word ".repeat(1000), 128)
        assertEquals(128, long.size)
        assertEquals(2, long.first())
        assertEquals(1, long.last())
        assertEquals(listOf(2, 1), tk.encode("anything", 2).toList())
    }
}
