package com.bol.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.model.ModelState
import com.bol.app.ai.processing.ProcessingStage
import com.bol.app.ai.processing.ProcessingStageTracker
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.model.CategoryWithCount
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A recent item bundled with its resolved category, for the Home "Today"
 * strip. [category] is nullable so the UI can gracefully skip rows whose
 * category has been deleted (shouldn't happen in practice, but harmless).
 */
data class RecentItemRow(
    val item: ItemEntity,
    val category: CategoryWithCount?,
)

class HomeViewModel(
    private val categoryRepository: CategoryRepository,
    private val itemRepository: ItemRepository,
    private val voiceQueueRepository: VoiceQueueRepository,
    modelManager: ModelManager,
    processingStageTracker: ProcessingStageTracker,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    // categories + recentItems are StateFlows (not plain Flows) so that when
    // the user navigates back to Home the cached values are immediately
    // visible on the very first composition frame. If these were cold Flows,
    // collectAsState(initial = emptyList()) would render one empty frame
    // while Room re-emitted — and during that frame the conditional "Today"
    // section disappears from the LazyColumn, shrinking the item count.
    // rememberLazyListState's saved firstVisibleItemIndex then clamps to the
    // new (smaller) max, and by the time data arrives, the scroll position
    // has been silently reset to near the top. Eagerly sharing keeps the
    // ViewModel's Flows hot across navigation so the LazyColumn's item
    // structure is stable and scroll state restores cleanly.
    val categories: StateFlow<List<CategoryWithCount>> =
        categoryRepository.getCategoriesWithCounts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingQueueCount: Flow<Int> = voiceQueueRepository.getActiveCount()
    val modelState: StateFlow<ModelState> = modelManager.state

    /**
     * Live pipeline phase from [BolProcessingService]. Drives the Home pill's
     * stage-aware copy so the user sees forward motion during the 30-60 s
     * voice-to-categorized window — TRANSCRIBING → CATEGORIZING → IDLE.
     */
    val processingStage: StateFlow<ProcessingStage> = processingStageTracker.stage

    /** One-off pipeline messages (a note that gave no tasks), shown as a toast. */
    val processingNotices = processingStageTracker.notices

    /**
     * How many times the user has already seen the "safe to close the app"
     * caption under the pill. Capped by
     * [PreferencesManager.PROCESSING_HINT_MAX_SHOWS] — past that, we trust
     * they've internalised the background-work model.
     */
    val processingHintShownCount: Flow<Int> = preferencesManager.processingHintShownCount

    /** Bumps the count once per processing session — the screen calls this
     *  when the pill first transitions from IDLE → working. */
    fun markProcessingHintShown() {
        viewModelScope.launch { preferencesManager.incrementProcessingHintShown() }
    }

    /**
     * True when the user has at least one active (uncompleted, set-time) reminder.
     * Used by the Home "reminders blocked" banner — that banner only fires when
     * notifications are off AND there's actually something at risk, so users who
     * don't use reminders never see a warning that doesn't apply to them.
     */
    val hasActiveReminders: StateFlow<Boolean> =
        itemRepository.getAllWithReminderFlow()
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Live stream of the 4 most recent active items, joined with their
     * category so the Home "Today" rows can paint the category chip without
     * a second DB round-trip per item. Also a StateFlow so re-entering Home
     * doesn't flicker through an empty Today section and reset scroll.
     */
    val recentItems: StateFlow<List<RecentItemRow>> =
        itemRepository.getRecentActiveFlow(limit = 4)
            .combine(categories) { items, cats ->
                val byId = cats.associateBy { it.id }
                items.map { RecentItemRow(it, byId[it.categoryId]) }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _greeting = MutableStateFlow(computeGreeting())
    val greeting: StateFlow<String> = _greeting

    private val _currentDate = MutableStateFlow(computeDate())
    val currentDate: StateFlow<String> = _currentDate

    private val _categoryError = MutableStateFlow<String?>(null)
    val categoryError: StateFlow<String?> = _categoryError

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                _greeting.value = computeGreeting()
                _currentDate.value = computeDate()
            }
        }
    }

    /** A task's box was ticked or unticked; saved at once ([com.bol.app.ui.components.TickToDone] keeps the row). */
    fun setDone(itemId: String, done: Boolean) {
        viewModelScope.launch { itemRepository.setCompleted(itemId, done) }
    }

    fun createCategory(name: String, icon: String, description: String) {
        viewModelScope.launch {
            val error = categoryRepository.createCategory(name, icon, description)
            _categoryError.value = error
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            // Default categories can't be deleted; only cancel alarms for one that will be.
            if (categoryRepository.getById(categoryId)?.isDefault == false) itemRepository.cancelRemindersInCategory(categoryId)
            val error = categoryRepository.deleteCategory(categoryId)
            _categoryError.value = error
        }
    }

    fun clearCategoryError() {
        _categoryError.value = null
    }

    /** User tapped the ✕ on the processing pill. Cancels every in-flight
     *  recording — queue rows flip to CANCELLED (pill disappears), in-flight
     *  coroutines detect the cancel at their next stage boundary and abort,
     *  and the associated recordings stay in History (cancelled=true) so
     *  the user can retry later. WAV files are preserved for retry. */
    fun cancelActiveRecordings() {
        viewModelScope.launch { voiceQueueRepository.cancelAllActive() }
    }

    private fun computeGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning!"
            hour < 17 -> "Good afternoon!"
            else -> "Good evening!"
        }
    }

    private fun computeDate(): String =
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}
