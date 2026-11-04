package com.bol.app.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.ActionRow
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.FactLine
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCheckbox
import com.bol.app.ui.components.Leaving
import com.bol.app.ui.components.rememberTickToDone
import com.bol.app.ui.components.withHeld
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CategoryDetailScreen(
    categoryId: String,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    viewModel: CategoryDetailViewModel = koinViewModel { parametersOf(categoryId) }
) {
    val items by viewModel.items.collectAsState(initial = emptyList())
    val allItems by viewModel.allItems.collectAsState(initial = emptyList())
    val hideCompleted by viewModel.hideCompleted.collectAsState(initial = true)
    val category by viewModel.category.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == com.bol.app.data.preferences.PreferencesManager.TIME_FORMAT_24H
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Ticking an open task: it stays in place, ticked, for a moment (TickToDone), then moves to Done.
    val tick = rememberTickToDone<com.bol.app.data.local.entity.ItemEntity>(snackbarHostState, viewModel::setDone)
    // A deleted task disappears at once; it's only removed for good if Undo isn't tapped.
    var pendingDelete by remember { mutableStateOf(emptySet<String>()) }
    val allCategories by viewModel.allCategories.collectAsState(initial = emptyList())

    // Tasks chosen with a long press, to get the same reminder together.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var remindingSelected by remember { mutableStateOf(false) }
    val selectedOpen = items.filter { it.id in selectedIds && !it.isCompleted }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    // Item whose reminder the user is currently editing. Non-null → dialog
    // is shown. Set by tapping "Set reminder" / "Edit reminder" in an
    // expanded item card; cleared on Save / Remove / Cancel / Dismiss.
    var editingReminderFor by remember { mutableStateOf<com.bol.app.data.local.entity.ItemEntity?>(null) }
    // Item whose category the user is currently changing. Same pattern.
    var movingCategoryFor by remember { mutableStateOf<com.bol.app.data.local.entity.ItemEntity?>(null) }
    // Item whose text the user is currently editing. Same pattern.
    var editingTextFor by remember { mutableStateOf<com.bol.app.data.local.entity.ItemEntity?>(null) }

    val categoryName = category?.name.orEmpty()
    val c = HeedTheme.colors

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Counts are computed from the unfiltered list so "X open · Y done"
            // reflects the truth even when the list is filtered to open-only.
            val open = allItems.count { !it.isCompleted }
            val done = allItems.size - open
            TopBar(
                title = categoryName,
                subtitle = "$open open · $done done",
                onBack = onBack,
                leading = {
                    BucketIcon(
                        emoji = category?.icon ?: "📝",
                        categoryId = categoryId,
                        name = categoryName,
                    )
                },
                actions = {
                    // Eye toggle — only worth showing when there's something to
                    // hide/reveal (i.e. there are completed items, or the user
                    // has hidden them and could un-hide).
                    if (done > 0 || (hideCompleted && allItems.isNotEmpty())) {
                        IconButton(onClick = { viewModel.toggleHideCompleted() }) {
                            Icon(
                                imageVector = if (hideCompleted) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (hideCompleted) "Show completed" else "Hide completed",
                                tint = c.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )

            if (allItems.isEmpty()) {
                EmptyState(
                    title = "Nothing here yet",
                    body = if (categoryName.isNotBlank()) {
                        "Tap the mic and tell me about $categoryName."
                    } else {
                        "Tap the mic — I'll catch every word."
                    },
                    mood = BolMood.Listening,
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else if (items.isEmpty() && tick.held.isEmpty()) {
                // Filter is on and there are only completed items — remind the
                // user what's hidden so they're not confused by the emptiness.
                EmptyState(
                    title = "All caught up",
                    body = "$done completed item${if (done == 1) "" else "s"} hidden — tap the eye to view.",
                    mood = BolMood.Happy,
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                // A ticked task stays at its place, drawn as done, until it moves to Done (TickToDone).
                val openItems = tick.withHeld(
                    allItems.filter { !it.isCompleted && it.id !in pendingDelete },
                    { it.id },
                    display = { it.copy(isCompleted = true) },
                ).filter { it.id !in pendingDelete }
                val doneItems = items.filter { it.isCompleted && !tick.isHeld(it.id) && it.id !in pendingDelete }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = HeedLayout.Gutter, vertical = 0.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 16.dp),
                ) {
                    if (open >= 2) {
                        item {
                            Text(
                                text = "Hold a task to select several.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    itemsIndexed(openItems, key = { _, item -> item.id }) { index, item ->
                      tick.Leaving(item.id) {
                        ItemCard(
                            item = item,
                            format24h = format24h,
                            selecting = selectedIds.isNotEmpty(),
                            selected = item.id in selectedIds,
                            onSelect = { selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id },
                            currentCategory = category,
                            onToggle = { tick.tick(item.id, item, index) },
                            onEditText = { editingTextFor = item },
                            onEditReminder = { editingReminderFor = item },
                            onMoveCategory = { movingCategoryFor = item },
                            onOpenDetails = { onOpenItem(item.id) },
                            onDelete = {
                                pendingDelete = pendingDelete + item.id
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Item deleted",
                                        actionLabel = "Undo",
                                        duration = androidx.compose.material3.SnackbarDuration.Short
                                    )
                                    if (result != androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        viewModel.deleteItem(item.id)
                                    }
                                    pendingDelete = pendingDelete - item.id
                                }
                            },
                        )
                      }
                    }
                    if (doneItems.isNotEmpty()) {
                        item(key = "done_header") {
                            SectionLabel("Done", trailing = doneItems.size.toString())
                        }
                        items(doneItems, key = { it.id }) { item ->
                            ItemCard(
                                item = item,
                                format24h = format24h,
                                selecting = selectedIds.isNotEmpty(),
                                selected = item.id in selectedIds,
                                onSelect = { selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id },
                                currentCategory = category,
                                onToggle = { viewModel.toggleItem(item.id) },
                                onEditText = { editingTextFor = item },
                                onEditReminder = { editingReminderFor = item },
                                onMoveCategory = { movingCategoryFor = item },
                                onOpenDetails = { onOpenItem(item.id) },
                                onDelete = {
                                    pendingDelete = pendingDelete + item.id
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Item deleted",
                                            actionLabel = "Undo",
                                            duration = androidx.compose.material3.SnackbarDuration.Short
                                        )
                                        if (result != androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            viewModel.deleteItem(item.id)
                                        }
                                        pendingDelete = pendingDelete - item.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Recording happens from Home via the MicPill; this screen is for viewing/managing items.
        if (selectedOpen.isNotEmpty()) {
            Surface(
                shape = HeedLayout.CardShape,
                color = c.surface,
                border = BorderStroke(1.dp, c.line),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = HeedLayout.Gutter, vertical = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = "${selectedOpen.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    HeedButton(
                        text = "Cancel",
                        onClick = { selectedIds = emptySet() },
                        style = HeedButtonStyle.Text,
                    )
                    Spacer(Modifier.width(4.dp))
                    HeedButton(
                        text = "Remind",
                        onClick = { remindingSelected = true },
                        style = HeedButtonStyle.Filled,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }

    if (remindingSelected) {
        com.bol.app.ui.components.ReminderEditDialog(
            initialTime = null,
            onSave = { newTime ->
                val count = selectedOpen.size
                viewModel.setReminders(selectedOpen.map { it.id }, newTime)
                remindingSelected = false
                selectedIds = emptySet()
                scope.launch {
                    snackbarHostState.showSnackbar("Reminder set for $count task${if (count == 1) "" else "s"}")
                }
            },
            onRemove = {},
            onDismiss = { remindingSelected = false },
            format24h = format24h,
            taskLabel = "${selectedOpen.size} task${if (selectedOpen.size == 1) "" else "s"}",
        )
    }

    // Reminder edit dialog — shown while an item is selected for editing.
    // Save writes the new time (and cancels/reschedules the alarm); Remove
    // clears the reminder on an existing item; Cancel/Dismiss do nothing.
    editingReminderFor?.let { item ->
        com.bol.app.ui.components.ReminderEditDialog(
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
            format24h = format24h,
            taskLabel = item.text,
        )
    }

    // Move-to-category dialog — picker lists every active category except
    // the item's current one. Picking a row commits the move immediately;
    // item disappears from this screen since it no longer belongs here.
    movingCategoryFor?.let { item ->
        com.bol.app.ui.components.CategoryPickerDialog(
            currentCategoryId = item.categoryId,
            categories = allCategories,
            onPick = { newCategoryId ->
                viewModel.moveItem(item.id, newCategoryId)
                movingCategoryFor = null
            },
            onDismiss = { movingCategoryFor = null },
        )
    }

    // Edit-text dialog — single OutlinedTextField pre-filled with the
    // current item text. Save commits via repo, dismiss / cancel does
    // nothing.
    editingTextFor?.let { item ->
        EditItemTextDialog(
            initial = item.text,
            onSave = { newText ->
                viewModel.editItemText(item.id, newText)
                editingTextFor = null
            },
            onDismiss = { editingTextFor = null },
        )
    }
}

@Composable
private fun EditItemTextDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank() && text != initial,
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Lazy-loaded the model metadata for an opened item: tag chips and up to three
 * paraphrases from the enrichment blob. Fetches in a coroutine on first
 * appearance so scrolling past cards doesn't hit the DAOs — only the card
 * the user actually expanded pays the cost.
 */
@Composable
private fun ItemDetailExtras(itemId: String, mutedColor: Color) {
    val tagDao: com.bol.app.data.local.dao.ItemTagDao = org.koin.compose.koinInject()
    val enrichDao: com.bol.app.data.local.dao.ItemEnrichmentDao = org.koin.compose.koinInject()

    var tags by remember(itemId) { mutableStateOf<List<String>>(emptyList()) }
    var paraphrases by remember(itemId) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(itemId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t = runCatching { tagDao.tagsForItem(itemId) }.getOrDefault(emptyList())
            val blob = runCatching { enrichDao.getForItem(itemId)?.searchContext }.getOrNull().orEmpty()
            // Blob format is: "<summary> · <paraphrase 1> · ... · <entities-comma-list> · <tags-comma-list>".
            // Skip comma-heavy parts (≥2 commas) to keep only the summary +
            // true alternate phrasings; take the first 3 for a subtle hint.
            val phrasings = blob.split(" · ")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.count { ch -> ch == ',' } <= 1 }
                .take(3)
            tags = t
            paraphrases = phrasings
        }
    }

    if (tags.isEmpty() && paraphrases.isEmpty()) return

    Spacer(Modifier.height(10.dp))
    if (tags.isNotEmpty()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tag in tags) HeedChip(label = tag)
        }
    }
    if (paraphrases.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "also: ${paraphrases.joinToString(" · ")}",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
    }
}

@Composable
private fun ItemCard(
    item: ItemEntity,
    format24h: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    currentCategory: com.bol.app.data.local.entity.CategoryEntity?,
    onToggle: () -> Unit,
    onEditText: () -> Unit,
    onEditReminder: () -> Unit,
    onMoveCategory: () -> Unit,
    onOpenDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val c = HeedTheme.colors

    // Reminder label for an open task with a reminder; null otherwise.
    val reminderLabel = item.reminderTime
        ?.takeUnless { item.isCompleted }
        ?.let { com.bol.app.util.DateTimeDisplay.reminderRelative(it, format24h = format24h) }
    val isOverdue = reminderLabel?.uppercase() == "OVERDUE"
    val timeAgo = com.bol.app.util.DateTimeDisplay.relative(item.createdAt)
    val meta = if (item.isProvisional) "$timeAgo · rule" else timeAgo

    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = item.text,
            meta = meta,
            done = item.isCompleted,
            titleMaxLines = Int.MAX_VALUE,
            modifier = Modifier.background(
                color = if (selected) c.brandSoft else Color.Transparent,
                shape = HeedLayout.ControlShape,
            ),
            leading = { HeedCheckbox(checked = item.isCompleted, onCheckedChange = onToggle) },
            trailing = if (reminderLabel != null) {
                {
                    HeedChip(
                        label = reminderLabel,
                        selected = !isOverdue,
                        leading = {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = if (isOverdue) c.danger else c.brand,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            } else null,
            // Tapping the row expands the detail block; the checkbox is a
            // separate target so a tap-to-expand doesn't also mark the item done.
            onClick = { if (selecting) { if (!item.isCompleted) onSelect() } else expanded = !expanded },
            onLongClick = { if (!item.isCompleted) onSelect() },
        )

        if (expanded) {
            Column(modifier = Modifier.padding(start = 52.dp, bottom = 12.dp)) {
                // Which bucket the item lives in; tapping lets the user move it.
                FactLine(label = "Bucket") {
                    HeedChip(
                        label = currentCategory?.name ?: item.categoryId,
                        leading = {
                            BucketIcon(
                                emoji = currentCategory?.icon ?: "📝",
                                categoryId = currentCategory?.id ?: item.categoryId,
                                name = currentCategory?.name,
                                size = 18.dp,
                            )
                        },
                        onClick = onMoveCategory,
                    )
                }
                FactLine(label = "Added", value = com.bol.app.util.DateTimeDisplay.dateTime(item.createdAt, format24h))
                if (item.updatedAt > item.createdAt + 60_000) {
                    FactLine(label = "Updated", value = com.bol.app.util.DateTimeDisplay.dateTime(item.updatedAt, format24h))
                }
                item.reminderTime?.let { rt ->
                    FactLine(label = "Reminder", value = com.bol.app.util.DateTimeDisplay.dateTime(rt, format24h))
                }
                if (item.sourceRecordingId != null) {
                    FactLine(label = "Source", value = "from your voice")
                }

                // Model-generated tags + enrichment paraphrases, loaded lazily
                // so only the item the user opened pays for the fetch.
                ItemDetailExtras(itemId = item.id, mutedColor = c.textMuted)

                Spacer(Modifier.height(6.dp))
                ActionRow {
                    HeedButton(text = "Open details", onClick = onOpenDetails, style = HeedButtonStyle.Text)
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

        RowDivider()
    }
}

