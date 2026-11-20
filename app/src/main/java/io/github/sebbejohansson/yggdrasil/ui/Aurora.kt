package io.github.sebbejohansson.yggdrasil.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Bifröst at night, ported from my-widget's `utils/aurora.ts` and
 * `Aurora.vue`: after the working day, or when the app has been left alone a
 * while, the ground turns into a slow aurora. A calm day is a slow green
 * aurora, a stormy one a restless violet one. Never red, no text, no numbers.
 */
object Aurora {
    /** Nothing touched for this long after the working day: the aurora comes. */
    const val IDLE_AFTER_HOURS_MS = 20_000L

    /** Nothing touched for this long during it. */
    const val IDLE_WORK_HOURS_MS = 5 * 60_000L

    /** The desktop's WORK_HOURS default: 09:00–17:00, Monday to Friday. */
    fun afterHours(now: LocalDateTime): Boolean =
        now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY || now.hour < 9 || now.hour >= 17

    fun due(idleMs: Long, afterHours: Boolean): Boolean =
        idleMs >= if (afterHours) IDLE_AFTER_HOURS_MS else IDLE_WORK_HOURS_MS

    data class SeenAlert(val severity: Severity, val lastSeenAt: Instant, val muted: Boolean)

    /** How much each thing today weighs towards a storm, as on the desktop. */
    private fun weight(s: Severity) = when (s) {
        Severity.INFO -> 0.08
        Severity.WARNING -> 0.35
        Severity.CRITICAL -> 0.6
    }
    private const val TROUBLED_PART = 0.2
    private const val STORM_SCALE = 1.2

    /** How stormy today has been: 0 calm, saturating towards 1. */
    fun storminess(alerts: List<SeenAlert>, troubledParts: Int, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Double {
        val today = now.atZone(zone).toLocalDate()
        var w = 0.0
        for (a in alerts) {
            if (a.muted || a.lastSeenAt.atZone(zone).toLocalDate() != today) continue
            w += weight(a.severity)
        }
        w += troubledParts.coerceAtLeast(0) * TROUBLED_PART
        return 1 - exp(-w / STORM_SCALE)
    }

    private val CALM = listOf(Color(90, 225, 160), Color(60, 200, 175), Color(80, 150, 200))
    private val STORMY = listOf(Color(150, 110, 235), Color(105, 95, 215), Color(185, 135, 235))

    data class Params(val palette: List<Color>, val speed: Float, val intensity: Float, val restlessness: Float)

    fun params(storm: Double): Params {
        val k = storm.toFloat().coerceIn(0f, 1f)
        return Params(
            palette = CALM.indices.map { mix(CALM[it], STORMY[it], k) },
            speed = 1 + 1.6f * k,
            intensity = 0.42f + 0.16f * k,
            restlessness = 0.25f + 0.75f * k,
        )
    }

    private fun mix(a: Color, b: Color, k: Float) =
        Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k)
}

/** The curtains. Draws frames only while [shown]; [alpha] fades it in and out. */
@Composable
fun AuroraSky(storm: Double, shown: Boolean, alpha: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val p = Aurora.params(storm)
    var t by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(shown, still, p.speed) {
        if (!shown || still) return@LaunchedEffect
        var last = 0L
        var drawn = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                if (now - drawn >= 1_000_000_000L / 30) {
                    drawn = now
                    t += min((now - last) / 1e9f, 0.1f) * p.speed
                    last = now
                    frame++
                }
            }
        }
    }
    if (alpha <= 0.001f) return

    Canvas(modifier.alpha(alpha)) {
        @Suppress("UNUSED_EXPRESSION") frame
        val w = size.width
        val h = size.height
        // A faint wash of the day's colour high in the sky.
        drawRect(
            Brush.verticalGradient(0f to p.palette[2].copy(alpha = 0.10f * p.intensity), 1f to Color.Transparent, endY = h * 0.7f),
        )
        val r = p.restlessness
        val phase = t * 0.05f
        val step = 2 * density
        val weights = floatArrayOf(1f, 0.75f, 0.55f)
        for (i in 0 until 3) {
            val c = p.palette[i]
            val base = h * (0.34f + 0.09f * i)
            var x = 0f
            while (x < w) {
                // The original's units are CSS pixels.
                val xc = x / density
                val wave = sin(xc * 0.018f + phase * 1.3f + i * 1.7f) * 0.6f +
                    sin(xc * (0.031f + 0.03f * r) - phase * (0.7f + r) + i * 2.3f) * 0.4f * r
                val y = base + h * 0.07f * wave
                val len = h * (0.16f + 0.1f * (0.5f + 0.5f * sin(xc * 0.045f + phase * 2 + i)))
                val shimmer = 0.5f + 0.5f * sin(xc * 0.07f + phase * (1.6f + 2 * r) + i * 1.3f)
                val a = min(1f, p.intensity * weights[i] * (0.3f + 0.7f * shimmer * shimmer))
                // A curtain strand: bright near its foot, fading up into the sky.
                drawRect(
                    Brush.verticalGradient(
                        0f to c.copy(alpha = 0f),
                        0.65f to c.copy(alpha = 0.45f * a),
                        0.92f to c.copy(alpha = a),
                        1f to c.copy(alpha = 0f),
                        startY = y - len, endY = y,
                    ),
                    topLeft = Offset(x, y - len),
                    size = Size(step, len),
                    blendMode = BlendMode.Plus,
                )
                x += step
            }
        }
    }
}
