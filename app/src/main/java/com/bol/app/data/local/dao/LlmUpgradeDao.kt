package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bol.app.data.local.entity.LlmUpgradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LlmUpgradeDao {
    @Insert
    suspend fun insert(entry: LlmUpgradeEntity)

    @Query("SELECT * FROM llm_upgrade_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<LlmUpgradeEntity>

    @Query("SELECT DISTINCT recordingId FROM llm_upgrade_queue WHERE status IN ('PENDING', 'PROCESSING')")
    fun getPendingRecordingIds(): Flow<List<String>>

    /** Reactive count of upgrades still waiting. Used by
     *  [com.bol.app.ai.indexing.IndexingCoordinator] so the banner pending
     *  count stays in sync as upgrades complete. */
    @Query("SELECT COUNT(*) FROM llm_upgrade_queue WHERE status = 'PENDING'")
    fun pendingCountFlow(): Flow<Int>

    @Query("UPDATE llm_upgrade_queue SET status = 'PROCESSING' WHERE id = :id")
    suspend fun markProcessing(id: String)

    @Query("UPDATE llm_upgrade_queue SET status = 'COMPLETED', processedAt = :processedAt WHERE id = :id")
    suspend fun markCompleted(id: String, processedAt: Long)

    @Query("UPDATE llm_upgrade_queue SET status = 'FAILED', errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Query("UPDATE llm_upgrade_queue SET status = 'PENDING', retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryAndReset(id: String)

    @Query("UPDATE llm_upgrade_queue SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun resetStuckProcessing()

    @Query("SELECT * FROM llm_upgrade_queue WHERE recordingId = :recordingId AND status IN ('PENDING', 'PROCESSING') LIMIT 1")
    suspend fun getActiveForRecording(recordingId: String): LlmUpgradeEntity?

    @Query("UPDATE llm_upgrade_queue SET status = 'FAILED', errorMessage = 'Max retries exceeded' WHERE status = 'PENDING' AND retryCount >= :maxRetries")
    suspend fun expireStaleEntries(maxRetries: Int)

    @Query("DELETE FROM llm_upgrade_queue WHERE status = 'COMPLETED' AND processedAt < :beforeTimestamp")
    suspend fun cleanupOldCompleted(beforeTimestamp: Long)

    /** Delete every upgrade-queue row tied to this recording — used by
     *  the recording-delete flow so a removed recording doesn't leave
     *  orphan upgrade work behind. */
    @Query("DELETE FROM llm_upgrade_queue WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: String)

    @Query("DELETE FROM llm_upgrade_queue")
    suspend fun deleteAll()
}
