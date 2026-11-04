package com.bol.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.brand
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dense, terra-bar waveform animation. Bar heights are deterministic (same formula as
 * ui.jsx::Waveform) so the visual is stable across rebuilds; each bar animates
 * independently with a staggered period (0.8s + i*0.1s).
 */
@Composable
fun WaveformAnimation(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    bars: Int = 28,
    height: Dp = 48.dp,
    color: Color = brand(),
) {
    val base = remember(bars) {
        FloatArray(bars) { i -> 0.3f + 0.7f * abs(sin(i * 1.7f) * cos(i * 0.6f)) }
    }

    val infinite = rememberInfiniteTransition(label = "wave")
    val phases = (0 until bars).map { i ->
        infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800 + (i % 5) * 100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar-$i",
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val barWidth = 4.dp.toPx()
        val gap = 4.dp.toPx()
        val total = barWidth * bars + gap * (bars - 1)
        val startX = (size.width - total) / 2f
        val cy = size.height / 2f

        for (i in 0 until bars) {
            val h = (if (active) base[i] * phases[i].value else base[i] * 0.3f) * size.height
            val x = startX + i * (barWidth + gap)
            val y = cy - h / 2f
            drawRoundRect(
                color = color.copy(alpha = if (active) 1f else 0.3f),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
