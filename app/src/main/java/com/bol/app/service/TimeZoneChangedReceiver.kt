package com.bol.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.ItemRepository
import com.bol.app.service.digest.DailyDigestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Re-anchors floating reminders when the device timezone changes.
 *
 * Heed follows the convention used by Apple Reminders, Google Tasks, Todoist,
 * and most personal-task apps: a reminder set as "tomorrow at 9 AM" should
 * fire at 9 AM wherever the user wakes up, not at 9 AM in the timezone where
 * the user happened to set it. This receiver implements that — when Android
 * broadcasts [Intent.ACTION_TIMEZONE_CHANGED], we walk pending reminders and
 * recompute their epoch-ms so the wall-clock interpretation is preserved in
 * the new timezone.
 *
 * Heuristic: only reminders firing >2h in the future get rebased. Anything
 * sooner is treated as elapsed-time intent (Snooze 1h, "in 30 min") and left
 * untouched. The 2h threshold is wide enough to cover all "tomorrow X" cases
 * and narrow enough to skip almost every snooze — getting the rare edge case
 * (long relative reminder spanning a fast TZ change) wrong is preferable to
 * surprising users by shifting their snoozed alarms.
 *
 * The daily-digest worker is also rescheduled so its next fire time is anchored
 * to the user's new local clock instead of the old one.
 */
class TimeZoneChangedReceiver : BroadcastReceiver(), KoinComponent {

    private val itemRepository: ItemRepository by inject()
    private val preferencesManager: PreferencesManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return

        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val newTzId = TimeZone.getDefault().id
                val oldTzId = preferencesManager.lastKnownTimeZoneId.first()

                // First run, or duplicate broadcast for the same TZ — nothing
                // to rebase. Just update the baseline so the next real change
                // has something to compare against.
                if (oldTzId.isEmpty() || oldTzId == newTzId) {
                    preferencesManager.setLastKnownTimeZoneId(newTzId)
                    return@launch
                }

                rebaseFloatingReminders(context, oldTzId, newTzId)

                // Daily digest fires at a wall-clock hour ("08:00"). Without
                // this re-schedule, the next fire would happen at the old
                // hour-of-day expressed in the new TZ — wrong. schedule() replaces
                // the booked run with one aimed at the new local clock.
                if (preferencesManager.digestEnabled.first()) {
                    DailyDigestScheduler.schedule(
                        context,
                        preferencesManager.digestTime.first(),
                    )
                }

                preferencesManager.setLastKnownTimeZoneId(newTzId)
            } catch (e: Exception) {
                Log.e(TAG, "TZ change handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun rebaseFloatingReminders(
        context: Context,
        oldTzId: String,
        newTzId: String,
    ) {
        val oldZone = try {
            ZoneId.of(oldTzId)
        } catch (e: Exception) {
            Log.w(TAG, "Old TZ id '$oldTzId' is invalid, skipping rebase", e)
            return
        }
        val newZone = try {
            ZoneId.of(newTzId)
        } catch (e: Exception) {
            Log.w(TAG, "New TZ id '$newTzId' is invalid, skipping rebase", e)
            return
        }
        if (oldZone == newZone) return

        val now = System.currentTimeMillis()
        val rebaseThreshold = now + REBASE_THRESHOLD_MS
        val reminders = itemRepository.getPendingReminders(now)
        var rebased = 0
        for (item in reminders) {
            val rt = item.reminderTime ?: continue
            if (rt <= rebaseThreshold) continue

            // Walk the wall-clock through the two zones: pull the local
            // datetime out of the old-TZ interpretation, then re-attach it to
            // the new TZ. ZonedDateTime's DST rules handle gaps and overlaps
            // (e.g. spring-forward gap → next valid local time).
            val localClock = Instant.ofEpochMilli(rt).atZone(oldZone).toLocalDateTime()
            val newMs = localClock.atZone(newZone).toInstant().toEpochMilli()

            if (newMs == rt) continue
            // Defensive: if the rebase math somehow produced a past time
            // (e.g. user crossed the date line and the wall-clock is now
            // already past), skip rather than try to fire instantly.
            if (newMs <= now) continue

            itemRepository.update(
                item.copy(
                    reminderTime = newMs,
                    updatedAt = now,
                )
            )
            ReminderScheduler.schedule(context, item.id, newMs)
            rebased++
        }
        Log.i(TAG, "Rebased $rebased reminders for TZ change $oldTzId -> $newTzId")
    }

    companion object {
        private const val TAG = "TimeZoneChanged"

        // Skip reminders firing within this window — they were almost
        // certainly set with elapsed-time intent (Snooze 1h, "in 30 min")
        // rather than wall-clock intent. 2 hours covers all reasonable
        // snoozes and short-relative cases while still catching every
        // "tomorrow X" / "Friday Y" type reminder.
        private const val REBASE_THRESHOLD_MS = 2L * 60L * 60L * 1000L
    }
}
