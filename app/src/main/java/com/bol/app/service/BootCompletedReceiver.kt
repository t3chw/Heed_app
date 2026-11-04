package com.bol.app.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * `AlarmManager` alarms do NOT survive a device reboot. On BOOT_COMPLETED (and on
 * the post-secure-unlock LOCKED_BOOT_COMPLETED) we walk the items table for any
 * pending future reminder and re-arm each via [ReminderScheduler].
 *
 * We skip anything already completed, or anything whose reminder time is now in
 * the past — for past reminders the user has probably already moved on; firing
 * them late would feel buggy.
 *
 * Also handles SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED: on Android 14+
 * exact alarms start denied, so reminders created before the user allowed them
 * were scheduled inexact. Re-arming them (same PendingIntent → replaced) makes
 * them exact the moment the user grants "Alarms & reminders".
 */
class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {

    private val itemRepository: ItemRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val exactAlarmGranted = action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            !exactAlarmGranted
        ) return
        // The permission broadcast is only sent when it is granted, but check anyway.
        if (exactAlarmGranted && !ReminderScheduler.canScheduleExact(context)) return

        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val count = itemRepository.rescheduleReminders()
                Log.i(TAG, "Rescheduled $count reminders (${if (exactAlarmGranted) "exact alarms granted" else "after boot"})")
            } catch (e: Exception) {
                Log.e(TAG, "Boot reschedule failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReminders"
    }
}
