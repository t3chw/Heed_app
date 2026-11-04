package com.bol.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bol.app.MainActivity
import com.bol.app.R

object NotificationHelper {

    const val CHANNEL_PROCESSING = "bol_processing"
    const val CHANNEL_PROCESSING_QUIET = "bol_processing_quiet"
    const val CHANNEL_RESULTS = "bol_results"
    const val CHANNEL_REMINDERS = "bol_reminders"
    const val CHANNEL_DIGEST = "bol_daily_digest"
    const val CHANNEL_INDEXING = "bol_indexing"
    const val PROCESSING_NOTIFICATION_ID = 2001
    const val RESULTS_NOTIFICATION_ID = 2002
    const val DIGEST_NOTIFICATION_ID = 2006
    const val INDEXING_BACKLOG_NOTIFICATION_ID = 2007

    /** The brand green (light / dark from values / values-night) as every notification's accent. */
    private fun accent(context: Context): Int = androidx.core.content.ContextCompat.getColor(context, R.color.heed_brand)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val processingChannel = NotificationChannel(
            CHANNEL_PROCESSING,
            "Processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Heed organizes your voice input"
            setShowBadge(false)
        }

        // Quiet variant used when "Notify when work is done" is OFF. A
        // foreground-service notification is legally required (Android 8+),
        // but IMPORTANCE_MIN renders it as a tiny collapsed row at the bottom
        // of the shade with no status-bar icon — the closest we can get to
        // "no notification at all" without breaking the foreground-service
        // contract.
        val processingQuietChannel = NotificationChannel(
            CHANNEL_PROCESSING_QUIET,
            "Background work (quiet)",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Silent indicator while Heed works in the background"
            setShowBadge(false)
        }

        val resultsChannel = NotificationChannel(
            CHANNEL_RESULTS,
            "Results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shown when Heed finishes organizing your items"
        }


        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDERS,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications fired at the time you set on an item"
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }

        val digestChannel = NotificationChannel(
            CHANNEL_DIGEST,
            "Daily digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Morning summary of what's due and what's new"
            setShowBadge(false)
        }

        val indexingChannel = NotificationChannel(
            CHANNEL_INDEXING,
            "Indexing reminders",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Occasional reminder to open Heed and let the search index catch up"
            setShowBadge(false)
        }

        manager.createNotificationChannel(processingChannel)
        manager.createNotificationChannel(processingQuietChannel)
        manager.createNotificationChannel(resultsChannel)
        manager.createNotificationChannel(reminderChannel)
        manager.createNotificationChannel(digestChannel)
        manager.createNotificationChannel(indexingChannel)
    }

    /**
     * Whether a reminder fired by [ReminderReceiver] would actually surface
     * to the user. Three layers can suppress it:
     *  1. App-level notifications turned off (or POST_NOTIFICATIONS denied)
     *  2. The Reminders channel itself muted (importance == NONE)
     *  3. Channel deleted (rare; defensive null check)
     *
     * Used by Home banner + reminder-edit pre-schedule check to warn the user
     * that any reminder they set will silently die at fire time.
     */
    fun areReminderNotificationsEnabled(context: Context): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(CHANNEL_REMINDERS) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Build an Intent that opens the Reminders channel page directly (Android
     * 8+) so the user can re-enable it in one tap. Falls back to the app's
     * notification settings if the channel intent isn't supported (older
     * Android, OEM stripped the action).
     */
    fun reminderChannelSettingsIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_REMINDERS)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun buildIndexingBacklogNotification(
        context: Context,
        pendingCount: Int,
    ): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Rough projection: ~3s/item on a tier-2 device, capped at 5 min in
        // the user-facing copy so we don't scare them with "12 minutes" on
        // very large backlogs. Their backlog still indexes in chunks
        // anyway — they don't have to keep the app open the whole time.
        val approxMin = ((pendingCount * 3) / 60).coerceIn(1, 5)
        return NotificationCompat.Builder(context, CHANNEL_INDEXING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle("Heed search index needs $pendingCount item${if (pendingCount == 1) "" else "s"}")
            .setContentText("Open Heed for ~$approxMin min to catch up — improves search.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Heed's on-device search index is behind by $pendingCount item${if (pendingCount == 1) "" else "s"}. " +
                "Open the app for about $approxMin minute${if (approxMin == 1) "" else "s"} and tap Start on the indexing banner to catch up. " +
                "It runs only while the app is open and uses no data."
            ))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun buildProcessingNotification(
        context: Context,
        queueSize: Int,
        currentText: String = "",
        quiet: Boolean = false,
    ): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context, 0,
            Intent(context, BolProcessingService::class.java).apply {
                action = BolProcessingService.ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Quiet path: minimal title, no description, no progress spinner —
        // pinned to CHANNEL_PROCESSING_QUIET (IMPORTANCE_MIN). The user opted
        // out of work-done pings, so we render the legally-required fg
        // notification as discreetly as possible.
        if (quiet) {
            return NotificationCompat.Builder(context, CHANNEL_PROCESSING_QUIET)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent(context))
                .setContentTitle("Heed")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(openIntent)
                .addAction(0, "Cancel", cancelIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val title = when {
            currentText.isNotBlank() -> currentText.take(50)
            queueSize == 1 -> "Organizing your thoughts..."
            queueSize > 1 -> "Organizing $queueSize items..."
            else -> "Processing in background"
        }
        val text = if (currentText.isNotBlank()) "Processing in background" else ""

        return NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openIntent)
            .addAction(0, "Cancel", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    fun buildResultsNotification(
        context: Context,
        summary: String,
        totalItems: Int
    ): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle("Organized $totalItems items")
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
    }

    /**
     * Rich reminder notification with Done / Snooze / Open actions.
     * Body is clickable → opens the item in its category.
     */
    fun buildReminderNotification(
        context: Context,
        itemId: String,
        itemText: String,
        categoryId: String,
    ): NotificationCompat.Builder {
        // Body tap → MainActivity with deep-link extras.
        val openIntent = PendingIntent.getActivity(
            context,
            itemId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_item", itemId)
                putExtra("open_category", categoryId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // "Done" action → ReminderActionReceiver marks completed + cancels alarm.
        val doneIntent = PendingIntent.getBroadcast(
            context,
            ("${itemId}_done").hashCode(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DONE
                putExtra(ReminderActionReceiver.EXTRA_ITEM_ID, itemId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // "Snooze 1h" action → ReminderActionReceiver reschedules + cancels notification.
        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            ("${itemId}_snooze").hashCode(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_SNOOZE_1H
                putExtra(ReminderActionReceiver.EXTRA_ITEM_ID, itemId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle(itemText)
            .setContentText("Tap to open · swipe to dismiss")
            .setStyle(NotificationCompat.BigTextStyle().bigText(itemText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "Done", doneIntent)
            .addAction(0, "Snooze 1h", snoozeIntent)
    }

    /**
     * Morning digest — a one-line summary of today's reminders + recent additions.
     * Expanded body lists the breakdown.
     */
    fun buildDigestNotification(
        context: Context,
        title: String,
        body: String,
        expandedBody: String,
    ): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
    }

}
