package com.bol.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Handdrawn 32x32 category icons — rendered as Compose paths to keep stroke width,
 * roundness, and proportions crisp at any density. Matches ui.jsx::CatIcon.
 */
@Composable
fun CatIcon(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 32f
        val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun moveQuad(path: Path, cx: Float, cy: Float, x: Float, y: Float) {
            path.quadraticBezierTo(cx * s, cy * s, x * s, y * s)
        }

        val key = name.trim().lowercase()
        when {
            key.startsWith("task") -> {
                // Checkbox with tick
                val box = Path().apply {
                    moveTo(p(5f, 6f).x, p(5f, 6f).y)
                    lineTo(p(27f, 6f).x, p(27f, 6f).y)
                    lineTo(p(27f, 28f).x, p(27f, 28f).y)
                    lineTo(p(5f, 28f).x, p(5f, 28f).y)
                    close()
                }
                drawPath(box, color, style = stroke)
                val tick = Path().apply {
                    moveTo(p(10f, 13f).x, p(10f, 13f).y)
                    lineTo(p(14f, 17f).x, p(14f, 17f).y)
                    lineTo(p(22f, 9f).x, p(22f, 9f).y)
                }
                drawPath(tick, color, style = stroke)
            }
            key.startsWith("buy") || key.startsWith("shop") -> {
                val bag = Path().apply {
                    moveTo(p(7f, 10f).x, p(7f, 10f).y)
                    lineTo(p(25f, 10f).x, p(25f, 10f).y)
                    lineTo(p(23f, 24f).x, p(23f, 24f).y)
                    lineTo(p(9f, 24f).x, p(9f, 24f).y)
                    close()
                }
                drawPath(bag, color, style = stroke)
                val handle = Path().apply {
                    moveTo(p(11f, 10f).x, p(11f, 10f).y)
                    lineTo(p(11f, 7f).x, p(11f, 7f).y)
                    moveQuad(this, 11f, 4f, 16f, 4f)
                    moveQuad(this, 21f, 4f, 21f, 7f)
                    lineTo(p(21f, 10f).x, p(21f, 10f).y)
                }
                drawPath(handle, color, style = stroke)
            }
            key.startsWith("watch") || key.startsWith("movie") -> {
                val screen = Path().apply {
                    moveTo(p(3f, 7f).x, p(3f, 7f).y)
                    lineTo(p(29f, 7f).x, p(29f, 7f).y)
                    lineTo(p(29f, 25f).x, p(29f, 25f).y)
                    lineTo(p(3f, 25f).x, p(3f, 25f).y)
                    close()
                }
                drawPath(screen, color, style = stroke)
                val play = Path().apply {
                    moveTo(p(14f, 13f).x, p(14f, 13f).y)
                    lineTo(p(20f, 16f).x, p(20f, 16f).y)
                    lineTo(p(14f, 19f).x, p(14f, 19f).y)
                    close()
                }
                drawPath(play, color)
            }
            key.startsWith("idea") -> {
                val bulb = Path().apply {
                    moveTo(p(16f, 4f).x, p(16f, 4f).y)
                    moveQuad(this, 10f, 4f, 8f, 10f)
                    moveQuad(this, 8f, 16f, 11f, 21f)
                    lineTo(p(21f, 21f).x, p(21f, 21f).y)
                    moveQuad(this, 24f, 16f, 24f, 10f)
                    moveQuad(this, 22f, 4f, 16f, 4f)
                    close()
                }
                drawPath(bulb, color, style = stroke)
                val base = Path().apply {
                    moveTo(p(12f, 24f).x, p(12f, 24f).y)
                    lineTo(p(20f, 24f).x, p(20f, 24f).y)
                    moveTo(p(13f, 27f).x, p(13f, 27f).y)
                    lineTo(p(19f, 27f).x, p(19f, 27f).y)
                }
                drawPath(base, color, style = stroke)
            }
            key.startsWith("work") -> {
                val case = Path().apply {
                    moveTo(p(4f, 10f).x, p(4f, 10f).y)
                    lineTo(p(28f, 10f).x, p(28f, 10f).y)
                    lineTo(p(28f, 27f).x, p(28f, 27f).y)
                    lineTo(p(4f, 27f).x, p(4f, 27f).y)
                    close()
                }
                drawPath(case, color, style = stroke)
                val handle = Path().apply {
                    moveTo(p(12f, 10f).x, p(12f, 10f).y)
                    lineTo(p(12f, 7f).x, p(12f, 7f).y)
                    moveQuad(this, 12f, 5f, 14f, 5f)
                    lineTo(p(18f, 5f).x, p(18f, 5f).y)
                    moveQuad(this, 20f, 5f, 20f, 7f)
                    lineTo(p(20f, 10f).x, p(20f, 10f).y)
                    moveTo(p(4f, 17f).x, p(4f, 17f).y)
                    lineTo(p(28f, 17f).x, p(28f, 17f).y)
                }
                drawPath(handle, color, style = stroke)
            }
            key.startsWith("remind") -> {
                val bell = Path().apply {
                    moveTo(p(7f, 23f).x, p(7f, 23f).y)
                    lineTo(p(7f, 15f).x, p(7f, 15f).y)
                    moveQuad(this, 7f, 9f, 16f, 9f)
                    moveQuad(this, 25f, 9f, 25f, 15f)
                    lineTo(p(25f, 23f).x, p(25f, 23f).y)
                    lineTo(p(27f, 26f).x, p(27f, 26f).y)
                    lineTo(p(5f, 26f).x, p(5f, 26f).y)
                    close()
                }
                drawPath(bell, color, style = stroke)
                val clapper = Path().apply {
                    moveTo(p(13f, 28f).x, p(13f, 28f).y)
                    moveQuad(this, 14f, 30f, 16f, 30f)
                    moveQuad(this, 18f, 30f, 19f, 28f)
                }
                drawPath(clapper, color, style = stroke)
            }
            key.startsWith("journal") -> {
                val book = Path().apply {
                    moveTo(p(7f, 5f).x, p(7f, 5f).y)
                    lineTo(p(24f, 5f).x, p(24f, 5f).y)
                    moveQuad(this, 26f, 5f, 26f, 7f)
                    lineTo(p(26f, 27f).x, p(26f, 27f).y)
                    lineTo(p(9f, 27f).x, p(9f, 27f).y)
                    moveQuad(this, 7f, 27f, 7f, 25f)
                    close()
                }
                drawPath(book, color, style = stroke)
                val lines = Path().apply {
                    moveTo(p(12f, 11f).x, p(12f, 11f).y)
                    lineTo(p(21f, 11f).x, p(21f, 11f).y)
                    moveTo(p(12f, 15f).x, p(12f, 15f).y)
                    lineTo(p(19f, 15f).x, p(19f, 15f).y)
                }
                drawPath(lines, color, style = stroke)
            }
            else -> {
                // Fallback — folder icon
                val folder = Path().apply {
                    moveTo(p(5f, 10f).x, p(5f, 10f).y)
                    lineTo(p(13f, 10f).x, p(13f, 10f).y)
                    lineTo(p(15f, 8f).x, p(15f, 8f).y)
                    lineTo(p(27f, 8f).x, p(27f, 8f).y)
                    lineTo(p(27f, 25f).x, p(27f, 25f).y)
                    lineTo(p(5f, 25f).x, p(5f, 25f).y)
                    close()
                }
                drawPath(folder, color, style = stroke)
            }
        }
    }
}
