package com.bol.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bol.app.service.NotificationHelper
import com.bol.app.service.ReminderScheduler
import com.bol.app.ui.screens.home.HomeViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Warns the user when reminder notifications are blocked AND they've actually
 * scheduled at least one reminder. Visible only at the intersection of "real
 * risk" and "user cares" — quiet for users who don't use reminders at all.
 *
 * Why this exists: [com.bol.app.service.ReminderReceiver] silently `return`s
 * when POST_NOTIFICATIONS is denied, and `NotificationManagerCompat.notify` is
 * a no-op when the Reminders channel is muted — so a reminder set today can
 * fire next week and the user simply never sees it. There's no in-app fallback,
 * no missed-reminders tray. The cheapest defence is to spot the dead-end state
 * before it bites and surface a one-tap fix.
 *
 * Re-checks on every ON_RESUME so the banner disappears the instant the user
 * returns from the channel-settings page with notifications re-enabled.
 */
@Composable
fun ReminderBlockedBanner(
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val hasActiveReminders by homeViewModel.hasActiveReminders.collectAsState()

    var notificationsAllowed by remember(context) {
        mutableStateOf(NotificationHelper.areReminderNotificationsEnabled(context))
    }
    // Android 14+ denies exact alarms by default for new installs; without
    // them reminders fall back to inexact alarms that can fire minutes late.
    var exactAlarmsAllowed by remember(context) {
        mutableStateOf(ReminderScheduler.canScheduleExact(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = NotificationHelper.areReminderNotificationsEnabled(context)
                exactAlarmsAllowed = ReminderScheduler.canScheduleExact(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasActiveReminders || (notificationsAllowed && exactAlarmsAllowed)) return
    // Notifications first: without them a reminder is silent, which is worse
    // than late.
    val silent = !notificationsAllowed

    HeedCallout(
        title = if (silent) "Reminders won't fire" else "Reminders may be late",
        body = if (silent) {
            "Notifications are off — your scheduled reminders will be silent until you re-enable them."
        } else {
            "Allow \"Alarms & reminders\" so reminders ring at the exact time instead of a few minutes late."
        },
        tone = CalloutTone.Danger,
        modifier = modifier,
    ) {
        HeedButton(
            text = if (silent) "Fix" else "Allow",
            style = HeedButtonStyle.Text,
            onClick = { if (silent) openReminderChannelSettings(context) else openExactAlarmSettings(context) },
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = ReminderScheduler.exactAlarmSettingsIntent(context) ?: return
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Some OEM builds drop the per-app page; fall back to app details.
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                )
            }
        }
}

private fun openReminderChannelSettings(context: Context) {
    runCatching { context.startActivity(NotificationHelper.reminderChannelSettingsIntent(context)) }
        .onFailure {
            // Older Android (API <26) or OEM stripped the channel-settings action.
            // Fall back to the app's notification settings page; the user can
            // navigate to Reminders from there.
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                )
            }
        }
}
