package com.bol.app.ui.screens.search

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.SearchQueryStatus
import com.bol.app.data.local.entity.SearchStage
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.ActionRow
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.FactLine
import com.bol.app.ui.components.CalloutTone
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCallout
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedCheckbox
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.components.WrapRow
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.components.CategoryPickerDialog
import com.bol.app.ui.components.ItemTextEditDialog
import com.bol.app.ui.components.ReminderEditDialog
import com.bol.app.util.BolHaptic
import com.bol.app.util.DateTimeDisplay
import com.bol.app.ui.theme.BolMono
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import com.bol.app.domain.usecase.SearchProcessingUseCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** How many ranked items to show by default before the "+ N more related"
 *  disclosure. Deterministic — doesn't trust the rerank's `llmPickedCount`
 *  because the task model's pick judgment is too lenient (often picks 60% of
 *  candidates as "relevant"). The rerank still orders the list, so the
 *  top N are the LLM's best guesses; users can expand for the rest. */
private const val DEFAULT_VISIBLE_RANKED = 3

@Composable
fun SearchDetailScreen(
    searchId: String,
    onBack: () -> Unit,
    onOpenSearch: (String) -> Unit = {},
    onOpenRecording: (String) -> Unit = {},
    viewModel: SearchDetailViewModel = koinViewModel { parametersOf(searchId) },
) {
    val state by viewModel.state.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H
    val categoriesById by viewModel.categoriesById.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState()
    val lastDeletedItem by viewModel.lastDeletedItem.collectAsState()
    val reRecordState by viewModel.reRecordState.collectAsState()
    val dictationState by viewModel.dictationState.collectAsState()

    // Dialog trigger states. Each holds the item currently being acted
    // on, or null when no dialog is open. Same pattern as CategoryDetail
    // so reading one file makes the other instantly familiar.
    var editingTextFor by remember { mutableStateOf<ItemEntity?>(null) }
    var editingReminderFor by remember { mutableStateOf<ItemEntity?>(null) }
    var movingCategoryFor by remember { mutableStateOf<ItemEntity?>(null) }
    // Edit-and-retry escape hatch: holds the current query string while the
    // user edits it in [EditQueryDialog]. Null when no dialog is open.
    var editingQueryFor by remember { mutableStateOf<String?>(null) }

    // Progressive-disclosure flag for the Sources list. When the rerank
    // pass produced fewer survivors than total items, the tail is hidden
    // behind a "+ N more related" toggle. rememberSaveable so a rotation
    // or back-and-forth nav doesn't snap the section back to collapsed.
    var showAllRanked by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // If the user navigates away (back, tab switch) mid-voice, hard-cancel
    // both flows so the STT engine releases and the shared recordingSignal
    // can't get stuck on. Compose nav keeps the VM alive across tab
    // switches, so we can't rely on onCleared() alone for this.
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelAllVoice() }
    }

    // Show Undo snackbar when a ranked item gets deleted. ActionPerformed
    // → restore via the VM. Dismissed → drop the held entity.
    LaunchedEffect(lastDeletedItem) {
        val deleted = lastDeletedItem ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Item deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDeleteItem()
            SnackbarResult.Dismissed -> viewModel.clearLastDeletedItem()
        }
    }

    // Retry creates a new search row and emits its ID so we can swap to the
    // detail view for it. Collect once; key is Unit because it's a one-shot
    // per-detail-session flow (a new retry → new navigation).
    LaunchedEffect(Unit) {
        viewModel.navigateTo.collect { newId -> onOpenSearch(newId) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                title = "Search",
                subtitle = if (state.createdAt > 0) formatTs(state.createdAt, format24h) else null,
                onBack = onBack,
            )
            Text(
                text = state.query,
                style = MaterialTheme.typography.headlineMedium,
                color = HeedTheme.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = HeedLayout.Gutter, vertical = 8.dp),
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                return@Column
            }

            // No LazyColumn verticalArrangement.spacedBy — each section
            // manages its own spacing so the list sections (ranked items,
            // sources) can use tight rows with hairline dividers while the
            // standalone sections (query, answer, stages) keep breathing
            // room above/below.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = HeedLayout.Gutter,
                    end = HeedLayout.Gutter,
                    top = 0.dp,
                    bottom = 24.dp,
                ),
            ) {
                // Indexing banner — visible while the deferred-indexing
                // pipeline has work pending or is running. When the user
                // submitted this search with pending indexing, the banner
                // shows live progress; the search itself is gated on
                // SearchViewModel waiting for indexing to settle first.
                item("indexing_banner") {
                    com.bol.app.ui.components.IndexingBanner()
                    Spacer(Modifier.height(12.dp))
                }

                // QuerySection removed — the query lives in the header now,
                // and showing it again at 26sp display directly below would
                // be visual repetition with no extra information.

                when (state.status) {
                    SearchQueryStatus.PENDING, SearchQueryStatus.PROCESSING -> {
                        item { StageChecklist(state); Spacer(Modifier.height(14.dp)) }
                        item { StopButtonRow(onStop = viewModel::stopSearch) }
                    }
                    SearchQueryStatus.FAILED -> {
                        item { FailureSection(state.errorMessage); Spacer(Modifier.height(10.dp)) }
                        if (reRecordState is ReRecordState.Listening) {
                            item { ReRecordListeningRow(onStop = viewModel::cancelReRecord) }
                        } else if (viewModel.canRetry(state.status, state.query)) {
                            item {
                                RetryRow(
                                    onRetry = viewModel::retrySearch,
                                    onEditAndRetry = { editingQueryFor = state.query },
                                    onReRecord = if (state.isAudioQuery) viewModel::startReRecord else null,
                                )
                            }
                        }
                    }
                    SearchQueryStatus.CANCELLED -> {
                        item { CancelledSection(); Spacer(Modifier.height(10.dp)) }
                        if (reRecordState is ReRecordState.Listening) {
                            item { ReRecordListeningRow(onStop = viewModel::cancelReRecord) }
                        } else if (viewModel.canRetry(state.status, state.query)) {
                            item {
                                RetryRow(
                                    onRetry = viewModel::retrySearch,
                                    onEditAndRetry = { editingQueryFor = state.query },
                                    onReRecord = if (state.isAudioQuery) viewModel::startReRecord else null,
                                )
                            }
                        }
                    }
                    SearchQueryStatus.COMPLETED -> {
                        // A written answer only exists on searches from the Gemma builds; new searches store a
                        // result count, which the list shows and which is not an answer to copy or share.
                        state.answer?.takeIf { it.isNotBlank() && !SearchProcessingUseCase.isResultSummary(it) }?.let {
                            item { AnswerSection(it) }
                        }
                        if (state.rankedItems.isNotEmpty()) {
                            val totalCount = state.rankedItems.size
                            val hasTail = totalCount > DEFAULT_VISIBLE_RANKED
                            val visibleItems = if (hasTail && !showAllRanked) {
                                state.rankedItems.take(DEFAULT_VISIBLE_RANKED)
                            } else {
                                state.rankedItems
                            }
                            item {
                                SectionLabel("Tasks", trailing = totalCount.toString())
                            }
                            items(visibleItems, key = { it.id }) { item ->
                                RankedItemRow(
                                    item = item,
                                    format24h = format24h,
                                    category = categoriesById[item.categoryId],
                                    onMoveCategory = { movingCategoryFor = item },
                                    onEditReminder = { editingReminderFor = item },
                                    onEditText = { editingTextFor = item },
                                    onDelete = { viewModel.deleteItem(item.id) },
                                    onToggleDone = { viewModel.toggleItemCompleted(item.id) },
                                )
                            }
                            if (hasTail) {
                                item {
                                    MoreRelatedToggleRow(
                                        expanded = showAllRanked,
                                        hiddenCount = totalCount - DEFAULT_VISIBLE_RANKED,
                                        onClick = { showAllRanked = !showAllRanked },
                                    )
                                }
                            }
                        }
                        if (state.sources.isNotEmpty()) {
                            item {
                                SectionLabel("Voice notes", trailing = state.sources.size.toString())
                            }
                            items(state.sources, key = { it.id }) { src ->
                                SourceRow(
                                    src = src,
                                    format24h = format24h,
                                    onClick = { onOpenRecording(src.id) },
                                )
                            }
                        }
                        if (state.rankedItems.isEmpty() && state.sources.isEmpty()) {
                            item { NoReferencesNote() }
                        }
                    }
                }
            }
        }

        // Snackbar host docked above the bottom nav. Used for the Undo
        // affordance after a ranked item is deleted from the inline
        // expandable row.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }

    // Per-action dialogs — same vocabulary as CategoryDetail so the user
    // feels like the screens speak the same language. Each is mounted at
    // the screen level (above the LazyColumn) so it overlays correctly.
    movingCategoryFor?.let { item ->
        CategoryPickerDialog(
            currentCategoryId = item.categoryId,
            categories = allCategories,
            onPick = { newCategoryId ->
                viewModel.moveItem(item.id, newCategoryId)
                movingCategoryFor = null
            },
            onDismiss = { movingCategoryFor = null },
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
    editingQueryFor?.let { initialText ->
        EditQueryDialog(
            initialText = initialText,
            dictationState = dictationState,
            onStartDictation = viewModel::startDictation,
            onStopDictation = viewModel::stopDictation,
            onConsumeDictation = viewModel::consumeDictationDone,
            onSave = { newText ->
                viewModel.retryWithEditedQuery(newText)
                editingQueryFor = null
            },
            onDismiss = {
                viewModel.cancelDictation()
                editingQueryFor = null
            },
        )
    }
}

/**
 * "What's happening right now" checklist. Each row is one stage of the
 * search pipeline with a live/done/pending icon. Flattened — no Card
 * elevation — but a subtle tinted surface still distinguishes it from
 * the rest of the page.
 */
@Composable
private fun StageChecklist(state: SearchDetailUiState) {
    val current = state.stage
    val rows = buildList {
        // Only the stages search still runs (reranking and written answers were parked with Gemma).
        if (state.isAudioQuery) add(SearchStage.TRANSCRIBE to "Transcribe")
        add(SearchStage.RETRIEVE to "Search your notes")
    }

    HeedCard {
        Column(modifier = Modifier.padding(14.dp)) {
            rows.forEachIndexed { i, (stageKey, label) ->
                val phase = phaseFor(stageKey, current, rows)
                val isCurrent = stageKey == current
                val detail = if (isCurrent) state.stageDetail else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StageIcon(phase)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = HeedTheme.colors.textPrimary,
                        )
                        detail?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = HeedTheme.colors.textMuted,
                            )
                        }
                    }
                }
                if (i < rows.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private enum class StagePhase { DONE, CURRENT, PENDING }

private fun phaseFor(
    stage: String,
    current: String?,
    rows: List<Pair<String, String>>,
): StagePhase {
    if (current == null) return StagePhase.PENDING
    val currentIdx = rows.indexOfFirst { it.first == current }
    val rowIdx = rows.indexOfFirst { it.first == stage }
    return when {
        rowIdx < currentIdx -> StagePhase.DONE
        rowIdx == currentIdx -> StagePhase.CURRENT
        else -> StagePhase.PENDING
    }
}

@Composable
private fun StageIcon(phase: StagePhase) {
    when (phase) {
        StagePhase.DONE -> Surface(
            shape = CircleShape,
            color = HeedTheme.colors.brand,
            modifier = Modifier.size(18.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = HeedTheme.colors.onBrand,
                )
            }
        }
        StagePhase.CURRENT -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = HeedTheme.colors.brand,
        )
        StagePhase.PENDING -> Surface(
            shape = CircleShape,
            color = HeedTheme.colors.line,
            modifier = Modifier.size(12.dp),
        ) { Box {} }
    }
}

