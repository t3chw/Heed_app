package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "llm_upgrade_queue")
data class LlmUpgradeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val recordingId: String,
    val rawText: String,
    val status: String = "PENDING",
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null
)
