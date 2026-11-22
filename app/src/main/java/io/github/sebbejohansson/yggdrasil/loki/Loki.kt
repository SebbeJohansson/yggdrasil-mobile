package io.github.sebbejohansson.yggdrasil.loki

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.core.Loops
import io.github.sebbejohansson.yggdrasil.core.Trick
import io.github.sebbejohansson.yggdrasil.core.Tricks
import io.github.sebbejohansson.yggdrasil.core.WatchState
import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.GjallarService
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import io.github.sebbejohansson.yggdrasil.parts.Parts
import io.github.sebbejohansson.yggdrasil.ratatoskr.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/** Something that could break with nobody noticing. severity: low, medium, high. */
data class BlindSpot(val id: String, val part: String, val text: String, val severity: String, val trick: TrickChoice? = null)

/** A trick Loki can play: health (the part reads as down) or loop (one of its loops fails). */
data class TrickChoice(val part: String, val kind: String, val loop: String? = null) {
    val label: String get() = if (kind == "loop") "${loop?.substringAfter('.')} loop fails" else "reads as down"
}

/** Who should notice, and when they did. */
data class Expect(val watcher: String, val key: String, val noticedAt: Long? = null)

/** One strike: what was tricked, how long, who noticed. state: striking, watching, caught, missed, stopped. */
data class Strike(
    val id: String,
    val trick: TrickChoice,
    val startedAt: Long,
    val endsAt: Long,
    val state: String,
    val checks: List<Expect>,
    val healedAt: Long? = null,
)

data class LokiStatus(val blindSpots: List<BlindSpot> = emptyList(), val strikes: List<Strike> = emptyList(), val running: Strike? = null, val planAt: Long? = null)

/**
 * Loki, ported from the desktop's loki/: tests the watchers. It lists blind
 * spots, what could break with nobody noticing, and plays tricks on a part
 * for 1-10 minutes to see whether Ratatoskr and Gjallar notice. Each trick
 * ends at its time whatever happens (core/Tricks), Loki never tricks itself,
 * and on the phone it leaves Gjallar and Ratatoskr alone: they share the
 * app's process with it, and a trick on them would test nothing real.
 *
 * Tricks, as the desktop's in-process switch: health (the part reads as down:
 * expected down:<part> on Ratatoskr's watch and in Gjallar) and loop (a loop's
 * rounds fail: expected failing:<part>:<loop>). On the desktop Ratatoskr looks
 * every 30 s; the phone's looks every 15 minutes, so during a strike Loki asks
 * it for a round every 30 s. What's tested is that its rules and Gjallar
 * notice, not Android's timing (that's a blind spot of its own).
 *
 * Verdict: caught when every check noticed during the strike or while it
 * healed (5 minutes at most); a miss raises missed:<part>:<kind> in Gjallar,
 * and a catch resolves it. The last 200 strikes are kept. Not ported: tales,
 * passive strikes, and the desktop-only tricks (proxy, freeze).
 */
