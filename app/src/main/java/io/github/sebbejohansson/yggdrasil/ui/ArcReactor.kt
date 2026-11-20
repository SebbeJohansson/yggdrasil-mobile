package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One ring segment: a part, and how it's doing. */
data class ReactorPart(val name: String, val state: PartState)

enum class PartState { UP, TROUBLED, DOWN }

/**
 * my-widget's arc reactor (`ArcReactor.vue`): one ring segment per part,
 * cyan when up, amber when troubled, dark when down; a core that breathes,
 * faster while [busy] (the model is writing).
 */
@Composable
fun ArcReactor(parts: List<ReactorPart>, busy: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val pulse = rememberInfiniteTransition(label = "core")
    val scale by pulse.animateFloat(
        initialValue = 0.92f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(if (busy) 700 else 2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val words = parts.joinToString(", ") { "${it.name} ${it.state.name.lowercase()}" }

    Canvas(modifier.size(size).semantics { contentDescription = words }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val u = this.size.minDimension / 120f // the original's 120-unit viewBox

        drawCircle(Color(0xFF0A161D), 57 * u, c)
        drawCircle(Hud.surface1, 57 * u, c, style = Stroke(1.5f * u))
        drawCircle(
            Color(0xFF30535A), 52 * u, c,
            style = Stroke(1f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 3 * u))),
        )

        val n = parts.size.coerceAtLeast(1)
        val gap = 6f
        val sweep = 360f / n - gap
        parts.forEachIndexed { i, p ->
            val color = when (p.state) {
                PartState.UP -> Hud.cyan
                PartState.TROUBLED -> Hud.amber
                PartState.DOWN -> Color(0xFF1B3440)
            }
            val start = -90f + i * (360f / n) + gap / 2
            val r = 41 * u
            val w = 10 * u
            // A soft glow under a crisp segment.
            if (p.state != PartState.DOWN) {
                drawArc(
                    color.copy(alpha = 0.35f), start, sweep, false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(w * 1.5f),
                )
            }
            drawArc(
                color, start, sweep, false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(w),
            )
        }

        drawCircle(Color(0xFF071017), 28 * u, c)
        drawCircle(Color(0xFF4C7378), 28 * u, c, style = Stroke(1.2f * u))
        val core = 24 * u * scale
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFF4FEFF), 0.35f to Color(0xFF9CF0FF), 1f to Hud.cyan.copy(alpha = 0f),
                center = c, radius = core,
            ),
            core, c,
        )
        drawCircle(Color(0xFFF4FEFF).copy(alpha = 0.8f), 5 * u, c)
    }
}
