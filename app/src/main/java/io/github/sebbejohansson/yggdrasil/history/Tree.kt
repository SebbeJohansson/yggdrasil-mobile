package io.github.sebbejohansson.yggdrasil.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The world tree, ported from my-widget's `utils/tree.ts`: a living tree drawn
 * from what really happened. Pure: the same events always give the same tree.
 *
 * - The trunk, three roots and three limbs are always there: Yggdrasil.
 * - Every branch event grows a branch. Where is seeded by its id; on which
 *   branch it grows is picked from the branches already there, oldest first,
 *   so a new one adds a branch and never moves an old one.
 * - Every scar event leaves a scar in the bark, seeded by its id.
 * - Every raven event brings a raven that stays, perched on a branch.
 * - Leaves grow along every branch; the season shows which ([seasonAt]).
 *
 * Coordinates are in a 1000 × 1000 box, y down; the ground is at [GROUND].
 */
object Tree {
    const val SIZE = 1000.0
    const val GROUND = 880.0
    const val DAY = 86_400_000L
    /** How long a branch takes to grow out. */
    const val GROW_MS = 2 * DAY
    private const val MAX_DEPTH = 7
    private const val MARGIN = 40.0

    // --- Randomness, seeded, exactly as the JavaScript (the tests check it) ---

    /** FNV-1a: an id to a 32-bit seed. */
    fun seedOf(id: String): Long {
        var hash = 0x811C9DC5.toInt()
        for (c in id) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash.toUInt().toLong()
    }

    /** mulberry32: numbers in [0, 1) from a seed, the same every time. */
    fun random(seed: Long): () -> Double {
        var state = seed.toInt()
        return {
            state += 0x6D2B79F5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            (t xor (t ushr 14)).toUInt().toDouble() / 4_294_967_296.0
        }
    }

    private fun between(rand: () -> Double, low: Double, high: Double) = low + (high - low) * rand()

    // --- Shapes ---

    data class Point(val x: Double, val y: Double)

    /** A quadratic curve from a through c to b. */
    data class Curve(val a: Point, val c: Point, val b: Point)

    fun pointOn(curve: Curve, t: Double): Point {
        val u = 1 - t
        return Point(
            u * u * curve.a.x + 2 * u * t * curve.c.x + t * t * curve.b.x,
            u * u * curve.a.y + 2 * u * t * curve.c.y + t * t * curve.b.y,
        )
    }

    /** The direction of the curve at t, in radians (0 is right, -π/2 up). */
    fun angleOn(curve: Curve, t: Double): Double {
        val dx = 2 * (1 - t) * (curve.c.x - curve.a.x) + 2 * t * (curve.b.x - curve.c.x)
        val dy = 2 * (1 - t) * (curve.c.y - curve.a.y) + 2 * t * (curve.b.y - curve.c.y)
        return atan2(dy, dx)
    }

    /** The first part of a curve, up to t: a branch still growing. */
    fun curveUntil(curve: Curve, t: Double): Curve {
        if (t >= 1) return curve
        val c = Point(curve.a.x + (curve.c.x - curve.a.x) * t, curve.a.y + (curve.c.y - curve.a.y) * t)
        return Curve(curve.a, c, pointOn(curve, t))
    }

    // --- The tree ---

    enum class Role { ROOT, TRUNK, LIMB, GROWN }

    data class Leaf(val t: Double, val off: Double, val angle: Double, val size: Double, val rank: Double, val turn: Double, val phase: Double)

    data class Branch(
        val id: String, val role: Role, val parent: Int, val depth: Int,
        /** When it starts growing (unix ms); Long.MIN_VALUE for the trunk, roots and limbs. */
        val start: Long,
        val curve: Curve, val length: Double, val leaves: List<Leaf>, val words: String? = null, val at: Long? = null,
    )

    data class Scar(val id: String, val branch: Int, val t: Double, val across: Double, val length: Double, val at: Long, val words: String)

    data class Raven(val id: String, val branch: Int, val t: Double, val facing: Int, val size: Double, val at: Long, val words: String)

    data class Grown(val branches: List<Branch>, val scars: List<Scar>, val ravens: List<Raven>, val first: Long?, val times: List<Long>)

    private fun curveFrom(a: Point, angle: Double, length: Double, bend: Double): Curve {
        val b = Point(a.x + cos(angle) * length, a.y + sin(angle) * length)
        val mid = Point((a.x + b.x) / 2, (a.y + b.y) / 2)
        val normal = angle + PI / 2
        return Curve(a, Point(mid.x + cos(normal) * bend * length, mid.y + sin(normal) * bend * length), b)
    }

