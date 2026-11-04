package com.bol.app.ui.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.ai.audio.AudioRecorder
import com.bol.app.ui.components.BolMascot
import com.bol.app.ui.components.BolMood
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedLayout
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.HeedTheme

/**
 * The face of the assistant flow. A single composable rendered by
 * [QuickCaptureActivity] over a dimmed scrim:
 *
 *   [scrim — tap dismisses]
 *      ┌──────────────────────────────────┐
 *      │  ◯  Bol mascot, listening        │
 *      │     wave wave wave (amplitude)   │
 *      │     "I'm listening..."           │
 *      │     [ Done ]                     │
 *      └──────────────────────────────────┘
 *
 * The card is intentionally minimal — Bol's brand voice in three lines.
 * Saving / Done states fade through quickly so the user isn't watching
 * progress.
 */
@Composable
fun QuickCaptureScreen(
    state: QuickCaptureUiState,
    recorderState: AudioRecorder.State,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.bol.app.ui.theme.HeedTheme.colors.scrim)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 360.dp)
                .clickable(
                    // Swallow taps inside the card so they don't bubble up
                    // to the scrim's dismiss handler.
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {},
                ),
            shape = RoundedCornerShape(20.dp),
            color = HeedTheme.colors.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Mascot layered over a soft expanding aura while recording.
                Box(contentAlignment = Alignment.Center) {
                    if (state is QuickCaptureUiState.Recording) {
                        ListeningAura(size = 180.dp)
                    }
                    BolMascot(
                        mood = when (state) {
                            QuickCaptureUiState.Recording -> BolMood.Listening
                            QuickCaptureUiState.Saving -> BolMood.Thinking
                            QuickCaptureUiState.Done -> BolMood.Happy
                            is QuickCaptureUiState.Error -> BolMood.Idle
                            QuickCaptureUiState.Idle -> BolMood.Idle
                        },
                        size = 120.dp,
                    )
                }

                Spacer(Modifier.height(14.dp))

                val title = when (state) {
                    QuickCaptureUiState.Idle -> "Ready when you are"
                    QuickCaptureUiState.Recording -> "I'm listening…"
                    QuickCaptureUiState.Saving -> "Got it"
                    QuickCaptureUiState.Done -> "Saved"
                    is QuickCaptureUiState.Error -> "Couldn't catch that"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = HeedTheme.colors.textPrimary,
                )

                Spacer(Modifier.height(6.dp))

                val sub: String = when (state) {
                    QuickCaptureUiState.Idle -> "Tap allow to grant the mic."
                    QuickCaptureUiState.Recording -> "Speak — I'll sort it later."
                    QuickCaptureUiState.Saving -> "Sorting in the background."
                    QuickCaptureUiState.Done -> "I'll buzz when it's tucked away."
                    is QuickCaptureUiState.Error -> state.message
                }
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeedTheme.colors.textMuted,
                )

                // Live waveform — amplitude-driven bars while recording.
                AnimatedVisibility(
                    visible = state is QuickCaptureUiState.Recording,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(160)),
                ) {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        Waveform(
                            amplitude = (recorderState as? AudioRecorder.State.Recording)?.amplitude ?: 0,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Done button only while recording. Saving / Done states
                // dismiss themselves so the user doesn't have to tap.
                if (state is QuickCaptureUiState.Recording) {
                    Surface(
                        onClick = onStop,
                        shape = HeedLayout.ControlShape,
                        color = HeedTheme.colors.recording,
                    ) {
                        Text(
                            text = "Done",
                            fontFamily = BolFonts.UI,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = HeedTheme.colors.onRecording,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
                        )
                    }
                } else if (state is QuickCaptureUiState.Error) {
                    HeedButton("Close", onClick = onCancel, style = HeedButtonStyle.Text)
                }
            }
        }
    }
}

/** Tiny bar-style waveform — five bars centered, height driven by amplitude. */
@Composable
private fun Waveform(amplitude: Int) {
    // Map amplitude (0..32767, but speech rarely exceeds ~10000) to a
    // 0..1 height multiplier. Clamp + mild compression.
    val normalized = (amplitude.toFloat() / 8000f).coerceIn(0f, 1f)
    val baseHeights = listOf(0.35f, 0.6f, 0.9f, 0.6f, 0.35f)

    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        baseHeights.forEach { ratio ->
            val h = (8.dp.value + (24.dp.value * ratio * (0.4f + 0.6f * normalized))).dp
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = h)
                    .background(HeedTheme.colors.brand, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/**
 * Two staggered expanding circles behind the mascot to make "listening"
 * feel alive. Reused vocabulary from HomeScreen's listening overlay so
 * users see the same animation in both places.
 */
@Composable
private fun ListeningAura(size: androidx.compose.ui.unit.Dp) {
    val infinite = rememberInfiniteTransition(label = "qc-aura")
    val ring1 by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "r1",
    )
    val ring2 by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(2000, easing = LinearEasing),
            RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(1000),
        ),
        label = "r2",
    )
    val ringColor = HeedTheme.colors.brand

    Canvas(modifier = Modifier.size(size)) {
        val maxR = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val stroke = 2.dp.toPx()
        listOf(ring1, ring2).forEach { p ->
            val r = maxR * (0.55f + 0.45f * p)
            val a = (1f - p) * 0.35f
            drawCircle(
                color = ringColor,
                radius = r,
                alpha = a,
                style = Stroke(width = stroke),
                center = center,
            )
        }
    }
}

