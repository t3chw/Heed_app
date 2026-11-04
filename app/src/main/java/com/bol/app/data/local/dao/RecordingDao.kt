package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    /** Bulk-insert used by backup restore. IGNORE strategy so a row
     *  whose id already exists locally (e.g. user is merging two
     *  backups that share recordings) is preserved as-is — the import
     *  pipeline pre-filters by id but this is the safety net. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(recordings: List<RecordingEntity>)

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<RecordingEntity>

    @Query("UPDATE recordings SET parsedItemCount = :count WHERE id = :id")
    suspend fun updateItemCount(id: String, count: Int)

    @Query("UPDATE recordings SET rawText = :rawText WHERE id = :id")
    suspend fun updateRawText(id: String, rawText: String)

    @Query("UPDATE recordings SET cancelled = :cancelled WHERE id = :id")
    suspend fun setCancelled(id: String, cancelled: Boolean)

    @Query("UPDATE recordings SET cancelled = 1 WHERE id IN (:ids)")
    suspend fun markManyCancelled(ids: List<String>)

    @Query("SELECT * FROM recordings WHERE rawText LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchByText(query: String, limit: Int = 10): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Recordings whose createdAt falls within [start, end]. Used by time-scoped search. */
    @Query("SELECT * FROM recordings WHERE createdAt BETWEEN :start AND :end ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getInRange(start: Long, end: Long, limit: Int): List<RecordingEntity>

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()
}
