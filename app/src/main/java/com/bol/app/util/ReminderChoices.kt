package com.bol.app.util

import java.util.Calendar

/** The one-tap times offered when the user sets a reminder by hand. */
object ReminderChoices {
    data class Choice(val label: String, val at: Long)

    private const val HOUR_MS = 3_600_000L
    private const val EVENING_HOUR = 18

    fun list(now: Long, morningHour: Int): List<Choice> = buildList {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        add(Choice("In 1 hour", now + HOUR_MS - now % 60_000))
        if (cal.get(Calendar.HOUR_OF_DAY) < EVENING_HOUR - 1) add(Choice("This evening", at(now, 0, EVENING_HOUR)))
        add(Choice("Tomorrow morning", at(now, 1, morningHour)))
        add(Choice("Next Monday", at(now, daysToMonday(cal), morningHour)))
    }

    /** At least 2 days ahead, so it never repeats "Tomorrow morning". */
    private fun daysToMonday(today: Calendar): Int {
        val days = (Calendar.MONDAY - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
        return if (days < 2) days + 7 else days
    }

    private fun at(now: Long, daysAhead: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
