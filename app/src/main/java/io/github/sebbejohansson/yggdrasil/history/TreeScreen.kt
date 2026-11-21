package io.github.sebbejohansson.yggdrasil.history

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

data class HistoryUiState(
    val events: List<HistoryEvent> = emptyList(),
    val tree: Tree.Grown? = null,
    val stones: List<Runestones.Stone> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val log: HistoryLog, private val gjallar: GjallarService) : ViewModel() {
    val state = combine(log.changes.flow, gjallar.changes.flow) { _, _ -> Unit }
        .mapLatest {
            val events = log.all()
            val open = gjallar.active().map { Runestones.OpenAlert(it.id, it.openedAt.toEpochMilli()) }
            HistoryUiState(events, Tree.grow(events), Runestones.carve(events, open, System.currentTimeMillis()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { HistoryViewModel(c.history, c.gjallar) } }
    }
}

// The dashboard's teal and sand (WorldTree.vue). Calm on purpose: no red.
private val BARK = Color(0xFF30535A)
private val BARK_LIT = Color(0xFF4C7378)
private val ROOT = Color(0xFF27454B)
private val GROUND_LINE = Color(0xFF23495A)
private val SCAR = Color(0xFF10232A)
private val SCAR_GOLD = Color(0xFFC9A24A)
private val RAVEN = Color(0xFF0E1F26)
private val RAVEN_SHEEN = Color(169, 196, 206, 153)
private val SELECTED = Color(0xFFE8C872)
private val LEAVES = mapOf(
    Tree.SeasonName.SPRING to listOf(Color(0xFF9AE6C4), Color(0xFFB9F0D9), Color(0xFF8FD6A8)),
    Tree.SeasonName.SUMMER to listOf(Color(0xFF4FB39A), Color(0xFF3C9A8B), Color(0xFF8FD6A8), Color(0xFF2F8079)),
    Tree.SeasonName.AUTUMN to listOf(Color(0xFF4FB39A), Color(0xFF3C9A8B), Color(0xFF8FD6A8)),
    Tree.SeasonName.WINTER to listOf(Color(0xFFA9C4CE), Color(0xFFD7EEF5)),
)
private val TURNED = listOf(Color(0xFFE8C872), Color(0xFFC9A24A), Color(0xFFD9B26A), Color(0xFFB8955A))

/** The top of the box is sky the crown seldom reaches: most of it is left out. */
private const val VIEW_TOP = 110.0

private data class Frame(val scale: Float, val left: Float, val top: Float)

private fun frameFor(size: Size): Frame {
    val view = Tree.SIZE - VIEW_TOP
    val scale = min(size.width / Tree.SIZE, size.height / view).toFloat()
    return Frame(scale, ((size.width - Tree.SIZE * scale) / 2).toFloat(), ((size.height - view * scale) / 2 - VIEW_TOP * scale).toFloat())
}

/** What a tap landed on: its words and day. */
private data class Picked(val words: String, val at: Long, val kind: String)

/**
 * The world tree, as on the desktop's Tree page: a branch for every fact Norn
 * came to remember, a scar for every alert that healed, a raven for every
 * time Huginn told you something. The leaves follow the seasons of your
 * weeks. Drag the slider to go back, Grow plays it from the first day; tap a
 * branch, scar or raven for what it was.
 */
@Composable
fun TreeScreen(vm: HistoryViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val tree = s.tree ?: Tree.grow(emptyList())
    val now = remember(s.events) { System.currentTimeMillis() }
    val start = Tree.timeStart(tree, now)
    var scrub by remember { mutableFloatStateOf(1f) }
    var growing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var clock by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(growing) {
        if (!growing) return@LaunchedEffect
        scrub = 0f
        while (scrub < 1f) {
            delay(33)
            scrub = (scrub + 0.004f).coerceAtMost(1f)
        }
        growing = false
    }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (t - last > 33_000_000) {
                    last = t
                    clock = t / 1e6f
                    frame++
                }
            }
        }
    }

    val at = start + ((now - start) * scrub).toLong()
    val moment = Tree.at(tree, at)
    val counts = "${moment.branches.count { it.branch.role == Tree.Role.GROWN }} branches · ${moment.scars.size} scars · ${moment.ravens.size} ravens"

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text("World tree", style = HudText.title)
            HudLabel("${moment.season.words} · $counts")
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(moment) {
                    detectTapGestures { tap ->
                        val f = frameFor(Size(size.width.toFloat(), size.height.toFloat()))
                        val p = Tree.Point(((tap.x - f.left) / f.scale).toDouble(), ((tap.y - f.top) / f.scale).toDouble())
                        picked = pick(moment, p)
                    }
                },
            ) {
                @Suppress("UNUSED_EXPRESSION") frame
                val f = frameFor(size)
                translate(f.left, f.top) {
                    scale(f.scale, f.scale, pivot = Offset.Zero) {
                        drawTree(moment, if (still) 0f else clock, picked)
                    }
                }
            }
            if (s.events.isEmpty()) {
                Text(
                    "Yggdrasil is bare yet. A branch grows for every fact Norn remembers, a scar for every alert that heals, " +
                        "and a raven comes each time Huginn tells you something.",
                    style = HudText.meta,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                )
            }
            picked?.let { p ->
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    HudLabel(p.kind, color = SELECTED)
                    Text(p.words, style = HudText.small)
                    Text(Tree.dayWords(p.at), style = HudText.meta)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                value = scrub, onValueChange = { scrub = it; growing = false; picked = null },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Hud.cyan, activeTrackColor = Hud.cyan, inactiveTrackColor = Hud.surface0),
            )
            HudButton(if (scrub < 1f) "Now" else "Grow", { if (scrub < 1f) { scrub = 1f; growing = false } else growing = true; picked = null }, kind = HudButtonKind.SMALL)
        }
        HudLabel(Tree.dayWords(at), modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), color = Hud.overlay0)
    }
}

