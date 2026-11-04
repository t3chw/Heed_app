package com.bol.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {

    private fun makeBundle(
        categories: List<BackupCategory> = emptyList(),
        items: List<BackupItem> = emptyList(),
        recordings: List<BackupRecording> = emptyList(),
        itemTags: List<BackupItemTag> = emptyList(),
        recordingTags: List<BackupRecordingTag> = emptyList(),
        itemEnrichments: List<BackupItemEnrichment> = emptyList(),
        searchQueries: List<BackupSearchQuery> = emptyList(),
        userContext: List<BackupUserContext> = emptyList(),
    ) = BackupBundle(
        data = BackupData(
            categories = categories,
            items = items,
            recordings = recordings,
            itemTags = itemTags,
            recordingTags = recordingTags,
            itemEnrichments = itemEnrichments,
            searchQueries = searchQueries,
            userContext = userContext,
        ),
    )

    private val cat = BackupCategory(
        id = "tasks", name = "Tasks", icon = "✓", color = "#abc",
        sortOrder = 0, isDefault = true,
    )

    private val item = BackupItem(
        id = "i1", categoryId = "tasks", text = "buy milk",
        createdAt = 1L, updatedAt = 1L,
    )

    private val recording = BackupRecording(
        id = "r1", rawText = "buy milk", parsedItemCount = 1, createdAt = 1L,
    )

    @Test
    fun emptyBundleHasNoIssues() {
        assertEquals(emptyList<ValidationIssue>(), BackupValidator.validate(makeBundle()))
    }

    @Test
    fun consistentBundleHasNoIssues() {
        val bundle = makeBundle(
            categories = listOf(cat),
            items = listOf(item),
            recordings = listOf(recording),
            itemTags = listOf(BackupItemTag(id = 1L, itemId = "i1", tag = "shopping", createdAt = 1L)),
            recordingTags = listOf(BackupRecordingTag(id = 1L, recordingId = "r1", tag = "alice", tagType = "person")),
            itemEnrichments = listOf(BackupItemEnrichment(itemId = "i1", searchContext = "milk grocery", createdAt = 1L)),
        )
        assertEquals(emptyList<ValidationIssue>(), BackupValidator.validate(bundle))
    }

    @Test
    fun itemReferencingMissingCategoryIsFlagged() {
        val bundle = makeBundle(items = listOf(item)) // no categories!
        val issues = BackupValidator.validate(bundle)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is ValidationIssue.MissingCategory)
        assertEquals("tasks", (issues[0] as ValidationIssue.MissingCategory).categoryId)
        assertTrue(BackupValidator.hasFatalIssue(issues))
    }

    @Test
    fun tagReferencingMissingItemIsFlagged() {
        val bundle = makeBundle(
            categories = listOf(cat),
            itemTags = listOf(BackupItemTag(id = 1L, itemId = "missing", tag = "x", createdAt = 1L)),
        )
        val issues = BackupValidator.validate(bundle)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is ValidationIssue.MissingItemForTag)
        assertTrue(BackupValidator.hasFatalIssue(issues))
    }

    @Test
    fun enrichmentReferencingMissingItemIsFlagged() {
        val bundle = makeBundle(
            categories = listOf(cat),
            itemEnrichments = listOf(BackupItemEnrichment(itemId = "missing", searchContext = "x", createdAt = 1L)),
        )
        val issues = BackupValidator.validate(bundle)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is ValidationIssue.MissingItemForEnrichment)
    }

    @Test
    fun recordingTagReferencingMissingRecordingIsFlagged() {
        val bundle = makeBundle(
            recordingTags = listOf(BackupRecordingTag(id = 1L, recordingId = "missing", tag = "x", tagType = "person")),
        )
        val issues = BackupValidator.validate(bundle)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is ValidationIssue.MissingRecordingForTag)
    }

    @Test
    fun multipleViolationsAreAllReported() {
        val bundle = makeBundle(
            items = listOf(item, item.copy(id = "i2", categoryId = "also_missing")),
            itemTags = listOf(BackupItemTag(id = 1L, itemId = "ghost", tag = "x", createdAt = 1L)),
        )
        val issues = BackupValidator.validate(bundle)
        // 2 missing-category (no categories at all) + 1 missing-item-for-tag = 3
        assertEquals(3, issues.size)
        assertTrue(BackupValidator.hasFatalIssue(issues))
    }

    @Test
    fun manifestCountMismatchIsInformationalNotFatal() {
        val bundle = makeBundle(categories = listOf(cat))
        val manifest = BackupManifest(
            schemaVersion = 1, appVersionName = "1.0", appVersionCode = 1,
            exportedAt = 0L, exportedFromTimezone = "UTC",
            counts = BackupCounts(categories = 99), // claims 99, actual is 1
            dataChecksum = "sha256:dead",
        )
        val issues = BackupValidator.validate(bundle, manifest)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is ValidationIssue.CountMismatch)
        // CountMismatch alone is not fatal — Room will still accept the rows.
        assertFalse(BackupValidator.hasFatalIssue(issues))
    }

    @Test
    fun summarizeFitsThreeIssuesPlusCounter() {
        val bundle = makeBundle(
            items = (1..5).map { item.copy(id = "i$it", categoryId = "missing$it") },
        )
        val issues = BackupValidator.validate(bundle)
        assertEquals(5, issues.size)
        val summary = BackupValidator.summarize(issues, maxItems = 3)
        assertTrue(summary, summary.contains("(+2 more)"))
    }
}
