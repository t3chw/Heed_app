package com.bol.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.service.NotificationHelper
import com.bol.app.service.ReminderScheduler
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.util.DateTimeDisplay
import com.bol.app.util.ReminderChoices
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.koin.compose.koinInject

/**
 * Set or change a reminder. Reminders are only ever set here, by the user; the model never creates one.
 *
 * One tap on a ready-made time saves it. "Pick a date and time" opens the date and time pickers.
 * [initialTime] != null adds "Remove reminder". [taskLabel] names the task or tasks at the top of the sheet.
 * The date picker only allows today or later, and a time already past cannot be saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditDialog(
    initialTime: Long?,
    onSave: (Long) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    format24h: Boolean = false,
    taskLabel: String? = null,
) {
    val now = System.currentTimeMillis()
    val seed = initialTime ?: defaultSeed(now)
    val seedCal = Calendar.getInstance().apply { timeInMillis = seed }

    // Material's DatePicker works in UTC midnight internally. We seed with
    // the UTC-midnight equivalent of the local calendar's year/month/day so
    // the dialog shows the right day regardless of timezone.
    val seedUtcMidnight = utcMidnightOfLocalDate(seedCal)
    val todayUtcMidnight = utcMidnightOfLocalDate(Calendar.getInstance().apply { timeInMillis = now })

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = seedUtcMidnight,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis >= todayUtcMidnight
        },
    )
    val timeState = rememberTimePickerState(
        initialHour = seedCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = seedCal.get(Calendar.MINUTE),
        is24Hour = format24h,
    )

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Compute the currently selected absolute instant (for display + validation).
    val selectedInstant = combineDateAndTime(
        dateState.selectedDateMillis ?: seedUtcMidnight,
        timeState.hour,
        timeState.minute,
    )
    val isInPast = selectedInstant <= now

    val preferences: PreferencesManager = koinInject()
    val morningHour by preferences.defaultReminderHour.collectAsState(initial = PreferencesManager.DEFAULT_REMINDER_HOUR_VALUE)
    var pickingOwnTime by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // The choices were worked out when the sheet was last drawn; a sheet left open can offer a time that has since
    // passed, and a past alarm is silently skipped. Checked again at the tap.
    val save: (Long) -> Unit = { at ->
        if (at > System.currentTimeMillis()) onSave(at)
        else android.widget.Toast.makeText(context, "That time has passed — pick another.", android.widget.Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HeedTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            SheetHeader(
                title = if (initialTime != null) "Change reminder" else "Remind me",
                subtitle = taskLabel,
            )
            if (initialTime != null) {
                Text(
                    text = "Set for ${DateTimeDisplay.full(initialTime, format24h)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = HeedTheme.colors.brand,
                )
            }
            ReminderPermissionHint()
            Spacer(Modifier.height(8.dp))
            ReminderChoices.list(now, morningHour).forEach { choice ->
                ChoiceRow(choice.label, shortWhen(choice.at, now, format24h)) { save(choice.at) }
            }
            ChoiceRow("Pick a date and time", "") { pickingOwnTime = !pickingOwnTime }
            if (pickingOwnTime) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HeedButton(
                        text = formatDate(selectedInstant),
                        onClick = { showDatePicker = true },
                        style = HeedButtonStyle.Outlined,
                        modifier = Modifier.weight(1f),
                    )
                    HeedButton(
                        text = formatClock(timeState.hour, timeState.minute, format24h),
                        onClick = { showTimePicker = true },
                        style = HeedButtonStyle.Outlined,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (isInPast) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Pick a time in the future.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HeedTheme.colors.danger,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HeedButton(
                    text = "Save",
                    onClick = { save(selectedInstant) },
                    style = HeedButtonStyle.Filled,
                    enabled = !isInPast,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (initialTime != null) {
                Spacer(Modifier.height(12.dp))
                HeedButton(
                    text = "Remove reminder",
                    onClick = onRemove,
                    style = HeedButtonStyle.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pick time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = HeedTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = HeedTheme.colors.textMuted)
    }
}

/** A reminder that cannot ring is worse than none, so the problem is shown at the moment of setting one. */
@Composable
private fun ReminderPermissionHint() {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(NotificationHelper.areReminderNotificationsEnabled(context)) }
    var exact by remember { mutableStateOf(ReminderScheduler.canScheduleExact(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = NotificationHelper.areReminderNotificationsEnabled(context)
                exact = ReminderScheduler.canScheduleExact(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val openChannel = { runCatching { context.startActivity(NotificationHelper.reminderChannelSettingsIntent(context)) }; Unit }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = NotificationHelper.areReminderNotificationsEnabled(context)
        if (!granted) openChannel()
    }
    if (notifications && exact) return
    Spacer(Modifier.height(10.dp))
    HeedCallout(
        body = if (!notifications) "Notifications are off, so this can't ring." else "Exact alarms are off, so this may ring late.",
        tone = CalloutTone.Danger,
    ) {
        HeedButton(
            text = "Turn on",
            style = HeedButtonStyle.Text,
            onClick = {
                val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                when {
                    !notifications && needsAsking -> askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    !notifications -> openChannel()
                    else -> ReminderScheduler.exactAlarmSettingsIntent(context)?.let { intent -> runCatching { context.startActivity(intent) } }
                }
            },
        )
    }
}

// ── helpers ──

/** Default "next sensible time" when opening the dialog in add-mode:
 *  today 9 AM if still future, else tomorrow 9 AM. */
private fun defaultSeed(now: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

/** Material3 DatePicker returns UTC-midnight timestamps. Convert from a
 *  local-calendar date to its UTC-midnight equivalent so the "selected day"
 *  matches what the user sees on their phone. */
private fun utcMidnightOfLocalDate(local: Calendar): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.set(Calendar.YEAR, local.get(Calendar.YEAR))
    utc.set(Calendar.MONTH, local.get(Calendar.MONTH))
    utc.set(Calendar.DAY_OF_MONTH, local.get(Calendar.DAY_OF_MONTH))
    utc.set(Calendar.HOUR_OF_DAY, 0)
    utc.set(Calendar.MINUTE, 0)
    utc.set(Calendar.SECOND, 0)
    utc.set(Calendar.MILLISECOND, 0)
    return utc.timeInMillis
}

/** Combine the DatePicker's UTC-midnight date with the local-clock hour +
 *  minute into a single local-timezone absolute timestamp. */
private fun combineDateAndTime(utcDateMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcDateMillis
    }
    val local = Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return local.timeInMillis
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(ts))

/** Just the clock for today, so the choice names keep their one line. */
private fun shortWhen(ts: Long, now: Long, format24h: Boolean): String {
    val clock = DateTimeDisplay.time(ts, format24h)
    return if (formatDate(ts) == formatDate(now)) clock else "${formatDate(ts)} · $clock"
}

private fun formatClock(hour: Int, minute: Int, format24h: Boolean): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    val pattern = if (format24h) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
}