class Loki(
    context: Context,
    private val gjallar: GjallarService,
    private val loops: Loops,
    private val watchdog: () -> Watchdog,
    /** Ratatoskr's watch states now. */
    private val ratatoskrChecks: () -> List<WatchState>,
    private val scope: CoroutineScope,
    private val devBuild: Boolean,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("loki", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(LokiStatus(strikes = loadStrikes(), planAt = prefs.getLong("planAt", 0).takeIf { it > 0 }))
    val status: StateFlow<LokiStatus> = _status
    private var job: Job? = null

    /** What can be tricked: every part with a watcher, but not Loki, Gjallar or Ratatoskr. */
    fun tricks(): List<TrickChoice> {
        val loopsByPart = Parts.allLoops(devBuild).groupBy { it.first.substringBefore('.') }
        return Parts.INFO.map { it.first }.filter { it !in UNTRICKABLE }.flatMap { part ->
            listOf(TrickChoice(part, "health")) + loopsByPart[part].orEmpty().map { TrickChoice(part, "loop", it.first) }
        }
    }

    fun start(t: TrickChoice, minutes: Int = 3): Result<Strike> = runCatching {
        require(t.part !in UNTRICKABLE) { "Loki leaves ${t.part} alone on the phone" }
        require(minutes in 1..10) { "a strike lasts 1 to 10 minutes" }
        check(_status.value.running == null) { "a strike is already running" }
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        Tricks.set(Trick(t.part, t.kind, t.loop, until), now)
        val key = if (t.kind == "loop") "failing:${t.part}:${t.loop?.substringAfter('.')}" else "down:${t.part}"
        val strike = Strike(id = "s" + now.toString(36), trick = t, startedAt = now, endsAt = until, state = "striking",
            checks = listOf(Expect("ratatoskr", key), Expect("gjallar", key)))
        _status.value = _status.value.copy(running = strike)
        job = scope.launch { run(strike) }
        strike
    }

    /** Stops the running strike: the trick ends now, with no verdict. */
    fun stop() {
        val s = _status.value.running ?: return
        job?.cancel()
        untrick(s)
        finish(s.copy(state = "stopped"), verdict = false)
    }

    private suspend fun run(start: Strike) {
        var s = start
        val heal = s.endsAt + HEAL_WAIT_MS
        var tick = 0
        while (true) {
            val now = System.currentTimeMillis()
            if (s.state == "striking" && now >= s.endsAt) {
                untrick(s)
                s = s.copy(state = "watching")
            }
            // Ratatoskr every 30 s; the tricked loop's round too, so its trick shows as it would.
            if (tick % 3 == 0) {
                if (s.state == "striking") s.trick.loop?.let { name -> Parts.allLoops(devBuild).firstOrNull { it.first == name }?.let { (n, every) -> loops.run(n, every) {} } }
                runCatching { watchdog().round() }
            }
            s = s.copy(checks = noticed(s))
            _status.value = _status.value.copy(running = s)
            if (s.state == "watching" && (healed(s) || now >= heal)) break
            tick++
            delay(POLL_MS)
        }
        val caught = s.checks.all { it.noticedAt != null }
        finish(s.copy(state = if (caught) "caught" else "missed", healedAt = if (healed(s)) System.currentTimeMillis() else null), verdict = true)
    }

    private suspend fun noticed(s: Strike): List<Expect> {
        val states = ratatoskrChecks().associateBy { it.key }
        val alerts = runCatching { gjallar.active(1000) + gjallar.resolved(500) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        return s.checks.map { e ->
            if (e.noticedAt != null) return@map e
            val seen = when (e.watcher) {
                "ratatoskr" -> states[e.key]?.down == true
                else -> alerts.any { a: Alert -> a.source == "ratatoskr" && a.key == e.key && a.lastSeenAt.toEpochMilli() >= s.startedAt }
            }
            if (seen) e.copy(noticedAt = now) else e
        }
    }

    /** Healed: Ratatoskr's check is up again and Gjallar's alert resolved. */
    private suspend fun healed(s: Strike): Boolean {
        val key = s.checks.first().key
        val down = ratatoskrChecks().firstOrNull { it.key == key }?.down == true
        val open = runCatching { gjallar.active(1000) }.getOrDefault(emptyList()).any { it.source == "ratatoskr" && it.key == key }
        return !down && !open
    }

    private fun untrick(s: Strike) {
        Tricks.clear()
        s.trick.loop?.let { loops.clearTrick(it) }
    }

    private fun finish(s: Strike, verdict: Boolean) {
        val strikes = (listOf(s) + _status.value.strikes.filter { it.id != s.id }).take(KEEP)
        saveStrikes(strikes)
        _status.value = _status.value.copy(running = null, strikes = strikes)
        if (!verdict) return
        val key = "missed:${s.trick.part}:${s.trick.kind}"
        scope.launch {
            if (s.state == "missed") {
                val who = s.checks.joinToString("\n") { e ->
                    "- ${e.watcher} ${e.key}: " + (e.noticedAt?.let { "noticed after ${(it - s.startedAt) / 1000} s" } ?: "did not notice")
                }
                runCatching {
                    gjallar.raise(SOURCE, key, Severity.WARNING, "Loki tricked ${title(s.trick.part)} (${s.trick.kind}) and it went unnoticed",
                        "${s.trick.label}, ${Duration.ofMillis(s.endsAt - s.startedAt).toMinutes()} min.\n$who")
                }
            } else runCatching { gjallar.resolve(SOURCE, key) }
            plan()
        }
    }

    /** The blind spots, worst first. Rebuilt hourly and after each strike. */
    suspend fun plan(): List<BlindSpot> {
        val strikes = _status.value.strikes
        val alerts = runCatching { gjallar.active(1000) + gjallar.resolved(1000) }.getOrDefault(emptyList())
        val spots = mutableListOf<BlindSpot>()
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            spots += BlindSpot("phone:notifications", "hermodr", "Ygg's notifications are off: a critical alert reaches no one until you open the app.", "high")
        }
        if (!app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) {
            spots += BlindSpot("phone:battery", "ratatoskr", "Android may hold back Ygg's background rounds; loops then stall with nobody looking until Ratatoskr's next round says it was silent.", "medium")
        }
        spots += BlindSpot("slow", "", "Nothing times the parts' work: a part that answers slowly looks fine to every watcher.", "medium")
        tricks().filter { it.kind == "health" }.forEach { t ->
            if (strikes.none { it.trick.part == t.part && it.trick.kind == "health" && it.state == "caught" }) {
                spots += BlindSpot("unproven:${t.part}", t.part, "${title(t.part)} has never been seen down by the watchers: no strike has proved they'd notice.", "low", t)
            }
        }
        tricks().filter { it.kind == "loop" }.forEach { t ->
            val loop = t.loop!!.substringAfter('.')
            val caught = strikes.any { it.trick == t && it.state == "caught" }
            val alerted = alerts.any { it.source == "ratatoskr" && (it.key == "failing:${t.part}:$loop" || it.key == "stalled:${t.part}:$loop") }
            if (!caught && !alerted) {
                spots += BlindSpot("loop:${t.part}:$loop", t.part, "${title(t.part)}'s $loop loop has never been seen failing or stalled.", "low", t)
            }
        }
        val order = mapOf("high" to 0, "medium" to 1, "low" to 2)
        val sorted = spots.sortedBy { order[it.severity] ?: 3 }
        val now = System.currentTimeMillis()
        prefs.edit { putLong("planAt", now) }
        _status.value = _status.value.copy(blindSpots = sorted, planAt = now)
        return sorted
    }

    private fun title(part: String) = Parts.INFO.firstOrNull { it.first == part }?.second ?: part

    private fun saveStrikes(list: List<Strike>) = prefs.edit {
        putString("strikes", JSONArray(list.map { s ->
            JSONObject().put("id", s.id).put("part", s.trick.part).put("kind", s.trick.kind).put("loop", s.trick.loop ?: "")
                .put("startedAt", s.startedAt).put("endsAt", s.endsAt).put("state", s.state).put("healedAt", s.healedAt ?: 0)
                .put("checks", JSONArray(s.checks.map { JSONObject().put("watcher", it.watcher).put("key", it.key).put("noticedAt", it.noticedAt ?: 0) }))
        }).toString())
    }

    private fun loadStrikes(): List<Strike> = runCatching {
        val a = JSONArray(prefs.getString("strikes", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val cs = o.getJSONArray("checks")
            Strike(
                id = o.getString("id"),
                trick = TrickChoice(o.getString("part"), o.getString("kind"), o.optString("loop").ifEmpty { null }),
                startedAt = o.getLong("startedAt"), endsAt = o.getLong("endsAt"),
                // A strike that was running when the app stopped: its trick ended with the process.
                state = o.getString("state").let { if (it == "striking" || it == "watching") "stopped" else it },
                checks = (0 until cs.length()).map { cs.getJSONObject(it) }.map { c ->
                    Expect(c.getString("watcher"), c.getString("key"), c.optLong("noticedAt").takeIf { it > 0 })
                },
                healedAt = o.optLong("healedAt").takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val SOURCE = "loki"
        const val POLL_MS = 10_000L
        const val HEAL_WAIT_MS = 5 * 60_000L
        const val KEEP = 200
        val UNTRICKABLE = setOf("loki", "gjallar", "ratatoskr")
    }
}
