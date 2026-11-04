package com.bol.app.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.ui.components.BolMascot
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.BucketIcon
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.theme.PageTitle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class OnboardingPageData(
    val eyebrow: String,
    val title: String,
    val titleAccent: String,
    val body: String,
    val bg: Color,
    val mood: BolMood,
    val art: @Composable () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val preferencesManager: PreferencesManager = koinInject()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Mic permission step. Asked when the user taps "Let's go" on the
    // last onboarding page — after they've heard the "Private. Always."
    // promise and committed to entering the app. Symmetric with how
    // POST_NOTIFICATIONS is asked during ModelDownloadScreen, instead
    // of the previous asymmetric "ask mic on first tap of the mic"
    // pattern that surprised users mid-thought.
    //
    // Whatever the user picks (grant or deny), we mark onboarding
    // completed and proceed to the model-download / home flow. Denying
    // here is recoverable: the existing first-tap-of-mic re-prompt
    // path still fires the next time they actually try to record.
    val finishOnboarding: () -> Unit = {
        scope.launch { preferencesManager.setOnboardingCompleted() }
        onComplete()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result intentionally ignored — onboarding completes either
        // way; the in-app flow handles a denied state separately.
        finishOnboarding()
    }
    val onLetsGo: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            finishOnboarding()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val pages = listOf(
        OnboardingPageData(
            eyebrow = "01 / 03",
            title = "Just talk.",
            titleAccent = "",
            body = "Forget ten different apps. Say what's on your mind — Heed is listening.",
            bg = HeedTheme.colors.background,
            mood = BolMood.Happy,
            art = { TalkArt() },
        ),
        OnboardingPageData(
            eyebrow = "02 / 03",
            title = "I'll sort it.",
            titleAccent = "",
            body = "Groceries, reminders, ideas, movies — everything lands exactly where it belongs.",
            bg = HeedTheme.colors.background,
            mood = BolMood.Thinking,
            art = { SortArt() },
        ),
        OnboardingPageData(
            eyebrow = "03 / 03",
            title = "Private. Always.",
            titleAccent = "",
            body = "Runs on your phone. No accounts, no cloud, nothing leaves your pocket.",
            bg = HeedTheme.colors.background,
            mood = BolMood.Happy,
            art = { PrivateArt() },
        ),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState) { index ->
            val p = pages[index]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(p.bg),
            ) {
                // Skip — also prompts for mic permission so the user
                // doesn't land on Home with no permission and silently
                // fail their first recording.
                HeedButton(
                    "Skip",
                    onClick = onLetsGo,
                    style = HeedButtonStyle.Text,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 12.dp),
                )

                // Art area (top 52%) with Bol mascot hero
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.52f)
                        .align(Alignment.TopCenter),
                    contentAlignment = Alignment.Center,
                ) {
                    p.art()
                    Box(modifier = Modifier.offset(y = 40.dp)) {
                        BolMascot(mood = p.mood, size = 160.dp)
                    }
                }

                // Copy block
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = HeedLayout.Gutter)
                        .padding(bottom = 40.dp),
                ) {
                    Text(p.eyebrow, style = BolMono.copy(color = HeedTheme.colors.textMuted))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = p.title,
                        style = PageTitle.copy(color = HeedTheme.colors.textPrimary),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = p.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HeedTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(28.dp))

                    // Dots + CTA
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pages.forEachIndexed { i, _ ->
                                val w by animateDpAsState(
                                    if (i == pagerState.currentPage) 24.dp else 8.dp,
                                    label = "dot-$i",
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = w, height = 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (i == pagerState.currentPage) HeedTheme.colors.brand else HeedTheme.colors.line
                                        ),
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        if (pagerState.currentPage < 2) {
                            HeedButton(
                                "Next",
                                onClick = {
                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                },
                                style = HeedButtonStyle.Filled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            HeedButton(
                                "Let's go",
                                onClick = onLetsGo,
                                style = HeedButtonStyle.Filled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================================================
// Background illustrations
// ==========================================================================

@Composable
private fun TalkArt() {
    val bob = rememberInfiniteTransition(label = "talk")
    val b1 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3000, easing = EaseInOut), RepeatMode.Reverse), label = "b1")
    val b2 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3400, easing = EaseInOut), RepeatMode.Reverse), label = "b2")
    val b3 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3800, easing = EaseInOut), RepeatMode.Reverse), label = "b3")
    val b4 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3200, easing = EaseInOut), RepeatMode.Reverse), label = "b4")

    Box(modifier = Modifier.fillMaxSize()) {
        Bubble(text = "call mom 📞",
            modifier = Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 20.dp).graphicsLayer { translationY = -b1 })
        Bubble(text = "buy milk",
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 16.dp).graphicsLayer { translationY = -b2 })
        Bubble(text = "Dune 2 🎬",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 30.dp).graphicsLayer { translationY = -b3 })
        Bubble(text = "pitch idea 💡",
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp).graphicsLayer { translationY = -b4 })
    }
}

@Composable
private fun Bubble(text: String, modifier: Modifier = Modifier) {
    HeedCard(modifier = modifier.wrapContentWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = HeedTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun SortArt() {
    Box(modifier = Modifier.fillMaxSize()) {
        MiniBucket("General", "✅", "tasks", Modifier.align(Alignment.TopStart).padding(top = 24.dp, start = 16.dp))
        MiniBucket("Buy", "🛒", "buy", Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 16.dp))
        MiniBucket("Ideas", "💡", "ideas", Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        MiniBucket("Watch", "📺", "watch", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
    }
}

@Composable
private fun MiniBucket(name: String, emoji: String, categoryId: String, modifier: Modifier = Modifier) {
    HeedCard(modifier = modifier.wrapContentWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BucketIcon(emoji = emoji, categoryId = categoryId, name = name, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = HeedTheme.colors.textPrimary)
        }
    }
}

@Composable
private fun PrivateArt() {
    val bob = rememberInfiniteTransition(label = "priv")
    val b1 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3000, easing = EaseInOut), RepeatMode.Reverse), label = "p1")
    val b2 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3300, easing = EaseInOut), RepeatMode.Reverse), label = "p2")
    val b3 by bob.animateFloat(0f, 6f, infiniteRepeatable(tween(3600, easing = EaseInOut), RepeatMode.Reverse), label = "p3")

    Box(modifier = Modifier.fillMaxSize()) {
        HeedChip("On-device", selected = true, modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 28.dp).graphicsLayer { translationY = -b1 })
        HeedChip("No cloud", selected = true, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, top = 24.dp).graphicsLayer { translationY = -b2 })
        HeedChip("No account", selected = true, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, top = 24.dp).graphicsLayer { translationY = -b3 })
    }
}
