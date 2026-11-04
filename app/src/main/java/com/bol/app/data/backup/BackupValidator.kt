package com.bol.app.data.backup

/**
 * Pre-flight integrity check for a decoded [BackupBundle]. Runs in
 * memory before any DB write so the user gets a clear "this backup
 * is broken because …" message instead of a SQLException from the
 * middle of an insert.
 *
 * The codec already verifies the SHA-256 checksum and the schema
 * version — those are bytes-level checks. The validator handles the
 * semantic checks: foreign keys, count agreement, plausible values.
 *
 * Pure functions; no side effects, no DB.
 */
object BackupValidator {

    /**
     * Walk the bundle and return every integrity issue found. Empty
     * list means the bundle is safe to insert.
     *
     * If [manifest] is provided, the validator also verifies that the
     * counts the manifest claims match the actual list sizes — a
     * mismatch hints at file-tampering or a bug in some external tool
     * that hand-edited the JSON.
     */
    fun validate(bundle: BackupBundle, manifest: BackupManifest? = null): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Build lookup sets once — every FK walk reuses them.
        val categoryIds = bundle.data.categories.mapTo(HashSet()) { it.id }
        val itemIds = bundle.data.items.mapTo(HashSet()) { it.id }
        val recordingIds = bundle.data.recordings.mapTo(HashSet()) { it.id }

        // items.categoryId must exist in categories.
        for (item in bundle.data.items) {
            if (item.categoryId !in categoryIds) {
                issues += ValidationIssue.MissingCategory(item.id, item.categoryId)
            }
        }

        // item_tags.itemId must exist in items.
        for (tag in bundle.data.itemTags) {
            if (tag.itemId !in itemIds) {
                issues += ValidationIssue.MissingItemForTag(tag.id, tag.itemId)
            }
        }

        // item_enrichments.itemId must exist in items.
        for (enrichment in bundle.data.itemEnrichments) {
            if (enrichment.itemId !in itemIds) {
                issues += ValidationIssue.MissingItemForEnrichment(enrichment.itemId)
            }
        }

        // recording_tags.recordingId must exist in recordings.
        for (tag in bundle.data.recordingTags) {
            if (tag.recordingId !in recordingIds) {
                issues += ValidationIssue.MissingRecordingForTag(tag.id, tag.recordingId)
            }
        }

        // Optional: count agreement with the manifest. A mismatch
        // doesn't make the backup unsafe — it makes it suspicious.
        if (manifest != null) {
            checkCount(issues, "categories", manifest.counts.categories, bundle.data.categories.size)
            checkCount(issues, "items", manifest.counts.items, bundle.data.items.size)
            checkCount(issues, "recordings", manifest.counts.recordings, bundle.data.recordings.size)
            checkCount(issues, "itemTags", manifest.counts.itemTags, bundle.data.itemTags.size)
            checkCount(issues, "recordingTags", manifest.counts.recordingTags, bundle.data.recordingTags.size)
            checkCount(issues, "itemEnrichments", manifest.counts.itemEnrichments, bundle.data.itemEnrichments.size)
            checkCount(issues, "searchQueries", manifest.counts.searchQueries, bundle.data.searchQueries.size)
            checkCount(issues, "userContext", manifest.counts.userContext, bundle.data.userContext.size)
        }

        return issues
    }

    /**
     * True when the issues list contains anything that would make a
     * Room insert fail at runtime (FK violations). Count mismatches
     * don't qualify — they're informational.
     */
    fun hasFatalIssue(issues: List<ValidationIssue>): Boolean =
        issues.any { it !is ValidationIssue.CountMismatch }

    /** A short user-facing description of the first few issues, suitable
     *  for a Toast or a Failed progress message. */
    fun summarize(issues: List<ValidationIssue>, maxItems: Int = 3): String {
        if (issues.isEmpty()) return "no issues"
        val head = issues.take(maxItems).joinToString("; ") { describe(it) }
        val tail = if (issues.size > maxItems) " (+${issues.size - maxItems} more)" else ""
        return head + tail
    }

    private fun describe(issue: ValidationIssue): String = when (issue) {
        is ValidationIssue.MissingCategory ->
            "item ${issue.itemId.take(8)}… references unknown category ${issue.categoryId}"
        is ValidationIssue.MissingItemForTag ->
            "tag ${issue.tagId} references unknown item ${issue.itemId.take(8)}…"
        is ValidationIssue.MissingItemForEnrichment ->
            "enrichment for unknown item ${issue.itemId.take(8)}…"
        is ValidationIssue.MissingRecordingForTag ->
            "recording-tag ${issue.tagId} references unknown recording ${issue.recordingId.take(8)}…"
        is ValidationIssue.CountMismatch ->
            "${issue.table} count mismatch: manifest=${issue.claimed}, data=${issue.actual}"
    }

    private fun checkCount(issues: MutableList<ValidationIssue>, table: String, claimed: Int, actual: Int) {
        if (claimed != actual) {
            issues += ValidationIssue.CountMismatch(table, claimed, actual)
        }
    }
}

sealed class ValidationIssue {
    /** An item references a category that isn't in the bundle. */
    data class MissingCategory(val itemId: String, val categoryId: String) : ValidationIssue()

    /** An item-tag row references an item that isn't in the bundle. */
    data class MissingItemForTag(val tagId: Long, val itemId: String) : ValidationIssue()

    /** An enrichment row references an item that isn't in the bundle. */
    data class MissingItemForEnrichment(val itemId: String) : ValidationIssue()

    /** A recording-tag row references a recording that isn't in the bundle. */
    data class MissingRecordingForTag(val tagId: Long, val recordingId: String) : ValidationIssue()

    /** The manifest claims one count but the actual list size is different.
     *  Informational, not fatal — Room will still accept the rows. */
    data class CountMismatch(val table: String, val claimed: Int, val actual: Int) : ValidationIssue()
}
