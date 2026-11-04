package com.bol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.HeedTheme

enum class BolButtonVariant { Primary, Brand, Ghost, Outline }

/**
 * A button with custom content (an icon and text). Same look as [HeedButton]: 48 dp, 10 dp corners, no shadow.
 * - Primary / Brand — brand green, on-brand text
 * - Ghost — no box, secondary text
 * - Outline — white with a line border
 */
@Composable
fun BolButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BolButtonVariant = BolButtonVariant.Primary,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = HeedTheme.colors
    val (bg, fg) = when (variant) {
        BolButtonVariant.Primary, BolButtonVariant.Brand -> c.brand to c.onBrand
        BolButtonVariant.Ghost -> Color.Transparent to c.textSecondary
        BolButtonVariant.Outline -> c.surface to c.textPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 120.dp)
            .alpha(if (enabled) 1f else 0.4f),
        shape = HeedLayout.ControlShape,
        color = bg,
        border = if (variant == BolButtonVariant.Outline) BorderStroke(1.dp, c.line) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                content()
            }
        }
    }
}

@Composable
fun BolButtonText(text: String) {
    Text(
        text = text,
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    )
}
