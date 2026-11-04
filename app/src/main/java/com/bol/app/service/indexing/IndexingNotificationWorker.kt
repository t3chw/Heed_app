package com.bol.app.service.indexing

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bol.app.ai.indexing.IndexingCoordinator
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.service.NotificationHelper
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that nudges the user to open Bol when the indexing
 * backlog has grown past [PENDING_THRESHOLD] items. Cadence escalates so
 * we never spam: first notification fires 1 day after the threshold is
 * crossed, then 3 days, 7, 14, and finally capped at 30 days. Once the
 * pending count drops back at or below the threshold, the cadence
 * resets to zero so the next escalation starts fresh.
 *
 * Runs every 12 hours via WorkManager. Each run reads the live pending
 * count from [IndexingCoordinator.refreshPendingCount], compares the
 * elapsed time against the cadence schedule, and either fires a
 * notification or returns early. State is persisted in DataStore via
 * [PreferencesManager] so a process restart picks up where the last
 * cycle left off.
 */
class IndexingNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val coordinator: IndexingCoordinator by inject()
    private val prefs: PreferencesManager by inject()

    override suspend fun doWork(): Result {
        return try {
            // Synchronous DB read — pendingTotal is a stateIn'd Flow whose
            // initial value is 0 until the first upstream Room emission
            // lands. WorkManager can cold-start the app process; reading
            // .value too early would always see 0, silently skipping the
            // notification even when the backlog has grown past 100.
            val pending = coordinator.getPendingNow()
            val now = System.currentTimeMillis()

            if (pending <= PENDING_THRESHOLD) {
                // Backlog cleared (or never crossed) — wipe the cadence
                // state so the next time it spikes, the cycle restarts.
                if (prefs.indexingNotifFirstSeenAt.first() != 0L ||
                    prefs.indexingNotifStep.first() != 0
                ) {
                    prefs.resetIndexingNotifState()
                    BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "indexing_notif_reset",
                        "Pending dropped to $pending (≤ $PENDING_THRESHOLD) — cadence reset")
                }
                return Result.success()
            }

            // pending > PENDING_THRESHOLD — escalation cadence applies.
            val firstSeenAt = prefs.indexingNotifFirstSeenAt.first()
            if (firstSeenAt == 0L) {
                // First time we've crossed the threshold this cycle.
                // Don't fire yet — wait one full schedule slot first.
                prefs.setIndexingNotifFirstSeenAt(now)
                return Result.success()
            }

            val lastAt = prefs.indexingNotifLastAt.first()
            val anchor = if (lastAt > 0L) lastAt else firstSeenAt
            val step = prefs.indexingNotifStep.first()
            val gapDays = CADENCE_DAYS[step.coerceAtMost(CADENCE_DAYS.lastIndex)]
            val gapMs = gapDays * 24L * 60 * 60 * 1000

            if (now - anchor < gapMs) return Result.success()

            // Time to fire.
            val mgr = ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)
                ?: return Result.success()
            val notif = NotificationHelper.buildIndexingBacklogNotification(applicationContext, pending)
            mgr.notify(NotificationHelper.INDEXING_BACKLOG_NOTIFICATION_ID, notif.build())

            prefs.recordIndexingNotifFired(now = now, nextStep = step + 1)
            BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "indexing_notif_fired",
                "Fired indexing backlog notification — $pending pending, step ${step + 1}, next gap ${CADENCE_DAYS.getOrNull(step + 1) ?: CADENCE_DAYS.last()}d")
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "indexing_notif_worker_failed",
                "Indexing-backlog worker threw — ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            Result.success()  // Don't retry — the next 12h tick will try again
        }
    }

    companion object {
        private const val UNIQUE_NAME = "indexing_backlog_notification"
        const val PENDING_THRESHOLD = 100

        /** Days between consecutive notifications. After [CADENCE_DAYS.last]
         *  the cadence holds steady at 30 days so the user is reminded at
         *  most once a month if the backlog stays elevated. */
        private val CADENCE_DAYS = listOf(1, 3, 7, 14, 30)

        /** Schedule the periodic worker. Idempotent — ExistingPeriodicWorkPolicy.UPDATE
         *  refreshes the schedule without losing the existing run history. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexingNotificationWorker>(
                12, TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
