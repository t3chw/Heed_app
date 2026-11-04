package com.bol.app.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.bol.app.data.local.dao.PerformanceLogDao
import com.bol.app.data.local.entity.PerformanceLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
enum class LogCategory { APP, MODEL, STT, PARSER, QUEUE, SEARCH, UPGRADE, MEMORY }

object BolLogger {

    private var dao: PerformanceLogDao? = null
    private var monitor: PerformanceMonitor? = null
    private var processName: String = "main"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { encodeDefaults = true }

    /**
     * What a log line may say about the user's own words (a transcript, a task, a search): the text in debug builds,
     * only its length in release. The log is kept in the app and can be exported from the log viewer, and someone
     * sending it with a bug report should not be sending their notes.
     */
    fun noteText(text: String, max: Int): String =
        if (com.bol.app.BuildConfig.DEBUG) text.take(max) else "<${text.length} chars>"

    fun init(dao: PerformanceLogDao, monitor: PerformanceMonitor, context: Context) {
        this.dao = dao
        this.monitor = monitor
        this.processName = resolveProcessName(context)

        scope.launch { trim(dao) }
    }

    /** Applies the 7-day / [MAX_ENTRIES] caps. */
    private suspend fun trim(dao: PerformanceLogDao) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            dao.deleteOlderThan(cutoff)
            val count = dao.getCount()
            if (count > MAX_ENTRIES) {
                dao.deleteOldest(MAX_ENTRIES)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Log cleanup failed", e)
        }
    }

    /** Writes since the last trim; the caps were only applied at start, and the process can live for days. */
    private val writesSinceTrim = java.util.concurrent.atomic.AtomicInteger(0)

    fun log(
        level: LogLevel,
        category: LogCategory,
        event: String,
        message: String = "",
        durationMs: Long? = null,
        extras: Map<String, String>? = null
    ) {
        writeEntry(level, category, event, message, durationMs, extras, metricsSnapshot = null)
    }

    fun logWithMetrics(
        level: LogLevel,
        category: LogCategory,
        event: String,
        message: String = "",
        durationMs: Long? = null,
        extras: Map<String, String>? = null
    ) {
        val snapshot = try { monitor?.capture() } catch (_: Exception) { null }
        writeEntry(level, category, event, message, durationMs, extras, snapshot)
    }

    inline fun <T> timed(
        level: LogLevel,
        category: LogCategory,
        event: String,
        message: String = "",
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - start
            logWithMetrics(level, category, event, message, durationMs = duration)
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            logWithMetrics(LogLevel.ERROR, category, "${event}_failed",
                "${e.javaClass.simpleName}: ${e.message?.take(200)}", durationMs = duration)
            throw e
        }
    }

    private fun writeEntry(
        level: LogLevel,
        category: LogCategory,
        event: String,
        message: String,
        durationMs: Long?,
        extras: Map<String, String>?,
        metricsSnapshot: MetricsSnapshot?
    ) {
        val d = dao ?: return

        val entry = PerformanceLogEntity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            level = level.name,
            category = category.name,
            event = event,
            message = message.take(500),
            durationMs = durationMs,
            metricsJson = metricsSnapshot?.let { json.encodeToString(it) },
            extrasJson = extras?.let { json.encodeToString(it) },
            processName = processName
        )

        scope.launch {
            try {
                d.insert(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log: $event", e)
            }
            if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                writesSinceTrim.set(0)
                trim(d)
            }
        }
    }

    private fun resolveProcessName(context: Context): String {
        return try {
            val pid = Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val proc = am.runningAppProcesses?.find { it.pid == pid }
            when {
                proc == null -> "unknown"
                proc.processName.endsWith(":ai") -> ":ai"
                else -> "main"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private const val TAG = "BolLogger"
    private const val MAX_ENTRIES = 5000
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val TRIM_EVERY = 500
}
