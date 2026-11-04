package com.bol.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bol.app.data.local.BolDatabase
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.ItemTagEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.RecordingTagEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.domain.usecase.ExportDataUseCase
import com.bol.app.domain.usecase.ImportDataUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * End-to-end round-trip tests for the backup pipeline.
 *
 * These exercise the real Room runtime (in-memory) plus the real
 * codec, validator, and ImportDataUseCase wiring. They cover the
 * gaps that pure-JVM tests can't:
 *   - Foreign-key + CASCADE behavior in Room
 *   - MERGE pre-filter actually skipping local IDs
 *   - REPLACE wipe-and-fill ending in the same state as the export
 *   - Tag autogen-id reset preventing primary-key collisions
 *
 * PreferencesManager is real (its DataStore writes to the test app's
 * data dir). DataStore state leaks between tests are harmless — the
 * tests assert on DB rows, not preferences.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private lateinit var database: BolDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var exportUseCase: ExportDataUseCase
    private lateinit var importUseCase: ImportDataUseCase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BolDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesManager = PreferencesManager(context)
        exportUseCase = ExportDataUseCase(
            database = database,
            categoryDao = database.categoryDao(),
            itemDao = database.itemDao(),
            recordingDao = database.recordingDao(),
            itemTagDao = database.itemTagDao(),
            recordingTagDao = database.recordingTagDao(),
            itemEnrichmentDao = database.itemEnrichmentDao(),
            searchQueryDao = database.searchQueryDao(),
            userContextDao = database.userContextDao(),
            preferencesManager = preferencesManager,
        )
        importUseCase = ImportDataUseCase(
            database = database,
            categoryDao = database.categoryDao(),
            itemDao = database.itemDao(),
            recordingDao = database.recordingDao(),
            itemTagDao = database.itemTagDao(),
            recordingTagDao = database.recordingTagDao(),
            itemEnrichmentDao = database.itemEnrichmentDao(),
            searchQueryDao = database.searchQueryDao(),
            userContextDao = database.userContextDao(),
            preferencesManager = preferencesManager,
            itemRepository = com.bol.app.data.repository.ItemRepository(database.itemDao(), context),
            appContext = context,
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun cat(id: String = "tasks") = CategoryEntity(
        id = id, name = id, icon = "✓", color = "#abc",
        sortOrder = 0, isDefault = true, isActive = true, description = "",
    )

    private fun item(id: String, categoryId: String = "tasks", text: String = "do $id") = ItemEntity(
        id = id, categoryId = categoryId, text = text,
        isCompleted = false, isProvisional = false,
        reminderTime = null, sourceRecordingId = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun recording(id: String) = RecordingEntity(
        id = id, rawText = "raw $id", parsedItemCount = 1,
        createdAt = 1L, cancelled = false,
    )

    /** Encodes a hand-built bundle to bytes via the real codec. */
    private fun encodeBundle(
        categories: List<BackupCategory> = emptyList(),
        items: List<BackupItem> = emptyList(),
        recordings: List<BackupRecording> = emptyList(),
        itemTags: List<BackupItemTag> = emptyList(),
        recordingTags: List<BackupRecordingTag> = emptyList(),
    ): ByteArray {
        val bundle = BackupBundle(
            data = BackupData(
                categories = categories,
                items = items,
                recordings = recordings,
                itemTags = itemTags,
                recordingTags = recordingTags,
            ),
        )
        val out = ByteArrayOutputStream()
        BackupCodec.encode(out, bundle, "1.0", 1)
        return out.toByteArray()
    }

    /** Drains the restore Flow synchronously, throwing if it fails. */
    private suspend fun runRestore(bytes: ByteArray, mode: RestoreMode) {
        importUseCase.restore(ByteArrayInputStream(bytes), mode).collect { progress ->
            if (progress is RestoreProgress.Failed) {
                throw IllegalStateException("Restore failed: ${progress.message}")
            }
        }
    }

    // ── Tests ───────────────────────────────────────────────────

    @Test
    fun fullExportImportRoundTripPreservesAllRows() = runBlocking {
        // Seed one of each entity type so every mapper path runs.
        val originalCat = cat()
        database.categoryDao().insertAll(listOf(originalCat))
        val originalItem = item("i1")
        database.itemDao().insertAll(listOf(originalItem))
        val originalRec = recording("r1")
        database.recordingDao().insertAll(listOf(originalRec))
        database.itemTagDao().insertAll(listOf(
            ItemTagEntity(id = 0, itemId = "i1", tag = "shopping", createdAt = 1L)
        ))
        database.recordingTagDao().insertAll(listOf(
            RecordingTagEntity(id = 0, recordingId = "r1", tag = "alice", tagType = "person")
        ))

        // Export.
        val out = ByteArrayOutputStream()
        exportUseCase.export(out)
        val bytes = out.toByteArray()
        assertTrue("export produced bytes", bytes.isNotEmpty())

        // Wipe (CASCADE handles items, item_tags, recording_tags).
        database.categoryDao().deleteAll()
        database.recordingDao().deleteAll()
        assertEquals(0, database.categoryDao().getAllSnapshot().size)
        assertEquals(0, database.itemDao().getAllSnapshot().size)
        assertEquals(0, database.recordingDao().getAllSnapshot().size)
        assertEquals(0, database.itemTagDao().getAllSnapshot().size)
        assertEquals(0, database.recordingTagDao().getAllSnapshot().size)

        // Restore.
        runRestore(bytes, RestoreMode.REPLACE)

        // Verify every row came back.
        assertEquals(listOf(originalCat), database.categoryDao().getAllSnapshot())
        assertEquals(listOf(originalItem), database.itemDao().getAllSnapshot())
        assertEquals(listOf(originalRec), database.recordingDao().getAllSnapshot())

        val itemTags = database.itemTagDao().getAllSnapshot()
        assertEquals(1, itemTags.size)
        assertEquals("i1", itemTags[0].itemId)
        assertEquals("shopping", itemTags[0].tag)

        val recTags = database.recordingTagDao().getAllSnapshot()
        assertEquals(1, recTags.size)
        assertEquals("r1", recTags[0].recordingId)
        assertEquals("alice", recTags[0].tag)
    }

    @Test
    fun mergeModePreservesLocalRowsOnIdCollision() = runBlocking {
        database.categoryDao().insertAll(listOf(cat()))
        // Local has item "X" with text "local".
        database.itemDao().insertAll(listOf(item("X", text = "local")))

        // Backup contains item "X" with different text.
        val bytes = encodeBundle(
            categories = listOf(cat().toBackup()),
            items = listOf(item("X", text = "imported").toBackup()),
        )

        runRestore(bytes, RestoreMode.MERGE)

        val items = database.itemDao().getAllSnapshot()
        assertEquals(1, items.size)
        // MERGE must NOT clobber local — text stays "local".
        assertEquals("local", items[0].text)
    }

    @Test
    fun mergeAddsNewRowsWithoutClobberingLocal() = runBlocking {
        database.categoryDao().insertAll(listOf(cat()))
        // Local items 1-5.
        val locals = (1..5).map { item("i$it", text = "local-$it") }
        database.itemDao().insertAll(locals)

        // Imported items 4-8 (overlap on 4, 5).
        val imported = (4..8).map { item("i$it", text = "imported-$it") }
        val bytes = encodeBundle(
            categories = listOf(cat().toBackup()),
            items = imported.map { it.toBackup() },
        )

        runRestore(bytes, RestoreMode.MERGE)

        val all = database.itemDao().getAllSnapshot().associateBy { it.id }
        assertEquals(8, all.size)
        // Local versions of 1..5 must survive untouched.
        for (i in 1..5) assertEquals("local-$i", all["i$i"]?.text)
        // 6..8 must be the imported ones.
        for (i in 6..8) assertEquals("imported-$i", all["i$i"]?.text)
    }

    @Test
    fun replaceModeWipesAndRestores() = runBlocking {
        database.categoryDao().insertAll(listOf(cat()))
        val locals = (1..5).map { item("local$it") }
        database.itemDao().insertAll(locals)

        // Backup carries 3 items with completely different IDs.
        val imported = (1..3).map { item("imp$it") }
        val bytes = encodeBundle(
            categories = listOf(cat().toBackup()),
            items = imported.map { it.toBackup() },
        )

        runRestore(bytes, RestoreMode.REPLACE)

        val ids = database.itemDao().getAllSnapshot().map { it.id }.sorted()
        assertEquals(listOf("imp1", "imp2", "imp3"), ids)
    }

    @Test
    fun recordingTagAutoIdResetAvoidsCollision() = runBlocking {
        database.recordingDao().insertAll(listOf(recording("r1")))
        // Three local recording_tags. SQLite assigns autogen ids 1, 2, 3.
        database.recordingTagDao().insertAll(listOf(
            RecordingTagEntity(id = 0, recordingId = "r1", tag = "local-1", tagType = "person"),
            RecordingTagEntity(id = 0, recordingId = "r1", tag = "local-2", tagType = "person"),
            RecordingTagEntity(id = 0, recordingId = "r1", tag = "local-3", tagType = "person"),
        ))
        assertEquals(3, database.recordingTagDao().getAllSnapshot().size)

        // Backup carries 3 different tags but with original ids 1, 2, 3 —
        // would collide on the primary key without the id=0 reset.
        val bytes = encodeBundle(
            recordings = listOf(recording("r1").toBackup()),
            recordingTags = listOf(
                BackupRecordingTag(id = 1L, recordingId = "r1", tag = "imported-1", tagType = "person"),
                BackupRecordingTag(id = 2L, recordingId = "r1", tag = "imported-2", tagType = "person"),
                BackupRecordingTag(id = 3L, recordingId = "r1", tag = "imported-3", tagType = "person"),
            ),
        )

        // Should not throw — id=0 reset triggers fresh autogen.
        runRestore(bytes, RestoreMode.MERGE)

        val tags = database.recordingTagDao().getAllSnapshot()
        // All 6 tags present (3 local + 3 imported, no tuple overlap).
        assertEquals(6, tags.size)
        // All ids unique.
        assertEquals(6, tags.map { it.id }.toSet().size)
    }
}
