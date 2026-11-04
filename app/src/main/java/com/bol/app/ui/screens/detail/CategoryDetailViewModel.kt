package com.bol.app.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CategoryDetailViewModel(
    private val categoryId: String,
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    /** Full unfiltered list — used by the header to compute "X open · Y done"
     *  counts so the user always sees the true totals regardless of filter. */
    val allItems: Flow<List<ItemEntity>> = itemRepository.getByCategory(categoryId)

    /** Whether the user has hidden completed items globally. */
    val hideCompleted: Flow<Boolean> = preferencesManager.hideCompletedItems

    /** Visible list — what the LazyColumn renders. Drops completed rows
     *  when [hideCompleted] is on. Open items keep their existing sort. */
    val items: Flow<List<ItemEntity>> = combine(allItems, hideCompleted) { items, hide ->
        if (hide) items.filter { !it.isCompleted } else items
    }

    val timeFormat: Flow<String> = preferencesManager.timeFormat

    /** Every active category — used by the Move-to picker on an item. */
    val allCategories: Flow<List<CategoryEntity>> = categoryRepository.getAllActive()

    private val _category = MutableStateFlow<CategoryEntity?>(null)
    val category: StateFlow<CategoryEntity?> = _category

    init {
        viewModelScope.launch {
            _category.value = categoryRepository.getById(categoryId)
        }
    }

    fun toggleItem(itemId: String) {
        viewModelScope.launch { itemRepository.toggleCompleted(itemId) }
    }

    /** An open task's box was ticked or unticked; saved at once ([com.bol.app.ui.components.TickToDone] keeps the row). */
    fun setDone(itemId: String, done: Boolean) {
        viewModelScope.launch { itemRepository.setCompleted(itemId, done) }
    }

    /** Flip the global "hide completed" toggle. Persists via DataStore so
     *  every CategoryDetail (and future surfaces that respect the pref)
     *  reflects the same choice. */
    fun toggleHideCompleted() {
        viewModelScope.launch {
            val current = preferencesManager.hideCompletedItems.first()
            preferencesManager.setHideCompletedItems(!current)
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { itemRepository.deleteById(itemId) }
    }

    fun addItem(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            itemRepository.insert(ItemEntity(categoryId = categoryId, text = text.trim()))
        }
    }

    /** Set or replace the reminder on an item. Cancels any existing alarm
     *  and schedules a new one with the user's picked time. */
    fun setReminder(itemId: String, newTime: Long) {
        viewModelScope.launch { itemRepository.setReminderTime(itemId, newTime) }
    }

    /** The same reminder on every selected task. */
    fun setReminders(itemIds: Collection<String>, newTime: Long) {
        viewModelScope.launch { itemIds.forEach { itemRepository.setReminderTime(it, newTime) } }
    }

    /** Clear the reminder from an item. Alarm cancelled, item stays in its
     *  category without a time chip. */
    fun clearReminder(itemId: String) {
        viewModelScope.launch { itemRepository.clearReminder(itemId) }
    }

    /** Move an item to a different category. All other metadata preserved. */
    fun moveItem(itemId: String, newCategoryId: String) {
        viewModelScope.launch { itemRepository.moveItem(itemId, newCategoryId) }
    }

    /** Replace an item's text. No-op on blank or unchanged input. */
    fun editItemText(itemId: String, newText: String) {
        viewModelScope.launch { itemRepository.editItemText(itemId, newText) }
    }
}
