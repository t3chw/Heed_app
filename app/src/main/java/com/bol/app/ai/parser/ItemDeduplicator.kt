package com.bol.app.ai.parser

import com.bol.app.domain.model.ParsedItem

/**
 * Drops near-duplicate [ParsedItem]s that the LLM emits across audio chunks.
 *
 * When we split a long braindump into overlapping chunks (see [com.bol.app.ai.audio.AudioChunker])
 * the 5-second overlap guarantees that items spoken near a chunk boundary survive —
 * but it also means the LLM will often emit the same item twice, once for each chunk.
 * This class deduplicates by Jaccard similarity over tokenized (lowercased, stopword-
 * filtered) item text, bucketed by category so that "buy milk" and "watch Milk the movie"
 * don't collide.
 *
 * Threshold [JACCARD_THRESHOLD] = 0.7 was picked to catch word-order variants and small
 * rephrasings (e.g. "pick up the kids" vs "pick the kids up") without merging genuinely
 * distinct items that happen to share a couple of common words.
 */
object ItemDeduplicator {

    /**
     * Returns [candidates] with any entries that are near-duplicates of:
     *   1. anything already in [existing], OR
     *   2. anything kept earlier in [candidates]
     * removed. Order of first-seen candidates is preserved.
     */
    fun dedupAgainst(
        existing: List<ParsedItem>,
        candidates: List<ParsedItem>,
        threshold: Double = JACCARD_THRESHOLD
    ): List<ParsedItem> {
        if (candidates.isEmpty()) return emptyList()

        val existingByCategory: Map<String, List<Set<String>>> = existing
            .groupBy { it.categoryId }
            .mapValues { (_, items) -> items.map { tokenize(it.text) } }

        val keptByCategory: MutableMap<String, MutableList<Set<String>>> = mutableMapOf()
        val accepted = ArrayList<ParsedItem>(candidates.size)

        for (candidate in candidates) {
            val tokens = tokenize(candidate.text)
            // If the item's text has no "content" words left after stopword removal we
            // can't reliably dedupe it — keep it rather than silently drop a real item.
            if (tokens.isEmpty()) {
                accepted += candidate
                continue
            }
            val sameCategoryExisting = existingByCategory[candidate.categoryId].orEmpty()
            val sameCategoryKept = keptByCategory[candidate.categoryId].orEmpty()
            val isDuplicate = (sameCategoryExisting.asSequence() + sameCategoryKept.asSequence())
                .any { jaccard(tokens, it) >= threshold }
            if (!isDuplicate) {
                accepted += candidate
                keptByCategory.getOrPut(candidate.categoryId) { mutableListOf() }.add(tokens)
            }
        }
        return accepted
    }

    /** Count how many candidates would be dropped — used for logging only. */
    fun countDuplicates(
        existing: List<ParsedItem>,
        candidates: List<ParsedItem>,
        threshold: Double = JACCARD_THRESHOLD
    ): Int = candidates.size - dedupAgainst(existing, candidates, threshold).size

    internal fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val normalized = text.lowercase()
            .replace(PUNCT_REGEX, " ")
        val tokens = HashSet<String>()
        for (raw in normalized.split(WHITESPACE_REGEX)) {
            if (raw.length >= 2 && raw !in STOPWORDS) tokens += raw
        }
        return tokens
    }

    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var intersection = 0
        for (t in smaller) if (t in larger) intersection++
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private const val JACCARD_THRESHOLD = 0.7

    private val PUNCT_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * High-frequency English function words + common braindump filler. Hindi pronouns/
     * particles are also included so the same dedup works on Hindi voice input. The
     * goal is NOT linguistic correctness — it is only to stop trivially-short items
     * like "I need to X" and "X please" from scoring a misleadingly high overlap.
     */
    private val STOPWORDS: Set<String> = setOf(
        // English function words
        "a", "an", "the", "and", "or", "but", "so", "then",
        "i", "me", "my", "mine", "we", "our", "you", "your", "yours",
        "to", "of", "in", "on", "at", "for", "with", "from", "by",
        "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "doing", "done",
        "have", "has", "had", "having",
        "this", "that", "these", "those", "it", "its",
        "not", "no", "nor",
        // Common filler / intent markers that the LLM often echoes
        "need", "needs", "want", "wants", "should", "must",
        "will", "would", "could", "can", "may", "might",
        "please", "okay", "ok", "yeah", "also", "just", "really", "maybe",
        // Hindi pronouns + common particles (transliterated + devanagari)
        "main", "mera", "meri", "mere", "ko", "ka", "ki", "ke", "hai", "hain",
        "मैं", "मेरा", "मेरी", "मेरे", "को", "का", "की", "के", "है", "हैं",
        "और", "या", "भी", "तो", "ही"
    )
}
