package com.bol.app.ai.retrieval

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * Pure-Kotlin tokenizer for EmbeddingGemma-300M (the Gemma 3 SentencePiece-style BPE with byte fallback), read from the
 * model's own `tokenizer.json`. No Android imports, so it runs in JVM unit tests.
 *
 * It reproduces `AutoTokenizer.from_pretrained("google/embeddinggemma-300m")(text, truncation=True, max_length=N,
 * add_special_tokens=True)["input_ids"]`:
 *  1. Added tokens (`<bos>`, `<unused12>`, `<table>` ...) are cut out of the raw text first, leftmost and longest.
 *  2. The rest is normalised by replacing every ASCII space with U+2581, and is then one BPE "word" (the pre-tokenizer
 *     splits on spaces, which no longer exist after step 2).
 *  3. Characters missing from the vocabulary become `<0xNN>` byte tokens. Merges are applied lowest rank first,
 *     leftmost first on ties, exactly like the Hugging Face `tokenizers` BPE.
 *  4. Output is `<bos>` + at most `maxLen - 2` tokens + `<eos>`.
 */
class GemmaTokenizer private constructor(
    private val charIds: Map<Int, Int>,
    private val byteIds: IntArray,
    private val unkId: Int,
    private val bosId: Int,
    private val eosId: Int,
    private val merges: MergeTable,
    private val mergedByRank: IntArray,
    private val added: List<Pair<String, Int>>,
) {
    private class TrieNode {
        var children: HashMap<Char, TrieNode>? = null
        var tokenId = -1
    }

    private val addedRoot = TrieNode().also { root ->
        for ((content, id) in added) {
            if (content.isEmpty()) continue
            var node = root
            for (ch in content) {
                val m = node.children ?: HashMap<Char, TrieNode>().also { node.children = it }
                node = m.getOrPut(ch) { TrieNode() }
            }
            node.tokenId = id
        }
    }

    /** Open-addressing table: (left id, right id) -> merge rank. */
    private class MergeTable(expected: Int) {
        private val cap: Int = Integer.highestOneBit(maxOf(16, expected) * 2 - 1) shl 1
        private val keys = LongArray(cap) { EMPTY }
        private val ranks = IntArray(cap)
        private val mask = cap - 1

        fun put(a: Int, b: Int, rank: Int) {
            val key = pack(a, b)
            var i = hash(key) and mask
            while (keys[i] != EMPTY) {
                if (keys[i] == key) return // the first (lowest) rank wins, like the HF merge map
                i = (i + 1) and mask
            }
            keys[i] = key
            ranks[i] = rank
        }

        /** Rank of the merge, or -1. */
        fun rank(a: Int, b: Int): Int {
            val key = pack(a, b)
            var i = hash(key) and mask
            while (true) {
                val k = keys[i]
                if (k == EMPTY) return -1
                if (k == key) return ranks[i]
                i = (i + 1) and mask
            }
        }

        /** Every stored merge as (left id, right id, rank). */
        fun forEach(f: (Int, Int, Int) -> Unit) {
            for (i in keys.indices) { val k = keys[i]; if (k != EMPTY) f((k ushr 32).toInt(), k.toInt(), ranks[i]) }
        }

        private fun pack(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        private fun hash(k: Long): Int {
            var h = k * -7046029254386353131L
            h = h xor (h ushr 32)
            return h.toInt()
        }

        companion object { private const val EMPTY = -1L }
    }

    /** `<bos>` + content + `<eos>`, content cut to [maxLen] - 2 tokens (the Hugging Face truncation=True rule). */
    fun encode(text: String, maxLen: Int): IntArray {
        val limit = (maxLen - 2).coerceAtLeast(0)
        val content = IntList()
        var segStart = 0
        var i = 0
        while (i < text.length && content.size <= limit) {
            val hit = matchAdded(text, i)
            if (hit != null) {
                if (i > segStart) appendBpe(text, segStart, i, content)
                content.add(hit.first)
                i += hit.second
                segStart = i
            } else {
                i++
            }
        }
        if (segStart < text.length && content.size <= limit) appendBpe(text, segStart, text.length, content)
        val n = minOf(content.size, limit)
        val out = IntArray(n + 2)
        out[0] = bosId
        for (k in 0 until n) out[k + 1] = content[k]
        out[n + 1] = eosId
        return out
    }

    /** (id, length in chars) of the longest added token starting at [at], or null. */
    private fun matchAdded(text: String, at: Int): Pair<Int, Int>? {
        var node = addedRoot.children?.get(text[at]) ?: return null
        var best: Pair<Int, Int>? = if (node.tokenId >= 0) node.tokenId to 1 else null
        var j = at + 1
        while (j < text.length) {
            node = node.children?.get(text[j]) ?: break
            j++
            if (node.tokenId >= 0) best = node.tokenId to (j - at)
        }
        return best
    }

    private fun appendBpe(text: String, from: Int, to: Int, out: IntList) {
        // initial symbols: one per Unicode scalar of the normalised fragment, or its UTF-8 byte tokens
        val sym = IntList()
        var p = from
        while (p < to) {
            val cp = text.codePointAt(p)
            p += Character.charCount(cp)
            val c = if (cp == 0x20) 0x2581 else cp
            val id = charIds[c]
            if (id != null) { sym.add(id); continue }
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (bytes.all { byteIds[it.toInt() and 0xFF] >= 0 }) {
                for (b in bytes) sym.add(byteIds[b.toInt() and 0xFF])
            } else if (sym.size == 0 || sym[sym.size - 1] != unkId) {
                sym.add(unkId) // fuse_unk
            }
        }
        val n = sym.size
        if (n == 0) return
        val ids = IntArray(n) { sym[it] }
        val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
        val prev = IntArray(n) { it - 1 }
        val alive = BooleanArray(n) { true }
        val heap = PriorityQueue<Long>()
        fun push(pos: Int) {
            val nx = next[pos]
            if (nx < 0) return
            val r = merges.rank(ids[pos], ids[nx])
            if (r >= 0) heap.add((r.toLong() shl 32) or pos.toLong())
        }
        for (k in 0 until n) push(k)
        while (heap.isNotEmpty()) {
            val top = heap.poll()!!
            val rank = (top ushr 32).toInt()
            val pos = (top and 0xFFFFFFFFL).toInt()
            if (!alive[pos]) continue
            val nx = next[pos]
            if (nx < 0) continue
            if (merges.rank(ids[pos], ids[nx]) != rank) continue // stale entry
            ids[pos] = mergedByRank[rank]
            alive[nx] = false
            next[pos] = next[nx]
            if (next[nx] >= 0) prev[next[nx]] = pos
            if (prev[pos] >= 0) push(prev[pos])
            push(pos)
        }
        var k = 0
        while (k >= 0) { out.add(ids[k]); k = next[k] }
    }

    private class IntList {
        private var a = IntArray(64)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    companion object {
        /**
         * Reads tokenizer.json with a streaming parser (the file is 33 MB; a DOM would cost hundreds of MB on a phone).
         * Only `added_tokens`, `model.vocab` and `model.merges` are used; the normaliser, pre-tokenizer and the
         * `<bos>`/`<eos>` template are fixed above and are covered by the parity test. `vocab` must come before
         * `merges` in the file, as it does in Google's.
         */
        fun load(stream: InputStream): GemmaTokenizer = load(InputStreamReader(stream, Charsets.UTF_8))

        fun load(reader: Reader): GemmaTokenizer {
            val js = JsonStream(reader)
            val vocab = HashMap<String, Int>(1 shl 19)
            var ma = IntArray(1 shl 19); var mb = IntArray(1 shl 19); var mm = IntArray(1 shl 19)
            var nMerges = 0
            fun addMerge(l: String, r: String) {
                if (nMerges == ma.size) { ma = ma.copyOf(nMerges * 2); mb = mb.copyOf(nMerges * 2); mm = mm.copyOf(nMerges * 2) }
                ma[nMerges] = vocab[l] ?: -1; mb[nMerges] = vocab[r] ?: -1; mm[nMerges] = vocab[l + r] ?: -1
                nMerges++
            }
            val added = ArrayList<Pair<String, Int>>()
            js.beginObject()
            while (js.hasNext()) {
                when (js.name()) {
                    "added_tokens" -> {
                        js.beginArray()
                        while (js.hasNext()) {
                            js.beginObject()
                            var id = -1; var content = ""
                            while (js.hasNext()) when (js.name()) {
                                "id" -> id = js.int()
                                "content" -> content = js.string()
                                else -> js.skip()
                            }
                            js.endObject()
                            added.add(content to id)
                        }
                        js.endArray()
                    }
                    "model" -> {
                        js.beginObject()
                        while (js.hasNext()) {
                            when (js.name()) {
                                "vocab" -> {
                                    js.beginObject()
                                    while (js.hasNext()) { val t = js.name(); vocab[t] = js.int() }
                                    js.endObject()
                                }
                                "merges" -> {
                                    check(vocab.isNotEmpty()) { "tokenizer.json lists merges before the vocabulary" }
                                    js.beginArray()
                                    while (js.hasNext()) {
                                        if (js.peekIsString()) {
                                            val s = js.string()
                                            val sp = s.indexOf(' ', 1)
                                            addMerge(s.substring(0, sp), s.substring(sp + 1))
                                        } else {
                                            js.beginArray(); val l = js.string(); val r = js.string(); js.endArray()
                                            addMerge(l, r)
                                        }
                                    }
                                    js.endArray()
                                }
                                else -> js.skip()
                            }
                        }
                        js.endObject()
                    }
                    else -> js.skip()
                }
            }
            js.endObject()

            val charIds = HashMap<Int, Int>(1 shl 15)
            for ((t, id) in vocab) if (t.isNotEmpty() && t.codePointCount(0, t.length) == 1) charIds[t.codePointAt(0)] = id
            val byteIds = IntArray(256) { vocab[String.format("<0x%02X>", it)] ?: -1 }
            val table = MergeTable(nMerges)
            val mergedByRank = IntArray(nMerges) { mm[it] }
            for (rank in 0 until nMerges) if (ma[rank] >= 0 && mb[rank] >= 0 && mm[rank] >= 0) table.put(ma[rank], mb[rank], rank)
            fun special(name: String) = added.first { it.first == name }.second
            return GemmaTokenizer(charIds, byteIds, special("<unk>"), special("<bos>"), special("<eos>"), table, mergedByRank, added)
        }

        /**
         * [json] through a compact binary copy ([cache], written on first use): ids only, no vocabulary strings, read in
         * one go. The copy names the size of the json it came from, so a new tokenizer.json is re-read.
         */
        fun loadCached(json: File, cache: File): GemmaTokenizer {
            if (cache.isFile) runCatching { readBinary(cache.readBytes(), json.length()) }.getOrNull()?.let { return it }
            val tk = json.inputStream().buffered(1 shl 16).use { load(it) }
            runCatching {
                val tmp = File(cache.path + ".part")
                tmp.outputStream().buffered(1 shl 16).use { tk.writeBinary(it, json.length()) }
                check(tmp.renameTo(cache))
            }
            return tk
        }

        private const val MAGIC = 0x47544B31 // "GTK1"

        /** The tokenizer written by [writeBinary], or null when [bytes] is another format or another source file. */
        fun readBinary(bytes: ByteArray, sourceBytes: Long): GemmaTokenizer? {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 12 || bb.int != MAGIC || bb.long != sourceBytes) return null
            fun ints(n: Int) = IntArray(n).also { bb.asIntBuffer().get(it); bb.position(bb.position() + 4 * n) }
            val unk = bb.int; val bos = bb.int; val eos = bb.int
            val byteIds = ints(256)
            val nChar = bb.int
            val cps = ints(nChar); val cids = ints(nChar)
            val charIds = HashMap<Int, Int>(nChar * 2)
            for (i in 0 until nChar) charIds[cps[i]] = cids[i]
            val mergedByRank = ints(bb.int)
            val nSlots = bb.int
            val a = ints(nSlots); val b = ints(nSlots); val r = ints(nSlots)
            val table = MergeTable(mergedByRank.size)
            for (i in 0 until nSlots) table.put(a[i], b[i], r[i])
            val added = List(bb.int) {
                val id = bb.int
                val s = ByteArray(bb.int).also { bb.get(it) }
                String(s, Charsets.UTF_8) to id
            }
            return GemmaTokenizer(charIds, byteIds, unk, bos, eos, table, mergedByRank, added)
        }
    }

    /** The compact form read by [readBinary]; [sourceBytes] is the size of the tokenizer.json it came from. */
    fun writeBinary(out: OutputStream, sourceBytes: Long) {
        val slots = ArrayList<IntArray>(mergedByRank.size)
        merges.forEach { a, b, r -> slots.add(intArrayOf(a, b, r)) }
        val addedBytes = added.map { it.first.toByteArray(Charsets.UTF_8) }
        val size = 4 + 8 + 12 + 4 * 256 + 4 + 8 * charIds.size + 4 + 4 * mergedByRank.size + 4 + 12 * slots.size + 4 + addedBytes.sumOf { 8 + it.size }
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC).putLong(sourceBytes).putInt(unkId).putInt(bosId).putInt(eosId)
        byteIds.forEach { bb.putInt(it) }
        val chars = charIds.entries.toList()
        bb.putInt(chars.size)
        chars.forEach { bb.putInt(it.key) }
        chars.forEach { bb.putInt(it.value) }
        bb.putInt(mergedByRank.size)
        mergedByRank.forEach { bb.putInt(it) }
        bb.putInt(slots.size)
        for (k in 0..2) slots.forEach { bb.putInt(it[k]) }
        bb.putInt(added.size)
        added.forEachIndexed { i, (_, id) -> bb.putInt(id).putInt(addedBytes[i].size).put(addedBytes[i]) }
        out.write(bb.array())
    }
}

