package com.bol.app.ai.audio

/**
 * Joins per-chunk transcripts of one recording (see [AudioChunker]).
 *
 * After a hard cut, chunk N+1 re-hears the last ~1 s of chunk N, so the same
 * few words end one transcript and start the next ("…buy milk and" + "milk
 * and eggs…"). For those boundaries the longest run of words (up to
 * [MAX_OVERLAP_WORDS]) that ends the text so far and starts the next chunk is
 * dropped from the next chunk. Comparison ignores case and punctuation.
 * Boundaries without overlap (silence cuts) are joined as-is, so genuinely
 * repeated words ("no, no") are never removed there.
 */
object TranscriptJoiner {

    private const val MAX_OVERLAP_WORDS = 8

    fun join(texts: List<String>, overlapsPrevious: List<Boolean>): String {
        require(texts.size == overlapsPrevious.size) { "one overlap flag per chunk" }
        val out = ArrayList<String>()
        for ((i, text) in texts.withIndex()) {
            var words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            if (i > 0 && overlapsPrevious[i] && out.isNotEmpty()) {
                words = words.drop(repeatedPrefixLength(out, words))
            }
            out += words
        }
        return out.joinToString(" ")
    }

    private fun repeatedPrefixLength(previous: List<String>, next: List<String>): Int {
        val max = minOf(MAX_OVERLAP_WORDS, previous.size, next.size)
        for (k in max downTo 1) {
            val tail = previous.subList(previous.size - k, previous.size)
            val head = next.subList(0, k)
            if (tail.indices.all { norm(tail[it]) == norm(head[it]) && norm(head[it]).isNotEmpty() }) return k
        }
        return 0
    }

    private fun norm(word: String): String =
        word.lowercase().filter { it.isLetterOrDigit() }
}
