package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "voice_queue")
data class VoiceQueueEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val status: String = QueueStatus.PENDING,
    val parsedItemCount: Int = 0,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val recordingId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null,
    /**
     * When true, the entry came from text (share-to-Bol) rather than audio, so
     * the processor should skip Whisper transcription and go straight to LLM
     * parsing. `rawText` is already populated with the final text.
     */
    val bypassStt: Boolean = false,
    /**
     * Set by the user's "Stop" tap on the processing pill. The pipeline polls
     * this at each stage boundary and bails out cleanly when it flips — the
     * recording row stays (cancelled=true in RecordingEntity) so it remains
     * retryable from History, but no items get committed to buckets.
     */
    val cancelRequested: Boolean = false,
)

object QueueStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