@Composable
private fun AnswerSection(answer: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    HeedCallout(title = "Answer", body = answer) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(answer))
                    BolHaptic.tap(context)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy answer",
                    tint = HeedTheme.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, answer)
                        putExtra(Intent.EXTRA_SUBJECT, "From Heed")
                    }
                    context.startActivity(Intent.createChooser(send, "Share answer"))
                    BolHaptic.tap(context)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.IosShare,
                    contentDescription = "Share answer",
                    tint = HeedTheme.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** "Show N more" / "Show fewer" under the task list; tap toggles full-list visibility. */
@Composable
private fun MoreRelatedToggleRow(
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        HeedButton(
            text = if (expanded) "Show fewer" else "Show $hiddenCount more",
            onClick = onClick,
            style = HeedButtonStyle.Text,
        )
    }
}

/**
 * Inline-expandable ranked search result row. Tap to expand and see metadata and act on the item without leaving
 * the surface. Title is textPrimary (struck through and muted only when the task is done).
 */
@Composable
private fun RankedItemRow(
    item: ItemEntity,
    format24h: Boolean,
    category: CategoryEntity?,
    onMoveCategory: () -> Unit,
    onEditReminder: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    onToggleDone: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val categoryLabel = category?.name ?: item.categoryId
    val colors = HeedTheme.colors

    val reminderLabel = item.reminderTime
        ?.takeUnless { item.isCompleted }
        ?.let { DateTimeDisplay.reminderRelative(it, format24h = format24h).uppercase() }
    val isOverdue = reminderLabel == "OVERDUE"
    val baseCaption = (categoryLabel + " · " + formatTs(item.createdAt, format24h)).uppercase()
    val captionText = if (reminderLabel == null) {
        AnnotatedString(baseCaption)
    } else {
        buildAnnotatedString {
            append("$baseCaption · ")
            if (isOverdue) {
                withStyle(SpanStyle(color = colors.danger)) {
                    append("🔔 $reminderLabel")
                }
            } else {
                append("🔔 $reminderLabel")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = item.text,
            titleMaxLines = 3,
            done = item.isCompleted,
            leading = { HeedCheckbox(checked = item.isCompleted, onCheckedChange = onToggleDone) },
            metaContent = {
                Text(
                    text = captionText,
                    style = BolMono.copy(color = colors.textMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            onClick = { expanded = !expanded },
        )

        if (expanded) {
            // Same expanded block as a bucket's task rows (CategoryDetailScreen).
            Column(modifier = Modifier.padding(start = 52.dp, bottom = 10.dp)) {
                FactLine(label = "Bucket") {
                    HeedChip(
                        label = category?.name ?: item.categoryId,
                        leading = {
                            BucketIcon(emoji = category?.icon ?: "📝", categoryId = item.categoryId, name = category?.name, size = 18.dp)
                        },
                        onClick = onMoveCategory,
                    )
                }
                FactLine(label = "Added", value = DateTimeDisplay.dateTime(item.createdAt, format24h))
                if (item.updatedAt > item.createdAt + 60_000) {
                    FactLine(label = "Updated", value = DateTimeDisplay.dateTime(item.updatedAt, format24h))
                }
                item.reminderTime?.let { rt ->
                    FactLine(label = "Reminder", value = DateTimeDisplay.dateTime(rt, format24h))
                }
                if (item.sourceRecordingId != null) {
                    FactLine(label = "Source", value = "from your voice")
                }
                Spacer(Modifier.height(6.dp))
                ActionRow {
                    HeedButton("Edit", onEditText, style = HeedButtonStyle.Text)
                    HeedButton(
                        if (item.reminderTime != null) "Edit reminder" else "Set reminder",
                        onEditReminder,
                        style = HeedButtonStyle.Text,
                    )
                    HeedButton("Delete", onDelete, style = HeedButtonStyle.DangerText)
                }
            }
        }
        RowDivider()
    }
}

/**
 * Flat row for a recording source snippet. Tap opens the recording (src.id is the recordingId persisted by
 * SearchProcessingUseCase).
 */
@Composable
private fun SourceRow(
    src: SourceInfo,
    format24h: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = src.snippet.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = HeedTheme.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (src.createdAt > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTs(src.createdAt, format24h).uppercase(),
                    style = BolMono.copy(color = HeedTheme.colors.textMuted),
                )
            }
        }
        RowDivider()
    }
}

@Composable
private fun StopButtonRow(onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        HeedButton("Stop", onStop, style = HeedButtonStyle.Danger)
    }
}

