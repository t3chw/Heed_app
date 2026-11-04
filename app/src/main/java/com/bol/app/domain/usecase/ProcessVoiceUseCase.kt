package com.bol.app.domain.usecase

import android.content.Context
import com.bol.app.ai.parser.IntentParser
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.RecordingTagEntity
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.domain.model.ParsedItem
import com.bol.app.domain.model.VoiceResult
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.service.ReminderScheduler

class ProcessVoiceUseCase(
    private val intentParser: IntentParser,
    private val itemRepository: ItemRepository,
    private val recordingRepository: RecordingRepository,
    private val categoryRepository: CategoryRepository,
    private val context: Context
) {
    suspend fun parse(rawText: String): VoiceResult {
        return intentParser.parse(rawText)
    }

    /**
     * Save parsed items to the database. Returns the saved [ItemEntity]
     * list so the caller can hand them off to [runPostSaveEnhancements].
     *
     * This path is the CRITICAL one — if it completes, the voice note is
     * permanently recorded and the voice_queue row should be marked
     * COMPLETED *before* any post-save the model work runs, so a crash in
     * tag/enrichment can never duplicate the note on the next process
     * restart.
     *
     * @param existingRecordingId If provided, uses this pre-created RecordingEntity (updates its item count).
     *                           If null, creates a new RecordingEntity.
     * @param isProvisional If true, items are marked as rule-based (temporary) and eligible for LLM upgrade.
     */
    suspend fun saveResults(voiceResult: VoiceResult, existingRecordingId: String? = null, isProvisional: Boolean = false): List<ItemEntity> {
        val recordingId = if (existingRecordingId != null) {
            // Update the pre-created recording with item count
            recordingRepository.updateItemCount(existingRecordingId, voiceResult.items.size)
            existingRecordingId
        } else {
            // Create new recording (legacy path / direct usage)
            val id = java.util.UUID.randomUUID().toString()
            recordingRepository.insert(
                RecordingEntity(
                    id = id,
                    rawText = voiceResult.rawText,
                    parsedItemCount = voiceResult.items.size
                )
            )
            id
        }

        // Constrain every item to an existing category. the model never creates
        // categories — it picks from the list we fed it. If for any reason a
        // category ID slips through that isn't in the DB (shouldn't happen
        // given the parser-side validation, but belt-and-braces), we fall
        // back to "tasks". Manual category creation via the UI is a separate
        // user-driven flow.
        val resolvedItems = voiceResult.items.map { parsed ->
            val categoryId = resolveCategoryId(parsed)
            parsed.copy(categoryId = categoryId)
        }

        val items = resolvedItems.map { parsed ->
            ItemEntity(
                categoryId = parsed.categoryId,
                text = parsed.text,
                isProvisional = isProvisional,
                reminderTime = parsed.reminderTime,
                sourceRecordingId = recordingId
            )
        }
        itemRepository.insertAll(items)
        // Search vectors come from SearchIndexer (it follows Room), not from here.

        val categoryBreakdown = items.groupBy { it.categoryId }
            .map { (cat, list) -> "$cat: ${list.size}" }
            .joinToString(", ")
        val reminders = items.count { it.reminderTime != null }

        BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "items_saved",
            "Saved ${items.size} item(s) to database for recording ${recordingId.take(8)}" +
                (if (isProvisional) " (PROVISIONAL — rule-based, pending LLM upgrade)" else " (final — LLM categorized)") +
                " | Categories: $categoryBreakdown" +
                (if (reminders > 0) " | $reminders reminder(s) scheduled" else ""),
            extras = mapOf("itemCount" to items.size.toString(),
                "isProvisional" to isProvisional.toString(),
                "recordingId" to recordingId.take(8),
                "reminders" to reminders.toString()))

        items.filter { it.reminderTime != null }.forEach { item ->
            // Scheduler now carries only the item id; the receiver re-reads text + category
            // from the DB at fire time so edits made after scheduling are reflected.
            ReminderScheduler.schedule(
                context = context,
                itemId = item.id,
                triggerAtMillis = item.reminderTime!!,
            )
        }

        if (voiceResult.tags.isNotEmpty()) {
            val tagEntities = voiceResult.tags
                .filter { it.isNotBlank() }
                .take(10)
                .map { tag ->
                    RecordingTagEntity(
                        recordingId = recordingId,
                        tag = tag.trim(),
                        tagType = "person"
                    )
                }
            recordingRepository.insertTags(tagEntities)
            BolLogger.log(LogLevel.DEBUG, LogCategory.QUEUE, "tags_saved",
                "Saved ${tagEntities.size} tag(s) for recording ${recordingId.take(8)}: ${BolLogger.noteText(tagEntities.joinToString(", ") { it.tag }, 200)}")
        }
        return items
    }


    /**
     * Resolve an incoming ParsedItem's categoryId to one that exists in the
     * DB. The LLM is NEVER allowed to invent a category — it picks from the
     * fixed list we show it in the prompt. If an ID somehow slips through
     * that isn't in the DB, fall back to "tasks" instead of auto-creating a
     * new category.
     *
     * Category creation is strictly a user-driven UI flow
     * (CategoryRepository.createCategory) — never an LLM-driven one. This
     * keeps the home grid predictable and prevents prompt regressions from
     * polluting the category list.
     */
    private suspend fun resolveCategoryId(item: ParsedItem): String {
        val existing = categoryRepository.getById(item.categoryId)
        if (existing != null) return item.categoryId
        // Also try name match — the LLM sometimes emits the display name
        // ("Buy") instead of the id ("buy"). Still only accept if it
        // already exists; never create a new category from it.
        val name = item.categoryName
        if (!name.isNullOrBlank()) {
            val byName = categoryRepository.findByName(name)
            if (byName != null) return byName.id
        }
        BolLogger.log(LogLevel.WARN, LogCategory.PARSER, "category_unknown_fallback",
            "LLM emitted unknown category '${item.categoryId}'" +
                (if (name != null) " (name: '$name')" else "") +
                " — routing item to 'tasks'")
        return "tasks"
    }
}
