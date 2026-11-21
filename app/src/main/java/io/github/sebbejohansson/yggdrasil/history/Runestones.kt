package io.github.sebbejohansson.yggdrasil.history

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.round

/**
 * Runestones, ported from my-widget's `utils/runestones.ts`: when something
 * memorable happens, a stone is carved, its inscription in Elder Futhark and
 * what it says in plain English on tap. Carved from the tree's history, which
 * is kept for good, so a stone once carved stays. Pure: the same history and
 * now always carve the same stones.
 *
 * What's memorable on the phone (the desktop's goals and Loki aren't here):
 * - first-branch  the first thing Norn remembered.
 * - branches      the world tree's hundredth branch, and its thousandth.
 * - busy-day      the first day Norn learned five things.
 * - first-raven   the first time Huginn told you something.
 * - ravens        Huginn's hundredth word to you.
 * - long-storm    an alert that healed after twelve hours or more open.
 * - quiet-week    the first whole week, Monday to Monday, with no alert open
 *                 at any time in it, after the first alert.
 */
object Runestones {
    private const val HOUR = 3_600_000L
    val BRANCH_MILESTONES = listOf(100, 1000)
    const val BUSY_DAY = 5
    const val RAVEN_MILESTONE = 100
    const val LONG_STORM_MS = 12 * HOUR

    data class Stone(val id: String, val kind: String, val at: Long, val carved: String, val runes: String, val meaning: String)

    /** An alert still open: when it opened. */
    data class OpenAlert(val id: Long, val openedAt: Long)

    private fun stone(id: String, kind: String, at: Long, carved: String, meaning: String) = Stone(id, kind, at, carved, Futhark.of(carved), meaning)

    private fun quote(words: String) = words.trim().let { if (it.length > 80) it.take(77) + "…" else it }

    private fun lasted(ms: Long): String {
        val hours = ms / HOUR
        return if (hours < 48) "$hours hours" else "${hours / 24} days"
    }