@Composable
private fun RetryRow(
    onRetry: () -> Unit,
    onEditAndRetry: () -> Unit,
    onReRecord: (() -> Unit)? = null,
) {
    WrapRow(modifier = Modifier.padding(top = 4.dp), spacing = 8.dp) {
        if (onReRecord != null) {
            HeedButton("Re-record", onReRecord, style = HeedButtonStyle.Outlined, icon = Icons.Default.Mic)
        }
        HeedButton("Edit & retry", onEditAndRetry, style = HeedButtonStyle.Outlined)
        HeedButton("Retry", onRetry)
    }
}

/** Replaces the [RetryRow] while voice re-recording is active. On voice completion the VM navigates to a fresh
 *  search detail, so this row only hosts the listening state. */
@Composable
private fun ReRecordListeningRow(onStop: () -> Unit) {
    HeedCallout(
        body = "Listening… speak now",
        icon = Icons.Default.Mic,
        tone = CalloutTone.Danger,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        TextButton(onClick = onStop) {
            Text("Cancel", color = HeedTheme.colors.danger)
        }
    }
}

/** Lets the user fix the search query before re-running. The escape hatch
 *  for Whisper mis-transcriptions. Type or tap the mic to dictate.
 *
 *  Live partials from STT engines that emit them (Google STT) flow into
 *  the text field as the user speaks; for Whisper the field stays still
 *  during capture and is replaced with the final transcription when the
 *  inline Whisper run finishes. */
