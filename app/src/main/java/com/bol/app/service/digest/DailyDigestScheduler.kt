package com.bol.app.service.digest

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Arranges [DailyDigestWorker] to run at the user's configured time, one day at a time: each run books the next
 * ([scheduleNextFromWorker]), aimed at the next occurrence of the wall-clock time, so DST and time-zone changes land
 * on the right hour.
 *
 * This used to be a 24 h periodic request with a 30 min flex. WorkManager starts such a request's first run at
 * enqueue + initial delay + (24 h − flex), i.e. the next day 30 min early, and the UPDATE on every app start kept
 * the first enqueue time but swapped in a new delay, so until it first ran the digest moved with each app start.
 */
object DailyDigestScheduler {

    /** The old periodic request, cancelled wherever the new one is set. */
    private const val LEGACY_PERIODIC_NAME = "daily_digest"

    /** The digest time or zone changed (Settings, restore, time-zone change): replace what is booked. */
    fun schedule(context: Context, hhmm: String) = enqueue(context, hhmm, ExistingWorkPolicy.REPLACE)

    /** App start: book one if none is waiting, and never disturb one that is booked or running. */
    fun ensureScheduled(context: Context, hhmm: String) = enqueue(context, hhmm, ExistingWorkPolicy.KEEP)

    /**
     * From inside [DailyDigestWorker] ([runningId]): tomorrow's run, chained after this one (REPLACE would cancel the
     * running run). Skipped when a later run already waits: Android can run the same job again after a kill, and
     * each pass would otherwise append one more.
     */
    suspend fun scheduleNextFromWorker(context: Context, hhmm: String, runningId: java.util.UUID) {
        val infos = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(DailyDigestWorker.UNIQUE_NAME).get()
        }
        val waiting = infos.any { it.id != runningId && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) }
        if (!waiting) enqueue(context, hhmm, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DailyDigestWorker.UNIQUE_NAME)
        wm.cancelUniqueWork(LEGACY_PERIODIC_NAME)
    }

    private fun enqueue(context: Context, hhmm: String, policy: ExistingWorkPolicy) {
        val (hour, minute) = parseHhMm(hhmm)
        val request = OneTimeWorkRequestBuilder<DailyDigestWorker>()
            .setInitialDelay(millisUntilNext(hour, minute), TimeUnit.MILLISECONDS)
            .build()
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_PERIODIC_NAME)
        wm.enqueueUniqueWork(DailyDigestWorker.UNIQUE_NAME, policy, request)
    }

    // ── helpers ──

    private fun parseHhMm(hhmm: String): Pair<Int, Int> {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return h to m
    }

    private fun millisUntilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (Calendar.getInstance().clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
