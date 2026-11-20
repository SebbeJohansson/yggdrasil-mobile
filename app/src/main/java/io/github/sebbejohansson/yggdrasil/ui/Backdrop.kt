package io.github.sebbejohansson.yggdrasil.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The ground under every screen, ported from my-widget's `Backdrop.vue`: a
 * dot matrix where a soft band of light rolls across, faint specks rising
 * over it, and a horizon glow along the bottom in the colour of the time of
 * day. When more comes to need you ([needs] goes up), the next wave passes
 * amber once. With animations off in Android's settings it's one still frame.
 */
@Composable
fun Backdrop(needs: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val specks = remember {
        val r = Random(7)
        List(SPECKS) { Speck(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.2f, 0.08f + r.nextFloat() * 0.22f, 4f + r.nextFloat() * 6f, r.nextFloat() * 2 * PI.toFloat()) }
    }
    var t by remember { mutableFloatStateOf(0f) }
    var amber by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }
    val currentNeeds by rememberUpdatedState(needs)
    var lastNeeds by remember { mutableIntStateOf(needs) }

    LaunchedEffect(needs) {
        if (needs > lastNeeds) amber = 1f
        lastNeeds = needs
    }

    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        var drawn = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - drawn >= FRAME_NS) {
                    drawn = now
                    val real = min((now - last) / 1e9f, 0.1f)
                    last = now
                    val dt = real * SPEED
                    t += dt
                    amber = max(0f, amber - dt / WAVE_PERIOD)
                    specks.forEach { s ->
                        s.y -= s.v * dt / 900f
                        if (s.y < -0.02f) { s.y = 1.02f; s.x = Random.nextFloat() }
                    }
                    frame++
                }
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // read, so each frame redraws
        @Suppress("UNUSED_EXPRESSION") currentNeeds
        val w = size.width
        val h = size.height

        // The horizon: a soft band of sky colour along the bottom edge.
        val sky = horizonAt(LocalTime.now().let { it.hour + it.minute / 60f })
        val breath = 1f + HORIZON_BREATHE * sin(System.nanoTime() / 1e9f / HORIZON_BREATH_S * 2 * PI.toFloat())
        val strength = min(1f, sky.strength * breath)
        if (strength > 0f) {
            val top = h * (1 - HORIZON_REACH)
            drawRect(
                Brush.verticalGradient(
                    0f to sky.color.copy(alpha = 0f),
                    (1 - HORIZON_SOFTNESS) to sky.color.copy(alpha = strength * 0.35f),
                    1f to sky.color.copy(alpha = strength),
                    startY = top, endY = h,
                ),
                topLeft = Offset(0f, top), size = Size(w, h - top),
            )
        }

        // The dot matrix and its rolling wave.
        val step = DOT_STEP.dp.toPx()
        val phase = t / WAVE_PERIOD * 2 * PI.toFloat()
        val tint = min(1f, amber * 1.5f)
        val wave = lerp(Hud.cyan, Hud.amber, tint)
        val unit = 260.dp.toPx()
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                val glow = max(0f, sin((x * 0.7f + y * 0.7f) / unit - phase)).pow(6)
                val r = (1f + glow * 0.8f) * density
                val c = if (glow > 0.02f) wave.copy(alpha = DOTS + glow * 0.28f) else Hud.cyan.copy(alpha = DOTS)
                drawRect(c, Offset(x - r / 2, y - r / 2), Size(r, r))
                x += step
            }
            y += step
        }

        // Specks rising.
        for (s in specks) {
            val c = if (s.p > 4.5f) Hud.green else Hud.cyan
            drawCircle(
                c.copy(alpha = s.a),
                radius = s.r * density,
                center = Offset(s.x * w + sin(t * 0.15f + s.p) * 14 * density, s.y * h),
            )
        }
    }
}

private class Speck(var x: Float, var y: Float, val r: Float, val a: Float, val v: Float, val p: Float)

private const val SPEED = 8f
private const val WAVE_PERIOD = 40f
private const val DOT_STEP = 28
private const val SPECKS = 50
private const val DOTS = 0.07f
private const val FRAME_NS = 1_000_000_000L / 30
private const val HORIZON_REACH = 0.95f
private const val HORIZON_SOFTNESS = 0.35f
private const val HORIZON_BREATHE = 0.15f
private const val HORIZON_BREATH_S = 12f

private fun lerp(a: Color, b: Color, k: Float) = Color(
    a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, 1f,
)

data class Horizon(val color: Color, val strength: Float)

private class Key(val hour: Float, val r: Int, val g: Int, val b: Int, val strength: Float)

/** my-widget's `utils/horizon.ts`: coral at dawn, cyan by day, amber at dusk, indigo at night. */
private val KEYS = listOf(
    Key(0f, 40, 50, 130, 0.10f),
    Key(5.5f, 80, 60, 150, 0.12f),
    Key(7f, 255, 120, 110, 0.18f),
    Key(9f, 79, 216, 240, 0.08f),
    Key(14f, 79, 216, 240, 0.08f),
    Key(16.5f, 154, 230, 196, 0.10f),
    Key(18.5f, 255, 140, 60, 0.20f),
    Key(21f, 110, 70, 180, 0.14f),
    Key(24f, 40, 50, 130, 0.10f),
)

fun horizonAt(hour: Float): Horizon {
    val h = hour.coerceIn(0f, 24f)
    var i = 0
    while (i < KEYS.size - 2 && KEYS[i + 1].hour <= h) i++
    val a = KEYS[i]
    val b = KEYS[i + 1]
    val linear = (h - a.hour) / (b.hour - a.hour)
    val k = linear * linear * (3 - 2 * linear)
    fun mix(x: Int, y: Int) = (x + (y - x) * k) / 255f
    return Horizon(Color(mix(a.r, b.r), mix(a.g, b.g), mix(a.b, b.b)), a.strength + (b.strength - a.strength) * k)
}
