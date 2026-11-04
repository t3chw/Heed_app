package com.bol.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Thin wrapper around `AlarmManager` for one-shot reminder alarms.
 *
 * The PendingIntent carries only the item id; [ReminderReceiver] re-fetches the
 * latest text/category from the database at fire time so edits made between
 * scheduling and firing are reflected in the notification.
 *
 * Request code is `itemId.hashCode()` — stable across schedule/cancel calls so
 * rescheduling the same item correctly replaces the prior alarm.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    fun schedule(
        context: Context,
        itemId: String,
        triggerAtMillis: Long,
    ) {
        if (triggerAtMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "Reminder time is in the past — skipping schedule for item $itemId")
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, itemId)

        try {
            // setExactAndAllowWhileIdle gives us doze-resistant delivery. Requires
            // SCHEDULE_EXACT_ALARM on Android 12+ (already declared in manifest, but
            // the user may still revoke it in settings — fall back to inexact below).
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked — fall back to inexact but still doze-safe.
            Log.w(TAG, "SCHEDULE_EXACT_ALARM denied, falling back to setAndAllowWhileIdle", e)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } catch (_: SecurityException) {
                // Even this can throw on very locked-down devices. Silently skip —
                // the item is still saved to DB, just without a firing alarm.
                Log.e(TAG, "All alarm scheduling failed for item $itemId")
            }
        }
    }

    fun cancel(context: Context, itemId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, itemId))
    }

    /**
     * Opens Android's "Alarms & reminders" page for this app, where the user
     * can allow exact alarms. Null below Android 12 (no such permission).
     * Android 14+ denies exact alarms by default for new installs, so without
     * this every reminder falls back to an inexact alarm that can fire late.
     */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /** `true` if we can schedule exact alarms on this device / OS. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun buildPendingIntent(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
