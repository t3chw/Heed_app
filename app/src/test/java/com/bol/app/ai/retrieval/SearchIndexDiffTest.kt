package com.bol.app.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sync step: what Room holds vs what the index holds becomes upserts (new, edited, moved, re-dated) and deletes. */
class SearchIndexDiffTest {
    private fun doc(key: String, day: Long, text: String) = SearchIndex.Doc(key, SearchIndex.KIND_TASK, key.drop(2), day, text)

    @Test fun newEditedMovedAndDeletedDocs() {
        val have = mapOf(
            "t:same" to (10L to "buy milk (buy)"),
            "t:edited" to (10L to "call mum (tasks)"),
            "t:moved" to (11L to "fix the tap (tasks)"),
            "t:gone" to (12L to "old task (tasks)"),
            "n:note" to (10L to "buy milk and call mum"),
        )
        val want = listOf(
            doc("t:same", 10, "buy milk (buy)"),
            doc("t:edited", 10, "call mum tonight (tasks)"),
            doc("t:moved", 11, "fix the tap (work)"),
            doc("n:note", 10, "buy milk and call mum"),
            doc("t:new", 13, "book the vet (tasks)"),
        )
        val (upserts, deletes) = SearchIndex.diff(want, have)
        assertEquals(listOf("t:edited", "t:moved", "t:new"), upserts.map { it.key })
        assertEquals(setOf("t:gone"), deletes)
    }

    @Test fun identicalIsNoWork() {
        val want = listOf(doc("t:a", 1, "x (buy)"), doc("n:b", 2, "y"))
        val (upserts, deletes) = SearchIndex.diff(want, want.associate { it.key to (it.day to it.text) })
        assertEquals(0, upserts.size)
        assertEquals(0, deletes.size)
    }

    @Test fun dayChangeIsAnUpsert() {
        val (upserts, _) = SearchIndex.diff(listOf(doc("t:a", 2, "x (buy)")), mapOf("t:a" to (1L to "x (buy)")))
        assertEquals(listOf("t:a"), upserts.map { it.key })
    }
}
