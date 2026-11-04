package com.bol.app.data.repository

import com.bol.app.data.local.dao.LlmUpgradeDao
import com.bol.app.data.local.entity.LlmUpgradeEntity
import kotlinx.coroutines.flow.Flow

class LlmUpgradeRepository(private val dao: LlmUpgradeDao) {

    suspend fun enqueue(recordingId: String, rawText: String): String {
        // Prevent duplicate upgrade entries — if one is already pending/processing, reuse it.
        // This guards against crash-recovery re-runs of the same voice entry creating
        // a second upgrade that would insert duplicate LLM items after the first completes.
        val existing = dao.getActiveForRecording(recordingId)
        if (existing != null) return existing.id
        val entry = LlmUpgradeEntity(recordingId = recordingId, rawText = rawText)
        dao.insert(entry)
        return entry.id
    }

    suspend fun getPending(): List<LlmUpgradeEntity> = dao.getPending()

    fun getPendingRecordingIds(): Flow<List<String>> = dao.getPendingRecordingIds()

    fun pendingCountFlow(): Flow<Int> = dao.pendingCountFlow()

    suspend fun markProcessing(id: String) = dao.markProcessing(id)

    suspend fun markCompleted(id: String) = dao.markCompleted(id, System.currentTimeMillis())

    suspend fun markFailed(id: String, error: String) = dao.markFailed(id, error)

    suspend fun incrementRetryAndReset(id: String) = dao.incrementRetryAndReset(id)

    suspend fun resetStuckProcessing() = dao.resetStuckProcessing()

    /** Drop a recording's upgrade: its text was edited or it was processed again. */
    suspend fun deleteByRecordingId(recordingId: String) = dao.deleteByRecordingId(recordingId)

    suspend fun expireStaleEntries(maxRetries: Int = 8) = dao.expireStaleEntries(maxRetries)

    suspend fun cleanupOld(daysToKeep: Int = 7) {
        val cutoff = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000)
        dao.cleanupOldCompleted(cutoff)
    }
}
