package com.bol.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.HeedTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Home-screen mic CTA.
 *
 * Idle: a white pill with a 2 dp brand border, a brand mic circle and "Tap to speak".
 * Recording: the pill collapses into a standalone stop-button in the recording colour —
 * no label, no pill chrome — and hand-drawn squiggle waveforms animate
 * out from both sides. The collapse is an [AnimatedContent] cross-fade
 * with a size transition, so the pill visually shrinks into the button
 * in place.
 *
 * The control is a single tap target — tap anywhere to toggle recording.
 * Long-pressing the idle pill opens a hidden text-entry sheet ([onLongClick])
 * for users who can't speak (meetings, late nights, accessibility). The
 * affordance is intentionally invisible to keep the voice-first brand
 * clean; users discover it via the one-shot tooltip after a few voice
 * entries, or via the hint in Settings → About.
 */
@Composable
fun MicPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) on each AnimatedVisibility so the waveforms expand
        // all the way to the screen edges when recording. When not
        // recording they collapse to zero, letting the pill sit centered
        // at its intrinsic width (no hidden weighted space).
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.weight(1f),
        ) {
            SquiggleWave(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                fadeFromLeft = true,
            )
        }
        AnimatedContent(
            targetState = isRecording,
            label = "mic-morph",
        ) { recording ->
            if (recording) StopCircle(onClick = onClick)
            else IdlePill(onClick = onClick, onLongClick = onLongClick)
        }
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.weight(1f),
        ) {
            SquiggleWave(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                fadeFromLeft = false,
            )
        }
    }
}

/** Full pill — mic icon + "Tap to speak" label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdlePill(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val infinite = rememberInfiniteTransition(label = "idle-pill")
    val glow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    val shape = RoundedCornerShape(28.dp)
    val colors = HeedTheme.colors

    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = Modifier
            .shadow(elevation = (2 + 2 * glow).dp, shape = shape, ambientColor = colors.brandShadow, spotColor = colors.brandShadow)
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, colors.brand, shape)
            .then(clickModifier)
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.brand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Record",
                tint = colors.onBrand,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Tap to speak",
            fontFamily = BolFonts.Display,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = colors.textPrimary,
        )
    }
}

/** Just a circle in the recording colour with a light stop square — no label, no pill. */
@Composable
private fun StopCircle(onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "stop-glow")
    val glow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    val colors = HeedTheme.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .shadow(
                elevation = (4 + 4 * glow).dp,
                shape = CircleShape,
                ambientColor = colors.recording,
                spotColor = colors.recording,
            )
            .clip(CircleShape)
            .background(colors.recording)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.onRecording),
        )
    }
}

/**
 * Hand-drawn squiggle that emanates from one side. Two layered sine paths
 * at different frequencies give the "organic" look; amplitude fades
 * quadratically toward the far edge so the wave visually radiates out
 * from the pill and dissipates like sound. Phase animates continuously
 * while this composable is in the tree, so the wave never sits still.
 *
 * [fadeFromLeft] = true means the LEFT edge is "near the pill" (full
 * amplitude) and the right edge fades. Set to false for the right-side
 * instance so both waves radiate outward from the central pill.
 */
@Composable
private fun SquiggleWave(
    modifier: Modifier = Modifier,
    fadeFromLeft: Boolean,
) {
    val infinite = rememberInfiniteTransition(label = "squiggle")
    val phase1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase1",
    )
    val phase2 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase2",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val waveColor = HeedTheme.colors.brand

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val midY = h / 2f
        val maxAmp = h * 0.42f * pulse

        fun ampAt(x: Float): Float {
            val distFromPill = if (fadeFromLeft) x / w else (w - x) / w
            val t = distFromPill.coerceIn(0f, 1f)
            val shaped = 1f - t * t
            return maxAmp * shaped
        }

        val twoPi = (2.0 * PI).toFloat()
        val step = 2f

        // Cycle counts scale with canvas width so the wave density stays
        // constant regardless of screen size — ~18dp per primary cycle,
        // ~12dp per secondary. Previously freq was hardcoded, which made
        // waves look sparse when the canvas got wider (edge-to-edge).
        data class Layer(val freq: Float, val phase: Float, val stroke: Float, val alpha: Float)
        val primaryCycles = (w / 18.dp.toPx()).coerceAtLeast(2f)
        val secondaryCycles = (w / 12.dp.toPx()).coerceAtLeast(3f)
        val layers = listOf(
            Layer(freq = primaryCycles, phase = phase1 * twoPi, stroke = 2.4.dp.toPx(), alpha = 0.85f),
            Layer(freq = secondaryCycles, phase = phase2 * twoPi + 1.3f, stroke = 1.6.dp.toPx(), alpha = 0.45f),
        )

        layers.forEach { layer ->
            val path = Path()
            var x = 0f
            var first = true
            while (x <= w) {
                val t = x / w
                val y = midY + sin(t * layer.freq * twoPi + layer.phase) * ampAt(x)
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += step
            }
            drawPath(
                path = path,
                color = waveColor.copy(alpha = layer.alpha),
                style = Stroke(width = layer.stroke, cap = StrokeCap.Round),
            )
        }
    }
}
