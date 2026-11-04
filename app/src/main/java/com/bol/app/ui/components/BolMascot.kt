package com.bol.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Mood states for Bol — matches Bol.jsx mood list verbatim. */
enum class BolMood { Idle, Listening, Thinking, Happy, Sleeping, Confused, Party }

/**
 * Bol — the sprout-in-a-pot mascot. Rendered entirely in Compose Canvas so it can
 * animate smoothly and take on any size. The SVG viewBox from Bol.jsx is (-20, -20,
 * 240, 320) which we scale uniformly to `size`.
 *
 * Animation timings are kept in lock-step with the CSS keyframes in Bol.jsx:
 * - Body (breathe/lean/tilt/sway) duration, amplitude, and rotation ranges match.
 * - Left and right leaves animate with independent 4.4s / 4.6s (idle) or 4.0s / 4.2s
 *   (happy) periods, the right side in reverse direction.
 * - Blink uses precise @92/94/98/100 keyframes (closed during 94–98%).
 * - Leaves stay static on sleeping/thinking/confused (no leaf animation assigned in
 *   the source CSS).
 */
@Composable
fun BolMascot(
    mood: BolMood = BolMood.Idle,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    // Stable label so the InfiniteTransition survives mood changes instead of being
    // re-created (which would cancel in-flight animations).
    val infinite = rememberInfiniteTransition(label = "bol-mascot")

    // ---- Body ----
    val bodyDur = when (mood) {
        BolMood.Idle -> 3600
        BolMood.Listening -> 2400
        BolMood.Thinking -> 3200
        BolMood.Happy -> 3200
        BolMood.Sleeping -> 5000
        BolMood.Confused -> 1400
        BolMood.Party -> 2200
    }
    val bodyPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(bodyDur, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "body",
    )

    // ---- Sprig (top leaf) wiggle ----
    val sprigDur = when (mood) {
        BolMood.Confused -> 1200
        BolMood.Party -> 2200
        BolMood.Thinking -> 6000
        BolMood.Sleeping -> 8000
        else -> 3200
    }
    val sprigPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(sprigDur, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sprig",
    )

    // ---- Leaves (only idle/listen/happy/party animate — matches source CSS) ----
    val animateLeaves = mood == BolMood.Idle || mood == BolMood.Listening ||
        mood == BolMood.Happy || mood == BolMood.Party
    val leftLeafDur = when (mood) {
        BolMood.Happy -> 4000
        BolMood.Party -> 2400
        else -> 4400
    }
    val rightLeafDur = when (mood) {
        BolMood.Happy -> 4200
        BolMood.Party -> 2400
        else -> 4600
    }
    val leftLeafPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(leftLeafDur, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "leaf-l",
    )
    // Right side uses `reverse` animation-direction in CSS → start at target and
    // count back down, giving a phase-offset sine wave.
    val rightLeafPhase by infinite.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(rightLeafDur, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "leaf-r",
    )

    // ---- Blink — precise keyframes matching bol-blink @92/94/98/100 ----
    // Semantic: 0 = eyes fully open, 1 = eyes fully closed. We keep initial != target
    // so Compose's InfiniteTransition actually drives the animation (when they're
    // equal, the runtime optimises it to a static value and blinking never fires).
    val blinkDur = when (mood) {
        BolMood.Listening -> 6000
        else -> 5500
    }
    val blink by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = blinkDur
                0f at 0
                0f at (blinkDur * 92 / 100)
                1f at (blinkDur * 94 / 100)
                1f at (blinkDur * 98 / 100)
                0f at blinkDur
            },
        ),
        label = "blink",
    )

    // ---- Eye look-up (thinking) — keyframes 0/30/50/80/100 → translate -2px mid ----
    val eyeLook by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                0f at 0
                0f at (3200 * 30 / 100)
                1f at (3200 * 50 / 100)
                1f at (3200 * 80 / 100)
                0f at 3200
            },
        ),
        label = "eye-look",
    )

    // ---- Eye dart (confused) — keyframes 0/25/60/100: 0, -2, +2, 0 ----
    // initial != target so the runtime doesn't optimise this to a constant.
    val eyeDart by infinite.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3000
                0f at 0
                -1f at (3000 * 25 / 100)
                1f at (3000 * 60 / 100)
                0f at 3000
            },
        ),
        label = "eye-dart",
    )

    // ---- Per-mood body transforms (match keyframe apex exactly) ----
    val bodyDy: Float
    val bodyScaleX: Float
    val bodyScaleY: Float
    val bodyRotDeg: Float
    when (mood) {
        BolMood.Idle, BolMood.Sleeping -> {
            bodyDy = -3f * bodyPhase
            bodyScaleX = 1f + 0.025f * bodyPhase
            bodyScaleY = 1f + 0.030f * bodyPhase
            bodyRotDeg = 0f
        }
        BolMood.Happy -> {
            bodyDy = -4f * bodyPhase
            bodyScaleX = 1f + 0.030f * bodyPhase
            bodyScaleY = 1f + 0.040f * bodyPhase
            bodyRotDeg = -1.5f + 3.0f * bodyPhase
        }
        BolMood.Listening -> {
            bodyDy = -1f * bodyPhase
            bodyScaleX = 1f
            bodyScaleY = 1f
            bodyRotDeg = 2f * bodyPhase
        }
        BolMood.Thinking -> {
            bodyDy = 0f
            bodyScaleX = 1f
            bodyScaleY = 1f
            bodyRotDeg = -6f * bodyPhase
        }
        BolMood.Confused -> {
            bodyDy = 0f
            bodyScaleX = 1f
            bodyScaleY = 1f
            bodyRotDeg = -7f + 14f * bodyPhase
        }
        BolMood.Party -> {
            bodyDy = -3f * bodyPhase
            bodyScaleX = 1f
            bodyScaleY = 1f
            bodyRotDeg = -3f + 6f * bodyPhase
        }
    }

    // Leaf rotations per side
    val leftLeafRot = if (animateLeaves) -5f * leftLeafPhase else 0f
    val rightLeafRot = if (animateLeaves) 5f * rightLeafPhase else 0f

    // Sprig rotation — -3° to +3° (keyframe 0,100 at -3, 50 at +3)
    val sprigRot = -3f + 6f * sprigPhase

    // ---- Palette (exact values from Bol.jsx :root) ----
    val leaf = Color(0xFF9FC28F)
    val leafDark = Color(0xFF6F9764)
    val leafLight = Color(0xFFB9D4AC)
    val bulb = Color(0xFFA8CF97)
    val bulbShade = Color(0xFF7FA76F)
    val bulbHighlight = Color(0xFFC2DEB9).copy(alpha = 0.55f)
    val pot = Color(0xFFC97A55)
    val potShade = Color(0xFFA65F3E).copy(alpha = 0.4f)
    val soil = Color(0xFF3A2618)
    val soilSpeck = Color(0xFF2A1810)
    val ink = Color(0xFF2A1F18)
    val blush = Color(0xFFE9A9A0).copy(alpha = 0.6f)
    val pupil = Color(0xFFFFF9F0)
    val shadow = Color.Black.copy(alpha = 0.15f)

    Canvas(modifier = modifier.size(size, size * (240f / 200f))) {
        val scale = kotlin.math.min(this.size.width / 240f, this.size.height / 320f)
        fun sx(v: Float) = (v + 20f) * scale
        fun sy(v: Float) = (v + 20f) * scale
        fun sv(v: Float) = v * scale
        val strokeW = 3f * scale
        val strokeMedium = 2.5f * scale

        // Ground shadow — darker when body is resting on the pot (not animating)
        val shadowAlpha = if (mood == BolMood.Sleeping) 0.10f else 0.15f
        drawOval(
            color = shadow.copy(alpha = shadowAlpha),
            topLeft = Offset(sx(25f), sy(282f)),
            size = Size(sv(150f), sv(16f)),
        )

        // ---- POT (static) ----
        val potPath = Path().apply {
            moveTo(sx(32f), sy(220f))
            lineTo(sx(168f), sy(220f))
            lineTo(sx(158f), sy(286f))
            quadraticTo(sx(155f), sy(294f), sx(148f), sy(294f))
            lineTo(sx(52f), sy(294f))
            quadraticTo(sx(45f), sy(294f), sx(42f), sy(286f))
            close()
        }
        drawPath(potPath, color = pot)
        drawPath(potPath, color = ink, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Pot shade
        val potShadePath = Path().apply {
            moveTo(sx(150f), sy(226f))
            lineTo(sx(143f), sy(286f))
            quadraticTo(sx(141f), sy(290f), sx(137f), sy(290f))
            lineTo(sx(156f), sy(290f))
            quadraticTo(sx(159f), sy(288f), sx(160f), sy(286f))
            lineTo(sx(168f), sy(226f))
            close()
        }
        drawPath(potShadePath, color = potShade)

        // Pot rim ellipse
        drawOval(
            color = pot,
            topLeft = Offset(sx(28f), sy(210f)),
            size = Size(sv(144f), sv(20f)),
        )
        drawOval(
            color = ink,
            topLeft = Offset(sx(28f), sy(210f)),
            size = Size(sv(144f), sv(20f)),
            style = Stroke(width = strokeW),
        )

        // Soil + specks
        drawOval(
            color = soil,
            topLeft = Offset(sx(34f), sy(214f)),
            size = Size(sv(132f), sv(12f)),
        )
        listOf(72f to 221f, 120f to 223f, 140f to 220f, 90f to 222f).forEach { (x, y) ->
            drawCircle(soilSpeck, radius = sv(1.6f), center = Offset(sx(x), sy(y)))
        }

        // Dashed waist line
        drawLine(
            color = ink,
            start = Offset(sx(50f), sy(248f)),
            end = Offset(sx(150f), sy(248f)),
            strokeWidth = 2.2f * scale,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(sv(5f), sv(6f))),
            cap = StrokeCap.Round,
        )

        // ---- BODY (animated) ----
        val pivotX = sx(100f)
        val pivotY = sy(218f)

        // Body group animates as a whole (matches prototype's `.sp-body` CSS class):
        // translate → scale → rotate, all pivoting on the pot's rim (bottom-center).
        translate(left = 0f, top = sv(bodyDy)) {
         scale(scaleX = bodyScaleX, scaleY = bodyScaleY, pivot = Offset(pivotX, pivotY)) {
            rotate(degrees = bodyRotDeg, pivot = Offset(pivotX, pivotY)) {
                // Stem
                val stemPath = Path().apply {
                    moveTo(sx(96f), sy(218f))
                    quadraticTo(sx(94f), sy(180f), sx(96f), sy(110f))
                    lineTo(sx(104f), sy(110f))
                    quadraticTo(sx(106f), sy(180f), sx(104f), sy(218f))
                    close()
                }
                drawPath(stemPath, color = leafDark)
                drawPath(stemPath, color = ink, style = Stroke(width = strokeW))

                // LEFT leaf
                rotate(degrees = leftLeafRot, pivot = Offset(sx(96f), sy(185f))) {
                    val tip = Path().apply {
                        moveTo(sx(94f), sy(184f))
                        quadraticTo(sx(92f), sy(188f), sx(94f), sy(192f))
                        quadraticTo(sx(98f), sy(190f), sx(98f), sy(186f))
                        close()
                    }
                    drawPath(tip, color = leafDark)
                    drawPath(tip, color = ink, style = Stroke(width = strokeMedium))

                    val main = Path().apply {
                        moveTo(sx(96f), sy(186f))
                        quadraticTo(sx(78f), sy(176f), sx(54f), sy(168f))
                        quadraticTo(sx(34f), sy(170f), sx(28f), sy(148f))
                        quadraticTo(sx(38f), sy(132f), sx(62f), sy(140f))
                        quadraticTo(sx(86f), sy(154f), sx(96f), sy(178f))
                        quadraticTo(sx(98f), sy(184f), sx(96f), sy(186f))
                        close()
                    }
                    drawPath(main, color = leaf)
                    drawPath(main, color = ink, style = Stroke(width = strokeW))

                    val vein = Path().apply {
                        moveTo(sx(96f), sy(184f))
                        quadraticTo(sx(72f), sy(166f), sx(36f), sy(148f))
                    }
                    drawPath(vein, color = leafDark, style = Stroke(width = 1.5f * scale, cap = StrokeCap.Round))
                }

                // RIGHT leaf
                rotate(degrees = rightLeafRot, pivot = Offset(sx(104f), sy(185f))) {
                    val tip = Path().apply {
                        moveTo(sx(106f), sy(184f))
                        quadraticTo(sx(108f), sy(188f), sx(106f), sy(192f))
                        quadraticTo(sx(102f), sy(190f), sx(102f), sy(186f))
                        close()
                    }
                    drawPath(tip, color = leafDark)
                    drawPath(tip, color = ink, style = Stroke(width = strokeMedium))

                    val main = Path().apply {
                        moveTo(sx(104f), sy(186f))
                        quadraticTo(sx(122f), sy(176f), sx(146f), sy(168f))
                        quadraticTo(sx(166f), sy(170f), sx(172f), sy(148f))
                        quadraticTo(sx(162f), sy(132f), sx(138f), sy(140f))
                        quadraticTo(sx(114f), sy(154f), sx(104f), sy(178f))
                        quadraticTo(sx(102f), sy(184f), sx(104f), sy(186f))
                        close()
                    }
                    drawPath(main, color = leaf)
                    drawPath(main, color = ink, style = Stroke(width = strokeW))

                    val vein = Path().apply {
                        moveTo(sx(104f), sy(184f))
                        quadraticTo(sx(128f), sy(166f), sx(164f), sy(148f))
                    }
                    drawPath(vein, color = leafDark, style = Stroke(width = 1.5f * scale, cap = StrokeCap.Round))
                }

                // BULB — drawn at natural size; breathing scale comes from the parent
                // group's `scale()` wrapper, so the face/leaves/sprig all scale together.
                val bulbCenter = Offset(sx(100f), sy(150f))
                val bulbRx = sv(58f)
                val bulbRy = sv(64f)
                drawOval(
                    color = bulb,
                    topLeft = Offset(bulbCenter.x - bulbRx, bulbCenter.y - bulbRy),
                    size = Size(bulbRx * 2f, bulbRy * 2f),
                )
                drawOval(
                    color = ink,
                    topLeft = Offset(bulbCenter.x - bulbRx, bulbCenter.y - bulbRy),
                    size = Size(bulbRx * 2f, bulbRy * 2f),
                    style = Stroke(width = 3.5f * scale),
                )

                // Shade crescent
                val bulbShadePath = Path().apply {
                    moveTo(sx(150f), sy(135f))
                    quadraticTo(sx(156f), sy(165f), sx(138f), sy(200f))
                    quadraticTo(sx(122f), sy(210f), sx(108f), sy(210f))
                    quadraticTo(sx(140f), sy(204f), sx(148f), sy(180f))
                    quadraticTo(sx(152f), sy(160f), sx(148f), sy(138f))
                    close()
                }
                drawPath(bulbShadePath, color = bulbShade.copy(alpha = 0.6f))

                // Highlight
                drawOval(
                    color = bulbHighlight,
                    topLeft = Offset(sx(62f), sy(96f)),
                    size = Size(sv(32f), sv(44f)),
                )

                // SPRIG (top leaf) with its own rotation
                rotate(degrees = sprigRot, pivot = Offset(sx(100f), sy(95f))) {
                    val sprigStem = Path().apply {
                        moveTo(sx(97f), sy(95f))
                        quadraticTo(sx(96f), sy(78f), sx(98f), sy(58f))
                        lineTo(sx(103f), sy(58f))
                        quadraticTo(sx(104f), sy(78f), sx(103f), sy(95f))
                        close()
                    }
                    drawPath(sprigStem, color = leafDark)
                    drawPath(sprigStem, color = ink, style = Stroke(width = strokeMedium))

                    val sprigLeafPath = Path().apply {
                        moveTo(sx(102f), sy(62f))
                        quadraticTo(sx(116f), sy(54f), sx(118f), sy(40f))
                        quadraticTo(sx(114f), sy(34f), sx(106f), sy(40f))
                        quadraticTo(sx(100f), sy(50f), sx(101f), sy(60f))
                        close()
                    }
                    drawPath(sprigLeafPath, color = leafLight)
                    drawPath(sprigLeafPath, color = ink, style = Stroke(width = strokeMedium))
                }

                // FACE — blush, eyes, mouth
                drawOval(color = blush, topLeft = Offset(sx(62f), sy(163f)), size = Size(sv(20f), sv(10f)))
                drawOval(color = blush, topLeft = Offset(sx(118f), sy(163f)), size = Size(sv(20f), sv(10f)))

                // Eye shifts (confused = horizontal dart, thinking = up-look)
                val eyeDx = if (mood == BolMood.Confused) sv(2f * eyeDart) else 0f
                val eyeDy = if (mood == BolMood.Thinking) sv(-2f * eyeLook) else 0f
                val leftEye = Offset(sx(85f) + eyeDx, sy(150f) + eyeDy)
                val rightEye = Offset(sx(115f) + eyeDx, sy(150f) + eyeDy)

                when (mood) {
                    BolMood.Happy, BolMood.Party -> {
                        val leftArc = Path().apply {
                            moveTo(sx(79f), sy(152f))
                            quadraticTo(sx(85f), sy(144f), sx(91f), sy(152f))
                        }
                        val rightArc = Path().apply {
                            moveTo(sx(109f), sy(152f))
                            quadraticTo(sx(115f), sy(144f), sx(121f), sy(152f))
                        }
                        drawPath(leftArc, ink, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                        drawPath(rightArc, ink, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                    }
                    BolMood.Sleeping -> {
                        val leftArc = Path().apply {
                            moveTo(sx(79f), sy(151f))
                            quadraticTo(sx(85f), sy(154f), sx(91f), sy(151f))
                        }
                        val rightArc = Path().apply {
                            moveTo(sx(109f), sy(151f))
                            quadraticTo(sx(115f), sy(154f), sx(121f), sy(151f))
                        }
                        drawPath(leftArc, ink, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                        drawPath(rightArc, ink, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                    }
                    else -> {
                        // `blink` now runs 0 (open) → 1 (closed). Map to eye squash.
                        val openness = 1f - blink * 0.9f
                        val eyeR = sv(6f)
                        val eyeRy = eyeR * openness
                        drawOval(
                            color = ink,
                            topLeft = Offset(leftEye.x - eyeR, leftEye.y - eyeRy),
                            size = Size(eyeR * 2f, eyeRy * 2f),
                        )
                        drawOval(
                            color = ink,
                            topLeft = Offset(rightEye.x - eyeR, rightEye.y - eyeRy),
                            size = Size(eyeR * 2f, eyeRy * 2f),
                        )
                        if (blink < 0.5f) {
                            val pupilR = sv(2f)
                            drawCircle(pupil, radius = pupilR, center = Offset(leftEye.x + sv(2f), leftEye.y - sv(2f)))
                            drawCircle(pupil, radius = pupilR, center = Offset(rightEye.x + sv(2f), rightEye.y - sv(2f)))
                        }
                    }
                }

                // Mouth
                val mouthStroke = Stroke(width = 2.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                when (mood) {
                    BolMood.Listening -> {
                        drawOval(
                            color = ink,
                            topLeft = Offset(sx(95f), sy(173.5f)),
                            size = Size(sv(10f), sv(7f)),
                        )
                        drawOval(
                            color = Color(0xFF8A5A5A),
                            topLeft = Offset(sx(97f), sy(176.4f)),
                            size = Size(sv(6f), sv(3.2f)),
                        )
                    }
                    BolMood.Thinking -> {
                        val p = Path().apply {
                            moveTo(sx(94f), sy(178f))
                            quadraticTo(sx(100f), sy(176f), sx(106f), sy(178f))
                        }
                        drawPath(p, ink, style = mouthStroke)
                    }
                    BolMood.Happy, BolMood.Party -> {
                        val smile = Path().apply {
                            moveTo(sx(88f), sy(174f))
                            quadraticTo(sx(90f), sy(173f), sx(93f), sy(173f))
                            lineTo(sx(107f), sy(173f))
                            quadraticTo(sx(110f), sy(173f), sx(112f), sy(174f))
                            quadraticTo(sx(111f), sy(182f), sx(106f), sy(186f))
                            quadraticTo(sx(100f), sy(189f), sx(94f), sy(186f))
                            quadraticTo(sx(89f), sy(182f), sx(88f), sy(174f))
                            close()
                        }
                        drawPath(smile, ink)
                        drawPath(smile, ink, style = Stroke(width = 2.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        val tongue = Path().apply {
                            moveTo(sx(92f), sy(180f))
                            quadraticTo(sx(100f), sy(187f), sx(108f), sy(180f))
                            quadraticTo(sx(100f), sy(184f), sx(92f), sy(180f))
                            close()
                        }
                        drawPath(tongue, Color(0xFFE89488))
                    }
                    BolMood.Sleeping -> {
                        drawOval(
                            color = ink,
                            topLeft = Offset(sx(96f), sy(175.6f)),
                            size = Size(sv(8f), sv(4.8f)),
                        )
                    }
                    BolMood.Confused -> {
                        val wavy = Path().apply {
                            moveTo(sx(88f), sy(178f))
                            quadraticTo(sx(92f), sy(172f), sx(96f), sy(178f))
                            quadraticTo(sx(100f), sy(184f), sx(104f), sy(178f))
                            quadraticTo(sx(108f), sy(172f), sx(112f), sy(178f))
                        }
                        drawPath(wavy, ink, style = mouthStroke)
                    }
                    else -> {
                        val smile = Path().apply {
                            moveTo(sx(90f), sy(174f))
                            quadraticTo(sx(100f), sy(184f), sx(110f), sy(174f))
                        }
                        drawPath(smile, ink, style = mouthStroke)
                    }
                }
            }
         }
        }
    }
}
