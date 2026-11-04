package com.bol.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.bol.app.ui.theme.HeedTheme
import com.bol.app.ui.theme.brand
import com.bol.app.ui.theme.ink3
import com.bol.app.ui.theme.lineSoft
import com.bol.app.ui.theme.surfaceSofter

// ========== Panel (white card with rounded corners + line border) ==========
@Composable
fun BolPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column { content() }
    }
}

@Composable
fun PanelRow(
    label: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink3(),
                )
            }
        }
        if (right != null) {
            Spacer(Modifier.width(12.dp))
            right()
        }
    }
}

@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(lineSoft())
    )
}

// ========== Switch (match prototype's pill toggle) ==========
@Composable
fun BolSwitch(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (checked) brand() else surfaceSofter(),
        label = "switch-bg",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) 22.dp else 4.dp,
        label = "switch-knob",
    )
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onToggle() },
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobX, top = 3.dp)
                .size(20.dp)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(HeedTheme.colors.knob),
        )
    }
}

/**
 * Design's `fade-up` enter animation — translateY(12px→0) + opacity(0→1) over 500ms
 * with `cubic-bezier(.2, .9, .3, 1.2)` (slight overshoot). Pass `delayMs = i * 50`
 * to stagger grid/list items the way Home.jsx::CategoryCard and History.jsx::Recording
 * do.
 */
@Composable
fun Modifier.fadeUpEnter(delayMs: Int = 0, durationMs: Int = 500): Modifier {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(
            durationMillis = durationMs,
            delayMillis = delayMs,
            easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f),
        ),
        label = "fade-up",
    )
    return this
        .graphicsLayer { alpha = progress }
        .offset(y = 12.dp * (1f - progress))
}
