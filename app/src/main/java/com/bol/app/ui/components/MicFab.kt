package com.bol.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.HeedTheme

/**
 * Brand-green mic FAB — simpler than the original prototype.
 *
 * The visual footprint IS `size` (no 2.2x container for a pulse-ring), which makes
 * it easy to dock above the bottom nav without it looking like it's floating in
 * mid-air. Two subtle animations run continuously:
 *
 *  - **mic-glow**: a breathing drop shadow (elevation 10dp ↔ 18dp, 2s ease-in-out).
 *  - **pulse-ring** (optional, via `pulse = true`): a faint halo that fades outward
 *    from the button edge via `drawBehind` with `graphicsLayer { clip = false }`,
 *    so the ring visually extends beyond the layout bounds without the button
 *    needing a larger layout region.
 */
@Composable
fun MicFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 28.dp,
    pulse: Boolean = true,
) {
    val infinite = rememberInfiniteTransition(label = "micfab")

    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring-scale",
    )
    val ringAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring-alpha",
    )
    val glow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    val brand = HeedTheme.colors.brand
    val onBrand = HeedTheme.colors.onBrand

    Box(
        modifier = modifier
            .size(size)
            // clip = false lets the pulse halo draw outside this Box without needing
            // a larger layout region — fixes the "FAB floating awkwardly above the nav"
            // issue caused by a 2.2x invisible container.
            .graphicsLayer { clip = false }
            .drawBehind {
                if (pulse) {
                    drawCircle(
                        color = brand.copy(alpha = ringAlpha),
                        radius = (this.size.minDimension / 2f) * ringScale,
                    )
                }
            }
            .shadow(
                elevation = (10 + 8 * glow).dp,
                shape = CircleShape,
                ambientColor = brand,
                spotColor = brand,
            )
            .clip(CircleShape)
            .background(brand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Record",
            tint = onBrand,
            modifier = Modifier.size(iconSize),
        )
    }
}
