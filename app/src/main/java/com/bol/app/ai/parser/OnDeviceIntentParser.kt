package com.bol.app.ai.parser

import com.bol.app.ai.model.ModelManager
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.domain.model.ParsedItem
import com.bol.app.domain.model.VoiceResult
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel

/**
 * Note → tasks with the fine-tuned Qwen3-0.6B.
 *
 * The model was trained on the raw text `Categories:\n- id (Name): meaning\n...Note: <note>\nTasks:\n` and answers
 * `{"tasks":[{"t":"...","c":"..."}]}`. A spoken time stays inside the task text. It was trained on each note as
 * written and as plain text, because the speech model gives lowercase text with no punctuation.
 */
class OnDeviceIntentParser(
    private val models: ModelManager,
    private val categoryRepository: CategoryRepository,
) : IntentParser {
    override suspend fun parse(rawText: String): VoiceResult {
        val note = rawText.trim().take(MAX_NOTE_CHARS)
        if (note.isBlank()) return VoiceResult(rawText = rawText, items = emptyList())
        val categories = categoryRepository.getAllActiveSnapshot().filter { it.id != RESERVED_REMINDERS }
        val t0 = System.currentTimeMillis()
        val raw = models.generateTasks(prompt(categories, note))
        val fallback = categories.firstOrNull { it.id == FALLBACK_CATEGORY }?.id ?: categories.first().id
        val tasks = TaskAnswer.read(raw, categories.map { it.id }.toSet(), fallback) ?: error("model answer was not readable: ${BolLogger.noteText(raw, 120)}")
        BolLogger.log(LogLevel.INFO, LogCategory.PARSER, "tasks_model_done",
            "Task model wrote ${tasks.size} task(s) in ${System.currentTimeMillis() - t0}ms", extras = mapOf("raw" to BolLogger.noteText(raw, 300)))
        return VoiceResult(rawText = rawText, items = tasks.map { toItem(it, categories) })
    }

    private fun toItem(task: TaskAnswer.Task, categories: List<CategoryEntity>): ParsedItem {
        val category = categories.first { it.id == task.category }
        return ParsedItem(
            text = task.text.take(MAX_TEXT_CHARS),
            categoryId = category.id,
            categoryName = category.name,
            categoryEmoji = category.icon,
        )
    }

    companion object {
        private const val MAX_NOTE_CHARS = 5000
        private const val MAX_TEXT_CHARS = 200
        private const val RESERVED_REMINDERS = "reminders"
        private const val FALLBACK_CATEGORY = "tasks"

        fun prompt(categories: List<CategoryEntity>, note: String): String = buildString {
            append("Categories:\n")
            categories.forEach { append("- ${it.id} (${it.name}): ${it.description}\n") }
            append("Note: $note\nTasks:\n")
        }
    }
}
