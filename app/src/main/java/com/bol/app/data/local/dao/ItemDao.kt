package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bol.app.data.local.entity.ItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE categoryId = :categoryId ORDER BY isCompleted ASC, createdAt DESC, rowid ASC")
    fun getByCategory(categoryId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ItemEntity>

    /** Live single-item observer. Used by TaskDetailScreen so the page
     *  reacts when the user toggles complete / sets a reminder / etc.
     *  without needing manual refresh. */
    @Query("SELECT * FROM items WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Update
    suspend fun update(item: ItemEntity)

    @Delete
    suspend fun delete(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND reminderTime > :currentTime AND isCompleted = 0")
    suspend fun getUpcomingReminders(currentTime: Long): List<ItemEntity>

    @Query("SELECT * FROM items WHERE sourceRecordingId = :recordingId ORDER BY createdAt ASC, rowid ASC")
    fun getByRecordingId(recordingId: String): Flow<List<ItemEntity>>

    /** Items whose createdAt falls within [start, end]. Used by time-scoped search. */
    @Query("SELECT * FROM items WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt DESC, rowid ASC LIMIT :limit")
    suspend fun getInRange(start: Long, end: Long, limit: Int): List<ItemEntity>

    @Query("SELECT * FROM items ORDER BY createdAt DESC, rowid ASC")
    suspend fun getAllSnapshot(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE categoryId = :categoryId AND isCompleted = 0 ORDER BY createdAt DESC, rowid ASC")
    suspend fun getActiveByCategorySnapshot(categoryId: String): List<ItemEntity>

    /**
     * Live stream of the N most recent active (uncompleted) items across all
     * categories. Powers the "Today" section on Home so the list updates the
     * moment an item is added, toggled completed, or moved.
     */
    @Query("SELECT * FROM items WHERE isCompleted = 0 ORDER BY createdAt DESC, rowid ASC LIMIT :limit")
    fun getRecentActiveFlow(limit: Int): Flow<List<ItemEntity>>

    /** Live stream of every active (uncompleted) item across all categories
     *  recent-first. Powers the "See all" page reachable from Home → Recent. */
    @Query("SELECT * FROM items WHERE isCompleted = 0 ORDER BY createdAt DESC, rowid ASC")
    fun getAllActiveFlow(): Flow<List<ItemEntity>>

    /** Includes completed items — used by time-scoped search so "what did I do
     *  yesterday" can surface done work too. Completed items are sorted last. */
    @Query("SELECT * FROM items WHERE categoryId = :categoryId ORDER BY isCompleted ASC, createdAt DESC, rowid ASC")
    suspend fun getByCategorySnapshot(categoryId: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE text LIKE '%' || :query || '%' AND isCompleted = 0")
    suspend fun searchByText(query: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE text LIKE '%' || :query || '%' ORDER BY isCompleted ASC, createdAt DESC, rowid ASC LIMIT :limit")
    suspend fun searchAllByText(query: String, limit: Int = 20): List<ItemEntity>

    @Query("SELECT * FROM items WHERE text LIKE '%' || :text || '%' AND isCompleted = 0 LIMIT 1")
    suspend fun findByText(text: String): ItemEntity?

    @Query("DELETE FROM items WHERE sourceRecordingId = :recordingId AND isProvisional = 1 AND isCompleted = 0")
    suspend fun deleteProvisionalByRecordingId(recordingId: String)

    @Query("SELECT COUNT(*) FROM items WHERE sourceRecordingId = :recordingId AND isProvisional = 0")
    suspend fun countFinalByRecordingId(recordingId: String): Int

    @Query("SELECT COUNT(*) FROM items WHERE sourceRecordingId = :recordingId")
    suspend fun countByRecordingId(recordingId: String): Int

    /** Tasks with an alarm, so a bulk delete (recording, category, everything) can cancel them first. */
    @Query("SELECT id FROM items WHERE reminderTime IS NOT NULL AND sourceRecordingId = :recordingId")
    suspend fun getReminderIdsByRecordingId(recordingId: String): List<String>

    @Query("SELECT id FROM items WHERE reminderTime IS NOT NULL AND categoryId = :categoryId")
    suspend fun getReminderIdsByCategory(categoryId: String): List<String>

    @Query("SELECT id FROM items WHERE reminderTime IS NOT NULL")
    suspend fun getAllReminderIds(): List<String>

    /** Unconditional delete — used when the user cancels a recording and
     *  every item tied to it should disappear (the user has said "this
     *  recording didn't happen"). Doesn't care about isCompleted or
     *  isProvisional. */
    @Query("DELETE FROM items WHERE sourceRecordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: String)

    /** Non-completed items with a future reminder — used by BootCompletedReceiver. */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND reminderTime > :now AND isCompleted = 0")
    suspend fun getPendingReminders(now: Long): List<ItemEntity>

    /**
     * Live stream of all items with a reminder (including overdue), not yet
     * completed. Used by the Scheduled Reminders screen in Settings so the
     * list updates as reminders fire, get cancelled, or get marked done.
     * Sorted ascending by reminderTime so overdue surfaces first.
     */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND isCompleted = 0 ORDER BY reminderTime ASC, rowid ASC")
    fun getAllWithReminderFlow(): kotlinx.coroutines.flow.Flow<List<ItemEntity>>

    /**
     * Snapshot version of the active-reminders list. Used by the search
     * engine's reminder-intent branch ("what's on my reminder") — a one-shot
     * read at query time, not a stream.
     */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND isCompleted = 0 ORDER BY reminderTime ASC, rowid ASC LIMIT :limit")
    suspend fun getActiveRemindersSnapshot(limit: Int = 40): List<ItemEntity>

    /**
     * Active reminders whose firing time falls in [start, end]. Used by the
     * search engine when the user combines a reminder-intent keyword with a
     * time phrase ("reminders for tomorrow", "what's due this week").
     */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND isCompleted = 0 AND reminderTime BETWEEN :start AND :end ORDER BY reminderTime ASC, rowid ASC LIMIT :limit")
    suspend fun getRemindersInWindow(start: Long, end: Long, limit: Int = 40): List<ItemEntity>

    /** Reminders due within [start, end] — used by the daily digest. */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND reminderTime BETWEEN :start AND :end AND isCompleted = 0 ORDER BY reminderTime ASC, rowid ASC")
    suspend fun getRemindersBetween(start: Long, end: Long): List<ItemEntity>

    /** Reminders whose time has already passed but that aren't completed yet. */
    @Query("SELECT * FROM items WHERE reminderTime IS NOT NULL AND reminderTime < :now AND isCompleted = 0 ORDER BY reminderTime DESC, rowid ASC")
    suspend fun getOverdueReminders(now: Long): List<ItemEntity>

    /** Per-category open-item counts for the daily digest summary. */
    @Query("SELECT categoryId, COUNT(*) AS count FROM items WHERE isCompleted = 0 GROUP BY categoryId")
    suspend fun countsByCategory(): List<com.bol.app.data.local.model.CategoryOpenCount>

    /** Total item count — used by [com.bol.app.ai.indexing.IndexingCoordinator]
     *  to compute (total - indexed) work pending. */
    @Query("SELECT COUNT(*) FROM items")
    suspend fun countAll(): Int

    /** Reactive total item count. Re-emits whenever the items table changes,
     *  so the indexing banner's "X items waiting" updates the moment a new
     *  voice note finishes saving — no need to wait for the next foreground. */
    @Query("SELECT COUNT(*) FROM items")
    fun countAllFlow(): kotlinx.coroutines.flow.Flow<Int>
}
