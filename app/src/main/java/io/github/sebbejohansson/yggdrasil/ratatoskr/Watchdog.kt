package io.github.sebbejohansson.yggdrasil.ratatoskr

import io.github.sebbejohansson.yggdrasil.core.Check
import io.github.sebbejohansson.yggdrasil.core.LoopRules
import io.github.sebbejohansson.yggdrasil.core.LoopRun
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.core.Tricks
import io.github.sebbejohansson.yggdrasil.core.Watch
import io.github.sebbejohansson.yggdrasil.core.WatchState
import io.github.sebbejohansson.yggdrasil.gjallar.Gjallar
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.parts.Parts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How one part looked to Ratatoskr this round. */
data class PartSeen(val name: String, val up: Boolean, val error: String? = null, val loops: List<Pair<LoopRun, LoopState>> = emptyList())

data class WatchdogStatus(val parts: List<PartSeen> = emptyList(), val checks: List<WatchState> = emptyList(), val checkedAt: Instant? = null)

/**
 * Ratatoskr's watchdog, ported from the desktop's ratatoskr.go: is each
 * part up, and do its loops run on time. It fixes nothing. On the phone the
 * parts share one process, so "up" is what a part can tell about itself
 * without the screens (the Claude bridge answering while it's meant to run,
 * say), and the loops are the parts' timed rounds, as Loops keeps them.
 *
 * Checks, as the desktop's:
 * - down:<part>, critical: "<Part> is down".
 * - stalled:<part>:<loop>, warning: not run within 3 intervals and a minute.
 * - failing:<part>:<loop>, warning: its last run failed.
 * A part that's down has its loops left alone: one problem, one alert. Each
 * goes through the watch (down after 2 failures, up after 2 passes).
 *
 * The desktop's heartbeat to Gjallar becomes [silent]: when Ratatoskr's own
 * round comes later than its allowance (Android stopped Ygg's background
 * work, or the phone was off), Gjallar raises silent:ratatoskr.
 */
class Watchdog(
    private val watch: Watch,
    private val gjallar: Gjallar,
    private val loop: (String, Duration) -> LoopRun,
    private val startedAt: Instant,
    private val devBuild: Boolean,
    /** Parts that can tell whether they're up, each giving null when up or why not. Null as a whole: not checked now. */
    private val probes: Map<String, suspend () -> String?>,
) {
    private val _status = MutableStateFlow(WatchdogStatus())
    val status: StateFlow<WatchdogStatus> = _status

    private fun title(part: String) = Parts.INFO.firstOrNull { it.first == part }?.second ?: part.replaceFirstChar { it.uppercase() }

    suspend fun round(now: Instant = Instant.now()): WatchdogStatus {
        silent(now)
        val loopsByPart = Parts.allLoops(devBuild).groupBy { it.first.substringBefore('.') }
        val names = (Parts.INFO.map { it.first } + loopsByPart.keys).distinct()
        val seen = names.map { part ->
            val down = if (Tricks.healthTricked(part)) "Loki's trick" else runCatching { probes[part]?.invoke() }.getOrElse { it.message ?: "didn't answer" }
            val loops = loopsByPart[part].orEmpty().map { (name, every) -> loop(name, every).let { it to LoopRules.state(it, startedAt, now) } }
            PartSeen(part, down == null, down, loops)
        }
        val checks = Watchdog.checks(seen, now) { title(it) }
        val states = watch.round(checks, now.toEpochMilli())
        return WatchdogStatus(seen, states, now).also { _status.value = it }
    }

    /** Ratatoskr's own last round, as Gjallar watches its heartbeat: silent when later than its allowance. */
    private suspend fun silent(now: Instant) {
        val (name, every) = Parts.RATATOSKR_CHECK
        val last = loop(name, every).lastRunAt ?: return
        val late = Duration.between(last, now)
        if (late > every.multipliedBy(3).plusMinutes(1)) {
            runCatching {
                gjallar.raise("gjallar", "silent:ratatoskr", Severity.WARNING, "Ratatoskr is silent",
                    "Its last round was ${late.toMinutes()} min ago, ${clock(last)}: Android may have stopped Ygg's background work, or the phone was off.")
            }
        } else runCatching { gjallar.resolve("gjallar", "silent:ratatoskr") }
    }

    companion object {
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        fun clock(t: Instant): String = t.atZone(ZoneId.systemDefault()).format(hhmm)

        /** The round's checks from what was seen. Pure, for tests. */
        fun checks(seen: List<PartSeen>, now: Instant, title: (String) -> String): List<Check> = seen.flatMap { p ->
            val down = Check("down:${p.name}", p.up, Severity.CRITICAL, "${title(p.name)} is down", p.error)
            if (!p.up) return@flatMap listOf(down)
            listOf(down) + p.loops.flatMap { (run, state) ->
                val loopName = run.name.substringAfter('.')
                val since = run.lastRunAt
                val late = Duration.between(since ?: now, now)
                listOf(
                    Check(
                        "stalled:${p.name}:$loopName", state != LoopState.STALLED, Severity.WARNING,
                        "${title(p.name)}'s $loopName loop has not run in ${late.toMinutes().coerceAtLeast(1)} min",
                        "it runs ${LoopRules.every(run.every)}; last run " + (since?.let { clock(it) } ?: "never since Ygg started"),
                    ),
                    Check(
                        "failing:${p.name}:$loopName", state != LoopState.ERROR, Severity.WARNING,
                        "${title(p.name)}'s $loopName loop failed its last run", run.lastError,
                    ),
                )
            }
        }
    }
}
