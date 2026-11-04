package com.bol.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "performance_logs")
data class PerformanceLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val level: String,
    val category: String,
    val event: String,
    val message: String,
    val durationMs: Long?,
    val metricsJson: String?,
    val extrasJson: String?,
    val processName: String
)
