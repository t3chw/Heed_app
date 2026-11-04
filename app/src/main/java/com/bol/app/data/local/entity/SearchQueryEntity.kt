package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "search_queries")
data class SearchQueryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val query: String,
    val answer: String? = null,
    val sourcesJson: String? = null,
    val status: String = SearchQueryStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /**
     * JSON array of item IDs in final ranked order (the model rerank picks first,
     * RRF tail appended). Populated from v16; nullable so older rows keep
     * rendering with just the answer + recording sources.
     */
    val rankedItemIdsJson: String? = null,
    /**
     * How many of the leading [rankedItemIdsJson] entries are LLM-picked
     * (rerank survivors). The rest are the RRF fallback tail. Null when
     * rerank was skipped (< 4 candidates, reminder intent, or timeout) —
     * the UI then shows the list flat without the "+ N more" disclosure.
     */
    val llmPickedCount: Int? = null,
    /**
     * Current stage of the search pipeline so the Detail screen can render
     * a "what's happening right now" checklist. Values are from
     * [SearchStage]. Null once the row reaches a terminal status.
     */
    val stage: String? = null,
    /** Optional sub-text for the current stage (e.g. "Batch 2 of 4"). */
    val stageDetail: String? = null,
    /**
     * Partial answer text streamed from the synthesis the model call. Written
     * at ~5 Hz while tokens arrive so the UI can show the answer filling
     * in word-by-word. Cleared once the final `answer` column is set.
     */
    val streamingAnswer: String? = null,
    /**
     * Cross-process cancellation flag. The UI sets this to true on a Stop
     * tap; SearchProcessingUseCase polls it at each pipeline boundary and
     * aborts when it flips. Stored as Boolean-in-INTEGER by Room.
     */
    val cancelRequested: Boolean = false,
)

object SearchQueryStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    /** User tapped Stop while the row was PENDING/PROCESSING. */
    const val CANCELLED = "CANCELLED"
}

/**
 * Pipeline stages the SearchDetailScreen renders as a live checklist.
 * Values are persisted as strings so the DB stays schema-free of enums.
 */
object SearchStage {
    const val TRANSCRIBE = "TRANSCRIBE"
    const val RETRIEVE = "RETRIEVE"
    const val RERANK = "RERANK"
    const val SYNTHESIZE = "SYNTHESIZE"
}
