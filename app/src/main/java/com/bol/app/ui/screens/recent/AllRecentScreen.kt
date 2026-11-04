package com.bol.app.ui.screens.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.HeedCheckbox
import com.bol.app.ui.components.Leaving
import com.bol.app.ui.components.rememberTickToDone
import com.bol.app.ui.components.withHeld
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.util.DateTimeDisplay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun AllRecentScreen(
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    viewModel: AllRecentViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val reminderFilter by viewModel.reminderFilter.collectAsState()
    // Time format pref drives the reminder timestamp's 12/24h rendering —
    // matches Home's caption so the row reads identically across screens.
    val prefs = koinInject<PreferencesManager>()
    val timeFormat by prefs.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H

    val snackbarHostState = remember { SnackbarHostState() }
    val tick = rememberTickToDone<AllRecentRow>(snackbarHostState, viewModel::setDone)
    // A ticked task keeps its place for a moment (TickToDone).
    val shown = tick.withHeld(items, { it.item.id })

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = "Recent",
                subtitle = "${items.size} item${if (items.size == 1) "" else "s"}",
                onBack = onBack,
            )

            // Two filter chips in one row, each opens a DropdownMenu. Resolved-name labels mean the user
            // always sees their current selection without opening the menu.
            val selectedCategoryName = when {
                selectedCategoryId == null -> "All"
                else -> categories.firstOrNull { it.id == selectedCategoryId }?.let { "${it.icon} ${it.name}" } ?: "All"
            }
            Row(
                modifier = Modifier.padding(horizontal = HeedLayout.Gutter, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChipMenu(
                    label = "Category",
                    value = selectedCategoryName,
                    active = selectedCategoryId != null,
                    options = buildList {
                        add(null to "All")
                        categories.forEach { add(it.id to "${it.icon} ${it.name}") }
                    },
                    selectedKey = selectedCategoryId,
                    onPick = { viewModel.selectCategory(it) },
                )
                FilterChipMenu(
                    label = "Reminder",
                    value = reminderFilter.label,
                    active = reminderFilter != ReminderFilter.ALL,
                    options = ReminderFilter.entries.map { it to it.label },
                    selectedKey = reminderFilter,
                    onPick = { viewModel.selectReminderFilter(it) },
                )
            }

            if (shown.isEmpty()) {
                // Distinguish "nothing yet" (fresh user) from "filters
                // matched nothing" (active filter). The latter offers a
                // hint to relax the filter rather than wonder why their
                // tasks vanished.
                val filterActive = selectedCategoryId != null ||
                    reminderFilter != ReminderFilter.ALL
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = if (filterActive) "No tasks match these filters." else "Nothing here yet.",
                        body = if (filterActive) "Try a different category or reminder filter." else "Tasks you speak will show up here.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = HeedLayout.Gutter, vertical = 8.dp),
                ) {
                    itemsIndexed(shown, key = { _, row -> row.item.id }) { index, row ->
                        tick.Leaving(row.item.id) {
                            Column {
                                AllRecentItemRow(
                                    row = row,
                                    format24h = format24h,
                                    checked = tick.isHeld(row.item.id),
                                    onToggle = { tick.tick(row.item.id, row, index) },
                                    onClick = { onItemClick(row.item.id) },
                                )
                                RowDivider()
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/**
 * Chip + DropdownMenu combo. Generic over the option key type so the
 * same composable handles "Category: nullable id" and "Reminder: enum".
 * The chip is brand-soft while its filter is narrowing the list.
 */
@Composable
private fun <T> FilterChipMenu(
    label: String,
    value: String,
    active: Boolean,
    options: List<Pair<T, String>>,
    selectedKey: T,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        HeedChip(
            label = "$label: $value",
            selected = active,
            trailingIcon = Icons.Default.ArrowDropDown,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            for ((key, displayName) in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayName,
                            // Bold the currently-selected option so the
                            // user sees their state without checkmarks.
                            fontWeight = if (key == selectedKey) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onPick(key)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AllRecentItemRow(
    row: AllRecentRow,
    format24h: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val category = row.category
    val c = HeedTheme.colors
    val item = row.item
    val categoryLabel = (category?.name ?: item.categoryId).uppercase()
    val timeLabel = DateTimeDisplay.relative(item.createdAt).uppercase()
    val reminderLabel = item.reminderTime
        ?.takeUnless { item.isCompleted }
        ?.let { DateTimeDisplay.reminderRelative(it, format24h = format24h).uppercase() }
    val isOverdue = reminderLabel == "OVERDUE"
    val captionText = if (reminderLabel == null) {
        AnnotatedString("$categoryLabel · $timeLabel")
    } else {
        buildAnnotatedString {
            append("$categoryLabel · $timeLabel · ")
            if (isOverdue) {
                withStyle(SpanStyle(color = c.danger)) { append("🔔 $reminderLabel") }
            } else {
                append("🔔 $reminderLabel")
            }
        }
    }
    ListRow(
        title = item.text,
        done = checked,
        // The box ticks the task done; the rest of the row opens it.
        leading = { HeedCheckbox(checked = checked, onCheckedChange = onToggle) },
        metaContent = {
            Text(text = captionText, style = BolMono.copy(color = c.textMuted), maxLines = 1)
        },
        onClick = onClick,
    )
}
