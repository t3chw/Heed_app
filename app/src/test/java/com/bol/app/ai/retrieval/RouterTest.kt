package com.bol.app.ai.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Expected values come from the study's Python reference: window() and route()/weighted().
 * Month names use real calendar months; the reference for that is window() with only the month table swapped.
 * router_route_cases.json: 8 queries over 120 random documents, from route().
 */
class RouterTest {
    private val nov3 = LocalDate.of(2026, 11, 3)
    private fun d(s: String) = LocalDate.parse(s).toEpochDay()

    @Test fun windowRelativeRulesMatchHarness() {
        val cases = listOf(
            "what did I say yesterday" to ("2026-11-02" to "2026-11-02"), "things from today" to ("2026-11-03" to "2026-11-03"),
            "3 days ago groceries" to ("2026-10-30" to "2026-11-01"), "two days ago" to ("2026-10-31" to "2026-11-02"),
            "few days ago" to ("2026-10-30" to "2026-11-01"), "last week plans" to ("2026-10-27" to "2026-11-03"),
            "past 7 days" to ("2026-10-27" to "2026-11-03"), "this week" to ("2026-10-28" to "2026-11-03"),
            "what did i note in the last month" to ("2026-10-04" to "2026-11-03"), "this month" to ("2026-10-04" to "2026-11-03"),
            "last couple of weeks" to ("2026-10-13" to "2026-11-03"), "this morning" to ("2026-11-03" to "2026-11-03"),
            "past few weeks" to ("2026-10-13" to "2026-11-03"), "yesterday in may" to ("2026-11-02" to "2026-11-02"),
        )
        for ((q, w) in cases) assertEquals(q, d(w.first) to d(w.second), Router.window(q, nov3))
    }

    @Test fun windowMonthsAreRealCalendarMonths() {
        val cases = listOf(
            "in may" to ("2026-05-01" to "2026-05-31"), "back in april" to ("2026-04-01" to "2026-04-30"),
            "during September" to ("2026-09-01" to "2026-09-30"), "december" to ("2025-12-01" to "2025-12-31"),
            "november ideas" to ("2026-11-01" to "2026-11-30"), "in january" to ("2026-01-01" to "2026-01-31"),
            "march and may" to ("2026-03-01" to "2026-03-31"),
            // the harness rule also reads "may" the verb as the month; kept as measured
            "may i go" to ("2026-05-01" to "2026-05-31"),
        )
        for ((q, w) in cases) assertEquals(q, d(w.first) to d(w.second), Router.window(q, nov3))
        assertEquals(d("2024-02-01") to d("2024-02-29"), Router.window("february", LocalDate.of(2024, 3, 10)))
    }

    @Test fun noWindowWithoutTimeWords() {
        // due words switch the "today" rule off
        for (q in listOf("something due today", "buy milk", "")) assertNull(q, Router.window(q, nov3))
    }

    @Test fun ftsMatchIsOrOfQuotedTerms() {
        assertEquals("\"whats\" OR \"dentists\" OR \"number\"", Router.ftsMatch("What's the dentist's number?"))
        assertNull(Router.ftsMatch("what is it"))
    }

    @Test fun weightedFusion() {
        val bm = listOf("a", "b", "c", "d", "e"); val de = listOf("c", "f", "a", "g", "h")
        assertEquals(listOf("c", "a", "f", "g", "h", "b", "d", "e"), Router.weightedScored(listOf(bm to 0.2, de to 0.8), 10).map { it.first })
    }

    private val lib = Router.Lib(mapOf("a" to 5L, "b" to 40L, "c" to 41L, "d" to 44L, "e" to 3L, "f" to 41L, "g" to 42L, "h" to 2L,
        "i" to 43L, "j" to 43L, "k" to 44L, "l" to 44L))
    private val bm = listOf("a", "b", "c", "d", "e"); private val de = listOf("c", "f", "a", "g", "h")
    private val day45 = LocalDate.ofEpochDay(45)

    @Test fun routeWindowFillsUpToTen() {
        // yesterday = day 44: only d is in the fused list, then k, l (day 44) come from the library, ahead of the rest
        assertEquals(listOf("d", "l", "k", "c", "a", "f", "g", "h", "b", "e"), Router.route("yesterday", day45, bm, de, lib))
        // last week = days 38..45: b c d f g inside the fused list, filled with l k j i from the library by day then key, descending
        assertEquals(listOf("c", "f", "g", "b", "d", "l", "k", "j", "i", "a", "h", "e"), Router.route("last week", day45, bm, de, lib))
    }

    @Test fun routeWithoutWindowIsPlainFusion() {
        assertEquals(listOf("c", "a", "f", "g", "h", "b", "d", "e"), Router.route("buy milk", day45, bm, de, lib))
        // no embedding list (model unavailable): BM25 order, date rule still applies
        assertEquals(bm, Router.route("buy milk", day45, bm, emptyList(), lib))
        assertEquals(listOf("d", "l", "k", "a", "b", "c", "e"), Router.route("yesterday", day45, bm, emptyList(), lib))
    }

    @Test fun routeFillIsCappedAtTenAndFiftyOverall() {
        val l2 = Router.Lib((0 until 30).associate { "x%02d".format(it) to it.toLong() })
        assertEquals(listOf("x28", "x25", "x27", "x29", "x26", "x24", "x23", "x22", "x01", "x05"),
            Router.route("last week", LocalDate.ofEpochDay(29), listOf("x05", "x28", "x27"), listOf("x28", "x01", "x25"), l2))
    }

    @Test fun routeMatchesPythonOnRandomLibraries() {
        val root = Json.parseToJsonElement(javaClass.getResource("/router_route_cases.json")!!.readText()).jsonObject
        val today = LocalDate.ofEpochDay(root["today"]!!.jsonPrimitive.long)
        val lib = Router.Lib(root["day"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
        val cases = root["cases"]!!.jsonArray
        assertEquals(8, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            fun list(k: String) = o[k]!!.jsonArray.map { it.jsonPrimitive.content }
            val q = o["q"]!!.jsonPrimitive.content
            assertEquals(q, list("want"), Router.route(q, today, list("bm"), list("de"), lib))
        }
    }

    @Test fun topKIsBestDotProductFirst() {
        val vecs = floatArrayOf(1f, 0f, 0f, 1f, 0.6f, 0.8f, -1f, 0f)
        assertArrayEquals(intArrayOf(1, 2, 0), Router.topK(vecs, 2, 4, floatArrayOf(0f, 1f), 3))
        assertArrayEquals(intArrayOf(1, 2, 0, 3), Router.topK(vecs, 2, 4, floatArrayOf(0f, 1f), 50))
    }
}
