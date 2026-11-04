package com.bol.app.ui.screens.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.dao.ItemEnrichmentDao
import com.bol.app.data.local.dao.ItemTagDao
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for a single task's detail page. Owns:
 *  - live item flow (reacts to toggle/edit-reminder/move/etc.)
 *  - resolved category (looked up reactively whenever the item's
 *    categoryId changes via Move-to)
 *  - all categories (for the Move picker)
 *  - lazy tags + enrichment paraphrases (the model metadata)
 *
 * Mirrors CategoryDetailViewModel's action methods so the same
 * underlying repo calls run regardless of which surface the user
 * acted from.
 */
@Suppress("OPT_IN_USAGE")
class TaskDetailViewModel(
    private val itemId: String,
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val recordingRepository: RecordingRepository,
    private val tagDao: ItemTagDao,
    private val enrichmentDao: ItemEnrichmentDao,
    preferencesManager: PreferencesManager,
) : ViewModel() {

    val item: StateFlow<ItemEntity?> =
        itemRepository.getByIdFlow(itemId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Resolved category for this item. flatMapLatest re-fires the lookup
     *  whenever the user moves the task to a different category. */
    val category: StateFlow<CategoryEntity?> =
        item.flatMapLatest { it ->
            kotlinx.coroutines.flow.flow {
                emit(it?.categoryId?.let { categoryRepository.getById(it) })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** All active categories — feeds the Move-to picker. */
    val allCategories: Flow<List<CategoryEntity>> =
        categoryRepository.getAllActive()

    val timeFormat: Flow<String> = preferencesManager.timeFormat

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags

    private val _paraphrases = MutableStateFlow<List<String>>(emptyList())
    val paraphrases: StateFlow<List<String>> = _paraphrases

    init {
        // Lazy-load the model metadata once. Tags and the enrichment blob
        // don't change after the first indexer pass for a given item, so a
        // one-shot fetch is enough — the screen doesn't need to react.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val t = runCatching { tagDao.tagsForItem(itemId) }.getOrDefault(emptyList())
                val blob = runCatching { enrichmentDao.getForItem(itemId)?.searchContext }
                    .getOrNull().orEmpty()
                // Same parsing as CategoryDetail's ItemDetailExtras —
                // dot-bullet separated, drop comma-heavy parts, take 3.
                val phrasings = blob.split(" · ")
                    .map { s -> s.trim() }
                    .filter { s -> s.isNotEmpty() && s.count { ch -> ch == ',' } <= 1 }
                    .take(3)
                _tags.value = t
                _paraphrases.value = phrasings
            }
        }
    }

    /** Source recording's user-friendly title, or null if not voice-originated
     *  or the recording row was deleted. */
    val sourceRecordingTitle: StateFlow<String?> =
        item.map { entity ->
            val rid = entity?.sourceRecordingId ?: return@map null
            recordingRepository.getById(rid)?.let { rec ->
                rec.rawText.takeIf { it.isNotBlank() }
                    ?.let { it.take(60) + if (it.length > 60) "…" else "" }
                    ?: "Voice note"
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleCompleted() {
        viewModelScope.launch { itemRepository.toggleCompleted(itemId) }
    }

    fun setReminder(newTime: Long) {
        viewModelScope.launch { itemRepository.setReminderTime(itemId, newTime) }
    }

    fun clearReminder() {
        viewModelScope.launch { itemRepository.clearReminder(itemId) }
    }

    /** Snooze 1h: mirrors what ReminderActionReceiver.ACTION_SNOOZE_1H does. */
    fun snoozeOneHour() {
        viewModelScope.launch {
            val newTime = System.currentTimeMillis() + 60L * 60L * 1000L
            itemRepository.setReminderTime(itemId, newTime)
        }
    }

    fun moveToCategory(newCategoryId: String) {
        viewModelScope.launch { itemRepository.moveItem(itemId, newCategoryId) }
    }

    fun delete() {
        viewModelScope.launch { itemRepository.deleteById(itemId) }
    }

    /** Replace the task's text. No-op on blank or unchanged input. */
    fun editText(newText: String) {
        viewModelScope.launch { itemRepository.editItemText(itemId, newText) }
    }
}