@Composable
private fun EditQueryDialog(
    initialText: String,
    dictationState: DictationState,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onConsumeDictation: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialText) }
    val trimmed = value.trim()
    val isDictating = dictationState !is DictationState.Idle

    // Mirror live partials and finals into the dialog's local text state
    // so the user sees the dictation result and can edit it further.
    LaunchedEffect(dictationState) {
        when (val s = dictationState) {
            is DictationState.Listening -> if (s.partial.isNotEmpty()) value = s.partial
            is DictationState.Done -> {
                if (s.text.isNotBlank()) value = s.text
                onConsumeDictation()
            }
            else -> {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit & retry") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (!isDictating) value = it },
                    readOnly = isDictating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HeedLayout.ControlShape,
                    colors = heedTextFieldColors(),
                    label = { Text("Search query") },
                    singleLine = false,
                    maxLines = 4,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (dictationState is DictationState.Listening) onStopDictation()
                                else if (dictationState is DictationState.Idle) onStartDictation()
                            },
                            enabled = dictationState !is DictationState.Transcribing,
                        ) {
                            Icon(
                                imageVector = if (dictationState is DictationState.Listening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (dictationState is DictationState.Listening) "Stop dictation" else "Dictate",
                                tint = when (dictationState) {
                                    is DictationState.Listening -> HeedTheme.colors.danger
                                    is DictationState.Transcribing -> HeedTheme.colors.textMuted
                                    else -> HeedTheme.colors.brand
                                },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (dictationState) {
                        is DictationState.Listening -> "Listening… speak now"
                        is DictationState.Transcribing -> "Transcribing…"
                        else -> "Type or tap the mic to dictate."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = HeedTheme.colors.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = trimmed.isNotBlank() && !isDictating,
            ) { Text("Search") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CancelledSection() {
    HeedCallout(
        title = "Stopped by you",
        body = "Partial work is discarded. Tap Retry to run the same query again.",
    )
}

@Composable
private fun FailureSection(message: String?) {
    HeedCallout(
        title = "Search failed",
        body = message?.takeIf { it.isNotBlank() } ?: "Something went wrong.",
        tone = CalloutTone.Danger,
    )
}

/** A completed search with no ranked items and no recording sources. */
@Composable
private fun NoReferencesNote() {
    EmptyState(title = "Nothing found", body = "Try different words.", mood = BolMood.Confused)
}

private fun formatTs(millis: Long, format24h: Boolean): String {
    val timePattern = if (format24h) "HH:mm" else "h:mm a"
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(millis))

    // Calendar-day comparison — not a rolling 24h window. An item created
    // at 10:26 PM yesterday should read "Yesterday" at 10:44 AM today, not
    // "Today" just because the diff is < 24h. Anchor to start-of-today
    // midnight; everything at/after is today, the prior 24h is yesterday.
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = cal.timeInMillis
    val startOfYesterday = startOfToday - 86_400_000L

    return when {
        millis >= startOfToday -> "Today, $timeStr"
        millis >= startOfYesterday -> "Yesterday, $timeStr"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
    }
}
