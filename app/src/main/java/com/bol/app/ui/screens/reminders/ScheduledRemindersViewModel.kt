package com.bol.app.ui.screens.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Drives the Settings → Scheduled reminders screen.
 *
 * The screen is the single place users find items that have a firing time
 * attached. Reminders themselves are not a category any more — they're just
 * items (in buy / tasks / work / ideas / journal) that carry a `reminderTime`.
 * This view bucketizes them by proximity so the list is scannable at a glance.
 */
class ScheduledRemindersViewModel(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    preferencesManager: PreferencesManager,
) : ViewModel() {

    /** User's 12h/24h clock preference — drives row timestamp format. */
    val timeFormat: Flow<String> = preferencesManager.timeFormat

    enum class Bucket { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER }

    data class ReminderRow(
        val item: ItemEntity,
        val category: CategoryEntity?,
        val bucket: Bucket,
    )

    data class UiState(
        val groups: Map<Bucket, List<ReminderRow>>,
        val totalCount: Int,
    ) {
        companion object {
            val EMPTY = UiState(emptyMap(), 0)
        }
    }

    /**
     * Stream of grouped reminders. Recomputes whenever the underlying item
     * list changes OR the category list changes (so a renamed category
     * immediately reflects in its chip). `now` is captured once per emission
     * — the bucket math doesn't auto-refresh as time passes; re-opening the
     * screen re-queries and re-buckets.
     */
    val state: StateFlow<UiState> = combine(
        itemRepository.getAllWithReminderFlow(),
        categoryRepository.getAllActive(),
    ) { items, categories ->
        buildState(items, categories)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState.EMPTY)

    private fun buildState(
        items: List<ItemEntity>,
        categories: List<CategoryEntity>,
    ): UiState {
        val now = System.currentTimeMillis()
        val catMap = categories.associateBy { it.id }
        val startOfTomorrow = startOfTomorrow(now)
        val startOfDayAfterTomorrow = startOfTomorrow + DAY
        val startOfWeekEnd = startOfTomorrow + 7 * DAY

        val rows = items.mapNotNull { item ->
            val t = item.reminderTime ?: return@mapNotNull null
            val bucket = when {
                t < now -> Bucket.OVERDUE
                t < startOfTomorrow -> Bucket.TODAY
                t < startOfDayAfterTomorrow -> Bucket.TOMORROW
                t < startOfWeekEnd -> Bucket.THIS_WEEK
                else -> Bucket.LATER
            }
            ReminderRow(item = item, category = catMap[item.categoryId], bucket = bucket)
        }

        // Keep bucket display order stable: Overdue → Today → Tomorrow → This week → Later.
        val groups = Bucket.values().associateWith { b -> rows.filter { it.bucket == b } }
            .filterValues { it.isNotEmpty() }

        return UiState(groups = groups, totalCount = rows.size)
    }

    fun cancelReminder(itemId: String) {
        viewModelScope.launch { itemRepository.clearReminder(itemId) }
    }

    fun completeItem(itemId: String) {
        viewModelScope.launch { itemRepository.toggleCompleted(itemId) }
    }

    /** Edit the reminder time on an existing item. Cancels the old alarm
     *  and schedules the new one. */
    fun setReminder(itemId: String, newTime: Long) {
        viewModelScope.launch { itemRepository.setReminderTime(itemId, newTime) }
    }

    companion object {
        private const val DAY = 86_400_000L

        private fun startOfTomorrow(now: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}
