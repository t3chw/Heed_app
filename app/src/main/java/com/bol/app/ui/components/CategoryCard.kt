package com.bol.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.data.local.model.CategoryWithCount
import com.bol.app.ui.theme.BolFonts
import com.bol.app.ui.theme.BolMono
import com.bol.app.ui.theme.Tone
import com.bol.app.ui.theme.surfaceWash
import com.bol.app.ui.theme.text

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryCard(
    category: CategoryWithCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    flashing: Boolean = false,
    enterDelayMs: Int = 0,
) {
    val tone = Tone.forCategory(category.id, category.name)

    val infinite = rememberInfiniteTransition(label = "flash-${category.id}")
    val bob by infinite.animateFloat(
        initialValue = 0f,
        targetValue = if (flashing) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fadeUpEnter(delayMs = enterDelayMs)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp, y = (-20).dp)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(tone.surfaceWash()),
            )

            Column(modifier = Modifier.padding(12.dp)) {
                val keys = listOf("task", "buy", "watch", "idea", "work", "remind", "journal")
                val hasHandIcon = keys.any {
                    category.name.lowercase().startsWith(it) || category.id.lowercase().startsWith(it)
                }

                if (hasHandIcon) {
                    CatIcon(name = category.name, color = tone.text(), size = 22.dp)
                } else {
                    Text(text = category.icon, fontSize = 20.sp)
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = category.name,
                            fontFamily = BolFonts.Display,
                            fontWeight = FontWeight.Normal,
                            fontSize = 18.sp,
                            letterSpacing = (-0.2).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        val sub = category.latestItemText
                            ?.takeIf { it.isNotBlank() }
                            ?.let { if (it.length > 20) it.take(18) + "…" else it }
                            ?: if (category.itemCount == 0) "empty" else "${category.itemCount} item${if (category.itemCount == 1) "" else "s"}"
                        Text(
                            text = sub,
                            style = BolMono.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "${category.itemCount}",
                        fontFamily = BolFonts.Display,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontSize = 24.sp,
                        color = tone.text(),
                        modifier = Modifier.graphicsLayer {
                            if (flashing) {
                                translationY = -6f * bob
                                rotationZ = 2f * bob
                            }
                        },
                    )
                }
            }
        }
    }
}
