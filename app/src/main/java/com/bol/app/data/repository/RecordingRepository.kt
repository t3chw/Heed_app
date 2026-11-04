package com.bol.app.data.repository

import android.content.Context
import com.bol.app.data.local.dao.ItemDao
import com.bol.app.data.local.dao.LlmUpgradeDao
import com.bol.app.data.local.dao.RecordingDao
import com.bol.app.data.local.dao.RecordingTagDao
import com.bol.app.data.local.dao.VoiceQueueDao
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.RecordingTagEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val recordingTagDao: RecordingTagDao,
    private val appContext: Context,
    private val itemDao: ItemDao,
    private val voiceQueueDao: VoiceQueueDao,
    private val llmUpgradeDao: LlmUpgradeDao,
) {
    /** Path where recorded audio WAV files live — one per recordingId. */
    private fun wavFile(recordingId: String): File =
        File(appContext.filesDir, "audio/$recordingId.wav")

    /** Does this recording have a playable WAV on disk? False for
     *  Google-STT recordings (which never produced one) and for audio
     *  that was deleted by the user or cleared from Settings. */
    fun hasAudio(recordingId: String): Boolean = wavFile(recordingId).exists()

    /** Absolute path of the WAV, or null if missing. UI uses this to
     *  decide whether the play button should be interactive. */
    fun audioPath(recordingId: String): String? =
        wavFile(recordingId).takeIf { it.exists() }?.absolutePath
    fun getAll(): Flow<List<RecordingEntity>> = recordingDao.getAll()

    suspend fun insert(recording: RecordingEntity) = recordingDao.insert(recording)

    suspend fun getAllSnapshot(): List<RecordingEntity> = recordingDao.getAllSnapshot()

    suspend fun updateItemCount(id: String, count: Int) = recordingDao.updateItemCount(id, count)

    suspend fun updateRawText(id: String, rawText: String) = recordingDao.updateRawText(id, rawText)

    /** cancelled = true surfaces the Retry action in History / Recording detail. */
    suspend fun setCancelled(id: String, cancelled: Boolean) = recordingDao.setCancelled(id, cancelled)

    suspend fun searchByText(query: String, limit: Int = 10): List<RecordingEntity> =
        recordingDao.searchByText(query, limit)

    suspend fun getById(id: String): RecordingEntity? = recordingDao.getById(id)

    suspend fun getByIds(ids: List<String>): List<RecordingEntity> = recordingDao.getByIds(ids)

    suspend fun searchByTag(name: String, limit: Int = 10): List<RecordingEntity> =
        recordingTagDao.searchRecordingsByTag(name, limit)

    suspend fun insertTags(tags: List<RecordingTagEntity>) =
        recordingTagDao.insertAll(tags)

    /** Delete the recording row AND its WAV. Used for hard-delete paths
     *  (non-speech detected on transcription, the Recording Detail's
     *  "Delete recording" button). Cancelled recordings don't use this
     *  by themselves — they stay around with cancelled=true so the user
     *  can Retry, until they explicitly remove them via Detail. */
    suspend fun deleteById(id: String) {
        recordingDao.deleteById(id)
        val wav = wavFile(id)
        if (wav.exists()) {
            wav.delete()
        }
    }

    /**
     * Full cascade-delete of a recording and everything derived from it.
     * Used by the Recording Detail screen's "Delete recording" action.
     * Wipes in a safe order:
     *
     *  1. items (category buckets will no longer show them)
     *  2. voice_queue rows (so a pending entry doesn't resurrect it)
     *  3. llm_upgrade_queue rows (same reason for provisional items)
     *  4. recording row (FK cascade also covers recording_tags)
     *  5. WAV file
     *
     * Not transactional across daos — each deletion is idempotent, so
     * a partial failure leaves the user with slightly more cleanup to
     * redo rather than a corrupt state.
     */
    suspend fun deleteRecordingAndArtifacts(id: String) {
        // Its tasks go too; cancel their alarms first so none stays registered.
        itemDao.getReminderIdsByRecordingId(id).forEach { com.bol.app.service.ReminderScheduler.cancel(appContext, it) }
        itemDao.deleteByRecordingId(id)
        voiceQueueDao.deleteByRecordingId(id)
        llmUpgradeDao.deleteByRecordingId(id)
        deleteById(id)   // row + WAV
    }


    suspend fun getInRange(start: Long, end: Long, limit: Int = 10): List<RecordingEntity> =
        recordingDao.getInRange(start, end, limit)
}
