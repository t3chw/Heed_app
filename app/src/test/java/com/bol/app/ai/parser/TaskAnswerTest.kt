package com.bol.app.ai.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAnswerTest {
    private val ids = setOf("tasks", "buy", "work")
    private fun read(raw: String) = TaskAnswer.read(raw, ids, "tasks")

    @Test fun `reads text and category`() {
        val tasks = read("""{"tasks":[{"t":"Call the bank at 5","c":"tasks"},{"t":"Buy milk","c":"buy"}]}""")!!
        assertEquals(TaskAnswer.Task("Call the bank at 5", "tasks"), tasks[0])
        assertEquals(TaskAnswer.Task("Buy milk", "buy"), tasks[1])
    }

    @Test fun `empty list is valid`() = assertTrue(read("""{"tasks":[]}""")!!.isEmpty())

    @Test fun `text after the object is ignored`() = assertEquals(1, read("""{"tasks":[{"t":"Buy milk","c":"buy"}]} extra""")!!.size)

    @Test fun `unknown category falls back`() = assertEquals("tasks", read("""{"tasks":[{"t":"Buy milk","c":"shopping"}]}""")!![0].category)

    @Test fun `bad row is dropped and good rows stay`() {
        val tasks = read("""{"tasks":[{"t":"","c":"buy"},7,{"c":"buy"},{"t":{"a":1},"c":"buy"},{"t":"Keep me","c":"buy"}]}""")!!
        assertEquals(listOf("Keep me"), tasks.map { it.text })
    }

    @Test fun `extra fields are ignored`() = assertEquals(TaskAnswer.Task("Keep me", "buy"), read("""{"tasks":[{"t":"Keep me","c":"buy","w":5,"r":"yes"}]}""")!![0])

    @Test fun `repeated tasks are removed`() = assertEquals(1, read("""{"tasks":[{"t":"Buy milk","c":"buy"},{"t":"buy milk","c":"buy"}]}""")!!.size)

    @Test fun `quotes and braces inside text survive`() {
        assertEquals("""Say "hi} there""", read("""{"tasks":[{"t":"Say \"hi} there","c":"tasks"}]}""")!![0].text)
    }

    @Test fun `cut answer keeps finished tasks`() {
        val tasks = read("""{"tasks":[{"t":"One","c":"buy"},{"t":"Two","c":"work"},{"t":"Thr""")!!
        assertEquals(listOf("One", "Two"), tasks.map { it.text })
    }

    @Test fun `unreadable answers give null`() {
        assertNull(read("no json"))
        assertNull(read("""{"other":1}"""))
        assertNull(read("""{"tasks":[{"t":"Buy"""))
        assertNull(read(""))
    }

    @Test fun `guard stops when the object closes`() {
        val g = AnswerGuard()
        assertNull(g.add("""{"tasks":[{"t":"Buy milk","c":"buy"}"""))
        assertEquals(AnswerGuard.Stop.CLOSED, g.add("]}"))
    }

    @Test fun `guard stops a repeating task`() {
        val g = AnswerGuard()
        g.add("""{"tasks":[""")
        assertNull(g.add("""{"t":"Buy milk","c":"buy"},"""))
        assertNull(g.add("""{"t":"Buy milk","c":"buy"},"""))
        assertEquals(AnswerGuard.Stop.LOOPING, g.add("""{"t":"Buy milk","c":"buy"},"""))
    }

    @Test fun `guard stops an endless answer`() {
        val g = AnswerGuard(maxPieces = 5)
        repeat(4) { assertNull(g.add("word ")) }
        assertEquals(AnswerGuard.Stop.TOO_LONG, g.add("word "))
    }

    @Test fun `a cut looping answer still gives its tasks`() {
        val g = AnswerGuard()
        g.add("""{"tasks":[{"t":"Buy milk","c":"buy"},{"t":"Buy milk","c":"buy"},{"t":"Buy milk","c":"buy"},""")
        assertEquals(1, read(g.answer)!!.size)
    }
}
