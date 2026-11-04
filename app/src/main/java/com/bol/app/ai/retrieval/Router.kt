package com.bol.app.ai.retrieval

import java.time.LocalDate

/**
 * Router v2 from the Heed study's search benchmark, as measured on a phone: BM25 top 50 (weight 0.2) and
 * EmbeddingGemma top 50 (weight 0.8) merged by weighted reciprocal rank fusion (k = 10), then the recorded-date rule.
 * Pure Kotlin (no Android imports) so it is unit-tested against the study's Python reference (`words`, `BM25`,
 * `window`, `weighted`, `route`) with the category, due, urgent and tasks-only rules off.
 *
 * Days are epoch days (`LocalDate.toEpochDay()`) in the phone's time zone.
 */
object Router {
    const val TOP_N = 50
    const val BM25_WEIGHT = 0.2
    const val RRF_K = 10

    val STOP: Set<String> = ("a an the and or of to in on at for with my me i is it this that what when where which who how do did does any " +
        "anything something things thing stuff about from was were be been have has had can could should would need so just like get got some " +
        "all there their them you your we our").split(" ").toSet()

    private val WORD = Regex("[a-z0-9']+")

    /** harness.words(): lowercase, [a-z0-9']+ tokens, stopwords dropped. */
    fun words(s: String): List<String> = WORD.findAll(s.lowercase()).map { it.value }.filter { it !in STOP }.toList()

    /** harness.BM25.search query for FTS5 MATCH: OR of quoted terms (apostrophes removed), null when there is no term. */
    fun ftsMatch(q: String): String? {
        val terms = words(q).map { it.replace("'", "") }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "\"$it\"" }
    }

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val I = RegexOption.IGNORE_CASE
    private val DUE = Regex("\\b(due|deadline|appointment|when is|when's|by when|schedule|scheduled|book|booked)\\b", I)
    private val YESTERDAY = Regex("\\byesterday\\b")
    private val TODAY = Regex("\\btoday\\b|\\bthis morning\\b|\\btonight\\b")
    private val DAYS_AGO = Regex("\\b(\\d+|two|three|four|few) days ago\\b")
    private val LAST_WEEK = Regex("\\b(last|past) (week|7 days)\\b")
    private val THIS_WEEK = Regex("\\bthis week\\b")
    private val LAST_MONTH = Regex("\\b(last|past) (month|30 days)\\b|\\bthis month\\b")
    private val FEW_WEEKS = Regex("\\b(last|past) (couple of|few) weeks\\b")
    private val MONTH_RX = MONTHS.map { Regex("\\b(in|during|back in) $it\\b|\\b$it\\b") }

    /**
     * harness.window() rule by rule: the recorded-date window (first day, last day) a query asks for, or null.
     * The one change: a month name is the latest real calendar month of that name that is not after [today]
     * (the benchmark's table assumed day 0 = 1 April and had no January–March).
     */
    fun window(q: String, today: LocalDate): Pair<Long, Long>? {
        val s = q.lowercase()
        val t = today.toEpochDay()
        if (YESTERDAY.containsMatchIn(s)) return (t - 1) to (t - 1)
        if (TODAY.containsMatchIn(s) && !DUE.containsMatchIn(s)) return t to t
        DAYS_AGO.find(s)?.let { m ->
            val g = m.groupValues[1]
            // A number too big for a Long would give Python an empty window far in the past: the same as none.
            val n = mapOf("two" to 2L, "three" to 3L, "four" to 4L, "few" to 3L)[g] ?: g.toLongOrNull() ?: return null
            return (t - n - 1) to (t - n + 1)
        }
        if (LAST_WEEK.containsMatchIn(s)) return (t - 7) to t
        if (THIS_WEEK.containsMatchIn(s)) return (t - 6) to t
        if (LAST_MONTH.containsMatchIn(s)) return (t - 30) to t
        if (FEW_WEEKS.containsMatchIn(s)) return (t - 21) to t
        for ((idx, rx) in MONTH_RX.withIndex()) {
            if (!rx.containsMatchIn(s)) continue
            val month = idx + 1
            val first = LocalDate.of(if (month <= today.monthValue) today.year else today.year - 1, month, 1)
            return first.toEpochDay() to first.withDayOfMonth(first.lengthOfMonth()).toEpochDay()
        }
        return null
    }

    /** router_offline.weighted(): sum w/(k+rank+1) over the lists, stable sort by descending score (ties keep first-seen order). */
    fun weightedScored(lists: List<Pair<List<String>, Double>>, k: Int): List<Pair<String, Double>> {
        val sc = LinkedHashMap<String, Double>()
        for ((lst, w) in lists) lst.forEachIndexed { r, d -> sc[d] = (sc[d] ?: 0.0) + w / (k + r + 1) }
        return sc.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** day: key -> recorded day of every document; byDay: (day, key) sorted descending (day, then key) like Python's tuple sort. */
    class Lib(val day: Map<String, Long>) {
        val byDay: List<Pair<Long, String>> = day.map { it.value to it.key }
            .sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenByDescending { it.second })
    }

    /**
     * router_offline.route(...) with cat=due=urgent=tasks_only=False: fuse [bm] and [de], then, when the query names a
     * recorded-date window, put the fused documents inside it first, filled up to 10 from that window by recency.
     */
    fun route(q: String, today: LocalDate, bm: List<String>, de: List<String>, lib: Lib, w: Double = BM25_WEIGHT, k: Int = RRF_K): List<String> {
        var fused = weightedScored(listOf(bm to w, de to (1 - w)), k).map { it.first }
        val win = window(q, today)
        if (win != null) {
            val range = win.first..win.second
            val inside = fused.filter { lib.day[it] in range }.toMutableList()
            if (inside.size < 10) {
                val have = inside.toHashSet()
                inside += lib.byDay.filter { (d, key) -> d in range && key !in have }.map { it.second }.take(10 - inside.size)
            }
            if (inside.isNotEmpty()) { val have = inside.toHashSet(); fused = inside + fused.filter { it !in have } }
        }
        return fused.take(TOP_N)
    }

    /** Indices of the [k] rows of [vecs] (n × dim, unit length) with the highest dot product with [q], best first. */
    fun topK(vecs: FloatArray, dim: Int, n: Int, q: FloatArray, k: Int): IntArray {
        val bestIdx = IntArray(k) { -1 }
        val bestScore = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            var s = 0f
            val base = i * dim
            for (d in 0 until dim) s += vecs[base + d] * q[d]
            if (s > bestScore[k - 1]) {
                var j = k - 1
                while (j > 0 && bestScore[j - 1] < s) { bestScore[j] = bestScore[j - 1]; bestIdx[j] = bestIdx[j - 1]; j-- }
                bestScore[j] = s; bestIdx[j] = i
            }
        }
        return bestIdx.filter { it >= 0 }.toIntArray()
    }
}
