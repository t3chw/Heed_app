package com.bol.app.service.digest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.service.NotificationHelper
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

/**
 * Fires once per day (scheduled via [DailyDigestScheduler]) to post a morning
 * summary notification: reminders due today, open items by category, recent
 * recording count.
 *
 * Runs entirely on-device. Skips posting when:
 *  - the user has disabled the digest,
 *  - the summary would be empty (no reminders, no open items in any category,
 *    zero activity yesterday) — no point nagging on a truly empty day,
 *  - notification permission hasn't been granted.
 */
class DailyDigestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val itemRepository: ItemRepository by inject()
    private val categoryRepository: CategoryRepository by inject()
    private val preferencesManager: PreferencesManager by inject()

    override suspend fun doWork(): Result {
        try {
            if (!preferencesManager.digestEnabled.first()) {
                Log.i(TAG, "Digest disabled — skipping")
                return Result.success()
            }
            // Book tomorrow's run first, so a skipped day (empty, no permission) or a crash below never ends the chain.
            DailyDigestScheduler.scheduleNextFromWorker(applicationContext, preferencesManager.digestTime.first(), id)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted — silent success")
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val endOfToday = endOfToday(now)
            val remindersToday = itemRepository.getRemindersBetween(now, endOfToday)
            val counts = itemRepository.countsByCategory()
            val categories = categoryRepository.getAllActiveSnapshot()
            val categoryNames = categories.associate { it.id to it.name }

            val summary = DigestSummary.build(remindersToday, counts, categoryNames)
            if (summary.isEmpty) {
                Log.i(TAG, "Empty day — no digest posted")
                return Result.success()
            }

            // The digest time is the user's choice (it can be 21:00), so the greeting follows the clock.
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val notification = NotificationHelper.buildDigestNotification(
                context = applicationContext,
                title = when {
                    hour in 5..11 -> "Good morning"
                    hour in 12..16 -> "Good afternoon"
                    else -> "Good evening"
                },
                body = summary.shortLine,
                expandedBody = summary.expandedBody,
            ).build()
            NotificationManagerCompat.from(applicationContext)
                .notify(NotificationHelper.DIGEST_NOTIFICATION_ID, notification)
            return Result.success()
        } catch (e: Exception) {
            // Success, not retry: tomorrow's run is chained after this one, and a retried run would book it twice
            // (a failed run would cancel it). One missed digest is the cost; the app start books again if needed.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Digest work failed", e)
            return Result.success()
        }
    }

    private fun endOfToday(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    companion object {
        private const val TAG = "DailyDigestWorker"
        /** One run at a time, each booking the next (see [DailyDigestScheduler]); "daily_digest" was the old periodic one. */
        const val UNIQUE_NAME = "daily_digest_next"
    }
}
