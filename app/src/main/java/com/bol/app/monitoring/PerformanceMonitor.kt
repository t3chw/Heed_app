package com.bol.app.monitoring

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import java.io.File

class PerformanceMonitor(private val context: Context) {

    @Volatile private var prevCpuTime = 0L
    @Volatile private var prevUptime = 0L

    fun capture(): MetricsSnapshot {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).toFloat() / MB
        val javaMax = runtime.maxMemory().toFloat() / MB
        val nativeHeap = Debug.getNativeHeapAllocatedSize().toFloat() / MB

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val cpuPercent = readCpuUsage()
        val thermal = readThermalStatus()
        val (batteryPct, batteryTemp, charging) = readBattery()

        return MetricsSnapshot(
            javaHeapUsedMb = javaUsed,
            javaHeapMaxMb = javaMax,
            nativeHeapMb = nativeHeap,
            systemAvailRamMb = memInfo.availMem.toFloat() / MB,
            systemTotalRamMb = memInfo.totalMem.toFloat() / MB,
            systemLowMemory = memInfo.lowMemory,
            cpuUsagePercent = cpuPercent,
            threadCount = Thread.activeCount(),
            thermalStatus = thermal,
            batteryPercent = batteryPct,
            batteryTemperature = batteryTemp,
            isCharging = charging
        )
    }

    /**
     * Compute process CPU usage % from /proc/self/stat between two calls.
     * Returns 0 on first call or if reading fails.
     */
    private fun readCpuUsage(): Float {
        return try {
            val statLine = File("/proc/self/stat").readText()
            val parts = statLine.split(" ")
            // utime(13) + stime(14) in clock ticks
            val cpuTime = parts[13].toLong() + parts[14].toLong()
            val uptime = System.nanoTime()

            val percent = if (prevUptime > 0 && uptime > prevUptime) {
                val cpuDelta = cpuTime - prevCpuTime
                val timeDeltaMs = (uptime - prevUptime) / 1_000_000L
                if (timeDeltaMs > 0) (cpuDelta * 10_000f / timeDeltaMs).coerceIn(0f, 100f) else 0f
            } else {
                0f
            }
            prevCpuTime = cpuTime
            prevUptime = uptime
            percent
        } catch (_: Exception) {
            0f
        }
    }

    private fun readThermalStatus(): Int {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (_: Exception) {
            -1
        }
    }

    private fun readBattery(): Triple<Int, Float, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else -1
            val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            Triple(pct, temp, charging)
        } catch (_: Exception) {
            Triple(-1, 0f, false)
        }
    }

    companion object {
        private const val MB = 1024f * 1024f
    }
}
