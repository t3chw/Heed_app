package com.bol.app.ui.screens.recording.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.ActionRow
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.CalloutTone
import com.bol.app.ui.components.FactLine
import com.bol.app.ui.components.CategoryPickerDialog
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCallout
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedCheckbox
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.IconTile
import com.bol.app.ui.components.ItemTextEditDialog
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.PanelDivider
import com.bol.app.ui.components.ReminderEditDialog
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    viewModel: RecordingDetailViewModel = koinViewModel { parametersOf(recordingId) },
) {
    val recording by viewModel.recording.collectAsState()
    val items by viewModel.items.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(
        initial = PreferencesManager.TIME_FORMAT_12H,
    )
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPlayback() }
    }

    // Per-item dialog trigger states. Each holds the item currently being
    // acted on, or null when no dialog is open. Matches CategoryDetail's
    // convention so reading one file makes the other instantly familiar.
    var editingTextFor by remember { mutableStateOf<ItemEntity?>(null) }
    var editingReminderFor by remember { mutableStateOf<ItemEntity?>(null) }
    var movingCategoryFor by remember { mutableStateOf<ItemEntity?>(null) }
    var showDeleteRecordingDialog by remember { mutableStateOf(false) }
    // Open the edit-transcription dialog. Holds the rawText being edited
    // so cancelling the dialog doesn't lose the user's in-progress edits
    // across composition (rememberSaveable inside the dialog handles
    // rotation while it's open).
    var editingTranscription by remember { mutableStateOf(false) }
    val isReprocessing by viewModel.isReprocessing.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Initial load is async — the ViewModel fires refreshRecording()
        // from init{} and flips the state once the DB query returns. While
        // null, show a centered spinner. DO NOT auto-pop: on first
        // composition recording is always null, so auto-popping here would
        // close the detail screen before it ever became visible (which was
        // the "tap does nothing" bug users hit). Explicit back navigation
        // after a delete happens via viewModel.deleteRecording(onBack).
        val rec = recording
        if (rec == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Box
        }
        val hasAudio = viewModel.hasAudio()

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = "Recording",
                subtitle = formatHeaderSubtitle(rec.createdAt),
                onBack = onBack,
                actions = {
                    if (!isReprocessing) {
                        IconButton(onClick = { editingTranscription = true }) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Edit transcription",
                                tint = HeedTheme.colors.textPrimary,
                            )
                        }
                    }
                },
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = HeedLayout.Gutter,
                    end = HeedLayout.Gutter,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
            ) {
                item("transcript") {
                    TranscriptBlock(rawText = rec.rawText, isReprocessing = isReprocessing)
                }

                item("player") {
                    Spacer(Modifier.height(12.dp))
                    if (hasAudio) {
                        AudioPlayerRow(
                            playback = playback,
                            onToggle = { viewModel.togglePlayback() },
                            onStop = { viewModel.stopPlayback() },
                        )
                    } else {
                        Text(
                            text = "The audio for this recording is no longer stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HeedTheme.colors.textMuted,
                        )
                    }
                }

                if (rec.cancelled) {
                    item("cancelled") {
                        Spacer(Modifier.height(24.dp))
                        CancelledBlock(onRetry = { viewModel.retryRecording() })
                    }
                } else if (items.isNotEmpty()) {
                    item("items") {
                        SectionLabel("Tasks", trailing = items.size.toString())
                        HeedCard {
                            items.forEachIndexed { index, item ->
                                if (index > 0) PanelDivider()
                                ItemRow(
                                    item = item,
                                    categories = categories,
                                    format24h = format24h,
                                    onToggle = { viewModel.toggleItem(item.id) },
                                    onEditText = { editingTextFor = item },
                                    onEditReminder = { editingReminderFor = item },
                                    onMoveCategory = { movingCategoryFor = item },
                                    onDelete = { viewModel.deleteItem(item.id) },
                                )
                            }
                        }
                    }
                } else if (!isReprocessing) {
                    // Suppress the "No items" placeholder during reprocessing —
                    // there's a brief window where deleteByRecordingId has run
                    // but the new items haven't been saved yet, and the
                    // transcript spinner is already telling the user we're
                    // re-categorizing. A second placeholder would be noise.
                    item("no_items") {
                        Text(
                            text = "No items were extracted from this recording.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeedTheme.colors.textMuted,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }

                item("danger") {
                    DangerZone(onDelete = { showDeleteRecordingDialog = true })
                }
            }
        }

        editingTextFor?.let { item ->
            ItemTextEditDialog(
                initialText = item.text,
                onSave = { newText ->
                    viewModel.editItemText(item.id, newText)
                    editingTextFor = null
                },
                onDismiss = { editingTextFor = null },
            )
        }
        editingReminderFor?.let { item ->
            ReminderEditDialog(
                initialTime = item.reminderTime,
                onSave = { newTime ->
                    viewModel.setReminder(item.id, newTime)
                    editingReminderFor = null
                },
                onRemove = {
                    viewModel.clearReminder(item.id)
                    editingReminderFor = null
                },
                onDismiss = { editingReminderFor = null },
                taskLabel = item.text,
                format24h = format24h,
            )
        }
        movingCategoryFor?.let { item ->
            CategoryPickerDialog(
                currentCategoryId = item.categoryId,
                categories = categories,
                onPick = { newCategoryId ->
                    viewModel.moveItem(item.id, newCategoryId)
                    movingCategoryFor = null
                },
                onDismiss = { movingCategoryFor = null },
            )
        }
        if (editingTranscription) {
            EditTranscriptionDialog(
                initialText = rec.rawText,
                onSave = { newText ->
                    viewModel.editTranscription(newText)
                    editingTranscription = false
                },
                onDismiss = { editingTranscription = false },
            )
        }
        if (showDeleteRecordingDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteRecordingDialog = false },
                title = { Text("Delete this recording?") },
                text = {
                    Text(
                        "The audio, transcript, and all ${items.size} " +
                            "item${if (items.size == 1) "" else "s"} from it will be " +
                            "permanently removed. This can't be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteRecordingDialog = false
                        viewModel.deleteRecording(onBack)
                    }) {
                        Text("Delete", color = HeedTheme.colors.danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteRecordingDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun TranscriptBlock(
    rawText: String,
    isReprocessing: Boolean,
) {
    HeedCard {
        if (isReprocessing) {
            // Re-categorization is running. Replace the transcript with a
            // small spinner+label so the user knows the LLM is working.
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = HeedTheme.colors.brand,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Re-categorizing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeedTheme.colors.textMuted,
                )
            }
        } else {
            Text(
                text = "“$rawText”",
                style = MaterialTheme.typography.bodyLarge,
                color = HeedTheme.colors.textPrimary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Dialog for editing a recording's transcription. Saving wipes the
 *  recording's existing items and re-runs categorization on the corrected
 *  text. Save is disabled when the trimmed text is blank or unchanged. */
@Composable
private fun EditTranscriptionDialog(
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialText) }
    val trimmed = value.trim()
    val canSave = trimmed.isNotBlank() && trimmed != initialText.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transcription") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transcription") },
                    singleLine = false,
                    maxLines = 8,
                    shape = HeedLayout.ControlShape,
                    colors = heedTextFieldColors(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Saving re-runs categorization. The items currently linked to this recording will be replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HeedTheme.colors.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = canSave,
            ) { Text("Save & re-run") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Play / pause card with a progress ring around the tile and elapsed / total
 * time. Tap toggles playback; long-press stops it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioPlayerRow(
    playback: RecordingDetailPlayback,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val c = HeedTheme.colors
    HeedCard(
        modifier = Modifier
            .clip(HeedLayout.CardShape)
            .combinedClickable(onClick = onToggle, onLongClick = onStop),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                IconTile(
                    icon = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    size = 40.dp,
                    tint = c.brand,
                    background = c.brandSoft,
                )
                if (playback.totalMs > 0) {
                    val arcColor = c.brand
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val stroke = 2.5.dp.toPx()
                        drawArc(
                            color = arcColor,
                            startAngle = -90f,
                            sweepAngle = 360f * playback.progressFraction.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // The only label of a tappable control, so screen readers read it.
                    text = if (playback.isPlaying) "Playing…" else "Play recording",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textPrimary,
                )
                val shown = if (playback.totalMs > 0) {
                    "${formatMs(playback.currentMs)} / ${formatMs(playback.totalMs)}"
                } else {
                    "tap to play"
                }
                Text(
                    text = shown,
                    style = BolMono.copy(color = c.textMuted),
                )
            }
        }
    }
}

@Composable
private fun CancelledBlock(onRetry: () -> Unit) {
    HeedCallout(
        tone = CalloutTone.Danger,
        title = "This recording wasn't processed.",
        body = "Retry to send it through transcription + categorisation again.",
    ) {
        Spacer(Modifier.height(12.dp))
        HeedButton(text = "Retry", onClick = onRetry)
    }
}

/**
 * Task row with expand-to-action pattern — mirrors CategoryDetail's
 * vocabulary so users who know one know both. Tapping the checkbox toggles
 * completion; tapping the row expands to show category + timestamps + the
 * Edit / Reminder / Delete actions.
 */
@Composable
private fun ItemRow(
    item: ItemEntity,
    categories: List<CategoryEntity>,
    format24h: Boolean,
    onToggle: () -> Unit,
    onEditText: () -> Unit,
    onEditReminder: () -> Unit,
    onMoveCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val c = HeedTheme.colors
    val category = categories.firstOrNull { it.id == item.categoryId }
    val categoryName = category?.name ?: item.categoryId

    val reminderLabel = item.reminderTime
        ?.takeUnless { item.isCompleted }
        ?.let { com.bol.app.util.DateTimeDisplay.reminderRelative(it, format24h = format24h).uppercase() }
    val isOverdue = reminderLabel == "OVERDUE"
    val baseCaption = "$categoryName · ${com.bol.app.util.DateTimeDisplay.relative(item.createdAt)}".uppercase()
    val captionText = if (reminderLabel == null) {
        AnnotatedString(baseCaption)
    } else {
        buildAnnotatedString {
            append("$baseCaption · ")
            if (isOverdue) {
                withStyle(SpanStyle(color = c.danger)) { append("🔔 $reminderLabel") }
            } else {
                append("🔔 $reminderLabel")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = item.text,
            done = item.isCompleted,
            metaContent = {
                Text(
                    text = captionText,
                    style = BolMono.copy(color = c.textMuted),
                    maxLines = 1,
                )
            },
            leading = { HeedCheckbox(checked = item.isCompleted, onCheckedChange = onToggle) },
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (expanded) {
            // Same expanded block as a bucket's task rows (CategoryDetailScreen), lined up with the title.
            Column(modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp)) {
                FactLine(label = "Bucket") {
                    HeedChip(
                        label = categoryName,
                        leading = {
                            BucketIcon(emoji = category?.icon ?: "📝", categoryId = item.categoryId, name = category?.name, size = 18.dp)
                        },
                        onClick = onMoveCategory,
                    )
                }
                FactLine("Added", com.bol.app.util.DateTimeDisplay.dateTime(item.createdAt, format24h))
                if (item.updatedAt > item.createdAt + 60_000) {
                    FactLine("Updated", com.bol.app.util.DateTimeDisplay.dateTime(item.updatedAt, format24h))
                }
                item.reminderTime?.let { rt ->
                    FactLine("Reminder", com.bol.app.util.DateTimeDisplay.dateTime(rt, format24h))
                }
                Spacer(Modifier.height(6.dp))
                ActionRow {
                    HeedButton(text = "Edit", onClick = onEditText, style = HeedButtonStyle.Text)
                    HeedButton(
                        text = if (item.reminderTime != null) "Edit reminder" else "Set reminder",
                        onClick = onEditReminder,
                        style = HeedButtonStyle.Text,
                    )
                    HeedButton(text = "Delete", onClick = onDelete, style = HeedButtonStyle.DangerText)
                }
            }
        }
    }
}


@Composable
private fun DangerZone(onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        HeedButton(
            text = "Delete recording",
            onClick = onDelete,
            style = HeedButtonStyle.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Removes the audio, the transcript and its tasks.",
            style = MaterialTheme.typography.bodySmall,
            color = HeedTheme.colors.textMuted,
        )
    }
}

private fun formatHeaderSubtitle(timestamp: Long): String {
    val fmt = SimpleDateFormat("EEE · MMM d · h:mm a", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

private fun formatMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}
