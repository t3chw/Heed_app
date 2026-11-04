package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bol.app.data.local.entity.VoiceQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceQueueDao {
    @Insert
    suspend fun insert(entry: VoiceQueueEntity)

    @Query("SELECT * FROM voice_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<VoiceQueueEntity>

    @Query("SELECT COUNT(*) FROM voice_queue WHERE status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM voice_queue WHERE status IN ('PENDING', 'PROCESSING')")
    fun getActiveCount(): Flow<Int>

    @Query("UPDATE voice_queue SET status = :status, processedAt = :processedAt, parsedItemCount = :itemCount WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, processedAt: Long? = null, itemCount: Int = 0)

    @Query("UPDATE voice_queue SET status = 'FAILED', errorMessage = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    /** Transient failure: stay PENDING for the next run, count the attempt. */
    @Query("UPDATE voice_queue SET retryCount = retryCount + 1, errorMessage = :error WHERE id = :id")
    suspend fun recordRetry(id: String, error: String)

    @Query("UPDATE voice_queue SET rawText = :text WHERE id = :id")
    suspend fun updateRawText(id: String, text: String)

    @Query("UPDATE voice_queue SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun resetStuckProcessing()

    @Query("SELECT * FROM voice_queue ORDER BY createdAt DESC")
    fun getAll(): Flow<List<VoiceQueueEntity>>

    @Query("SELECT * FROM voice_queue WHERE id = :id")
    suspend fun getById(id: String): VoiceQueueEntity?

    /**
     * Flag every in-flight row as cancelled. Sets status = CANCELLED so
     * getActiveCount() drops to zero immediately (the pill disappears on
     * tap) and cancelRequested=1 so in-flight processing that's already
     * past markProcessing() detects the cancel at its next stage boundary.
     * Returns the list of affected voice_queue IDs for follow-up cleanup.
     */
    @Query("UPDATE voice_queue SET cancelRequested = 1, status = 'CANCELLED' WHERE status IN ('PENDING', 'PROCESSING')")
    suspend fun cancelAllActive()

    /** Queue entries whose recording the user might retry. */
    @Query("SELECT recordingId FROM voice_queue WHERE status IN ('PENDING', 'PROCESSING') AND recordingId IS NOT NULL")
    suspend fun getActiveRecordingIds(): List<String>

    @Query("DELETE FROM voice_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Delete every queue row pointing at this recording — used by the
     *  recording-delete flow to clean up any processing / pending /
     *  cancelled entries that reference a recording the user is about
     *  to remove outright. */
    @Query("DELETE FROM voice_queue WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: String)

    @Query("DELETE FROM voice_queue WHERE status = 'COMPLETED' AND processedAt < :beforeTimestamp")
    suspend fun cleanupOldCompleted(beforeTimestamp: Long)

    @Query("DELETE FROM voice_queue")
    suspend fun deleteAll()
}
