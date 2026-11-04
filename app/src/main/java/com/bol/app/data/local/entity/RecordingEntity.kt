package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val parsedItemCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * True when the user tapped the Stop button on the processing pill
     * after this recording was enqueued. Cancelled recordings stay in
     * History with a "Cancelled" label + Retry action; the WAV file is
     * intentionally preserved so a retry re-runs the full pipeline.
     */
    val cancelled: Boolean = false,
)
