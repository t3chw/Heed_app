package com.bol.app.ui.screens.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.data.local.entity.SearchQueryStatus
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.EmptyState
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.PageHeader
import com.bol.app.ui.components.RowDivider
import com.bol.app.domain.usecase.SearchProcessingUseCase
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.WrapRow
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pool of placeholder examples — the search bar live-cycles through them
 * while the field is empty, crossfading from one to the next every
 * [PlaceholderCycleMs] ms. Each variant teaches a different shape of
 * question (time, content, category) so the user picks up the engine's
 * range just by watching the prompt for a moment.
 */
private val placeholderExamples = listOf(
    "what did manager say?",
    "what's due tomorrow?",
    "what did i buy?",
    "what did i record?",
)

/** How long each placeholder example sits before crossfading to the next.
 *  3s is slow enough to read, fast enough to feel alive. */
private const val PlaceholderCycleMs = 3000L

/**
 * Suggestion chips on the empty state. Each one is a real query that
 * exercises a different branch of the search engine. Tapping one fires
 * the search immediately — both prefill + submit in one tap so the user
 * doesn't have to re-type after seeing the example.
 */
private val emptyStateSuggestions = listOf(
    "what's due today?",
    "what did i record yesterday?",
    "groceries to buy",
)

@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit = {},
    viewModel: SearchViewModel = koinViewModel()
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val queryText by viewModel.queryText.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H
    val lastDeleted by viewModel.lastDeleted.collectAsState()

    // Live-cycling placeholder. Starts at index 0 (deterministic per
    // launch — easier to reason about than a random initial pick) and
    // advances every PlaceholderCycleMs while SearchScreen is composed.
    // OutlinedTextField only renders the placeholder slot when the field
    // is empty, so the crossfade animation is invisible while the user
    // is typing — no flicker, no distraction. We don't gate the timer on
    // queryText.isBlank() because incrementing an int every 3s while the
    // animation is hidden costs nothing.
    var placeholderIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(PlaceholderCycleMs)
            placeholderIndex = (placeholderIndex + 1) % placeholderExamples.size
        }
    }
    val placeholder = placeholderExamples[placeholderIndex]

    val snackbarHostState = remember { SnackbarHostState() }

    // Show the Undo snackbar whenever the ViewModel captures a freshly
    // deleted row. Result is either ActionPerformed (user tapped Undo —
    // re-insert) or Dismissed (timed out — drop the held entity).
    LaunchedEffect(lastDeleted) {
        val deleted = lastDeleted ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Search deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.clearLastDeleted()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openDetail.collect { searchId -> onOpenDetail(searchId) }
    }

    // Hard-cancel voice on screen leave. Without this, the shared
    // RecordingSignal can outlive the screen if the user navigates away
    // mid-listening (system back, swipe to recents, etc.) — the signal
    // would stay true and bleed the focus-mode blur into Home's nav bar
    // until STT finally fires its FinalResult. cancelVoiceInput() is the
    // synchronous variant that destroys the engine and clears the signal
    // immediately, no waiting on the STT callback chain.
    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.voiceState.value is VoiceInputState.Listening) {
                viewModel.cancelVoiceInput()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceInput()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Fixed top region — header, search bar + dedicated mic chip,
            // optional listening indicator. Kept outside the LazyColumn so
            // the bar stays accessible while the user scrolls history.
            Column(modifier = Modifier.padding(horizontal = HeedLayout.Gutter)) {
                Spacer(Modifier.height(4.dp))
                PageHeader(
                    eyebrow = "Search",
                    title = "Find anything",
                    accent = "you said.",
                    mood = BolMood.Thinking,
                )
                Spacer(Modifier.height(4.dp))
                com.bol.app.ui.components.IndexingBanner()
                Spacer(Modifier.height(12.dp))
                SearchBar(
                    queryText = queryText,
                    placeholder = placeholder,
                    voiceState = voiceState,
                    onQueryChange = { viewModel.updateQuery(it) },
                    onSubmit = { viewModel.submitQuery() },
                    onVoiceStart = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onVoiceStop = { viewModel.stopVoiceInput() },
                )
                VoiceListeningIndicator(voiceState)
                Spacer(Modifier.height(12.dp))
            }

            // Scrollable area with focus-mode treatment during voice
            // listening: content gets a soft blur + a tap-absorbing scrim
            // sits on top so accidental row taps can't fire mid-speech.
            // The Search bar + mic chip above this Box stay crisp and
            // interactive — user can still hit the Stop chip.
            val isVoiceListening = voiceState is VoiceInputState.Listening
            val contentBlur by animateDpAsState(
                targetValue = if (isVoiceListening) 6.dp else 0.dp,
                animationSpec = tween(durationMillis = 260),
                label = "search-content-blur",
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().blur(contentBlur)) {
                    if (searchHistory.isEmpty()) {
                        EmptySearchState(onSuggestionTap = { viewModel.submitText(it) })
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = HeedLayout.Gutter, end = HeedLayout.Gutter, bottom = 20.dp),
                        ) {
                            item(key = "recent_label") { SectionLabel("Recent searches") }
                            itemsIndexed(searchHistory, key = { _, it -> it.id }) { index, item ->
                                if (index > 0) RowDivider()
                                SearchResultRow(
                                    item = item,
                                    format24h = format24h,
                                    onClick = { onOpenDetail(item.id) },
                                    onLongClick = { viewModel.deleteSearch(item.id) },
                                )
                            }
                        }
                    }
                }
                // Scrim alpha is animated directly so the layer fades in/out
                // smoothly without nesting AnimatedVisibility (which hits a
                // Compose scope-resolution quirk inside Box-in-Column-in-
                // Column). Skipping the Box entirely when alpha is ~0 keeps
                // pointerInput from absorbing taps when there's nothing to
                // dim.
                val scrimAlpha by animateFloatAsState(
                    targetValue = if (isVoiceListening) 1f else 0f,
                    animationSpec = tween(durationMillis = 260),
                    label = "search-scrim",
                )
                if (scrimAlpha > 0.001f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(com.bol.app.ui.theme.HeedTheme.colors.scrim.copy(alpha = com.bol.app.ui.theme.HeedTheme.colors.scrim.alpha * scrimAlpha))
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

/**
 * Search bar + dedicated voice mic chip side-by-side. Earlier the mic
 * was tucked inside the OutlinedTextField's trailingIcon, alongside the
 * Search action — cramped, and inconsistent with the app's mic-first
 * vocabulary (Home MicPill is the hero recording control). Lifting it
 * out into its own 52dp brand chip mirrors the Home affordance at a
 * smaller scale and gives the user a clear, large tap target.
 */
@Composable
private fun SearchBar(
    queryText: String,
    placeholder: String,
    voiceState: VoiceInputState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = queryText,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                // Crossfade between examples whenever the parent cycles
                // the placeholder string. Crossfade is much simpler than
                // AnimatedContent for this case (no size changes, no
                // direction; just text-to-text). 400ms tween feels like
                // a deliberate fade, not a snap.
                Crossfade(
                    targetState = placeholder,
                    animationSpec = tween(durationMillis = 400),
                    label = "placeholder-cycle",
                ) { current ->
                    Text(
                        text = current,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            },
            singleLine = true,
            shape = HeedLayout.ControlShape,
            colors = heedTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Clear only once there's text; the search arrow turns green when there's something to search.
                    if (queryText.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = HeedTheme.colors.textMuted)
                        }
                    }
                    IconButton(
                        onClick = onSubmit,
                        enabled = queryText.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (queryText.isNotBlank()) HeedTheme.colors.brand else HeedTheme.colors.textMuted,
                        )
                    }
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        VoiceMicChip(
            isListening = voiceState is VoiceInputState.Listening,
            onStart = onVoiceStart,
            onStop = onVoiceStop,
        )
    }
}

