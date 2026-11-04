package com.bol.app.ui.screens.task

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.CategoryPickerDialog
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.PanelDivider
import com.bol.app.ui.components.PropertyRow
import com.bol.app.ui.components.ReminderEditDialog
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.util.DateTimeDisplay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TaskDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenRecording: (String) -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(parameters = { parametersOf(itemId) }),
) {
    val item by viewModel.item.collectAsState()
    val category by viewModel.category.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState(initial = emptyList())
    val tags by viewModel.tags.collectAsState()
    val paraphrases by viewModel.paraphrases.collectAsState()
    val sourceTitle by viewModel.sourceRecordingTitle.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H

    var editingReminder by remember { mutableStateOf(false) }
    var movingCategory by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = item
    val c = HeedTheme.colors

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(
                title = "Task",
                onBack = onBack,
                actions = {
                    if (current != null) {
                        IconButton(onClick = { editingText = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit task",
                                tint = c.textPrimary,
                            )
                        }
                    }
                },
            )

            if (current == null) {
                // Either still loading the row, or the item was deleted while the
                // screen was open. Either way, show a quiet placeholder.
                Box(
                    modifier = Modifier.fillMaxSize().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Task not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                    )
                }
                return@Column
            }

            Column(modifier = Modifier.padding(horizontal = HeedLayout.Gutter)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = current.text,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (current.isCompleted) c.textMuted else c.textPrimary,
                    textDecoration = if (current.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                )
                if (current.isProvisional) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "SORTED ROUGHLY · WILL BE RE-SORTED",
                        style = BolMono.copy(color = c.textMuted),
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (current.isCompleted) {
                    HeedButton(
                        text = "Mark not done",
                        onClick = { viewModel.toggleCompleted() },
                        style = HeedButtonStyle.Outlined,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    HeedButton(
                        text = "Mark done",
                        onClick = { viewModel.toggleCompleted() },
                        style = HeedButtonStyle.Filled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Fallback display when the category row is missing (deleted
                // out from under the task, or briefly null while the flow
                // resolves on first composition). Avoids leaking the raw id.
                val reminderAt = current.reminderTime
                val isOverdue = reminderAt != null && !current.isCompleted &&
                    reminderAt < System.currentTimeMillis()
                HeedCard {
                    PropertyRow(label = "Bucket", onClick = { onOpenCategory(current.categoryId) }) {
                        BucketIcon(
                            emoji = category?.icon ?: "📝",
                            categoryId = category?.id ?: current.categoryId,
                            name = category?.name,
                            size = 24.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = category?.name ?: "Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        HeedButton(
                            text = "Move",
                            onClick = { movingCategory = true },
                            style = HeedButtonStyle.Text,
                        )
                    }
                    PanelDivider()
                    PropertyRow(label = "Reminder", onClick = { editingReminder = true }) {
                        Text(
                            text = reminderAt?.let { DateTimeDisplay.dateTime(it, format24h) } ?: "None",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (reminderAt == null) c.textMuted else if (isOverdue) c.danger else c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (reminderAt != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            HeedButton(text = "Snooze 1h", onClick = { viewModel.snoozeOneHour() }, style = HeedButtonStyle.Text)
                            HeedButton(text = "Clear", onClick = { viewModel.clearReminder() }, style = HeedButtonStyle.Text)
                        }
                    }
                    PanelDivider()
                    PropertyRow(label = "Added") {
                        Text(
                            text = DateTimeDisplay.dateTime(current.createdAt, format24h),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textPrimary,
                        )
                    }
                    if (current.updatedAt > current.createdAt + 60_000) {
                        PanelDivider()
                        PropertyRow(label = "Updated") {
                            Text(
                                text = DateTimeDisplay.dateTime(current.updatedAt, format24h),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textPrimary,
                            )
                        }
                    }
                }

                // ---- Source recording ----
                val sourceId = current.sourceRecordingId
                if (sourceId != null) {
                    SectionLabel("From your voice")
                    HeedCard(onClick = { onOpenRecording(sourceId) }) {
                        Text(
                            text = sourceTitle ?: "Open recording",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // ---- Tags / paraphrases ----
                if (tags.isNotEmpty()) {
                    SectionLabel("Tags")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (tag in tags) HeedChip(label = tag)
                    }
                }
                if (paraphrases.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "also: ${paraphrases.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }

                Spacer(Modifier.height(28.dp))

                // Destructive action, isolated from the constructive ones.
                HeedButton(
                    text = "Delete task",
                    onClick = { confirmDelete = true },
                    style = HeedButtonStyle.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (editingReminder && current != null) {
        ReminderEditDialog(
            initialTime = current.reminderTime,
            onSave = { newTime ->
                viewModel.setReminder(newTime)
                editingReminder = false
            },
            onRemove = {
                viewModel.clearReminder()
                editingReminder = false
            },
            onDismiss = { editingReminder = false },
            format24h = format24h,
            taskLabel = current.text,
        )
    }

    if (movingCategory && current != null) {
        CategoryPickerDialog(
            currentCategoryId = current.categoryId,
            categories = allCategories,
            onPick = { newCategoryId ->
                viewModel.moveToCategory(newCategoryId)
                movingCategory = false
            },
            onDismiss = { movingCategory = false },
        )
    }

    if (editingText && current != null) {
        var draft by remember(current.text) { mutableStateOf(current.text) }
        AlertDialog(
            onDismissRequest = { editingText = false },
            title = { Text("Edit task") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Task") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editText(draft)
                        editingText = false
                    },
                    enabled = draft.isNotBlank() && draft != current.text,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingText = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                    onBack()
                }) { Text("Delete", color = HeedTheme.colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
