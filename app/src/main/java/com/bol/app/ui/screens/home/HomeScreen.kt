package com.bol.app.ui.screens.home

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bol.app.ai.model.ModelState
import com.bol.app.data.local.model.CategoryWithCount
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.AddBucketCard
import com.bol.app.ui.components.BolMascot
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.BolPermissionDialog
import com.bol.app.ui.components.BucketCard
import com.bol.app.ui.components.MicPill
import com.bol.app.ui.components.HeedCheckbox
import com.bol.app.ui.components.Leaving
import com.bol.app.ui.components.rememberTickToDone
import com.bol.app.ui.components.withHeld
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.components.ListRow
import com.bol.app.ui.components.PageHeader
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.SectionLabel
import com.bol.app.ui.components.heedTextFieldColors
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.screens.recording.RecordingUiState
import com.bol.app.ui.screens.recording.RecordingViewModel
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.BolMono
import com.bol.app.util.BolHaptic
import com.bol.app.util.DateTimeDisplay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun HomeScreen(
    onCategoryClick: (String) -> Unit,
    onItemClick: (String) -> Unit = {},
    onNavigateToAllRecent: () -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
    recordingViewModel: RecordingViewModel = koinViewModel(),
) {
    // No `initial =` arg: these are StateFlows, so collectAsState captures
    // the current cached value on the first composition frame. Passing an
    // explicit initial here would force the Flow overload and lose the
    // cached value, which re-introduces the transient-empty-frame scroll
    // reset on navigating back to Home.
    val categories by viewModel.categories.collectAsState()
    val recentItems by viewModel.recentItems.collectAsState()
    val queueCount by viewModel.pendingQueueCount.collectAsState(initial = 0)
    val modelState by viewModel.modelState.collectAsState()
    val processingStage by viewModel.processingStage.collectAsState()
    val processingHintShownCount by viewModel.processingHintShownCount.collectAsState(initial = Int.MAX_VALUE)
    val categoryError by viewModel.categoryError.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val currentDate by viewModel.currentDate.collectAsState()
    val recordingState by recordingViewModel.uiState.collectAsState()
    val isRecording = recordingState is RecordingUiState.Listening

    // Bump the "safe to close the app" hint counter once per processing
    // session — the moment the pill first appears. `Int.MAX_VALUE` initial
    // means we don't briefly flash the hint to long-time users while the
    // DataStore Flow is loading; the real value clamps below the cap fast.
    val pillVisible = processingStage != com.bol.app.ai.processing.ProcessingStage.IDLE || queueCount > 0
    LaunchedEffect(pillVisible) {
        if (pillVisible &&
            processingHintShownCount < com.bol.app.data.preferences.PreferencesManager.PROCESSING_HINT_MAX_SHOWS
        ) {
            viewModel.markProcessingHintShown()
        }
    }

    val context = LocalContext.current

    // Mic permission. If denied, we silently return to idle — the system
    // dialog is self-explanatory and popping our own error on top would
    // be noisy. Haptic fires after grant so the user feels the recording
    // actually starting, matching the sync-branch experience.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            BolHaptic.tap(context)
            recordingViewModel.startRecording()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.processingNotices.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // Toast on error, then reset so the pill is clickable again.
    LaunchedEffect(recordingState) {
        val state = recordingState
        if (state is RecordingUiState.Error) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            recordingViewModel.reset()
        }
    }

    // Once STT finalised and the recording got enqueued, reset so the pill
    // snaps back to idle. The "Sorting N recording…" processing pill
    // (driven by the voice-queue flow) takes over the feedback role — no
    // popup/sheet is needed.
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingUiState.Queued) {
            recordingViewModel.reset()
        }
    }

    // Auto-stop on navigation. When the user leaves Home (tab switch or
    // tapping into a category), stop an active recording so we don't keep
    // an orphaned STT engine alive. If the user had meaningful speech, the
    // onFinalResult path already enqueued it; stop just flushes the tail.
    DisposableEffect(Unit) {
        onDispose {
            if (recordingViewModel.uiState.value is RecordingUiState.Listening) {
                recordingViewModel.stopRecording("left Home")
            }
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<CategoryWithCount?>(null) }

    // First-tap mic-permission rationale. We show Heed's explainer once
    // before the OS permission dialog so the user knows *why* we want
    // the mic and *what we promise* about it. Subsequent denials route
    // straight to the OS prompt — no double-friction. `initial = true`
    // suppresses the dialog while the DataStore flow is loading on
    // first composition (would briefly read as "not shown" otherwise).
    val prefs: PreferencesManager = koinInject()
    val hasShownMicRationale by prefs.hasShownMicRationale.collectAsState(initial = true)
    val timeFormat by prefs.timeFormat.collectAsState(initial = PreferencesManager.TIME_FORMAT_12H)
    val format24h = timeFormat == PreferencesManager.TIME_FORMAT_24H
    val voiceEntryCount by prefs.voiceEntryCount.collectAsState(initial = 0)
    val hasShownTypeHint by prefs.hasShownTypeHint.collectAsState(initial = true)
    val hasShownAssistantHint by prefs.hasShownAssistantHint.collectAsState(initial = true)
    val hasShownBatteryHint by prefs.hasShownBatteryHint.collectAsState(initial = true)
    var showMicRationale by remember { mutableStateOf(false) }
    var showTypeSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val tick = rememberTickToDone<RecentItemRow>(snackbarHostState, viewModel::setDone)
    // A ticked task keeps its place for a moment (TickToDone); the list stays four rows long meanwhile.
    val shownRecent = tick.withHeld(recentItems, { it.item.id }, limit = 4)
    // Battery whitelist hint — heavy-OEM only (Oppo / Xiaomi / Vivo etc.).
    // The biggest single lever for lock-screen voice processing speed on
    // these phones; without it, on-device Pass-1 stretches from ~12s to
    // >180s. Re-checks isIgnoringBatteryOptimizations on every resume so
    // the card disappears immediately after the user toggles it on.
    // Highest priority of the three hints — fires earliest (3+ entries)
    // and suppresses the others while visible, because failed lock-screen
    // captures are a dealbreaker, not a missed convenience.
    val isHeavyOemForHint = remember { isHeavyThrottleOemHome() }
    var batteryOptIgnoredHome by remember(context) {
        mutableStateOf(checkBatteryOptStatusHome(context))
    }
    val lifecycleOwnerHome = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwnerHome) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryOptIgnoredHome = checkBatteryOptStatusHome(context)
            }
        }
        lifecycleOwnerHome.lifecycle.addObserver(obs)
        onDispose { lifecycleOwnerHome.lifecycle.removeObserver(obs) }
    }
    val showBatteryHint = isHeavyOemForHint && !batteryOptIgnoredHome && !hasShownBatteryHint &&
        voiceEntryCount >= 3 && !isRecording && !showTypeSheet
    // Long-press hint fires once after the user is comfortable with voice
    // (5+ entries). Hidden during recording / when the sheet is already up
    // / when the higher-priority battery hint is visible.
    val showTypeHint = voiceEntryCount >= 5 && !hasShownTypeHint && !isRecording && !showTypeSheet &&
        !showBatteryHint
    // Assistant hint fires later (15+ entries) — power users who'd benefit
    // from "long-press power → record from anywhere". We stage it after
    // the type hint so we don't stack two cards on the same screen.
    val showAssistantHint = voiceEntryCount >= 15 && hasShownTypeHint && !hasShownAssistantHint &&
        !isRecording && !showTypeSheet && !showTypeHint && !showBatteryHint

    // Widget tap → start the same MicPill flow the user gets from
    // tapping the on-screen mic. RecordingSignal.pendingStart is set
    // by MainActivity on widget intent; we consume it once here so a
    // second observer (e.g. recomposition) doesn't re-trigger.
    val recordingSignal: com.bol.app.service.RecordingSignal = koinInject()
    val pendingStart by recordingSignal.pendingStart.collectAsState()
    LaunchedEffect(pendingStart) {
        if (!pendingStart) return@LaunchedEffect
        if (isRecording) {
            // Widget tap while already recording — drop the signal,
            // keep recording. Toggle-stop would surprise the user
            // who tapped expecting "start a new note".
            recordingSignal.consumeStart()
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            BolHaptic.tap(context)
            recordingViewModel.startRecording()
        } else if (!hasShownMicRationale) {
            showMicRationale = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        recordingSignal.consumeStart()
    }

    categoryError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearCategoryError() },
            confirmButton = { TextButton(onClick = { viewModel.clearCategoryError() }) { Text("OK") } },
            text = { Text(error) }
        )
    }

    val totalItems = categories.sumOf { it.itemCount }
    val processingMsg = processingMessageFor(processingStage, queueCount, modelState)
    val showProcessingHint = pillVisible &&
        processingHintShownCount < com.bol.app.data.preferences.PreferencesManager.PROCESSING_HINT_MAX_SHOWS

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Animated blur radius — on API 31+ this softens everything behind
        // the recording UI; on older devices Modifier.blur is a silent
        // no-op and the scrim layer alone carries the "focus mode" cue.
        // Kept subtle (6dp) — content should look gently out of focus, not
        // frosted-glass obscured.
        val blurRadius by animateDpAsState(
            targetValue = if (isRecording) 6.dp else 0.dp,
            animationSpec = tween(durationMillis = 260),
            label = "home-blur",
        )

        // Single scrolling column: header + status + "Today" list + "BUCKETS"
        // grid. Bottom padding is generous (260dp) so that with the grid
        // layout (taller than the old horizontal strip) the last row of
        // buckets sits clearly above the MicPill, giving the pill visible
        // breathing room at rest and when the list is at max scroll.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 4.dp,
                bottom = 260.dp,
            ),
        ) {
            item("reminder_blocked_banner") {
                com.bol.app.ui.components.ReminderBlockedBanner(homeViewModel = viewModel)
            }

            item("header") {
                PageHeader(
                    eyebrow = currentDate,
                    title = greeting.removeSuffix("!"),
                    accent = "be here now.",
                    mood = BolMood.Happy,
                    meta = { StatusLine(totalItems = totalItems) },
                )
            }
            item("indexing_banner") {
                com.bol.app.ui.components.IndexingBanner()
            }

            // "Today" strip — 4 most recent active items. Hidden when the user
            // has no active items so the first-run screen stays clean.
            if (shownRecent.isNotEmpty()) {
                item("today_header") {
                    SectionLabel(
                        text = "Recent",
                        trailing = "${shownRecent.size} item${if (shownRecent.size == 1) "" else "s"}",
                        action = "See all",
                        onAction = onNavigateToAllRecent,
                    )
                }
                itemsIndexed(shownRecent, key = { _, row -> row.item.id }) { index, row ->
                    tick.Leaving(row.item.id) {
                        Column {
                            RecentItemRow(
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

            item("buckets_header") {
                SectionLabel("Buckets")
            }

            item("buckets_grid") {
                // 2-column grid, wrapped into rows. Non-lazy: with at most a
                // few dozen buckets, measuring them all is cheap and avoids
                // the nested-scrollable complexity of a LazyVerticalGrid
                // inside a LazyColumn. The whole Home scrolls together.
                //
                // Using `null` as an in-list marker for the AddBucketCard so
                // the chunking and spacer-fill code stays uniform. The
                // isNotEmpty guard is kept as a defensive measure even
                // though the old LazyRow scroll-anchor bug is no longer
                // applicable — if this ever moves into a lazy parent again
                // it stays correct.
                val cells: List<CategoryWithCount?> = buildList {
                    addAll(categories)
                    if (categories.isNotEmpty() &&
                        categories.size < CategoryRepository.MAX_CATEGORIES) {
                        add(null)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cells.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { cell ->
                                if (cell != null) {
                                    BucketCard(
                                        category = cell,
                                        onClick = { onCategoryClick(cell.id) },
                                        onLongClick = if (!cell.isDefault) {
                                            { showDeleteDialog = cell }
                                        } else null,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    AddBucketCard(
                                        onClick = { showCreateDialog = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            // Partial last row — fill with invisible weight(1f)
                            // spacers so its real cells don't stretch wider
                            // than cells in full rows.
                            repeat(2 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Recording scrim — dims the Home content and absorbs all taps so
        // mis-hits on buckets / scrolls don't interrupt a dump. Sits
        // between the content layer and the bottom cluster so the MicPill
        // stays both visible and interactive above it. Scrim fades in/out
        // with the recording state.
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.bol.app.ui.theme.HeedTheme.colors.scrim)
                    .pointerInput(Unit) {
                        // Swallow all taps on the content behind the scrim.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        // Listening character — Heed leans in attentively when the user
        // taps the mic. Sits above the dimmed scrim, below the still-
        // interactive MicPill, as the focal point of focus mode. Calm
        // via the existing BolMood.Listening (slight body sway + parted
        // mouth + 6 s blinks); little excited via the soft expanding
        // sound-wave rings around the mascot. Scales + fades in with the
        // scrim so the transition feels like one motion, not two.
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(tween(320)) + scaleIn(initialScale = 0.88f, animationSpec = tween(320)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ListeningAura(size = 240.dp)
                    BolMascot(mood = BolMood.Listening, size = 160.dp)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "I'm listening…",
                    fontFamily = BolFonts.Display,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    color = com.bol.app.ui.theme.HeedTheme.colors.onScrim,
                )
                Spacer(Modifier.height(10.dp))
                val secondsLeft by recordingViewModel.secondsLeft.collectAsState()
                RecordingTimeLeft(secondsLeft)
            }
        }

        // Docked bottom cluster: processing strip → MicPill. Intentionally
        // no gradient fade — on the cream background the alpha transition
        // was rendering as a visible horizontal seam across the buckets.
        // The MicPill isn't full-width so there's no hard edge to mask.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = processingMsg != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                if (processingMsg != null) {
                    // Only offer ✕ when the pill is about a recording in
                    // flight — model-download / load / error states aren't
                    // user-cancelable here.
                    val showCancel = queueCount > 0
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ProcessingPill(
                            message = processingMsg,
                            onCancel = if (showCancel) {
                                {
                                    BolHaptic.confirm(context)
                                    viewModel.cancelActiveRecordings()
                                }
                            } else null,
                        )
                        // First-N-times reassurance — shown for the user's
                        // first few processing sessions, then dropped. This
                        // is the safety net for users who tap-dismissed the
                        // recording sheet's "add another, or close the app"
                        // copy too fast to read it. After
                        // PROCESSING_HINT_MAX_SHOWS times the pill stands
                        // alone — they've internalised the model.
                        if (showProcessingHint) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "safe to close the app — we'll keep working",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }

            // Battery whitelist hint — heavy-OEM only. Highest priority:
            // the only hint that fixes a real performance problem, so it
            // fires first (3+ entries) and suppresses the others.
            AnimatedVisibility(
                visible = showBatteryHint,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                BatteryHintCard(
                    onAllow = {
                        coroutineScope.launch { prefs.setBatteryHintShown() }
                        openBatteryOptimizationSettingsFromHome(context)
                    },
                    onDismiss = {
                        coroutineScope.launch { prefs.setBatteryHintShown() }
                    },
                )
            }
            // One-shot tooltip — appears once the user has 5+ voice entries
            // and the hint hasn't been dismissed. Sits between the
            // processing strip and the MicPill, animates in/out so it
            // doesn't snap.
            AnimatedVisibility(
                visible = showTypeHint,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                TypeHintCard(
                    onDismiss = {
                        coroutineScope.launch { prefs.setTypeHintShown() }
                    },
                )
            }
            // Second one-shot — appears later (after the type hint is past)
            // for users who voice-capture often enough to benefit from the
            // long-press-power flow. "Set up" deep-links to system settings.
            AnimatedVisibility(
                visible = showAssistantHint,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                AssistantHintCard(
                    onSetUp = {
                        coroutineScope.launch { prefs.setAssistantHintShown() }
                        openAssistantSettingsFromHome(context)
                    },
                    onDismiss = {
                        coroutineScope.launch { prefs.setAssistantHintShown() }
                    },
                )
            }

            // "Marked done · Undo" after ticking a Recent task, just above the mic.
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            MicPill(
                isRecording = isRecording,
                onClick = {
                    if (isRecording) {
                        // BolHaptic uses the Vibrator service directly, so it
                        // fires even on devices where "touch vibration" is
                        // switched off in system settings (OnePlus default).
                        // Predefined EFFECT_HEAVY_CLICK on API 29+ / DEFAULT
                        // amplitude oneShot below that — noticeable on all.
                        BolHaptic.confirm(context)
                        recordingViewModel.stopRecording("stop tapped")
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            BolHaptic.tap(context)
                            recordingViewModel.startRecording()
                        } else if (!hasShownMicRationale) {
                            showMicRationale = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onLongClick = {
                    BolHaptic.confirm(context)
                    showTypeSheet = true
                    // Dismiss the tooltip too — once they've found it, the
                    // hint has done its job.
                    if (!hasShownTypeHint) {
                        coroutineScope.launch { prefs.setTypeHintShown() }
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // Type sheet — opened by long-pressing the MicPill. Submits typed text
    // through the same processing pipeline as voice. The pill itself
    // looks identical at rest; this affordance is intentionally hidden.
    if (showTypeSheet) {
        com.bol.app.ui.components.TypeSheet(
            onSubmit = { text ->
                recordingViewModel.submitTextEntry(text)
                showTypeSheet = false
            },
            onDismiss = { showTypeSheet = false },
        )
    }

    if (showCreateDialog) {
        CreateCategoryDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, icon, description ->
                viewModel.createCategory(name, icon, description)
                showCreateDialog = false
            }
        )
    }

    if (showMicRationale) {
        BolPermissionDialog(
            title = "Heed needs the mic",
            body = "Tap and speak — Heed will sort what you say. " +
                "Audio stays on this phone. Never uploaded anywhere.",
            onAllow = {
                showMicRationale = false
                coroutineScope.launch { prefs.setMicRationaleShown() }
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = {
                showMicRationale = false
                // Mark shown so the next mic tap skips our dialog and
                // goes straight to the OS prompt (or the recording flow
                // if the user has since granted).
                coroutineScope.launch { prefs.setMicRationaleShown() }
            },
        )
    }

    showDeleteDialog?.let { category ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete ${category.name}?") },
            text = {
                Text("This will delete the category and all ${category.itemCount} item${if (category.itemCount != 1) "s" else ""} in it. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(category.id)
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = HeedTheme.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatusLine(totalItems: Int) {
    val c = HeedTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(c.brand))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Private · on-device" +
                if (totalItems > 0) " · $totalItems task${if (totalItems == 1) "" else "s"}" else "",
            style = BolMono.copy(color = c.textMuted),
        )
    }
}

/** One row of the Recent list: the bucket's icon, the task, and "BUCKET · TIME-AGO" (+ reminder) under it. */
@Composable
private fun RecentItemRow(
    row: com.bol.app.ui.screens.home.RecentItemRow,
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

/**
 * One-shot tooltip suggesting the user make Heed their default Digital
 * Assistant. Fires after enough voice usage that power-shortcut value
 * is real — they'd benefit from "long-press power → record from
 * anywhere". Two buttons because the destination (system settings)
 * matters: Set up takes them there, Maybe later just dismisses.
 */
@Composable
private fun AssistantHintCard(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                text = "Want even faster?",
                fontFamily = BolFonts.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Make Heed your assistant — long-press power and capture from anywhere, even when locked.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(
                        text = "Maybe later",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onSetUp) {
                    Text(
                        text = "Set up",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * Heavy-OEM card. Shorter than AssistantHintCard because the value prop
 * is concrete (your locked-phone notes will be 10× faster) and the user
 * already had at least one slow lock-screen capture by the time this fires.
 * Two CTAs: Allow now (deep-link) and Maybe later (just dismiss).
 */
@Composable
private fun BatteryHintCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            Text(
                text = "One tap, much faster.",
                fontFamily = BolFonts.Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Your phone throttles background apps. Allow Heed to run unthrottled — locked-screen voice notes finish 10× faster.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(
                        text = "Maybe later",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onAllow) {
                    Text(
                        text = "Allow now",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * True if Heed is currently exempt from OS battery optimization. Wrapped
 * in try/catch because PowerManager.isIgnoringBatteryOptimizations can
 * throw on weird OEM ROMs.
 */
private fun checkBatteryOptStatusHome(context: android.content.Context): Boolean {
    return try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

/**
 * True for OEMs with aggressive background throttling. Mirror of the
 * Settings helper — kept inline here to avoid a cross-package dependency.
 */
private fun isHeavyThrottleOemHome(): Boolean {
    val mfr = android.os.Build.MANUFACTURER.lowercase()
    return mfr in listOf("oppo", "xiaomi", "vivo", "oneplus", "huawei", "realme", "honor")
}

/**
 * Routes to the OS battery-optimization whitelist. ACTION_REQUEST_IGNORE_*
 * lands directly on Heed's row with a single Allow prompt; falls back to
 * the broader screens if blocked.
 */
private fun openBatteryOptimizationSettingsFromHome(context: android.content.Context) {
    val intents = listOf(
        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        },
        android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        },
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* try next */ }
    }
}

/**
 * Tries the same fallback chain as Settings — direct Voice Input page,
 * then Default Apps, then top-level Settings. Returns silently on
 * failure (the row in Settings is still discoverable as a fallback).
 */
private fun openAssistantSettingsFromHome(context: android.content.Context) {
    val intents = listOf(
        android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* try next */ }
    }
}

/**
 * One-shot tooltip nudging the user to long-press the mic for typing. Sits
 * just above the MicPill and disappears for good when dismissed (the pref
 * flag flips). Intentionally small and quiet — the goal is to plant the
 * idea, not interrupt.
 */
@Composable
private fun TypeHintCard(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tip: long-press the mic to type instead.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(
                    text = "Got it",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Composed message that tells the user what the app is doing right now.
 *
 * Stage-aware: voice/search work drives copy that *changes* across the
 * pipeline ("Listening back…" → "Finding homes for it…") so the user sees
 * forward motion. The old queue-count phrasing ("Sorting 1 recording…")
 * read as static and was the main driver of the "feels slow" complaint —
 * users were watching the *same text* for 30+ s and concluding the app was
 * stuck.
 *
 * Model unpacking / error keep their own copy because they're not
 * voice-pipeline phases and have meaningful sub-state (% progress, retry
 * affordance) the user benefits from seeing.
 */
private fun processingMessageFor(
    stage: com.bol.app.ai.processing.ProcessingStage,
    queueCount: Int,
    state: ModelState,
): String? = when {
    state is ModelState.NotDownloaded -> "Getting Heed ready…"
    // Nothing to tap: the unpack is tried again on the next note and the next app start, and the usual cause is a
    // full disk.
    state is ModelState.Error -> "Couldn't open the models — free some space"
    stage == com.bol.app.ai.processing.ProcessingStage.TRANSCRIBING -> "Listening back…"
    stage == com.bol.app.ai.processing.ProcessingStage.CATEGORIZING -> "Finding homes for it…"
    stage == com.bol.app.ai.processing.ProcessingStage.SEARCHING -> "Pulling up your notes…"
    // Bridge case: queue has rows but BolProcessingService hasn't reported a
    // stage yet (sub-second window between enqueue and service start). Keep
    // the pill visible so it doesn't pop in/out, with neutral copy.
    queueCount > 0 -> "Sorting your thoughts…"
    else -> null
}

@Composable
private fun ProcessingPill(
    message: String,
    onCancel: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 14.dp,
                end = if (onCancel != null) 4.dp else 14.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.8.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                fontSize = 13.sp,
            )
            if (onCancel != null) {
                Spacer(Modifier.width(6.dp))
                // ✕ chip inside the pill — tappable to cancel the in-flight
                // recording(s). Tone-tinted so it reads as a "stop" action,
                // not a passive close.
                Surface(
                    onClick = onCancel,
                    shape = CircleShape,
                    color = com.bol.app.ui.theme.HeedTheme.colors.dangerSoft,
                    modifier = Modifier.size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = com.bol.app.ui.theme.HeedTheme.colors.danger,
                                fontWeight = FontWeight.Bold,
                            ),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, icon: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf("") }

    val emojiOptions = listOf(
        "🎯", "✅", "📋", "📝", "📚", "🧠", "💼", "📊",
        "💡", "🔧", "🚀", "🔥", "⏰", "📞", "🎵", "🎨",
        "🎮", "📷", "🎁", "⭐", "🌙", "☕", "🍎", "🏠",
        "✈️", "💪", "❤️", "🌿", "🌱", "☀️", "🧘", "💤",
    )

    if (selectedEmoji.isEmpty()) selectedEmoji = emojiOptions.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = HeedLayout.ControlShape,
                    colors = heedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 60) description = it },
                    label = { Text("Description (helps AI categorize)") },
                    singleLine = true,
                    shape = HeedLayout.ControlShape,
                    colors = heedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(emojiOptions) { emoji ->
                        Surface(
                            onClick = { selectedEmoji = emoji },
                            shape = RoundedCornerShape(8.dp),
                            color = if (emoji == selectedEmoji) HeedTheme.colors.brandSoft
                                    else HeedTheme.colors.surface,
                        ) {
                            Text(
                                text = emoji,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, selectedEmoji, description) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Time left in the recording. The last seconds grow into a large number, so the end is hard to miss. */
@Composable
private fun RecordingTimeLeft(secondsLeft: Int) {
    val ending = secondsLeft <= com.bol.app.ui.screens.recording.RecordingCountdown.WARNING_SECONDS
    Text(
        text = if (ending) "$secondsLeft" else "%d:%02d left".format(secondsLeft / 60, secondsLeft % 60),
        fontFamily = BolFonts.Display,
        fontWeight = if (ending) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = if (ending) 44.sp else 15.sp,
        color = if (ending) com.bol.app.ui.theme.HeedTheme.colors.recording else com.bol.app.ui.theme.HeedTheme.colors.onScrim.copy(alpha = 0.7f),
    )
}

/**
 * Soft expanding sound-wave rings rendered behind the listening Heed
 * mascot. Two rings staggered by half a period give a continuous
 * "I'm catching every word" pulse without screaming for attention.
 *
 * Drawn in Compose Canvas so it scales with the mascot and stays
 * lightweight — no Lottie / image assets needed for this. Uses white
 * over the dimmed scrim so the ripple reads on the cream theme.
 */
@Composable
private fun ListeningAura(size: Dp, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "listening-aura")
    val ring1 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
        ),
        label = "ring1",
    )
    val ring2 by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            initialStartOffset = StartOffset(1000),
        ),
        label = "ring2",
    )

    val ringColor = com.bol.app.ui.theme.HeedTheme.colors.onScrim
    Canvas(modifier = modifier.size(size)) {
        val maxRadius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val strokeWidth = 2.dp.toPx()
        // Two staggered ripples — radius grows from 55 % → 100 % of the
        // canvas, alpha fades from 0.45 → 0 across each cycle.
        listOf(ring1, ring2).forEach { progress ->
            val radius = maxRadius * (0.55f + 0.45f * progress)
            val alpha = (1f - progress) * 0.45f
            drawCircle(
                color = ringColor,
                radius = radius,
                alpha = alpha,
                style = Stroke(width = strokeWidth),
                center = center,
            )
        }
    }
}
