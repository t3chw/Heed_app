package com.bol.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderChoicesTest {
    private fun day(dayOfWeek: Int, hour: Int, minute: Int = 20): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 2, hour, minute, 30)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, dayOfWeek)
    }.timeInMillis

    private fun labels(now: Long) = ReminderChoices.list(now, 9).map { it.label }
    private fun field(ts: Long, f: Int) = Calendar.getInstance().apply { timeInMillis = ts }.get(f)

    @Test fun `every choice is in the future`() {
        for (dow in Calendar.SUNDAY..Calendar.SATURDAY) for (hour in 0..23) {
            val now = day(dow, hour)
            ReminderChoices.list(now, 9).forEach { assertTrue(it.label, it.at > now) }
        }
    }

    @Test fun `this evening goes once the evening is close`() {
        assertTrue("This evening" in labels(day(Calendar.TUESDAY, 16)))
        assertFalse("This evening" in labels(day(Calendar.TUESDAY, 17)))
    }

    @Test fun `tomorrow morning uses the chosen hour`() {
        val at = ReminderChoices.list(day(Calendar.TUESDAY, 20), 7).first { it.label == "Tomorrow morning" }.at
        assertEquals(7, field(at, Calendar.HOUR_OF_DAY))
        assertEquals(Calendar.WEDNESDAY, field(at, Calendar.DAY_OF_WEEK))
    }

    @Test fun `next monday is a monday and never tomorrow`() {
        for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
            val now = day(dow, 10)
            val choices = ReminderChoices.list(now, 9)
            val monday = choices.first { it.label == "Next Monday" }.at
            assertEquals(Calendar.MONDAY, field(monday, Calendar.DAY_OF_WEEK))
            assertTrue(monday > choices.first { it.label == "Tomorrow morning" }.at)
        }
    }

    @Test fun `in 1 hour drops the seconds`() {
        val now = day(Calendar.TUESDAY, 10)
        val at = ReminderChoices.list(now, 9).first().at
        assertEquals(0L, at % 60_000)
        assertTrue(at - now in 3_540_000L..3_600_000L)
    }
}
