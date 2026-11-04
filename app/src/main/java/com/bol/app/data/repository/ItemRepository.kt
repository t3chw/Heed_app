package com.bol.app.data.repository

import android.content.Context
import com.bol.app.data.local.dao.ItemDao
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.service.ReminderScheduler
import kotlinx.coroutines.flow.Flow

class ItemRepository(
    private val itemDao: ItemDao,
    private val applicationContext: Context,
) {
    fun getByCategory(categoryId: String): Flow<List<ItemEntity>> =
        itemDao.getByCategory(categoryId)

    suspend fun insert(item: ItemEntity) = itemDao.insert(item)

    suspend fun insertAll(items: List<ItemEntity>) = itemDao.insertAll(items)

    suspend fun update(item: ItemEntity) = itemDao.update(item)

    suspend fun deleteById(id: String) {
        // A pending reminder on a deleted item would fire into the void and try to
        // fetch a gone row; cancel first so the alarm never runs.
        ReminderScheduler.cancel(applicationContext, id)
        itemDao.deleteById(id)
    }

    suspend fun toggleCompleted(id: String) {
        val item = itemDao.getById(id) ?: return
        val nowCompleted = !item.isCompleted
        itemDao.update(
            item.copy(
                isCompleted = nowCompleted,
                updatedAt = System.currentTimeMillis(),
            )
        )
        // Completing an item cancels its reminder. Un-completing sets it again if
        // its time is still ahead: the task keeps showing that time, so it must ring.
        val reminderTime = item.reminderTime ?: return
        if (nowCompleted) {
            ReminderScheduler.cancel(applicationContext, id)
        } else if (reminderTime > System.currentTimeMillis()) {
            ReminderScheduler.schedule(applicationContext, id, reminderTime)
        }
    }

    /** Done or not done, whatever it is now; nothing happens (no reminder work either) if it already is. */
    suspend fun setCompleted(id: String, completed: Boolean) {
        val item = itemDao.getById(id) ?: return
        if (item.isCompleted != completed) toggleCompleted(id)
    }

    /** Put back a task that was just deleted (Undo), with its reminder if that is still ahead. */
    suspend fun restore(item: ItemEntity) {
        itemDao.insert(item)
        val reminderTime = item.reminderTime ?: return
        if (!item.isCompleted && reminderTime > System.currentTimeMillis()) {
            ReminderScheduler.schedule(applicationContext, item.id, reminderTime)
        }
    }

    suspend fun getUpcomingReminders(currentTime: Long): List<ItemEntity> =
        itemDao.getUpcomingReminders(currentTime)

    fun getByRecordingId(recordingId: String): Flow<List<ItemEntity>> =
        itemDao.getByRecordingId(recordingId)

    suspend fun getAllSnapshot(): List<ItemEntity> = itemDao.getAllSnapshot()

    suspend fun getActiveByCategorySnapshot(categoryId: String): List<ItemEntity> =
        itemDao.getActiveByCategorySnapshot(categoryId)

    /** Live N most recent active items across all categories — used by Home's "Today" strip. */
    fun getRecentActiveFlow(limit: Int = 4): Flow<List<ItemEntity>> =
        itemDao.getRecentActiveFlow(limit)

    /** Every active item across all categories, recent-first. Used by the
     *  All Recent screen so the user can scroll the full backlog. */
    fun getAllActiveFlow(): Flow<List<ItemEntity>> = itemDao.getAllActiveFlow()

    suspend fun getByCategorySnapshot(categoryId: String): List<ItemEntity> =
        itemDao.getByCategorySnapshot(categoryId)

    suspend fun searchByText(query: String): List<ItemEntity> =
        itemDao.searchByText(query)

    suspend fun searchAllByText(query: String, limit: Int = 20): List<ItemEntity> =
        itemDao.searchAllByText(query, limit)

    suspend fun findByText(text: String): ItemEntity? =
        itemDao.findByText(text)

    suspend fun deleteProvisionalByRecordingId(recordingId: String) =
        itemDao.deleteProvisionalByRecordingId(recordingId)

    /** Before a category is deleted: its tasks go with it, and their alarms would stay registered. */
    suspend fun cancelRemindersInCategory(categoryId: String) =
        itemDao.getReminderIdsByCategory(categoryId).forEach { ReminderScheduler.cancel(applicationContext, it) }

    /** Before Clear all data. */
    suspend fun cancelAllReminders() =
        itemDao.getAllReminderIds().forEach { ReminderScheduler.cancel(applicationContext, it) }

    /** Tasks of a recording written by the task model (not provisional). */
    suspend fun countFinalByRecordingId(recordingId: String): Int =
        itemDao.countFinalByRecordingId(recordingId)

    /** Unconditional delete — used by the cancel-recording flow so that
     *  every trace of a cancelled recording's items disappears from the
     *  buckets regardless of completion/provisional state. */
    suspend fun deleteByRecordingId(recordingId: String) {
        // A task of the recording may carry a hand-set reminder; its alarm goes with it.
        itemDao.getReminderIdsByRecordingId(recordingId).forEach { ReminderScheduler.cancel(applicationContext, it) }
        itemDao.deleteByRecordingId(recordingId)
    }

    suspend fun countByRecordingId(recordingId: String): Int = itemDao.countByRecordingId(recordingId)

    suspend fun getInRange(start: Long, end: Long, limit: Int = 40): List<ItemEntity> =
        itemDao.getInRange(start, end, limit)

    suspend fun getById(id: String): ItemEntity? = itemDao.getById(id)

    suspend fun getByIds(ids: List<String>): List<ItemEntity> = itemDao.getByIds(ids)

    /** Live flow of a single item by id — drives TaskDetailScreen. */
    fun getByIdFlow(id: String): Flow<ItemEntity?> = itemDao.getByIdFlow(id)

    suspend fun getPendingReminders(now: Long = System.currentTimeMillis()): List<ItemEntity> =
        itemDao.getPendingReminders(now)

    /**
     * Alarms live outside the database, so after a reboot or a restore every future reminder is set again. [staleIds]
     * (tasks that had an alarm before a restore replaced them) are cancelled first; a task that still has a reminder
     * gets it straight back. Returns how many reminders were set.
     */
    suspend fun rescheduleReminders(staleIds: Collection<String> = emptyList()): Int {
        staleIds.forEach { ReminderScheduler.cancel(applicationContext, it) }
        val items = itemDao.getPendingReminders(System.currentTimeMillis())
        items.forEach { item -> item.reminderTime?.let { ReminderScheduler.schedule(applicationContext, item.id, it) } }
        return items.size
    }

    /** Live stream of all active reminders (incl. overdue), sorted by time. */
    fun getAllWithReminderFlow(): kotlinx.coroutines.flow.Flow<List<ItemEntity>> =
        itemDao.getAllWithReminderFlow()

    /** One-shot snapshot of all active reminders — used by search. */
    suspend fun getActiveRemindersSnapshot(limit: Int = 40): List<ItemEntity> =
        itemDao.getActiveRemindersSnapshot(limit)

    /** Active reminders whose reminderTime is within [start, end]. */
    suspend fun getRemindersInWindow(start: Long, end: Long, limit: Int = 40): List<ItemEntity> =
        itemDao.getRemindersInWindow(start, end, limit)

    /** Strip the reminder time from an item (cancels the alarm too). */
    suspend fun clearReminder(id: String) {
        val item = itemDao.getById(id) ?: return
        if (item.reminderTime == null) return
        com.bol.app.service.ReminderScheduler.cancel(applicationContext, id)
        itemDao.update(item.copy(reminderTime = null, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Set or replace an item's reminder time. Cancels any existing alarm for
     * the item before scheduling the new one so the user's edit takes effect
     * exactly once. If the new time is in the past, [ReminderScheduler]
     * itself will log and skip — but the UI should normally prevent that via
     * input validation.
     */
    suspend fun setReminderTime(id: String, newTime: Long) {
        val item = itemDao.getById(id) ?: return
        com.bol.app.service.ReminderScheduler.cancel(applicationContext, id)
        itemDao.update(item.copy(reminderTime = newTime, updatedAt = System.currentTimeMillis()))
        com.bol.app.service.ReminderScheduler.schedule(applicationContext, id, newTime)
    }

    /**
     * Replace the text of an existing item. Trims and skips no-op edits
     * (blank or unchanged). Other fields (reminder, category, source
     * recording) are preserved; updatedAt advances so search-rerank /
     * indexers see the change.
     */
    suspend fun editItemText(id: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        val item = itemDao.getById(id) ?: return
        if (trimmed == item.text) return
        itemDao.update(item.copy(text = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Move an item to a different category. Preserves everything else —
     * reminder time, completion state, source recording, tags — so the user's
     * only change is where the item lives on the home grid. No-op when the
     * target equals the current category.
     */
    suspend fun moveItem(id: String, newCategoryId: String) {
        val item = itemDao.getById(id) ?: return
        if (item.categoryId == newCategoryId) return
        itemDao.update(item.copy(categoryId = newCategoryId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun getRemindersBetween(start: Long, end: Long): List<ItemEntity> =
        itemDao.getRemindersBetween(start, end)

    suspend fun getOverdueReminders(now: Long = System.currentTimeMillis()): List<ItemEntity> =
        itemDao.getOverdueReminders(now)

    suspend fun countsByCategory(): List<com.bol.app.data.local.model.CategoryOpenCount> =
        itemDao.countsByCategory()
}