    private fun leavesFor(id: String, length: Double, role: Role): List<Leaf> {
        if (role == Role.ROOT || role == Role.TRUNK) return emptyList()
        val rand = random(seedOf("leaves:$id"))
        val count = min(16, 3 + floor(length / 14).toInt())
        return List(count) {
            Leaf(
                t = if (role == Role.LIMB) between(rand, 0.55, 1.0) else between(rand, 0.35, 1.0),
                off = between(rand, -1.0, 1.0) * 9,
                angle = between(rand, -PI, PI),
                size = between(rand, 4.5, 8.0),
                rank = rand(),
                turn = rand(),
                phase = between(rand, 0.0, PI * 2),
            )
        }
    }

    /** Yggdrasil without any history: the trunk, three roots and three limbs. The same for everyone. */
    private fun skeleton(): MutableList<Branch> {
        val base = Point(SIZE / 2, GROUND)
        val trunk = curveFrom(base, -PI / 2, 330.0, 0.04)
        fun fixed(id: String, role: Role, parent: Int, depth: Int, curve: Curve): Branch {
            val length = hypot(curve.b.x - curve.a.x, curve.b.y - curve.a.y)
            return Branch(id, role, parent, depth, Long.MIN_VALUE, curve, length, leavesFor(id, length, role))
        }
        val midTrunk = pointOn(trunk, 0.62)
        return mutableListOf(
            fixed("trunk", Role.TRUNK, -1, 0, trunk),
            fixed("root:urd", Role.ROOT, -1, 0, curveFrom(Point(base.x - 18, GROUND - 6), PI - 0.32, 210.0, -0.12)),
            fixed("root:mimir", Role.ROOT, -1, 0, curveFrom(Point(base.x + 18, GROUND - 6), 0.3, 200.0, 0.12)),
            fixed("root:hvergelmir", Role.ROOT, -1, 0, curveFrom(Point(base.x + 4, GROUND), PI / 2 - 0.1, 100.0, 0.2)),
            fixed("limb:west", Role.LIMB, 0, 1, curveFrom(midTrunk, -PI / 2 - 0.85, 190.0, -0.12)),
            fixed("limb:east", Role.LIMB, 0, 1, curveFrom(pointOn(trunk, 0.8), -PI / 2 + 0.8, 180.0, 0.12)),
            fixed("limb:crown", Role.LIMB, 0, 1, curveFrom(pointOn(trunk, 0.94), -PI / 2 - 0.08, 170.0, 0.08)),
        )
    }

    /** Whether a branch had grown out all the way by then, so nothing sits in mid-air. */
    private fun grownBy(branch: Branch, at: Long) = branch.start == Long.MIN_VALUE || branch.start + GROW_MS <= at

    private fun inside(p: Point) = p.x >= MARGIN && p.x <= SIZE - MARGIN && p.y >= MARGIN && p.y <= GROUND - 30

    /** The branch a new one grows from: fewer children and shallower is likelier; never a root. */
    private fun pickParent(branches: List<Branch>, children: List<Int>, at: Long, rand: () -> Double): Int {
        var total = 0.0
        val weights = branches.mapIndexed { index, b ->
            if (b.role == Role.ROOT || b.depth >= MAX_DEPTH || (b.start != Long.MIN_VALUE && b.start > at)) {
                0.0
            } else {
                val crowd = 1.0 + children[index]
                ((if (b.role == Role.TRUNK) 0.25 else 1.0) * b.length / (crowd * crowd) / (1 + b.depth * 0.35)).also { total += it }
            }
        }
        var pick = rand() * total
        for (i in weights.indices) {
            pick -= weights[i]
            if (weights[i] > 0 && pick <= 0) return i
        }
        return 0
    }

    private fun growBranch(e: HistoryEvent, branches: List<Branch>, children: List<Int>): Branch {
        val rand = random(seedOf(e.id))
        val parentIndex = pickParent(branches, children, e.at, rand)
        val parent = branches[parentIndex]
        val along = between(rand, 0.4, 0.96)
        val from = pointOn(parent.curve, along)
        val side = if (rand() < 0.5) -1 else 1
        var angle = angleOn(parent.curve, along) + side * between(rand, 0.35, 0.8)
        // Lean up a little, as trees do; never down past level.
        angle += (-PI / 2 - angle) * 0.15
        if (sin(angle) > 0.1) angle = atan2(0.1, cos(angle))
        var length = max(22.0, parent.length * between(rand, 0.5, 0.78))
        val bend = between(rand, -0.18, 0.18)
        var curve = curveFrom(from, angle, length, bend)
        var tries = 0
        while (tries < 6 && !(inside(curve.b) && inside(curve.c))) {
            length *= 0.75
            curve = curveFrom(from, angle, length, bend)
            tries++
        }
        // A branch grows out once its parent has reached where it sits.
        val parentStart = if (parent.start == Long.MIN_VALUE) Long.MIN_VALUE else parent.start + (GROW_MS * along).toLong()
        val start = max(e.at, parentStart)
        return Branch(e.id, Role.GROWN, parentIndex, parent.depth + 1, start, curve, length, leavesFor(e.id, length, Role.GROWN), e.words, e.at)
    }

