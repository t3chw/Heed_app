package com.bol.app.ui.screens.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduledRemindersScreen(
    onBack: () -> Unit,
    viewModel: ScheduledRemindersViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H
    var editing by remember { mutableStateOf<com.bol.app.data.local.entity.ItemEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = "Scheduled reminders",
                subtitle = if (state.totalCount == 0) "none yet" else "${state.totalCount} set",
                onBack = onBack,
            )

            if (state.groups.isEmpty()) {
                EmptyState(
                    title = "No scheduled reminders",
                    body = "Set a time on any task.",
                    mood = BolMood.Idle,
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = HeedLayout.Gutter,
                        end = HeedLayout.Gutter,
                        bottom = 24.dp,
                    ),
                ) {
                    state.groups.forEach { (bucket, rows) ->
                        item(key = "header_${bucket.name}") {
                            SectionLabel(
                                text = bucketLabel(bucket),
                                trailing = rows.size.toString(),
                                color = if (bucket == ScheduledRemindersViewModel.Bucket.OVERDUE)
                                    HeedTheme.colors.danger else HeedTheme.colors.textMuted,
                            )
                        }
                        items(rows, key = { it.item.id }) { row ->
                            ReminderRow(
                                row = row,
                                format24h = format24h,
                                onCancel = { viewModel.cancelReminder(row.item.id) },
                                onComplete = { viewModel.completeItem(row.item.id) },
                                onEdit = { editing = row.item },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        com.bol.app.ui.components.ReminderEditDialog(
            initialTime = item.reminderTime,
            onSave = { newTime ->
                viewModel.setReminder(item.id, newTime)
                editing = null
            },
            onRemove = {
                viewModel.cancelReminder(item.id)
                editing = null
            },
            onDismiss = { editing = null },
            format24h = format24h,
            taskLabel = item.text,
        )
    }
}

/**
 * Reminder row. Clicking the body opens the edit dialog; the two trailing icons are
 * separate tap targets for complete / cancel.
 */
@Composable
private fun ReminderRow(
    row: ScheduledRemindersViewModel.ReminderRow,
    format24h: Boolean,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    onEdit: () -> Unit,
) {
    val isOverdue = row.bucket == ScheduledRemindersViewModel.Bucket.OVERDUE
    val c = HeedTheme.colors
    val whenText = formatTime(row.item.reminderTime ?: 0L, format24h).uppercase()
    ListRow(
        title = row.item.text,
        // The "Overdue" section label already says it; the time turns red instead of a repeated word.
        meta = whenText,
        metaContent = if (isOverdue) {
            { Text(text = whenText, style = BolMono.copy(color = c.danger), maxLines = 1) }
        } else null,
        leading = {
            BucketIcon(
                emoji = row.category?.icon ?: "\uD83D\uDCDD",
                categoryId = row.category?.id ?: row.item.categoryId,
                name = row.category?.name,
            )
        },
        trailing = {
            Row {
                IconButton(onClick = onComplete) {
                    Icon(Icons.Default.Done, contentDescription = "Mark done", tint = c.textMuted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel reminder", tint = c.textMuted, modifier = Modifier.size(20.dp))
                }
            }
        },
        onClick = onEdit,
    )
    RowDivider()
}

private fun bucketLabel(b: ScheduledRemindersViewModel.Bucket): String = when (b) {
    ScheduledRemindersViewModel.Bucket.OVERDUE -> "Overdue"
    ScheduledRemindersViewModel.Bucket.TODAY -> "Today"
    ScheduledRemindersViewModel.Bucket.TOMORROW -> "Tomorrow"
    ScheduledRemindersViewModel.Bucket.THIS_WEEK -> "This week"
    ScheduledRemindersViewModel.Bucket.LATER -> "Later"
}

private fun formatTime(ts: Long, format24h: Boolean): String {
    val timePattern = if (format24h) "HH:mm" else "h:mm a"
    val fmtToday = SimpleDateFormat(timePattern, Locale.getDefault())
    val fmtOther = SimpleDateFormat("EEE MMM d · $timePattern", Locale.getDefault())
    val now = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val sameDay = kotlin.math.abs(now - ts) < dayMs &&
        SimpleDateFormat("yyyyDDD", Locale.US).format(Date(now)) ==
        SimpleDateFormat("yyyyDDD", Locale.US).format(Date(ts))
    return if (sameDay) fmtToday.format(Date(ts)) else fmtOther.format(Date(ts))
}
