package com.bol.app.data.backup

import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEnrichmentEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.ItemTagEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.RecordingTagEntity
import com.bol.app.data.local.entity.SearchQueryEntity
import com.bol.app.data.local.entity.UserContextEntity
import kotlinx.serialization.Serializable

/**
 * Versioned wire format for Heed's backup files.
 *
 * Every column from each persisted user-data table is round-tripped here.
 *
 * Skipped on purpose:
 *  - `item_embeddings` — regenerable from text + model; large; tied to a
 *    specific embedding-model version that may not match on restore.
 *  - `voice_queue` / `llm_upgrade_queue` — transient pending work; restoring
 *    them on a different device would mis-attribute work to processes that
 *    aren't running.
 *  - `performance_logs` / `eval_queries` — dev/diagnostics, not user data.
 *
 * Forward / backward compatibility: every list defaults to empty and every
 * non-required field defaults to null/false/etc., so a v1 reader can ingest
 * a v2 file's missing-newer-fields case (after we strip unknowns) and a v2
 * reader can ingest a v1 file's missing-future-fields case.
 */
@Serializable
data class BackupBundle(
    val data: BackupData = BackupData(),
    val prefs: BackupPrefs = BackupPrefs(),
)

@Serializable
data class BackupData(
    val categories: List<BackupCategory> = emptyList(),
    val items: List<BackupItem> = emptyList(),
    val recordings: List<BackupRecording> = emptyList(),
    val itemTags: List<BackupItemTag> = emptyList(),
    val recordingTags: List<BackupRecordingTag> = emptyList(),
    val itemEnrichments: List<BackupItemEnrichment> = emptyList(),
    val searchQueries: List<BackupSearchQuery> = emptyList(),
    val userContext: List<BackupUserContext> = emptyList(),
)

@Serializable
data class BackupCategory(
    val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val isActive: Boolean = true,
    val description: String = "",
)

