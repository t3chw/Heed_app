package com.bol.app.service.digest

import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.model.CategoryOpenCount

/**
 * Renderable form of the digest: a one-line body for the collapsed notification
 * and a longer listing for the expanded view. Pure function of the day's data.
 */
data class DigestSummary(
    val shortLine: String,
    val expandedBody: String,
    val isEmpty: Boolean,
) {
    companion object {
        fun build(
            remindersToday: List<ItemEntity>,
            counts: List<CategoryOpenCount>,
            categoryNames: Map<String, String>,
        ): DigestSummary {
            // Short line: "3 reminders today · 12 open items"
            val parts = mutableListOf<String>()
            if (remindersToday.isNotEmpty()) {
                parts += "${remindersToday.size} reminder${if (remindersToday.size == 1) "" else "s"} today"
            }
            val totalOpen = counts.sumOf { it.count }
            if (totalOpen > 0) {
                parts += "$totalOpen open item${if (totalOpen == 1) "" else "s"}"
            }
            val shortLine = parts.joinToString(" · ")

            // Expanded body: reminders first (with time), then per-category counts.
            val sb = StringBuilder()
            if (remindersToday.isNotEmpty()) {
                sb.appendLine("Today's reminders:")
                remindersToday.take(5).forEach { item ->
                    sb.appendLine("• ${item.text}")
                }
                if (remindersToday.size > 5) {
                    sb.appendLine("…and ${remindersToday.size - 5} more")
                }
                if (counts.isNotEmpty()) sb.appendLine()
            }
            if (counts.isNotEmpty()) {
                val breakdown = counts
                    .sortedByDescending { it.count }
                    .take(6)
                    .joinToString(" · ") { c ->
                        val name = categoryNames[c.categoryId] ?: c.categoryId
                        "${c.count} ${name.lowercase()}"
                    }
                sb.append("Open: $breakdown")
            }
            return DigestSummary(
                shortLine = shortLine.ifEmpty { "Nothing new" },
                expandedBody = sb.toString().trim(),
                isEmpty = remindersToday.isEmpty() && totalOpen == 0,
            )
        }
    }
}
