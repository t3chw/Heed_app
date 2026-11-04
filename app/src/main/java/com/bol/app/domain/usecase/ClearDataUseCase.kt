package com.bol.app.domain.usecase

import android.content.Context
import androidx.room.withTransaction
import com.bol.app.data.local.BolDatabase
import com.bol.app.data.local.DefaultCategories
import com.bol.app.data.local.dao.CategoryDao
import com.bol.app.data.local.dao.LlmUpgradeDao
import com.bol.app.data.local.dao.PerformanceLogDao
import com.bol.app.data.local.dao.RecordingDao
import com.bol.app.data.local.dao.SearchQueryDao
import com.bol.app.data.local.dao.UserContextDao
import com.bol.app.data.local.dao.VoiceQueueDao
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resets the app's local data while preserving downloaded models and
 * user preferences. Cascading FKs handle dependent tables:
 *   - deleting categories cascades items → item_embeddings, item_tags,
 *     item_enrichments, eval_queries
 *   - deleting recordings cascades recording_tags
 *
 * The on-disk WAV files live outside Room and must be deleted manually.
 */
class ClearDataUseCase(
    private val database: BolDatabase,
    private val categoryDao: CategoryDao,
    private val recordingDao: RecordingDao,
    private val voiceQueueDao: VoiceQueueDao,
    private val llmUpgradeDao: LlmUpgradeDao,
    private val searchQueryDao: SearchQueryDao,
    private val userContextDao: UserContextDao,
    private val performanceLogDao: PerformanceLogDao,
    private val itemRepository: com.bol.app.data.repository.ItemRepository,
    private val appContext: Context,
) {
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        // The tasks are about to go; their alarms would otherwise stay registered with Android.
        itemRepository.cancelAllReminders()
        // Order matters only insofar as cascades help us — row-count wise
        // wiping categories last is cleanest so the FK to items stays valid
        // right up until we truncate it. One transaction: a kill between the
        // category wipe and the reseed used to leave no categories at all, and
        // every later note failed on an empty category list.
        database.withTransaction {
            searchQueryDao.deleteAll()
            voiceQueueDao.deleteAll()
            llmUpgradeDao.deleteAll()
            userContextDao.deleteAll()
            performanceLogDao.deleteAll()
            recordingDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.insertAll(DefaultCategories.list)
        }

        deleteDir(File(appContext.filesDir, "audio"))
        deleteDir(File(appContext.filesDir, "search_audio"))

        BolLogger.log(
            LogLevel.INFO, LogCategory.APP, "data_cleared",
            "User requested full data wipe — DB tables reset, categories reseeded, audio dirs deleted"
        )
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) deleteDir(f) else f.delete()
        }
    }
}