@Serializable
data class BackupItem(
    val id: String,
    val categoryId: String,
    val text: String,
    val isCompleted: Boolean = false,
    val isProvisional: Boolean = false,
    val reminderTime: Long? = null,
    val sourceRecordingId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupRecording(
    val id: String,
    val rawText: String,
    val parsedItemCount: Int,
    val createdAt: Long,
    val cancelled: Boolean = false,
)

@Serializable
data class BackupItemTag(
    val id: Long,
    val itemId: String,
    val tag: String,
    val createdAt: Long,
)

@Serializable
data class BackupRecordingTag(
    val id: Long,
    val recordingId: String,
    val tag: String,
    val tagType: String = "person",
)

@Serializable
data class BackupItemEnrichment(
    val itemId: String,
    val searchContext: String,
    val createdAt: Long,
)

@Serializable
data class BackupSearchQuery(
    val id: String,
    val query: String,
    val answer: String? = null,
    val sourcesJson: String? = null,
    val status: String,
    val errorMessage: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val rankedItemIdsJson: String? = null,
    val llmPickedCount: Int? = null,
    val stage: String? = null,
    val stageDetail: String? = null,
    val streamingAnswer: String? = null,
    val cancelRequested: Boolean = false,
)

@Serializable
data class BackupUserContext(
    val key: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * DataStore preferences that round-trip across export/import.
 *
 * All fields are nullable — null means "this key wasn't in the file"
 * (older format) or "the user never set it" (default applies). On
 * restore, only non-null fields are written, so missing keys keep their
 * current local default rather than being clobbered.
 *
 * Intentionally NOT included:
 *  - Backend benchmark results (per-device — restoring them on another
 *    phone would lock the wrong GPU/CPU choice).
 *  - Indexing-notification cadence state (transient).
 */
@Serializable
data class BackupPrefs(
    val hasCompletedOnboarding: Boolean? = null,
    val themeMode: String? = null,
    val language: String? = null,
    val sttEngine: String? = null,
    val llmBackend: String? = null,
    val selectedModelId: String? = null,
    val timeFormat: String? = null,
    val digestEnabled: Boolean? = null,
    val digestTime: String? = null,
    val notifyOnWorkDone: Boolean? = null,
    val processingHintShownCount: Int? = null,
    val llmTopK: Int? = null,
    val llmTopP: Float? = null,
    val llmTemperature: Float? = null,
    val llmMaxTokens: Int? = null,
    val defaultReminderHour: Int? = null,
    val hasShownMicRationale: Boolean? = null,
    val hasShownNotifRationale: Boolean? = null,
    val hideCompletedItems: Boolean? = null,
    val voiceEntryCount: Int? = null,
    val hasShownTypeHint: Boolean? = null,
    val hasShownAssistantHint: Boolean? = null,
    val hasShownBatteryHint: Boolean? = null,
    val devModeEnabled: Boolean? = null,
    val lastKnownTimeZoneId: String? = null,
)

@Serializable
data class BackupManifest(
    val schemaVersion: Int,
    val appVersionName: String,
    val appVersionCode: Int,
    val exportedAt: Long,
    val exportedFromTimezone: String,
    val encrypted: Boolean = false,
    val kdf: String? = null,
    val kdfSalt: String? = null,
    val cipher: String? = null,
    val iv: String? = null,
    val counts: BackupCounts = BackupCounts(),
    val dataChecksum: String,
)

@Serializable
data class BackupCounts(
    val categories: Int = 0,
    val items: Int = 0,
    val recordings: Int = 0,
    val itemTags: Int = 0,
    val recordingTags: Int = 0,
    val itemEnrichments: Int = 0,
    val searchQueries: Int = 0,
    val userContext: Int = 0,
)

// ── Entity ↔ backup mappers ─────────────────────────────────────
//
// Enumerated field-by-field on purpose: when an entity gains or loses a
// field, the compiler points at the affected mapper so we can't silently
// drop user data.

fun CategoryEntity.toBackup(): BackupCategory =
    BackupCategory(id, name, icon, color, sortOrder, isDefault, isActive, description)

fun BackupCategory.toEntity(): CategoryEntity =
    CategoryEntity(id, name, icon, color, sortOrder, isDefault, isActive, description)

fun ItemEntity.toBackup(): BackupItem =
    BackupItem(id, categoryId, text, isCompleted, isProvisional, reminderTime, sourceRecordingId, createdAt, updatedAt)

fun BackupItem.toEntity(): ItemEntity =
    ItemEntity(id, categoryId, text, isCompleted, isProvisional, reminderTime, sourceRecordingId, createdAt, updatedAt)

fun RecordingEntity.toBackup(): BackupRecording =
    BackupRecording(id, rawText, parsedItemCount, createdAt, cancelled)

fun BackupRecording.toEntity(): RecordingEntity =
    RecordingEntity(id, rawText, parsedItemCount, createdAt, cancelled)

fun ItemTagEntity.toBackup(): BackupItemTag =
    BackupItemTag(id, itemId, tag, createdAt)

fun BackupItemTag.toEntity(): ItemTagEntity =
    ItemTagEntity(id, itemId, tag, createdAt)

fun RecordingTagEntity.toBackup(): BackupRecordingTag =
    BackupRecordingTag(id, recordingId, tag, tagType)

fun BackupRecordingTag.toEntity(): RecordingTagEntity =
    RecordingTagEntity(id, recordingId, tag, tagType)

fun ItemEnrichmentEntity.toBackup(): BackupItemEnrichment =
    BackupItemEnrichment(itemId, searchContext, createdAt)

fun BackupItemEnrichment.toEntity(): ItemEnrichmentEntity =
    ItemEnrichmentEntity(itemId, searchContext, createdAt)

fun SearchQueryEntity.toBackup(): BackupSearchQuery = BackupSearchQuery(
    id = id,
    query = query,
    answer = answer,
    sourcesJson = sourcesJson,
    status = status,
    errorMessage = errorMessage,
    createdAt = createdAt,
    completedAt = completedAt,
    rankedItemIdsJson = rankedItemIdsJson,
    llmPickedCount = llmPickedCount,
    stage = stage,
    stageDetail = stageDetail,
    streamingAnswer = streamingAnswer,
    cancelRequested = cancelRequested,
)

fun BackupSearchQuery.toEntity(): SearchQueryEntity = SearchQueryEntity(
    id = id,
    query = query,
    answer = answer,
    sourcesJson = sourcesJson,
    status = status,
    errorMessage = errorMessage,
    createdAt = createdAt,
    completedAt = completedAt,
    rankedItemIdsJson = rankedItemIdsJson,
    llmPickedCount = llmPickedCount,
    stage = stage,
    stageDetail = stageDetail,
    streamingAnswer = streamingAnswer,
    cancelRequested = cancelRequested,
)

fun UserContextEntity.toBackup(): BackupUserContext =
    BackupUserContext(key, value, updatedAt)

fun BackupUserContext.toEntity(): UserContextEntity =
    UserContextEntity(key, value, updatedAt)