/** Minimal pull parser for the subset of JSON found in tokenizer.json. Commas are treated as whitespace (valid input only). */
internal class JsonStream(private val r: Reader) {
    private val buf = CharArray(1 shl 16)
    private var len = 0
    private var pos = 0
    private var peeked = -2

    private fun read(): Int {
        if (peeked != -2) { val c = peeked; peeked = -2; return c }
        if (pos == len) { len = r.read(buf, 0, buf.size); pos = 0; if (len <= 0) { len = 0; return -1 } }
        return buf[pos++].code
    }

    private fun peek(): Int { if (peeked == -2) peeked = read(); return peeked }

    private fun ws(): Int {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == ','.code) read() else return c
        }
    }

    private fun expect(ch: Char) {
        val c = ws()
        check(c == ch.code) { "json: expected '$ch' got '${if (c < 0) "EOF" else c.toChar().toString()}'" }
        read()
    }

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    /** True while the current object/array has another member. */
    fun hasNext(): Boolean { val c = ws(); return c != '}'.code && c != ']'.code && c >= 0 }

    fun name(): String { val s = rawString(); expect(':'); return s }
    fun string(): String = rawString()
    fun peekIsString(): Boolean = ws() == '"'.code

    fun int(): Int {
        ws()
        var n = 0L; var neg = false
        var c = peek()
        if (c == '-'.code) { neg = true; read(); c = peek() }
        while (c >= '0'.code && c <= '9'.code) { n = n * 10 + (c - '0'.code); read(); c = peek() }
        return (if (neg) -n else n).toInt()
    }

    private fun rawString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = read()
            check(c >= 0) { "json: unterminated string" }
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> when (val e = read()) {
                    'n'.code -> sb.append('\n')
                    't'.code -> sb.append('\t')
                    'r'.code -> sb.append('\r')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000c')
                    'u'.code -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(read(), 16) }; sb.append(v.toChar()) }
                    else -> sb.append(e.toChar()) // " \ /
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    /** Skips one value of any kind. */
    fun skip() {
        when (val c = ws()) {
            '"'.code -> rawString()
            '{'.code -> { beginObject(); while (hasNext()) { name(); skip() }; endObject() }
            '['.code -> { beginArray(); while (hasNext()) skip(); endArray() }
            else -> { // number, true, false, null
                check(c >= 0) { "json: unexpected EOF" }
                while (true) {
                    val d = peek()
                    if (d < 0 || d == ','.code || d == '}'.code || d == ']'.code || d == ' '.code || d == '\n'.code || d == '\r'.code || d == '\t'.code) break
                    read()
                }
            }
        }
    }
}