private fun pick(m: Tree.Moment, p: Tree.Point): Picked? {
    val byIndex = m.branches.associateBy { it.index }
    m.ravens.minByOrNull { r -> byIndex[r.branch]?.let { val q = Tree.pointOn(it.branch.curve, r.t); hypot(q.x - p.x, q.y - 10 - p.y) } ?: 1e9 }?.let { r ->
        val live = byIndex[r.branch] ?: return@let
        val q = Tree.pointOn(live.branch.curve, r.t)
        if (hypot(q.x - p.x, q.y - 10 - p.y) < 24) return Picked(r.words, r.at, "Raven")
    }
    m.scars.forEach { sc ->
        val live = byIndex[sc.branch] ?: return@forEach
        val q = Tree.pointOn(live.branch.curve, sc.t)
        if (hypot(q.x - p.x, q.y - p.y) < 16) return Picked(sc.words, sc.at, "Scar")
    }
    var best: Tree.LiveBranch? = null
    var bestD = 30.0
    for (live in m.branches) {
        if (live.branch.role != Tree.Role.GROWN) continue
        for (i in 0..10) {
            val q = Tree.pointOn(live.curve, i / 10.0)
            val d = hypot(q.x - p.x, q.y - p.y)
            if (d < bestD) { bestD = d; best = live }
        }
    }
    return best?.let { Picked(it.branch.words.orEmpty(), it.branch.at ?: 0, "Branch") }
}

/** A branch as a tapering shape: wide where it starts, thin at its tip. */
private fun outline(live: Tree.LiveBranch): List<Tree.Point> {
    val steps = if (live.branch.role == Tree.Role.GROWN) 8 else 14
    val tip = when (live.branch.role) { Tree.Role.ROOT -> 0.12; Tree.Role.TRUNK -> 0.5; else -> 0.35 }
    val left = mutableListOf<Tree.Point>()
    val right = mutableListOf<Tree.Point>()
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val p = Tree.pointOn(live.curve, t)
        val angle = Tree.angleOn(live.curve, min(t, 0.999)) + PI / 2
        var half = live.width / 2 * (1 - (1 - tip) * t)
        if (live.branch.role == Tree.Role.TRUNK) half *= 1 + 0.55 * maxOf(0.0, 0.18 - t) / 0.18 // the flare at its foot
        left += Tree.Point(p.x + cos(angle) * half, p.y + sin(angle) * half)
        right += Tree.Point(p.x - cos(angle) * half, p.y - sin(angle) * half)
    }
    return left + right.reversed()
}

private fun path(points: List<Tree.Point>, close: Boolean = true) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat()) }
    if (close) close()
}

private fun hash01(n: Int): Double {
    val x = sin(n * 12.9898) * 43758.5453
    return x - kotlin.math.floor(x)
}

