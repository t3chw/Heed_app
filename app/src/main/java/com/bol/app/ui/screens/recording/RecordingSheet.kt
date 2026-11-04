package com.bol.app.ui.screens.recording

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bol.app.ui.components.BolMascot
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.WaveformAnimation
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.theme.PageTitle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@Composable
private fun BlinkingCaret() {
    val infinite = rememberInfiniteTransition(label = "caret")
    val caretAlpha by infinite.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caret-alpha",
    )
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(width = 2.dp, height = 18.dp)
            .graphicsLayer { this.alpha = caretAlpha }
            .background(HeedTheme.colors.brand)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSheet(
    onDismiss: () -> Unit,
    viewModel: RecordingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording() else onDismiss()
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) viewModel.startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = HeedTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val state = uiState) {
                is RecordingUiState.Idle -> {
                    Spacer(Modifier.height(24.dp))
                    Text("PREPARING…", style = BolMono.copy(color = HeedTheme.colors.textMuted))
                    BolMascot(mood = BolMood.Idle, size = 120.dp)
                    Spacer(Modifier.height(20.dp))
                }

                is RecordingUiState.Listening -> {
                    LaunchedEffect(Unit) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

                    Text("HEED IS LISTENING", style = BolMono.copy(color = HeedTheme.colors.textMuted))
                    Spacer(Modifier.height(18.dp))
                    BolMascot(mood = BolMood.Listening, size = 120.dp)
                    Spacer(Modifier.height(18.dp))
                    WaveformAnimation(bars = 36, height = 56.dp)

                    Spacer(Modifier.height(18.dp))
                    HeedCard {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "\"${state.partialText.ifBlank { "…" }}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = HeedTheme.colors.textPrimary,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // Blinking terra caret — design's float-dot 0.9s applied to a
                            // vertical bar mimics a text cursor at the transcript's tail.
                            BlinkingCaret()
                            Text(
                                text = "\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = HeedTheme.colors.textPrimary,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("TAP TO STOP", style = BolMono.copy(color = HeedTheme.colors.textMuted))

                    Spacer(Modifier.height(18.dp))
                    Surface(
                        onClick = { viewModel.stopRecording("sheet stop tapped") },
                        modifier = Modifier.size(68.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = HeedTheme.colors.recording,
                        shadowElevation = 8.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(com.bol.app.ui.theme.HeedTheme.colors.onRecording),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                is RecordingUiState.Queued -> {
                    LaunchedEffect(Unit) { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                    LaunchedEffect(Unit) {
                        delay(1800L)
                        viewModel.reset()
                        onDismiss()
                    }

                    Spacer(Modifier.height(24.dp))
                    var showCheck by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { showCheck = true }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("GOT IT!", style = BolMono.copy(color = HeedTheme.colors.textMuted))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Sorting your\nthoughts.",
                                style = PageTitle,
                                color = HeedTheme.colors.textPrimary,
                            )
                        }
                        AnimatedVisibility(visible = showCheck, enter = scaleIn()) {
                            BolMascot(mood = BolMood.Happy, size = 64.dp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "add another, or close the app — we've got this.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HeedTheme.colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))

                    HeedButton(
                        text = "Looks good",
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        style = HeedButtonStyle.Filled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is RecordingUiState.Error -> {
                    Spacer(Modifier.height(24.dp))
                    BolMascot(mood = BolMood.Confused, size = 96.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HeedTheme.colors.danger,
                    )
                    Spacer(Modifier.height(16.dp))
                    HeedButton(
                        text = "Try Again",
                        onClick = { viewModel.startRecording() },
                        style = HeedButtonStyle.Filled,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
