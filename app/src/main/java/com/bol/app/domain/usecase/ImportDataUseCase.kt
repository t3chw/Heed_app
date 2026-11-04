package com.bol.app.domain.usecase

import androidx.room.withTransaction
import com.bol.app.data.backup.BackupCodec
import com.bol.app.data.backup.BackupCorruptException
import com.bol.app.data.backup.BackupData
import com.bol.app.data.backup.BackupPreview
import com.bol.app.data.backup.BackupSchemaVersion
import com.bol.app.data.backup.BackupValidator
import com.bol.app.data.backup.BackupVersionException
import com.bol.app.data.backup.RestoreMode
import com.bol.app.data.backup.RestoreProgress
import com.bol.app.data.backup.RestoreReport
import com.bol.app.data.backup.toEntity
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
import com.bol.app.data.repository.ItemRepository
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.service.digest.DailyDigestScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream

/**
 * Restores a `.heedbackup` archive into the live database and
 * DataStore. Mirrors [ExportDataUseCase] in shape but goes the other
 * direction.
 *
 * Pipeline (single Room transaction wraps phases 3–5):
 *   1. Decode + checksum + version-validate (BackupCodec.decode)
 *   2. FK + count integrity check (BackupValidator)
 *   3. (REPLACE only) Wipe data tables — CASCADE handles dependents
 *   4. Pre-filter imported rows so MERGE doesn't clobber local IDs
 *   5. Insert in parents-first order
 *   6. After commit: restore prefs (DataStore is separate from Room)
 *   7. Set reminder alarms and the digest schedule from what was restored
 *      (both live in Android, outside the database)
 *
 * Failure semantics:
 *   - Anything before phase 3 → no DB writes happened, abort cleanly.
 *   - Anything during phases 3–5 → Room rolls back the whole
 *     transaction, leaving the DB exactly as it was.
 *   - DataStore restore failure → DB is already committed, so we
 *     emit a Done event with `prefsRestored = false` and the user's
 *     data IS restored. They can re-pick prefs manually.
 *
 * Embeddings are NOT restored (the backup doesn't carry them by
 * design) — Phase E will trigger IndexingCoordinator after restore
 * to regenerate them.
 */
