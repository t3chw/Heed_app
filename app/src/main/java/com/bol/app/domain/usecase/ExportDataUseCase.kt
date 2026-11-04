package com.bol.app.domain.usecase

import androidx.room.withTransaction
import com.bol.app.BuildConfig
import com.bol.app.data.backup.BackupBundle
import com.bol.app.data.backup.BackupCodec
import com.bol.app.data.backup.BackupData
import com.bol.app.data.backup.BackupManifest
import com.bol.app.data.backup.toBackup
import com.bol.app.data.local.BolDatabase
import com.bol.app.data.local.dao.CategoryDao
import com.bol.app.data.local.dao.ItemDao
import com.bol.app.data.local.dao.ItemEnrichmentDao
import com.bol.app.data.local.dao.ItemTagDao
import com.bol.app.data.local.dao.RecordingDao
import com.bol.app.data.local.dao.RecordingTagDao
import com.bol.app.data.local.dao.SearchQueryDao
import com.bol.app.data.local.dao.UserContextDao
import com.bol.app.data.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Writes a complete `.heedbackup` archive (a ZIP with manifest.json,
 * data.json, prefs.json) to a caller-supplied [OutputStream].
 *
 * Every user-data table that the schema in [com.bol.app.data.backup]
 * covers is read in a single Room transaction so a write landing
 * mid-export can't leave the file in an inconsistent state (e.g. an
 * item referencing a recording that the recording snapshot hadn't seen
 * yet). DataStore preferences are snapshotted just before the ZIP is
 * written; DataStore writes are atomic individually, so the worst case
 * is a freshly-flipped pref ending up with whichever side of the flip
 * happened to land first — never mid-write garbage.
 *
 * The resulting backup includes:
 *   - all categories (active + soft-deleted, so item FKs aren't broken)
 *   - all items (open + completed + provisional)
 *   - all recordings + per-item / per-recording tags
 *   - all item enrichments (search-context blobs)
 *   - all search-query history
 *   - all user-context rows
 *   - whitelisted DataStore prefs (no per-device benchmark / cadence state)
 *
 * Embeddings are intentionally excluded — they're regenerable from
 * `text` plus an embedding-model version that may not match on restore.
 */
class ExportDataUseCase(
    private val database: BolDatabase,
    private val categoryDao: CategoryDao,
    private val itemDao: ItemDao,
    private val recordingDao: RecordingDao,
    private val itemTagDao: ItemTagDao,
    private val recordingTagDao: RecordingTagDao,
    private val itemEnrichmentDao: ItemEnrichmentDao,
    private val searchQueryDao: SearchQueryDao,
    private val userContextDao: UserContextDao,
    private val preferencesManager: PreferencesManager,
) {

    /**
     * Encode the full backup into [out]. The OutputStream is NOT closed
     * by this method — the caller owns it (typically a SAF
     * `ContentResolver.openOutputStream(uri)?.use { … }` block).
     *
     * Runs on [Dispatchers.IO] for both the DB reads and the ZIP write.
     * Returns the manifest that was written so the UI can show counts
     * in a confirmation toast.
     */
    suspend fun export(out: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        // Single transaction across every backup-relevant DAO read so
        // the snapshot is internally consistent. Each suspend DAO call
        // dispatches inside the transactional context that
        // `withTransaction` sets up.
        val data = database.withTransaction {
            BackupData(
                categories = categoryDao.getAllSnapshot().map { it.toBackup() },
                items = itemDao.getAllSnapshot().map { it.toBackup() },
                recordings = recordingDao.getAllSnapshot().map { it.toBackup() },
                itemTags = itemTagDao.getAllSnapshot().map { it.toBackup() },
                recordingTags = recordingTagDao.getAllSnapshot().map { it.toBackup() },
                itemEnrichments = itemEnrichmentDao.getAllSnapshot().map { it.toBackup() },
                searchQueries = searchQueryDao.getAllSnapshot().map { it.toBackup() },
                userContext = userContextDao.getAllSnapshot().map { it.toBackup() },
            )
        }

        val prefs = preferencesManager.snapshot()

        BackupCodec.encode(
            out = out,
            bundle = BackupBundle(data = data, prefs = prefs),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }
}
