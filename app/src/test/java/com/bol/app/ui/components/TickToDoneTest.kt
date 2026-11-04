package com.bol.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TickToDoneTest {

    private fun held(vararg rows: Pair<String, Int>) = rows.associate { (id, index) -> id to TickToDone.Held(id, index) }

    @Test
    fun heldRowKeepsItsPlace_whenTheQueryDropsIt() {
        // Home: "a" was ticked at the top; the done-free query now returns b, c, d, e.
        val shown = placeHeld(listOf("b", "c", "d", "e"), held("a" to 0), { it }, limit = 4)
        assertEquals(listOf("a", "b", "c", "d"), shown)
    }

    @Test
    fun twinTimestamps_doNotSwap() {
        // Two tasks from one note share a timestamp; ticking the first must not move it below its twin
        // (a second tap then hit the twin on the phone, 4 Nov).
        val shown = placeHeld(listOf("twin", "c"), held("first" to 0), { it })
        assertEquals(listOf("first", "twin", "c"), shown)
    }

    @Test
    fun heldRowStillInTheQuery_isNotShownTwice() {
        // A bucket's list keeps done tasks; the held one is drawn once, at its place.
        val shown = placeHeld(listOf("a", "b", "c"), held("b" to 1), { it }, display = { it.uppercase() })
        assertEquals(listOf("a", "B", "c"), shown)
    }

    @Test
    fun severalHeldRows_goBackInOrder() {
        val shown = placeHeld(listOf("b", "d"), held("c" to 2, "a" to 0), { it })
        assertEquals(listOf("a", "b", "c", "d"), shown)
    }

    @Test
    fun placePastTheEnd_goesLast() {
        val shown = placeHeld(listOf("a"), held("z" to 5), { it })
        assertEquals(listOf("a", "z"), shown)
    }
}
