package com.bol.app.ui.screens.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.model.CategoryWithCount
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Item + its resolved category — same shape Home uses for "Recent" rows. */
data class AllRecentRow(
    val item: ItemEntity,
    val category: CategoryWithCount?,
)

/** Quick-filter slices on the reminder dimension. ALL is the default
 *  "show everything"; the others let the user triage what's actionable. */
enum class ReminderFilter(val label: String) {
    ALL("All"),
    WITH_REMINDER("With reminder"),
    OVERDUE("Overdue"),
    NONE("No reminder"),
}

class AllRecentViewModel(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryWithCount>> =
        categoryRepository.getCategoriesWithCounts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** null = "All", non-null = filter to that category's id. */
    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId

    private val _reminderFilter = MutableStateFlow(ReminderFilter.ALL)
    val reminderFilter: StateFlow<ReminderFilter> = _reminderFilter

    val items: StateFlow<List<AllRecentRow>> = combine(
        itemRepository.getAllActiveFlow(),
        categories,
        _selectedCategoryId,
        _reminderFilter,
    ) { items, cats, catId, reminder ->
        val byId = cats.associateBy { it.id }
        val now = System.currentTimeMillis()
        items.asSequence()
            .filter { catId == null || it.categoryId == catId }
            .filter {
                when (reminder) {
                    ReminderFilter.ALL -> true
                    ReminderFilter.WITH_REMINDER -> it.reminderTime != null
                    ReminderFilter.OVERDUE -> it.reminderTime != null && it.reminderTime!! < now
                    ReminderFilter.NONE -> it.reminderTime == null
                }
            }
            .map { AllRecentRow(it, byId[it.categoryId]) }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectCategory(id: String?) {
        _selectedCategoryId.value = id
    }

    fun selectReminderFilter(filter: ReminderFilter) {
        _reminderFilter.value = filter
    }

    /** A task's box was ticked or unticked; saved at once ([com.bol.app.ui.components.TickToDone] keeps the row). */
    fun setDone(itemId: String, done: Boolean) {
        viewModelScope.launch { itemRepository.setCompleted(itemId, done) }
    }
}