    /** Every stone the history has carved by [now], oldest first; ties by id. */
    fun carve(events: List<HistoryEvent>, open: List<OpenAlert>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Stone> {
        val sorted = events.sortedWith(compareBy<HistoryEvent> { it.at }.thenBy { it.id })
        val branches = sorted.filter { it.kind == HistoryKind.BRANCH }
        val ravens = sorted.filter { it.kind == HistoryKind.RAVEN }
        val scars = sorted.filter { it.kind == HistoryKind.SCAR }
        val stones = mutableListOf<Stone>()

        branches.firstOrNull()?.let { stones += stone("first-branch", "first-branch", it.at, "Here the first branch grew", "The first thing Norn remembered: ${quote(it.words)}") }
        for (m in BRANCH_MILESTONES) {
            val b = branches.getOrNull(m - 1) ?: continue
            val words = Futhark.numberWords(m.toLong()).replaceFirst(Regex("^one "), "a ")
            stones += stone("branches:$m", "branches", b.at, "The tree stood $words branches strong",
                "The world tree grew its ${if (m == 100) "hundredth" else "thousandth"} branch: ${quote(b.words)}")
        }
        val perDay = linkedMapOf<java.time.LocalDate, MutableList<HistoryEvent>>()
        for (b in branches) {
            val day = perDay.getOrPut(Instant.ofEpochMilli(b.at).atZone(zone).toLocalDate()) { mutableListOf() }
            day += b
            if (day.size == BUSY_DAY) {
                stones += stone("busy-day", "busy-day", b.at, "A day of many branches", "A day Norn learned much: ${quote(day[0].words)}, ${quote(b.words)} and more")
                break
            }
        }

        ravens.firstOrNull()?.let { stones += stone("first-raven", "first-raven", it.at, "The raven flew home", "Huginn's first word to you: ${quote(it.words)}") }
        ravens.getOrNull(RAVEN_MILESTONE - 1)?.let {
            stones += stone("ravens:$RAVEN_MILESTONE", "ravens", it.at, "A hundred ravens came", "Huginn told you its hundredth thing: ${quote(it.words)}")
        }

        for (s in scars) {
            val from = s.startedAt ?: continue
            if (s.at - from >= LONG_STORM_MS) {
                stones += stone("long-storm:${s.id}", "long-storm", s.at, "The long storm passed", "${quote(s.words)}, after ${lasted(s.at - from)}")
            }
        }

        quietWeek(scars, open, now, zone)?.let { stones += it }

        val seen = mutableSetOf<String>()
        return stones.filter { it.at in 1..now && seen.add(it.id) }.sortedWith(compareBy<Stone> { it.at }.thenBy { it.id })
    }

    private fun weekStart(at: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()

    private fun nextWeek(start: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(start).atZone(zone).plusWeeks(1).toInstant().toEpochMilli()

    private fun quietWeek(scars: List<HistoryEvent>, open: List<OpenAlert>, now: Long, zone: ZoneId): Stone? {
        val spans = (scars.map { (it.startedAt ?: it.at) to it.at } + open.map { it.openedAt to maxOf(it.openedAt, now) })
            .filter { it.first > 0 }.sortedBy { it.first }
        if (spans.isEmpty()) return null
        var start = nextWeek(weekStart(spans[0].first, zone), zone)
        while (true) {
            val end = nextWeek(start, zone)
            if (end > now) return null
            if (spans.none { (from, to) -> from < end && to >= start }) {
                val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                return stone("quiet-week:$day", "quiet-week", end, "A week of peace", "The first whole week with no alert at all")
            }
            start = end
        }
    }

    // --- The field ---

    const val FIELD_HEIGHT = 360.0
    const val GROUND_Y = 250.0
    const val STONE_STEP = 150.0
    private const val FIELD_MARGIN = 120.0

    data class Shape(val width: Double, val height: Double, val lean: Double, val dome: Double, val peak: Double, val bulge: Double, val tone: Int, val moss: Double)

    data class Placed(val stone: Stone, val shape: Shape, val x: Double, val y: Double, val scale: Double)

    private fun r(n: Double, places: Int): Double = when (places) {
        0 -> round(n)
        1 -> round(n * 10) / 10
        else -> round(n * 100) / 100
    }

    /** A stone's shape, seeded by its id: the same stone always looks the same. */
    fun shape(id: String): Shape {
        val rand = Tree.random(Tree.seedOf("shape:$id"))
        fun between(low: Double, high: Double) = low + (high - low) * rand()
        return Shape(
            width = r(between(70.0, 104.0), 0), height = r(between(130.0, 190.0), 0), lean = r(between(-4.0, 4.0), 1),
            dome = r(between(0.25, 1.0), 2), peak = r(between(-0.35, 0.35), 2), bulge = r(between(0.0, 0.12), 2),
            tone = (rand() * 3).toInt(), moss = r(rand(), 2),
        )
    }

    /** The stones in a row along the field, oldest on the left, each nudged and set back by its own seed. */
    fun lay(stones: List<Stone>): Pair<List<Placed>, Double> {
        val placed = stones.mapIndexed { index, s ->
            val rand = Tree.random(Tree.seedOf("place:${s.id}"))
            val depth = rand()
            val nudge = (rand() - 0.5) * 40
            Placed(s, shape(s.id), round(FIELD_MARGIN + index * STONE_STEP + nudge), round(GROUND_Y + 20 - depth * 40), r(1 - depth * 0.22, 2))
        }.sortedWith(compareBy<Placed> { it.y }.thenBy { it.x })
        return placed to FIELD_MARGIN * 2 + maxOf(0, stones.size - 1) * STONE_STEP
    }

    /** The band the runes run in, as on the old stones: up the left side, over the top and down the right. */
    fun band(shape: Shape): List<Tree.Curve> {
        val inset = 15.0
        val half = shape.width / 2 - inset
        val foot = -18.0
        val h = shape.height - inset - 4
        val shoulder = h * 0.68
        val top = Tree.Point(shape.peak * half * 0.6, -h)
        fun line(a: Tree.Point, b: Tree.Point) = Tree.Curve(a, Tree.Point((a.x + b.x) / 2, (a.y + b.y) / 2), b)
        return listOf(
            line(Tree.Point(-half, foot), Tree.Point(-half, -shoulder)),
            Tree.Curve(Tree.Point(-half, -shoulder), Tree.Point(-half, -h), top),
            Tree.Curve(top, Tree.Point(half, -h), Tree.Point(half, -shoulder)),
            line(Tree.Point(half, -shoulder), Tree.Point(half, foot)),
        )
    }

    /** One rune carved: its strokes, where it goes, how it's turned. */
    data class CarvedRune(val strokes: String, val x: Double, val y: Double, val degrees: Double, val scale: Double, val width: Double)

    /**
     * The inscription carved along its band, each rune turned to follow it with
     * its top to the stone's edge, as large as fits (8 to 16 high) and centred.
     */
    fun carveRunes(shape: Shape, runes: String): List<CarvedRune> {
        data class Step(val at: Tree.Point, val angle: Double, val from: Double, val to: Double)
        val steps = mutableListOf<Step>()
        var length = 0.0
        for (curve in band(shape)) {
            for (i in 0 until 24) {
                val a = Tree.pointOn(curve, i / 24.0)
                val b = Tree.pointOn(curve, (i + 1) / 24.0)
                val step = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                if (step < 1e-6) continue
                steps += Step(a, kotlin.math.atan2(b.y - a.y, b.x - a.x), length, length + step)
                length += step
            }
        }
        val glyphs = Futhark.split(runes).filter { it in Futhark.STROKES }
        val units = glyphs.sumOf { Futhark.advance(it) }
        val size = if (units > 0) (length * 10 / units).coerceIn(8.0, 16.0) else 14.0
        val scale = size / 10
        var along = maxOf(0.0, (length - units * scale) / 2)
        val out = mutableListOf<CarvedRune>()
        for (rune in glyphs) {
            val adv = Futhark.advance(rune) * scale
            val middle = along + adv / 2
            along += adv
            if (middle > length) break
            val step = steps.firstOrNull { it.to >= middle } ?: steps.last()
            val t = (middle - step.from) / (step.to - step.from)
            val x = step.at.x + kotlin.math.cos(step.angle) * (step.to - step.from) * t
            val y = step.at.y + kotlin.math.sin(step.angle) * (step.to - step.from) * t
            out += CarvedRune(Futhark.STROKES.getValue(rune), x, y, step.angle * 180 / Math.PI, scale, Futhark.advance(rune) - 1.5)
        }
        return out
    }

    fun dayWords(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))
}
