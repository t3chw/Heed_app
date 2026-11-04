package com.bol.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bol.app.ui.navigation.TaskDetailRoute
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bol.app.ai.model.ModelManager
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.preferences.ThemeMode
import com.bol.app.service.RecordingSignal
import com.bol.app.ui.navigation.BolNavGraph
import com.bol.app.ui.navigation.BottomNavBar
import com.bol.app.ui.navigation.HomeRoute
import com.bol.app.ui.navigation.OnboardingRoute
import com.bol.app.ui.theme.BolTheme
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    // When the user taps a reminder notification's body, the system re-launches
    // MainActivity with `open_item`/`open_category` extras. We surface that id via
    // a simple mutableStateOf so a LaunchedEffect can react and navigate.
    private val pendingDeepLink = androidx.compose.runtime.mutableStateOf<DeepLink?>(null)

    // Shared signal injected at the activity level so onCreate /
    // onNewIntent can fire pendingStart in response to widget taps
    // before the Compose tree is even alive.
    private val recordingSignalEager: RecordingSignal by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Widget tap (and any future notification action) sends
        // open_recording=true so we can start the same MicPill flow
        // the user gets from Home. Fire the shared signal here;
        // HomeScreen consumes it on the next composition.
        if (intent?.getBooleanExtra("open_recording", false) == true) {
            recordingSignalEager.requestStart()
        }
        pendingDeepLink.value = DeepLink.from(intent)
        enableEdgeToEdge()
        setContent {
            val preferencesManager: PreferencesManager = koinInject()
            val modelManager: ModelManager = koinInject()

            val themeModeKey by preferencesManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM.key)
            val themeMode = ThemeMode.fromKey(themeModeKey)
            val hasCompletedOnboarding by preferencesManager.hasCompletedOnboarding.collectAsState(initial = null)
            val backendBenchStatus by preferencesManager.backendBenchmarkStatus.collectAsState(initial = null)

            BolTheme(themeMode = themeMode) {
                // Wait for DataStore to load before deciding start destination
                val onboardingLoaded = hasCompletedOnboarding
                val benchStatusLoaded = backendBenchStatus
                if (onboardingLoaded == null || benchStatusLoaded == null) {
                    // Cold-start placeholder. Paints the home-screen
                    // colour while DataStore loads so the moment between
                    // the system splash dismissing and the real UI
                    // mounting doesn't flash a black surface.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    return@BolTheme
                }

                val navController = rememberNavController()

                val startDestination: Any = remember(onboardingLoaded) {
                    if (!onboardingLoaded) OnboardingRoute else HomeRoute
                }

                // Consume a pending reminder deep-link once the nav graph is ready.
                // Drops onto TaskDetailRoute — the per-task page is the right
                // home for a reminder tap because the user came to act on THIS
                // task, not browse anything else.
                //
                // popUpTo(HomeRoute) + launchSingleTop is the "open from
                // notification" idiom most apps use: clear whatever the user
                // was doing inside our app, leave Home as the back-stack root,
                // and push the task on top. Tapping back from the task always
                // returns to Home — predictable, matches Apple Reminders /
                // Google Tasks / Todoist. Without popUpTo, back could land
                // them on Settings (or wherever they were when the reminder
                // fired), which is jarring.
                val dl = pendingDeepLink.value
                if (dl != null && onboardingLoaded) {
                    LaunchedEffect(dl.itemId) {
                        navController.navigate(TaskDetailRoute(dl.itemId)) {
                            popUpTo<HomeRoute> { inclusive = false }
                            launchSingleTop = true
                        }
                        pendingDeepLink.value = null
                    }
                }

                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route.orEmpty()
                // Shared signal — true while the user is actively recording
                // on Home. Drives the nav-bar blur + scrim so tapping
                // elsewhere during a dump can't fire.
                val recordingSignal: RecordingSignal = koinInject()
                val isRecording by recordingSignal.isRecording.collectAsState()

                // Widget tap → MainActivity sets pendingStart on the same
                // signal. If the user happens to be on a non-Home screen
                // (Settings, Search, a category…) we route them to Home
                // here so HomeScreen can pick up the signal and run the
                // mic flow.
                //
                // The `currentEntry != null` guard is critical for cold
                // starts. `currentBackStackEntryAsState()` returns null
                // until NavHost has processed its startDestination push.
                // During that brief window `currentRoute` is the empty
                // string and `!"".contains("HomeRoute")` is true — which
                // would re-trigger a navigate(HomeRoute) on top of the
                // about-to-land HomeRoute. Even with launchSingleTop +
                // popUpTo, that re-composes the destination, disposes
                // the just-mounted HomeScreen, and HomeScreen's
                // DisposableEffect.onDispose calls stopRecording() —
                // killing the recording right after it started. By the
                // time currentEntry is non-null, we're either already
                // on HomeRoute (cold start, no nav needed) or on a
                // different route (warm start, nav needed); the guard
                // makes both paths correct.
                val pendingStart by recordingSignal.pendingStart.collectAsState()
                if (pendingStart && onboardingLoaded && currentEntry != null &&
                    !currentRoute.contains("HomeRoute")
                ) {
                    LaunchedEffect(Unit) {
                        navController.navigate(HomeRoute) {
                            popUpTo<HomeRoute> { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
                // CategoryDetail keeps the nav bar visible so the user can
                // jump to Search / History / Settings without first popping
                // back to Home — a small app shouldn't hide primary nav.
                // Full-screen flows (onboarding, first-run download, log
                // viewer) stay hidden since they're modal in nature.
                val showBottomBar = true
                    && !currentRoute.contains("OnboardingRoute")
                    && !currentRoute.contains("LogViewerRoute")

                // Animated nav-bar blur — matches HomeScreen's content blur
                // radius so the whole non-recording surface softens together.
                val navBlur by animateDpAsState(
                    targetValue = if (isRecording) 6.dp else 0.dp,
                    animationSpec = tween(durationMillis = 260),
                    label = "nav-blur",
                )

                // No Scaffold FAB — the Home screen handles the mic inline
                // via its MicPill (morphs in place, no modal). Scaffold just
                // owns the bottom nav and content insets.
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomBar) {
                            Box {
                                Box(modifier = Modifier.blur(navBlur)) {
                                    BottomNavBar(navController)
                                }
                                // Scrim + pointer-consume layer on top of the
                                // (already-blurred) nav while recording. Dim
                                // value matches the HomeScreen scrim so the
                                // two read as one continuous "focus mode"
                                // overlay. pointerInput swallows taps so nav
                                // buttons can't fire underneath.
                                if (isRecording) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(com.bol.app.ui.theme.HeedTheme.colors.scrim)
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
                    },
                ) { innerPadding ->
                    BolNavGraph(
                        navController = navController,
                        innerPadding = innerPadding,
                        startDestination = startDestination,
                    )
                }

            }
        }
    }

    // Handle reminder taps + widget taps when the activity is already
    // running. Without this, a second widget tap while the app is
    // foregrounded would do nothing — the new intent's open_recording
    // extra would be ignored because onCreate doesn't run again.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.from(intent)?.let { pendingDeepLink.value = it }
        if (intent.getBooleanExtra("open_recording", false)) {
            recordingSignalEager.requestStart()
        }
    }

    private data class DeepLink(val itemId: String, val categoryId: String) {
        companion object {
            fun from(intent: Intent?): DeepLink? {
                if (intent == null) return null
                val itemId = intent.getStringExtra("open_item") ?: return null
                val categoryId = intent.getStringExtra("open_category") ?: return null
                return DeepLink(itemId, categoryId)
            }
        }
    }
}
