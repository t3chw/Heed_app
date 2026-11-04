package com.bol.app.monitoring

import kotlinx.serialization.Serializable

@Serializable
data class MetricsSnapshot(
    val javaHeapUsedMb: Float,
    val javaHeapMaxMb: Float,
    val nativeHeapMb: Float,
    val systemAvailRamMb: Float,
    val systemTotalRamMb: Float,
    val systemLowMemory: Boolean,
    val cpuUsagePercent: Float,
    val threadCount: Int,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val batteryTemperature: Float,
    val isCharging: Boolean
)
