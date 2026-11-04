package com.bol.app.data.repository

import com.bol.app.data.local.dao.ItemDao
import com.bol.app.data.local.dao.RecordingDao
import com.bol.app.data.local.dao.VoiceQueueDao
import com.bol.app.data.local.entity.QueueStatus
import com.bol.app.data.local.entity.VoiceQueueEntity
import com.bol.app.data.local.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

class VoiceQueueRepository(
    private val dao: VoiceQueueDao,
    private val recordingDao: RecordingDao,
    private val itemDao: ItemDao,
) {

    suspend fun enqueue(rawText: String, recordingId: String? = null, bypassStt: Boolean = false): String {
        val entry = VoiceQueueEntity(rawText = rawText, recordingId = recordingId, bypassStt = bypassStt)
        dao.insert(entry)
        return entry.id
    }

    /**
     * Enqueue already-transcribed text (e.g. from a share intent). The entry
     * is marked `bypassStt = true` so [QueueProcessingUseCase] skips speech
     * and goes straight to the task model.
     *
     * It gets a recording row like a typed note: without one, a provisional
     * result was never upgraded, Cancel threw the text away with nothing to
     * retry, and a failure left no trace in History.
     */
    suspend fun enqueueText(text: String): String {
        val note = text.take(5000)   // parser cap
        val recording = RecordingEntity(rawText = note, parsedItemCount = 0)
        recordingDao.insert(recording)
        val entry = VoiceQueueEntity(
            rawText = note,
            recordingId = recording.id,
            bypassStt = true,
        )
        dao.insert(entry)
        return entry.id
    }

    suspend fun getPending(): List<VoiceQueueEntity> = dao.getPending()

    fun getPendingCount(): Flow<Int> = dao.getPendingCount()

    fun getActiveCount(): Flow<Int> = dao.getActiveCount()

    suspend fun markProcessing(id: String) =
        dao.updateStatus(id, QueueStatus.PROCESSING)

    suspend fun markCompleted(id: String, itemCount: Int) =
        dao.updateStatus(id, QueueStatus.COMPLETED, System.currentTimeMillis(), itemCount)

    suspend fun markFailed(id: String, error: String) =
        dao.markFailed(id, error)

    suspend fun recordRetry(id: String, error: String) =
        dao.recordRetry(id, error)

    suspend fun updateRawText(id: String, text: String) =
        dao.updateRawText(id, text)

    suspend fun resetStuckProcessing() =
        dao.resetStuckProcessing()

    fun getAll(): Flow<List<VoiceQueueEntity>> = dao.getAll()

    suspend fun cleanupOld(daysToKeep: Int = 7) {
        val cutoff = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000)
        dao.cleanupOldCompleted(cutoff)
    }

    suspend fun getById(id: String): VoiceQueueEntity? = dao.getById(id)

    /**
     * Cancel every in-flight recording. Flips voice_queue rows to CANCELLED
     * (so the pill disappears from Home) + flags cancelRequested=1 so any
     * in-flight coroutine bails at its next stage boundary. Marks the
     * associated recordings cancelled=true so they show up in History as
     * retryable. Pre-emptively deletes items tied to those recordings in
     * case save finished just before cancel fired. WAV files are kept on
     * disk so Retry can re-run the full pipeline.
     */
    suspend fun cancelAllActive() {
        val recordingIds = dao.getActiveRecordingIds()
        dao.cancelAllActive()
        if (recordingIds.isNotEmpty()) {
            recordingDao.markManyCancelled(recordingIds)
            recordingIds.forEach { itemDao.deleteByRecordingId(it) }
        }
    }

    /**
     * Re-enqueue a previously-cancelled recording. Unsets the cancelled
     * flag and inserts a fresh voice_queue row pointing at the same
     * recording + WAV. If the recording was already audio-transcribed
     * before the original cancel, [VoiceQueueEntity.rawText] holds the
     * text and the pipeline will skip Whisper; otherwise rawText is
     * "[audio]" and Whisper re-runs on the preserved WAV.
     */
    suspend fun retryRecording(recordingId: String): String? {
        val recording = recordingDao.getById(recordingId) ?: return null
        recordingDao.setCancelled(recordingId, false)
        // Wipe any stale items that survived (belt-and-braces) so the
        // retry's output is the only thing tied to this recording.
        itemDao.deleteByRecordingId(recordingId)
        val entry = VoiceQueueEntity(
            rawText = recording.rawText,
            recordingId = recordingId,
            bypassStt = recording.rawText != "[audio]",
        )
        dao.insert(entry)
        return entry.id
    }
}
