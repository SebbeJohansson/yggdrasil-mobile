package io.github.sebbejohansson.yggdrasil.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import kotlin.math.abs
import kotlin.math.min

// Grey-green stones in a dusk field, the runes cut pale into them.
private val TONES = listOf(Color(0xFF3E5759), Color(0xFF4A6362), Color(0xFF55706B))
private val TONE_EDGE = Color(0xFF2A3E40)
private val GROOVE = Color(0xFF2C4446)
private val CUT = Color(0xFFCFE0D8)
private val MOSS = Color(0xFF3C6B52)
private val DUSK_TOP = Color(0xFF0B1A22)
private val DUSK_LOW = Color(0xFF1A2E33)

/**
 * The runestones, as on the desktop's Runes page: a dusk field you scroll
 * along, oldest on the left. Each inscription is the moment in English,
 * written in Elder Futhark and drawn as strokes. Tap a stone for what it
 * says in plain words, and its day.
 */
@Composable
fun RunesScreen(vm: HistoryViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val (placed, fieldWidth) = remember(s.stones) { Runestones.lay(s.stones) }
    var picked by remember { mutableStateOf<Runestones.Stone?>(null) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val strokes = remember { Futhark.STROKES.mapValues { (_, d) -> PathParser().parsePathString(d).toPath() } }

    // Open on the newest stone, at the right.
    LaunchedEffect(s.stones.size) { scroll.scrollTo(scroll.maxValue) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text("Runestones", style = HudText.title)
            HudLabel(if (s.stones.isEmpty()) "none carved yet" else "${s.stones.size} carved")
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Field units to pixels: the field's height fills the space.
            Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
                    // About two and a half stones to a screen, the field's ground a little below the middle.
                    val heightPx = with(density) { maxHeight.toPx() }
                    val screenPx = LocalWindowInfo.current.containerSize.width.toFloat()
                    val unit = min(heightPx / Runestones.FIELD_HEIGHT.toFloat(), screenPx / 420f)
                    val lift = (heightPx * 0.62f - Runestones.GROUND_Y.toFloat() * unit).coerceAtLeast(0f)
                    val widthDp = with(density) { maxOf((fieldWidth * unit).toFloat(), screenPx).toDp() }
                    Canvas(
                        Modifier.width(widthDp).fillMaxHeight().clipToBounds().pointerInput(placed) {
                            detectTapGestures { tap ->
                                val x = tap.x / unit
                                val y = (tap.y - lift) / unit
                                picked = placed.lastOrNull { p ->
                                    abs(x - p.x) < p.shape.width / 2 * p.scale && y < p.y && y > p.y - p.shape.height * p.scale
                                }?.stone
                            }
                        },
                    ) {
                        drawRect(Brush.verticalGradient(listOf(DUSK_TOP, DUSK_LOW), endY = lift + Runestones.GROUND_Y.toFloat() * unit))
                        val ground = lift + (Runestones.GROUND_Y.toFloat() - 30f) * unit
                        drawRect(Brush.verticalGradient(listOf(Color(0xFF14252B), Color(0xFF0E1C21)), startY = ground, endY = size.height),
                            topLeft = Offset(0f, ground), size = androidx.compose.ui.geometry.Size(size.width, size.height - ground))
                        translate(0f, lift) { scale(unit, unit, pivot = Offset.Zero) { drawField(placed, strokes, picked) } }
                    }
                }
            }
            if (s.stones.isEmpty()) {
                Text(
                    "When something memorable happens, a stone is carved: the first thing Norn remembers, the first raven, " +
                        "a long storm passing, a week of peace.",
                    style = HudText.meta,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().height(96.dp).padding(16.dp)) {
            picked?.let { st ->
                HudLabel(st.carved, color = CUT)
                Text(st.meaning, style = HudText.small)
                Text(Runestones.dayWords(st.at), style = HudText.meta)
            } ?: Text("Tap a stone for what it says.", style = HudText.meta)
        }
    }
}

private fun stoneOutline(shape: Runestones.Shape): Path {
    val half = (shape.width / 2).toFloat()
    val h = shape.height.toFloat()
    val shoulder = h * (0.62f + 0.18f * (1 - shape.dome.toFloat()))
    val topX = (shape.peak * half * 0.6).toFloat()
    val swell = half * (1 + shape.bulge.toFloat())
    val dome = shape.dome.toFloat()
    val shoulderTop = -h + (h - shoulder) * (1 - dome)
    return Path().apply {
        moveTo(-half, 0f)
        cubicTo(-swell, -shoulder * 0.45f, -swell, -shoulder * 0.8f, -half * 0.92f, -shoulder)
        cubicTo(-half * 0.8f, shoulderTop, topX - half * 0.5f * dome, -h, topX, -h)
        cubicTo(topX + half * 0.5f * dome, -h, half * 0.8f, shoulderTop, half * 0.92f, -shoulder)
        cubicTo(swell, -shoulder * 0.8f, swell, -shoulder * 0.45f, half, 0f)
        close()
    }
}

private fun DrawScope.drawField(placed: List<Runestones.Placed>, strokes: Map<String, Path>, picked: Runestones.Stone?) {
    for (p in placed) {
        translate(p.x.toFloat(), p.y.toFloat()) {
            scale(p.scale.toFloat(), p.scale.toFloat(), pivot = Offset.Zero) {
                rotate(p.shape.lean.toFloat(), pivot = Offset.Zero) {
                    val outline = stoneOutline(p.shape)
                    drawOval(Color.Black.copy(alpha = 0.35f), Offset((-p.shape.width * 0.6).toFloat(), -6f),
                        androidx.compose.ui.geometry.Size((p.shape.width * 1.2).toFloat(), 12f))
                    drawPath(outline, TONES[p.shape.tone])
                    drawPath(outline, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                        startX = (-p.shape.width / 2).toFloat(), endX = (p.shape.width / 2).toFloat()))
                    drawPath(outline, if (picked?.id == p.stone.id) CUT else TONE_EDGE, style = Stroke(if (picked?.id == p.stone.id) 2f else 1.2f))
                    if (p.shape.moss > 0.35) {
                        drawOval(MOSS.copy(alpha = (p.shape.moss * 0.5).toFloat()), Offset((-p.shape.width * 0.45).toFloat(), -14f),
                            androidx.compose.ui.geometry.Size((p.shape.width * 0.9).toFloat(), 16f))
                    }
                    // The band the runes run in: a faint groove.
                    val band = Path().apply {
                        Runestones.band(p.shape).forEachIndexed { i, c ->
                            if (i == 0) moveTo(c.a.x.toFloat(), c.a.y.toFloat())
                            quadraticTo(c.c.x.toFloat(), c.c.y.toFloat(), c.b.x.toFloat(), c.b.y.toFloat())
                        }
                    }
                    drawPath(band, GROOVE, style = Stroke(1f))
                    for (rune in Runestones.carveRunes(p.shape, p.stone.runes)) {
                        val path = strokes.entries.firstOrNull { Futhark.STROKES[it.key] == rune.strokes }?.value ?: continue
                        translate(rune.x.toFloat(), rune.y.toFloat()) {
                            rotate(rune.degrees.toFloat(), pivot = Offset.Zero) {
                                scale(rune.scale.toFloat(), rune.scale.toFloat(), pivot = Offset.Zero) {
                                    translate((-rune.width / 2).toFloat(), -5f) {
                                        val pen = Stroke(width = 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        drawPath(path, Color.Black.copy(alpha = 0.35f), style = pen)
                                        translate(-0.25f, -0.25f) { drawPath(path, CUT, style = pen) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

