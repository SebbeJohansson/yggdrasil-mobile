package io.github.sebbejohansson.yggdrasil.parts

import io.github.sebbejohansson.yggdrasil.core.LoopRules
import io.github.sebbejohansson.yggdrasil.core.LoopRun
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertRow
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import java.time.Duration
import java.time.Instant

/** Up, needs a look, or down: the dashboard's three. */
enum class PartHealth(val words: String) { UP("up"), DEGRADED("needs a look"), DOWN("down"), OFF("not set up") }

/** What a part says about itself right now, gathered by the caller. */
data class PartReport(
    val name: String,
    /** Down: it can't do its job. */
    val down: Boolean = false,
    /** Up, but something wants you: a permission, a failing job. */
    val troubled: Boolean = false,
    /** Optional and not set up: dark, not troubled. */
    val off: Boolean = false,
    /** What's wrong, in one line. */
    val problem: String? = null,
    val facts: List<String> = emptyList(),
    val loops: List<LoopRun> = emptyList(),
)

data class PartCard(
    val name: String,
    val label: String,
    val what: String,
    val health: PartHealth,
    val problem: String?,
    val facts: List<String>,
    val loops: List<Pair<LoopRun, LoopState>>,
    val alerts: List<AlertRow>,
)

/**
 * The phone's Health page, as the dashboard's (`pages/dashboard/parts.vue`):
 * one card per part, with what it does, how it is, its loops and its alerts.
 */
object Parts {
    /** Each part's key (as alerts name it), label and job, in the order the page lists them. */
    val INFO = listOf(
        Triple("norn", "Norn", "the assistant: chat, memory, the models"),
        Triple("huginn", "Huginn", "reacts to what just happened: messages, meetings, alerts"),
        Triple("dellingr", "Dellingr", "runs the jobs you put on a clock"),
        Triple("hermodr", "Hermóðr", "carries every message to you, holds what can wait"),
        Triple("vedrfolnir", "Veðrfölnir", "reports on the phone: battery, storage, memory, heat"),
        Triple("saga", "Sága", "keeps alerts in step with the desktop through Mímir"),
        Triple("claude", "Claude", "the optional bridge to Claude Code, for bigger questions"),
        Triple("gjallar", "Gjallar", "alerts and warnings from every part, in one place"),
        Triple("ratatoskr", "Ratatoskr", "the watchdog: the parts' loops, and your own rhythm"),
        Triple("munin", "Munin", "checks what the parts depend on outside the phone: Mímir, GitHub, Anthropic"),
        Triple("heimdall", "Heimdall", "the security watchman: the repo's findings and the phone's own"),
        Triple("loki", "Loki", "tests the watchers: blind spots, and tricks to see who notices"),
    )

    /** An alert belongs to a part when it raised it or names it in its key. */
    fun owns(part: String, row: AlertRow): Boolean {
        val a = row.alert
        return a.source == part || a.key.endsWith(":$part") || a.key.contains(":$part:")
    }

    fun cards(reports: List<PartReport>, alerts: List<AlertRow>, startedAt: Instant, now: Instant): List<PartCard> {
        val byName = reports.associateBy { it.name }
        return INFO.mapNotNull { (name, label, what) ->
            val r = byName[name] ?: return@mapNotNull null
            val loops = r.loops.map { it to LoopRules.state(it, startedAt, now) }.sortedBy { it.first.name }
            val own = alerts.filter { owns(name, it) }.sortedByDescending { it.alert.lastSeenAt }
            val critical = own.any { it.alert.severity == Severity.CRITICAL && it.alert.state == AlertState.OPEN && !it.muted }
            val health = when {
                r.off -> PartHealth.OFF
                r.down -> PartHealth.DOWN
                r.troubled || critical || loops.any { it.second == LoopState.STALLED || it.second == LoopState.ERROR } -> PartHealth.DEGRADED
                else -> PartHealth.UP
            }
            PartCard(name, label, what, health, r.problem, r.facts, loops, own)
        }
    }

    /** Alerts no card claims: from a source that isn't a part, naming none. */
    fun unclaimed(cards: List<PartCard>, alerts: List<AlertRow>): List<AlertRow> {
        val claimed = cards.flatMap { c -> c.alerts.map { it.alert.id } }.toSet()
        return alerts.filter { it.alert.id !in claimed }
    }

    /** "3 min", "2 h", "4 d": how long ago, short. */
    fun ago(t: Instant, now: Instant): String {
        val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toHours() < 1 -> "${d.toMinutes()} min ago"
            d.toDays() < 1 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
    }

    /** The parts' loops: name, interval. */
    val VEDRFOLNIR_CHECK = "vedrfolnir.check" to Duration.ofMinutes(15)
    val DELLINGR_TICK = "dellingr.tick" to Duration.ofMinutes(15)
    val HUGINN_CALENDAR = "huginn.calendar" to Duration.ofMinutes(15)
    val RATATOSKR_YOU = "ratatoskr.you" to Duration.ofMinutes(15)
    val SAGA_SYNC = "saga.sync" to Duration.ofMinutes(15)
    val UPDATES_CHECK = "updates.check" to Duration.ofHours(6)
    val NORN_PROMISES = "norn.promises" to Duration.ofMinutes(15)
    val RATATOSKR_CHECK = "ratatoskr.check" to Duration.ofMinutes(15)
    val MUNIN_CHECK = "munin.check" to Duration.ofMinutes(15)
    val HEIMDALL_WATCH = "heimdall.watch" to Duration.ofMinutes(15)
    val LOKI_PLAN = "loki.plan" to Duration.ofHours(1)

    /** Every loop the parts run, for Ratatoskr to watch: name and interval. */
    fun allLoops(devBuild: Boolean) = listOfNotNull(
        VEDRFOLNIR_CHECK, DELLINGR_TICK, HUGINN_CALENDAR, RATATOSKR_YOU, SAGA_SYNC, NORN_PROMISES,
        UPDATES_CHECK.takeIf { !devBuild }, RATATOSKR_CHECK, MUNIN_CHECK, HEIMDALL_WATCH, LOKI_PLAN,
    )
}