    private fun placeScar(e: HistoryEvent, branches: List<Branch>): Scar {
        val rand = random(seedOf(e.id))
        // The bark: the trunk about half the time, else a limb or a branch right off one.
        val weights = branches.map { b ->
            if ((b.role == Role.GROWN || b.role == Role.LIMB) && b.depth <= 2 && grownBy(b, e.at)) b.length / 100 else 0.0
        }.toMutableList()
        val others = weights.sum()
        val trunkIndex = branches.indexOfFirst { it.role == Role.TRUNK }
        weights[trunkIndex] = max(3.0, others)
        val total = others + max(3.0, others)
        var pick = rand() * total
        var index = 0
        for (i in weights.indices) {
            pick -= weights[i]
            if (weights[i] > 0 && pick <= 0) {
                index = i
                break
            }
        }
        val trunk = branches[index].role == Role.TRUNK
        return Scar(
            e.id, index,
            t = if (trunk) between(rand, 0.08, 0.8) else between(rand, 0.1, 0.6),
            across = between(rand, -0.6, 0.6), length = between(rand, 7.0, 15.0), at = e.at, words = e.words,
        )
    }

    private fun perchRaven(e: HistoryEvent, branches: List<Branch>, ravens: List<Raven>): Raven {
        val rand = random(seedOf(e.id))
        // A level branch is a better perch; the trunk and roots are none.
        val perches = branches.withIndex().filter { (_, b) -> b.role != Role.ROOT && b.role != Role.TRUNK && grownBy(b, e.at) }
        var best: Raven? = null
        for (attempt in 0 until 6) {
            var total = 0.0
            val weights = perches.map { (_, b) -> (0.2 + abs(cos(angleOn(b.curve, 0.7)))).also { total += it } }
            var pick = rand() * total
            var index = perches.firstOrNull()?.index ?: 4
            for (i in perches.indices) {
                pick -= weights[i]
                if (pick <= 0) {
                    index = perches[i].index
                    break
                }
            }
            val raven = Raven(e.id, index, between(rand, 0.45, 0.92), if (rand() < 0.5) -1 else 1, between(rand, 0.9, 1.15), e.at, e.words)
            best = raven
            val here = pointOn(branches[index].curve, raven.t)
            val crowded = ravens.any { o ->
                val there = pointOn(branches[o.branch].curve, o.t)
                hypot(there.x - here.x, there.y - here.y) < 22
            }
            if (!crowded) break
        }
        return best!!
    }

    /** The whole tree from the history, every event in its place. */
    fun grow(events: List<HistoryEvent>): Grown {
        val branches = skeleton()
        val children = MutableList(branches.size) { 0 }
        val scars = mutableListOf<Scar>()
        val ravens = mutableListOf<Raven>()
        val ordered = events.sortedWith(compareBy<HistoryEvent> { it.at }.thenBy { it.id })
        for (e in ordered) {
            when (e.kind) {
                HistoryKind.BRANCH -> {
                    val b = growBranch(e, branches, children)
                    children[b.parent]++
                    branches += b
                    children += 0
                }
                HistoryKind.SCAR -> scars += placeScar(e, branches)
                HistoryKind.RAVEN -> ravens += perchRaven(e, branches, ravens)
            }
        }
        return Grown(branches, scars, ravens, ordered.firstOrNull()?.at, ordered.map { it.at })
    }

    // --- One moment ---

    enum class SeasonName { WINTER, SPRING, SUMMER, AUTUMN }

    data class Season(
        val name: SeasonName,
        /** "early spring", "late autumn": the only words under the tree. */
        val words: String,
        /** How many leaves show, 0 to 1. */
        val density: Double,
        /** How far into autumn the leaves have turned, 0 to 1. */
        val turned: Double,
        /** How fresh the leaves are: small and light early in spring. */
        val fresh: Double,
        /** How busy the week up to then was, 0 (quiet) to 1. */
        val busy: Double,
    )

    /** The season of the calendar where you are (the northern year), day by day. */
    private fun calendarSeason(at: Long, zone: ZoneId): Pair<SeasonName, Double> {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val day = date.dayOfYear - 1 // 0 = 1 January
        val leap = if (date.isLeapYear) 1 else 0
        val march = 59 + leap
        val june = 151 + leap
        val september = 243 + leap
        val december = 334 + leap
        return when {
            day < march -> SeasonName.WINTER to (day + 31.0) / (march + 31)
            day < june -> SeasonName.SPRING to (day - march).toDouble() / (june - march)
            day < september -> SeasonName.SUMMER to (day - june).toDouble() / (september - june)
            day < december -> SeasonName.AUTUMN to (day - september).toDouble() / (december - september)
            else -> SeasonName.WINTER to (day - december).toDouble() / (march + 31)
        }
    }