class ImportDataUseCase(
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
    private val itemRepository: ItemRepository,
    private val appContext: android.content.Context,
) {

    /**
     * Read just the manifest from a backup file and decide whether
     * Heed v1 can restore it. No DB writes; no parse of the (possibly
     * large) data.json. Caller closes [input].
     *
     * Throws [BackupCorruptException] when the file isn't a valid
     * Heed backup at all (not a ZIP, manifest missing/malformed).
     * Otherwise returns a [BackupPreview] whose `canRestore` flag
     * tells the UI whether to enable the restore button.
     */
    suspend fun previewBackup(input: InputStream): BackupPreview {
        val manifest = BackupCodec.decodeManifestOnly(input)
        val (canRestore, reason) = when {
            manifest.encrypted ->
                false to "This backup is encrypted — Heed v1 doesn't support encrypted backups yet."
            manifest.schemaVersion > BackupSchemaVersion.CURRENT ->
                false to "This backup was made with a newer Heed (v${manifest.appVersionName}). Please update Heed first."
            manifest.schemaVersion < BackupSchemaVersion.MIN_SUPPORTED ->
                false to "This backup is older than Heed can read."
            else -> true to null
        }
        return BackupPreview(
            manifest = manifest,
            canRestore = canRestore,
            incompatibleReason = reason,
        )
    }

    /**
     * Decode + validate + restore. Emits progress events on the
     * returned Flow; the flow always terminates with [RestoreProgress.Done]
     * or [RestoreProgress.Failed].
     *
     * Caller closes [input]. The IO and DB work runs on
     * [Dispatchers.IO] so the collector can observe from any
     * dispatcher (the UI typically collects on Main).
     */
    fun restore(input: InputStream, mode: RestoreMode): Flow<RestoreProgress> = flow {
        emit(RestoreProgress.Validating)

        val decoded = try {
            BackupCodec.decode(input)
        } catch (e: BackupVersionException) {
            emit(RestoreProgress.Failed(e.message ?: "Incompatible backup version", e))
            return@flow
        } catch (e: BackupCorruptException) {
            emit(RestoreProgress.Failed(e.message ?: "Backup file is corrupted", e))
            return@flow
        }

        val issues = BackupValidator.validate(decoded.bundle, decoded.manifest)
        if (BackupValidator.hasFatalIssue(issues)) {
            val msg = "Backup failed integrity check: " + BackupValidator.summarize(issues)
            emit(RestoreProgress.Failed(msg, IllegalStateException(msg)))
            return@flow
        }

        // Tasks with a live alarm now. REPLACE deletes them without cancelling, so their alarms are cancelled after
        // the commit (the ones that come back with a reminder are set again).
        val armedBefore = runCatching { itemRepository.getPendingReminders().map { it.id } }.getOrDefault(emptyList())

        if (mode == RestoreMode.REPLACE) emit(RestoreProgress.Wiping)
        emit(RestoreProgress.Writing("data"))

        val report = try {
            database.withTransaction {
                writeAll(decoded.bundle.data, mode)
            }
        } catch (e: Throwable) {
            // Room rolled back. Surface the error and bail.
            emit(RestoreProgress.Failed("Restore aborted: ${e.message ?: "unknown error"}", e))
            return@flow
        }

        emit(RestoreProgress.RestoringPrefs)
        val prefsRestored = try {
            preferencesManager.restore(decoded.bundle.prefs)
            true
        } catch (_: Throwable) {
            // DB is already committed; the restore is mostly successful.
            // Fall through with prefsRestored=false; the user can re-pick
            // prefs manually if anything important didn't land.
            false
        }

        // Without this, restored reminders stayed silent until the phone restarted, and a restored digest setting
        // waited for the next app start.
        runCatching {
            val armed = itemRepository.rescheduleReminders(staleIds = armedBefore)
            if (preferencesManager.digestEnabled.first()) {
                DailyDigestScheduler.schedule(appContext, preferencesManager.digestTime.first())
            } else {
                DailyDigestScheduler.cancel(appContext)
            }
            BolLogger.log(LogLevel.INFO, LogCategory.APP, "restore_alarms_set", "Restore: $armed reminder alarm(s) set, digest schedule updated")
        }.onFailure { e ->
            BolLogger.log(LogLevel.WARN, LogCategory.APP, "restore_alarms_failed",
                "Restore: setting reminder alarms failed — ${e.javaClass.simpleName}: ${e.message?.take(150)}")
        }

        emit(RestoreProgress.Done(report.copy(prefsRestored = prefsRestored)))
    }.flowOn(Dispatchers.IO)

    /**
     * Inside the Room transaction. Wipes (REPLACE) or pre-filters
     * (MERGE), then inserts in parents-first order so foreign keys
     * resolve.
     *
     * Returns a [RestoreReport] populated with how many rows actually
     * landed (in MERGE mode this is `<= manifest count` because
     * collisions are dropped). `prefsRestored` is filled in by the
     * caller after the DataStore step.
     */
    private suspend fun writeAll(data: BackupData, mode: RestoreMode): RestoreReport {
        if (mode == RestoreMode.REPLACE) {
            // CASCADE handles dependents:
            //   categories → items → item_tags + item_enrichments
            //   recordings → recording_tags
            categoryDao.deleteAll()
            recordingDao.deleteAll()
            searchQueryDao.deleteAll()
            userContextDao.deleteAll()
        }

        // ── parents first ──
        val categoriesToWrite = filterMissing(
            imported = data.categories,
            mode = mode,
            keyOf = { it.id },
        ) { categoryDao.getAllSnapshot().mapTo(HashSet()) { it.id } }
        categoryDao.insertAll(categoriesToWrite.map { it.toEntity() })

        val itemsToWrite = filterMissing(
            imported = data.items,
            mode = mode,
            keyOf = { it.id },
        ) { itemDao.getAllSnapshot().mapTo(HashSet()) { it.id } }
        itemDao.insertAll(itemsToWrite.map { it.toEntity() })

        // A category deleted on this phone is only switched off (isActive = 0) and keeps its id, so MERGE skipped
        // the backup's copy and its restored tasks landed in a hidden category. Switch such a category back on
        // when the backup has it on.
        if (mode == RestoreMode.MERGE && itemsToWrite.isNotEmpty()) {
            val activeInBackup = data.categories.filter { it.isActive }.mapTo(HashSet()) { it.id }
            itemsToWrite.mapTo(HashSet()) { it.categoryId }.filter { it in activeInBackup }.forEach { categoryDao.activate(it) }
        }

        val recordingsToWrite = filterMissing(
            imported = data.recordings,
            mode = mode,
            keyOf = { it.id },
        ) { recordingDao.getAllSnapshot().mapTo(HashSet()) { it.id } }
        recordingDao.insertAll(recordingsToWrite.map { it.toEntity() })

        // ── tag tables: id is autogen with no semantic meaning, so
        //    reset to 0 before insert. SQLite assigns a fresh primary
        //    key, avoiding id collisions with local autogen sequence
        //    in MERGE mode. UNIQUE(itemId, tag) on item_tags handles
        //    semantic dedup at SQL level via OnConflictStrategy.IGNORE;
        //    recording_tags has no UNIQUE so we pre-filter by tuple. ──
        val itemTagsToWrite = if (mode == RestoreMode.MERGE) {
            val existing = itemTagDao.getAllSnapshot()
                .mapTo(HashSet()) { it.itemId to it.tag }
            data.itemTags.filter { (it.itemId to it.tag) !in existing }
        } else {
            data.itemTags
        }
        itemTagDao.insertAll(itemTagsToWrite.map { it.toEntity().copy(id = 0L) })

        val recordingTagsToWrite = if (mode == RestoreMode.MERGE) {
            val existing = recordingTagDao.getAllSnapshot()
                .mapTo(HashSet()) { Triple(it.recordingId, it.tag, it.tagType) }
            data.recordingTags.filter { Triple(it.recordingId, it.tag, it.tagType) !in existing }
        } else {
            data.recordingTags
        }
        recordingTagDao.insertAll(recordingTagsToWrite.map { it.toEntity().copy(id = 0L) })

        val enrichmentsToWrite = filterMissing(
            imported = data.itemEnrichments,
            mode = mode,
            keyOf = { it.itemId },
        ) { itemEnrichmentDao.getAllSnapshot().mapTo(HashSet()) { it.itemId } }
        itemEnrichmentDao.insertAll(enrichmentsToWrite.map { it.toEntity() })

        val searchQueriesToWrite = filterMissing(
            imported = data.searchQueries,
            mode = mode,
            keyOf = { it.id },
        ) { searchQueryDao.getAllSnapshot().mapTo(HashSet()) { it.id } }
        searchQueryDao.insertAll(searchQueriesToWrite.map { it.toEntity() })

        val userContextToWrite = filterMissing(
            imported = data.userContext,
            mode = mode,
            keyOf = { it.key },
        ) { userContextDao.getAllSnapshot().mapTo(HashSet()) { it.key } }
        userContextDao.insertAll(userContextToWrite.map { it.toEntity() })

        return RestoreReport(
            mode = mode,
            categoriesWritten = categoriesToWrite.size,
            itemsWritten = itemsToWrite.size,
            recordingsWritten = recordingsToWrite.size,
            itemTagsWritten = itemTagsToWrite.size,
            recordingTagsWritten = recordingTagsToWrite.size,
            itemEnrichmentsWritten = enrichmentsToWrite.size,
            searchQueriesWritten = searchQueriesToWrite.size,
            userContextWritten = userContextToWrite.size,
            prefsRestored = false, // caller fills in after DataStore step
        )
    }

    /**
     * In MERGE mode, drop rows whose primary key already exists locally.
     * In REPLACE mode (after wipe) just return the imported list as-is —
     * there's nothing to filter against, and pre-fetching empty key sets
     * would burn one extra query per table.
     */
    private inline fun <T, K> filterMissing(
        imported: List<T>,
        mode: RestoreMode,
        keyOf: (T) -> K,
        existingKeys: () -> Set<K>,
    ): List<T> = when (mode) {
        RestoreMode.REPLACE -> imported
        RestoreMode.MERGE -> {
            val existing = existingKeys()
            imported.filter { keyOf(it) !in existing }
        }
    }
}
