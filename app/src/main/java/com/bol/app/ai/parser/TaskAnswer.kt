package com.bol.app.ai.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** One task as the model writes it. */
@Serializable
private data class RawTask(val t: String = "", val c: String = "")

/**
 * The schema of the task model's answer. Rows are checked one by one, so one bad task never costs the others.
 * A cut-off answer keeps every task that finished. Unknown categories fall to [fallbackCategory].
 */
object TaskAnswer {
    data class Task(val text: String, val category: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private const val MAX_TASKS = 30
    private const val MAX_TEXT_CHARS = 200

    /** Null only when nothing readable came back, so the caller can fall back. An empty list is a valid answer. */
    fun read(raw: String, categoryIds: Set<String>, fallbackCategory: String): List<Task>? {
        val rows = rows(raw) ?: return null
        val seen = HashSet<String>()
        return rows.mapNotNull { checked(it, categoryIds, fallbackCategory) }
            .filter { seen.add(it.text.lowercase()) }
            .take(MAX_TASKS)
    }

    private fun checked(row: JsonElement, categoryIds: Set<String>, fallback: String): Task? {
        val task = runCatching { json.decodeFromJsonElement<RawTask>(row) }.getOrNull() ?: return null
        val text = task.t.trim().take(MAX_TEXT_CHARS)
        if (text.isEmpty()) return null
        val category = task.c.trim().takeIf { it in categoryIds } ?: fallback
        return Task(text, category)
    }

    private fun rows(raw: String): JsonArray? {
        val whole = firstObject(raw)
        if (whole != null) return runCatching { json.parseToJsonElement(whole).jsonObject["tasks"]?.jsonArray }.getOrNull()
        return salvage(raw)
    }

    /** Answer cut before it closed: close the array after the last finished task. */
    private fun salvage(raw: String): JsonArray? {
        val start = raw.indexOf('[').takeIf { it >= 0 } ?: return null
        val end = lastTaskEnd(raw, start)
        if (end < 0) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1) + "]").jsonArray }.getOrNull()
    }

    private fun lastTaskEnd(text: String, from: Int): Int {
        var last = -1
        scan(text, from) { i, depth -> if (depth == 1 && text[i] == '}') last = i; false }
        return last
    }

    /** The first complete top-level object, or null if it never closes. */
    fun firstObject(text: String): String? {
        val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
        var end = -1
        scan(text, start) { i, depth -> if (depth == 0 && text[i] == '}') { end = i; true } else false }
        return if (end >= 0) text.substring(start, end + 1) else null
    }

    private inline fun scan(text: String, from: Int, onBracket: (Int, Int) -> Boolean) {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> { depth++; if (onBracket(i, depth)) return }
                '}', ']' -> { depth--; if (onBracket(i, depth)) return }
            }
        }
    }
}

/**
 * Watches the answer as it is written and says when to stop: the JSON has closed, the answer is far longer than
 * any real one, or the model is repeating a task. The cap is in stream pieces, about one token each.
 */
class AnswerGuard(private val maxPieces: Int = MAX_PIECES) {
    enum class Stop { CLOSED, TOO_LONG, LOOPING }

    private val text = StringBuilder()
    private var pieces = 0

    val answer: String get() = text.toString()

    fun add(piece: String): Stop? {
        text.append(piece)
        pieces++
        if ('}' !in piece) return if (pieces >= maxPieces) Stop.TOO_LONG else null
        val soFar = text.toString()
        return when {
            TaskAnswer.firstObject(soFar) != null -> Stop.CLOSED
            pieces >= maxPieces -> Stop.TOO_LONG
            repeats(soFar) -> Stop.LOOPING
            else -> null
        }
    }

    private fun repeats(soFar: String): Boolean =
        TASK.findAll(soFar).groupingBy { it.groupValues[1].lowercase() }.eachCount().values.any { it >= MAX_REPEATS }

    companion object {
        /** The longest training answer is 290 tokens. */
        const val MAX_PIECES = 400
        private const val MAX_REPEATS = 3
        private val TASK = Regex("\"t\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