/**
 * 52dp rounded square that toggles voice input. Idle = soft brand wash with the
 * brand icon; listening = the recording colour with a light Stop icon,
 * so the active state is unmistakable at a glance. Same vocabulary as
 * the Home MicPill.
 */
@Composable
private fun VoiceMicChip(
    isListening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // Listening is recording: the recording colour, like Home's stop button.
    val bg = if (isListening) HeedTheme.colors.recording else HeedTheme.colors.brandSoft
    val fg = if (isListening) HeedTheme.colors.onRecording else HeedTheme.colors.brand
    Surface(
        onClick = if (isListening) onStop else onStart,
        shape = HeedLayout.ControlShape,
        color = bg,
        modifier = Modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop voice search" else "Voice search",
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Understated listening strip — spinner + partial text, no card. */
@Composable
private fun VoiceListeningIndicator(voiceState: VoiceInputState) {
    AnimatedVisibility(visible = voiceState is VoiceInputState.Listening) {
        val listening = voiceState as? VoiceInputState.Listening
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = HeedTheme.colors.brand,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = listening?.partialText?.ifBlank { "listening…" } ?: "listening…",
                fontFamily = BolFonts.Display,
                fontSize = 14.sp,
                color = HeedTheme.colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptySearchState(onSuggestionTap: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = HeedLayout.Gutter), contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Nothing to search yet",
            body = "Ask about anything you have said.",
            mood = BolMood.Idle,
        ) {
            SectionLabel("Try searching")
            WrapRow(spacing = 8.dp, centered = true, modifier = Modifier.fillMaxWidth()) {
                emptyStateSuggestions.forEach { suggestion ->
                    HeedChip(label = suggestion, onClick = { onSuggestionTap(suggestion) })
                }
            }
        }
    }
}

/**
 * Flat search result row — display-serif query title + status-dependent
 * body + mono-caps timestamp + hairline divider. Tap navigates to the
 * detail screen; long-press deletes via Undo snackbar.
 *
 * Earlier copy on FAILED / CANCELLED rows said "tap to retry" but the
 * tap actually navigated to the detail screen (where the Retry button
 * lives), confusing users. Dropped the misleading suffix; the status
 * word stands alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    item: SearchResultItem,
    format24h: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val mutedText = HeedTheme.colors.textMuted
    // A stored count ("25 results, best match first.") or no-results line goes on the date line, not as a body
    // line repeated on every row; an old written answer still shows as text.
    val answer = item.answer?.takeIf { it.isNotBlank() }
    val summary = item.status == SearchQueryStatus.COMPLETED && answer != null && SearchProcessingUseCase.isResultSummary(answer)
    val countLabel = if (summary) {
        answer!!.takeWhile { it.isDigit() }.toIntOrNull()?.let { n -> "$n result${if (n == 1) "" else "s"}" } ?: "no matches"
    } else null
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = item.query,
                style = MaterialTheme.typography.bodyLarge,
                color = HeedTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            when (item.status) {
                SearchQueryStatus.PENDING, SearchQueryStatus.PROCESSING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = HeedTheme.colors.brand,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "searching…",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = HeedTheme.colors.brand,
                            ),
                        )
                    }
                }
                SearchQueryStatus.COMPLETED -> {
                    answer?.takeIf { !summary }?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeedTheme.colors.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.sources.isNotEmpty()) {
                        if (!summary) Spacer(Modifier.height(4.dp))
                        item.sources.take(2).forEach { source ->
                            Text(
                                text = source.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                SearchQueryStatus.FAILED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(HeedTheme.colors.danger)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "search failed",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = HeedTheme.colors.danger,
                            ),
                        )
                    }
                }
                SearchQueryStatus.CANCELLED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(mutedText)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "stopped",
                            style = MaterialTheme.typography.bodySmall.copy(color = mutedText),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = listOfNotNull(formatTimestamp(item.createdAt, format24h), countLabel).joinToString(" · ").uppercase(),
                style = BolMono.copy(color = mutedText),
            )
        }
    }
}

/**
 * Small color dot for FAILED / CANCELLED status rows. Pairs with the
 * spinner used for PENDING / PROCESSING so all four states have a
 * consistent leading visual: motion-or-color → text. COMPLETED rows
 * skip this — the tinted answer surface is already the visual marker.
 */
@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun formatTimestamp(millis: Long, format24h: Boolean): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val dayMs = 86_400_000L
    val timePattern = if (format24h) "HH:mm" else "h:mm a"
    return when {
        diff < dayMs -> "Today, " + SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(millis))
        diff < 2 * dayMs -> "Yesterday, " + SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
    }
}
