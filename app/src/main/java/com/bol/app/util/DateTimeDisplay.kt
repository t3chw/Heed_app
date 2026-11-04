package com.bol.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Centralised time/date formatting for the UI.
 *
 * All entry points take a millisecond timestamp and return a human-readable string
 * in the user's preferred time format (12-hour or 24-hour). Display rules:
 *
 * - [relative]  — "just now" < 1m, "5m ago" < 1h, "2h ago" < 1d, "yesterday" < 2d,
 *                 weekday name < 7d, "Apr 3" < 1y, "Apr 3, 2024" otherwise.
 * - [time]      — just the clock: "2:15 pm" (12h) or "14:15" (24h).
 * - [full]      — full absolute: "Saturday, April 19, 2026 · 2:15 pm".
 * - [smart]     — relative for < 7d, otherwise a short absolute ("Apr 3 · 2:15 pm").
 *
 * Relative output is intentionally lowercase and terse so it blends into card
 * metadata lines without shouting.
 */
object DateTimeDisplay {

    /** Clock time only — e.g. "2:15 pm" (12h) or "14:15" (24h). */
    fun time(ts: Long, format24h: Boolean = false): String {
        val pattern = if (format24h) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(ts))
            .lowercase(Locale.getDefault())
    }

    /**
     * Human-relative label. Prefers the least precise form that still communicates
     * when the item was added. Falls back to a date string for anything > 7 days old.
     */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - ts
        if (diff < 0) return "just now"
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 2 && isYesterday(ts, now) -> "yesterday"
            days < 7 -> shortWeekday(ts)
            else -> shortDate(ts, now)
        }
    }

    /**
     * Hybrid — relative for fresh items, short absolute for older. Used where the
     * list naturally mixes recent and aged rows and both need legibility.
     */
    fun smart(ts: Long, now: Long = System.currentTimeMillis(), format24h: Boolean = false): String {
        val diff = now - ts
        val days = diff / 86_400_000
        return when {
            diff < 3_600_000 -> relative(ts, now)              // < 1h → "5m ago"
            days < 1 -> relative(ts, now)                       // < 24h → "2h ago"
            days < 2 && isYesterday(ts, now) -> "yesterday · ${time(ts, format24h)}"
            days < 7 -> "${shortWeekday(ts)} · ${time(ts, format24h)}"
            else -> "${shortDate(ts, now)} · ${time(ts, format24h)}"
        }
    }

    /** Full, explicit timestamp for detail/expanded views. */
    fun full(ts: Long, format24h: Boolean = false): String {
        val pattern = if (format24h) "EEEE, MMM d, yyyy · HH:mm" else "EEEE, MMM d, yyyy · h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(ts))
            .let { if (!format24h) it.replace(" AM", " am").replace(" PM", " pm") else it }
    }

    /** Date and time without the weekday ("Nov 4, 2026 · 8:32 am"), for property lines that must fit one line. */
    fun dateTime(ts: Long, format24h: Boolean = false): String {
        val pattern = if (format24h) "MMM d, yyyy · HH:mm" else "MMM d, yyyy · h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(ts))
            .let { if (!format24h) it.replace(" AM", " am").replace(" PM", " pm") else it }
    }

    /**
     * Compact "Current time" phrase for LLM prompts. Includes weekday + date + time
     * so the model can resolve "today", "tomorrow", "yesterday", "last week".
     */
    fun forLlmPrompt(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US)
            .format(Date(ts))

    /**
     * Compact future-relative label for an item's reminder time, designed to
     * ride on the same caption line as [relative]. Returns lowercase + terse
     * so callers can `.uppercase()` it alongside the existing time-ago label.
     *
     *   past (not completed) → "overdue"
     *   < 1m                 → "now"
     *   < 1h                 → "in 30m"
     *   < 24h                → "in 5h"
     *   tomorrow             → "tomorrow 9 am"
     *   < 7 days             → "fri 9 am"
     *   else                 → "apr 30 9 am"
     */
    fun reminderRelative(
        reminderTs: Long,
        now: Long = System.currentTimeMillis(),
        format24h: Boolean = false,
    ): String {
        val diff = reminderTs - now
        if (diff <= 0) return "overdue"
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val daysAhead = calendarDaysAhead(reminderTs, now)

        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "in ${minutes}m"
            hours < 24 -> "in ${hours}h"
            daysAhead == 1 -> "tomorrow ${time(reminderTs, format24h)}"
            daysAhead in 2..6 -> "${shortWeekday(reminderTs)} ${time(reminderTs, format24h)}"
            else -> "${shortDate(reminderTs, now)} ${time(reminderTs, format24h)}"
        }
    }

    // ── helpers ──

    private fun isYesterday(ts: Long, now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val startOfYesterday = startOfToday - 86_400_000
        return ts in startOfYesterday until startOfToday
    }

    /** Calendar-day delta from [now] to [ts]. 0 = today, 1 = tomorrow, -1 = yesterday. */
    private fun calendarDaysAhead(ts: Long, now: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        cal.timeInMillis = ts
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startOfTsDay = cal.timeInMillis
        return ((startOfTsDay - startOfToday) / 86_400_000).toInt()
    }

    private fun shortWeekday(ts: Long): String =
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))

    /** Same year → "Apr 3"; earlier year → "Apr 3, 2024". */
    private fun shortDate(ts: Long, now: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val thisYear = cal.get(Calendar.YEAR)
        cal.timeInMillis = ts
        val tsYear = cal.get(Calendar.YEAR)
        val pattern = if (tsYear == thisYear) "MMM d" else "MMM d, yyyy"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    }
}