private fun DrawScope.drawTree(m: Tree.Moment, clock: Float, picked: Picked?) {
    val g = Tree.GROUND.toFloat()
    drawCircle(
        Brush.radialGradient(listOf(Hud.cyan.copy(alpha = 0.06f), Color.Transparent), center = Offset(500f, g), radius = 420f),
        radius = 420f, center = Offset(500f, g),
    )
    drawLine(GROUND_LINE, Offset(120f, g), Offset(880f, g), strokeWidth = 1.5f)

    // Roots first, then the wood from the trunk out, so younger branches lie on top.
    val order = m.branches.sortedWith(compareBy<Tree.LiveBranch> { if (it.branch.role == Tree.Role.ROOT) 0 else 1 }.thenBy { it.branch.depth })
    for (live in order) {
        val o = outline(live)
        val shape = path(o)
        if (live.branch.role == Tree.Role.ROOT) {
            drawPath(shape, Brush.verticalGradient(listOf(ROOT, ROOT.copy(alpha = 0f)), startY = 870f, endY = 990f))
        } else {
            val isPicked = picked?.kind == "Branch" && live.branch.words == picked.words && live.branch.at == picked.at
            drawPath(shape, if (isPicked) SELECTED.copy(alpha = 0.85f) else BARK)
            if (live.width > 3) {
                drawPath(path(o.take(o.size / 2), close = false), BARK_LIT, style = Stroke(width = maxOf(0.6f, (live.width * 0.06).toFloat())))
            }
        }
    }
    drawLeaves(m, clock)

    val byIndex = m.branches.associateBy { it.index }
    // Scars: a dark seam in the bark, healed in sand.
    for (sc in m.scars) {
        val live = byIndex[sc.branch] ?: continue
        val p = Tree.pointOn(live.branch.curve, sc.t)
        val angle = Tree.angleOn(live.branch.curve, sc.t)
        val across = sc.across * live.width * 0.4
        val cx = (p.x + cos(angle + PI / 2) * across).toFloat()
        val cy = (p.y + sin(angle + PI / 2) * across).toFloat()
        val w = sc.length.toFloat()
        val h = maxOf(2.4f, (sc.length / 3.5).toFloat())
        rotate((angle * 180 / PI).toFloat(), pivot = Offset(cx, cy)) {
            drawOval(SCAR, Offset(cx - w / 2, cy - h / 2), Size(w, h))
            drawOval(if (picked?.kind == "Scar" && picked.words == sc.words) SELECTED else SCAR_GOLD.copy(alpha = 0.75f), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(1f))
        }
    }
    // Ravens, perched on top of their branch, facing either way.
    for (r in m.ravens) {
        val live = byIndex[r.branch] ?: continue
        val p = Tree.pointOn(live.branch.curve, r.t)
        drawRaven(p.x.toFloat(), (p.y - live.width * 0.45).toFloat(), r.facing, (r.size * 1.25).toFloat(), picked?.kind == "Raven" && picked.words == r.words)
    }
}

private fun DrawScope.drawLeaves(m: Tree.Moment, clock: Float) {
    val season = m.season
    val sway = if (clock == 0f) 0.0 else 0.6 + 1.6 * season.busy
    val palette = LEAVES.getValue(season.name)
    for (live in m.branches) {
        live.branch.leaves.forEachIndexed { i, leaf ->
            if (leaf.rank >= season.density || leaf.t > live.grown) return@forEachIndexed
            val p = Tree.pointOn(live.branch.curve, leaf.t)
            val normal = Tree.angleOn(live.branch.curve, leaf.t) + PI / 2
            val wave = sin(clock / 1400.0 + leaf.phase + p.x / 140)
            val x = p.x + cos(normal) * leaf.off + wave * sway * leaf.t
            val y = p.y + sin(normal) * leaf.off + cos(clock / 1700.0 + leaf.phase) * sway * 0.4
            val colours = if (leaf.turn < season.turned) TURNED else palette
            val colour = colours[(hash01(i + live.index * 31) * colours.size).toInt().coerceIn(0, colours.size - 1)]
            val grow = (1 - 0.45 * season.fresh)
            val w = (leaf.size * grow).toFloat()
            val h = (leaf.size * 0.48 * grow).toFloat()
            rotate(((leaf.angle + wave * 0.15 * (if (sway > 0) 1 else 0)) * 180 / PI).toFloat(), pivot = Offset(x.toFloat(), y.toFloat())) {
                drawOval(colour.copy(alpha = if (season.name == Tree.SeasonName.WINTER) 0.55f else 0.82f), Offset(x.toFloat() - w, y.toFloat() - h), Size(2 * w, 2 * h))
            }
        }
    }
}

private fun DrawScope.drawRaven(x: Float, y: Float, facing: Int, s: Float, selected: Boolean) {
    translate(x, y) {
        scale(facing * s, s, pivot = Offset.Zero) {
            // Tail, back, head and beak, then the breast down to the feet.
            val body = Path().apply {
                moveTo(-15f, -3f)
                quadraticTo(-9f, -6f, -5f, -10f)
                quadraticTo(0f, -15f, 5f, -15f)
                quadraticTo(9f, -17f, 11f, -14f)
                lineTo(15f, -13f)
                lineTo(11f, -11.5f)
                quadraticTo(10f, -6f, 5f, -3f)
                quadraticTo(1f, 0f, -4f, -1f)
                close()
            }
            drawPath(body, RAVEN)
            drawPath(body, if (selected) SELECTED else RAVEN_SHEEN, style = Stroke(0.8f))
            drawLine(RAVEN, Offset(-1f, -1f), Offset(-1f, 1f), strokeWidth = 1f)
            drawLine(RAVEN, Offset(2f, -1.5f), Offset(2f, 1f), strokeWidth = 1f)
            drawCircle(Color(154, 230, 196, 204), 0.9f, Offset(8.2f, -14.2f))
        }
    }
}