    /** How many events happened in the seven days up to [at]. */
    fun weekCount(times: List<Long>, at: Long): Int = times.count { it <= at && it > at - 7 * DAY }

    /**
     * The leaves at a moment. The calendar season gives their colour and how
     * full the crown can be, the week how much of that it is: a busy week fills
     * the crown and makes it sway more, a quiet one leaves it calm and sparse.
     */
    fun seasonAt(times: List<Long>, at: Long, zone: ZoneId = ZoneId.systemDefault()): Season {
        val (name, progress) = calendarSeason(at, zone)
        val busy = 1 - exp(-weekCount(times, at) / 4.0)
        val full = when (name) {
            SeasonName.SUMMER -> 1.0
            SeasonName.SPRING -> 0.3 + 0.6 * progress
            SeasonName.AUTUMN -> 0.9 - 0.65 * progress
            SeasonName.WINTER -> 0.12
        }
        val phase = if (progress < 1.0 / 3) "early" else if (progress < 2.0 / 3) "high" else "late"
        val n = name.name.lowercase()
        val words = when {
            name == SeasonName.SUMMER && phase == "high" -> "midsummer"
            name == SeasonName.WINTER && phase == "high" -> "midwinter"
            phase == "high" -> n
            else -> "$phase $n"
        }
        return Season(
            name, words, full * (0.4 + 0.6 * busy),
            turned = if (name == SeasonName.AUTUMN) min(1.0, 0.15 + progress * 1.1) else 0.0,
            fresh = if (name == SeasonName.SPRING) 1 - progress else 0.0,
            busy = busy,
        )
    }

    /** A branch as it is at a moment: how far grown, and how thick from what grows on it. */
    data class LiveBranch(val index: Int, val branch: Branch, val curve: Curve, val grown: Double, val width: Double)

    data class Moment(val branches: List<LiveBranch>, val scars: List<Scar>, val ravens: List<Raven>, val season: Season)

    /** The tree at [at]: only what had happened by then, branches grown as far as they had. */
    fun at(tree: Grown, at: Long, zone: ZoneId = ZoneId.systemDefault()): Moment {
        val grown = tree.branches.map { b ->
            if (b.start == Long.MIN_VALUE) 1.0 else ((at - b.start).toDouble() / GROW_MS).coerceIn(0.0, 1.0)
        }
        // Thicker for every branch grown on it, the trunk most of all.
        val load = DoubleArray(tree.branches.size)
        for (i in tree.branches.indices.reversed()) {
            val b = tree.branches[i]
            if (grown[i] <= 0 || b.parent < 0) continue
            load[b.parent] += 1 + load[i] * grown[i]
        }
        val live = mutableListOf<LiveBranch>()
        tree.branches.forEachIndexed { index, b ->
            if (grown[index] <= 0) return@forEachIndexed
            val base = when (b.role) { Role.TRUNK -> 26.0; Role.ROOT -> 14.0; Role.LIMB -> 11.0; Role.GROWN -> 3.2 }
            val cap = when (b.role) { Role.TRUNK -> 62.0; Role.ROOT -> 30.0; else -> 34.0 }
            val width = min(cap, base + sqrt(load[index]) * (if (b.role == Role.TRUNK) 3.2 else 2.2)) * (0.35 + 0.65 * grown[index])
            live += LiveBranch(index, b, curveUntil(b.curve, grown[index]), grown[index], width)
        }
        // Roots thicken with the trunk.
        val trunk = live.firstOrNull { it.branch.role == Role.TRUNK }
        val withRoots = live.map { l -> if (l.branch.role == Role.ROOT && trunk != null) l.copy(width = max(l.width, trunk.width * 0.45)) else l }
        return Moment(withRoots, tree.scars.filter { it.at <= at }, tree.ravens.filter { it.at <= at }, seasonAt(tree.times, at, zone))
    }

    /**
     * Where the time slider starts: just before the first event, a twentieth of
     * the tree's life back (between six hours and three days). A week back for
     * a tree with nothing yet.
     */
    fun timeStart(tree: Grown, now: Long): Long {
        val first = tree.first ?: return now - 7 * DAY
        val lead = min(3 * DAY, max(DAY / 4, (now - first) / 20))
        return min(first - lead, now - DAY)
    }

    fun dayWords(at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d: LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.UK))
    }
}
