package io.github.sebbejohansson.yggdrasil.boot

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import java.time.LocalTime

/**
 * The boot sequence, ported from my-widget's `utils/boot.ts`: the first time
 * you open the app each day, a heads-up display brings Yggdrasil online in
 * front of you, part by part, then the outside, then the phone, and a calm
 * voice says how it went. Every other open gets the quick one: the same
 * check in about a second, no tap, no voice, see-through. Every decision is
 * here, as plain functions.
 */
enum class LineStatus(val word: String) { ONLINE("ONLINE"), DEGRADED("DEGRADED"), OFFLINE("OFFLINE"), NO_SIGNAL("NO SIGNAL") }

enum class LineGroup(val title: String) { PARTS("PARTS"), OUTSIDE("OUTSIDE"), MACHINE("PHONE") }

data class BootLine(val label: String, val status: LineStatus, val group: LineGroup)

/** A part's health, as the reactor shows it. */
enum class Health { UP, TROUBLED, DOWN, UNKNOWN }

data class BootPhone(val ramBytes: Long?, val batteryPercent: Int?, val hot: Boolean)

enum class BootKind { FULL, QUICK, NONE }

data class BootChoice(val on: Boolean = true, val voice: Boolean = true, val quick: Boolean = true)

object BootLogic {
    private fun status(h: Health) = when (h) {
        Health.UP -> LineStatus.ONLINE
        Health.TROUBLED -> LineStatus.DEGRADED
        Health.DOWN -> LineStatus.OFFLINE
        Health.UNKNOWN -> LineStatus.NO_SIGNAL
    }

    /** The lines, in the order they come online: the parts, the outside, the phone. */
    fun lines(parts: List<Pair<String, Health>>, outside: List<Pair<String, Boolean>>, phone: BootPhone): List<BootLine> = buildList {
        parts.forEach { (name, h) -> add(BootLine(name.uppercase(), status(h), LineGroup.PARTS)) }
        outside.take(6).forEach { (name, up) -> add(BootLine(name.uppercase(), if (up) LineStatus.ONLINE else LineStatus.OFFLINE, LineGroup.OUTSIDE)) }
        val specs = listOfNotNull(
            phone.ramBytes?.takeIf { it > 0 }?.let { "${Math.round(it / (1024.0 * 1024 * 1024))} GB" },
            phone.batteryPercent?.let { "BATTERY $it %" },
        )
        add(
            BootLine(
                if (specs.isEmpty()) "PHONE" else "PHONE · " + specs.joinToString(" · "),
                when {
                    specs.isEmpty() -> LineStatus.NO_SIGNAL
                    phone.hot -> LineStatus.DEGRADED
                    else -> LineStatus.ONLINE
                },
                LineGroup.MACHINE,
            ),
        )
    }

    data class Verdict(val words: String, val calm: Boolean)

    fun verdict(lines: List<BootLine>): Verdict {
        val parts = lines.filter { it.group == LineGroup.PARTS }
        val off = parts.count { it.status == LineStatus.OFFLINE || it.status == LineStatus.NO_SIGNAL }
        return if (off == 0) Verdict("ALL SYSTEMS ONLINE", true) else Verdict("${parts.size - off} OF ${parts.size} SYSTEMS ONLINE", false)
    }

    fun greeting(now: LocalTime) = when {
        now.hour < 5 -> "Working late"
        now.hour < 12 -> "Good morning"
        now.hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    private fun list(names: List<String>) =
        if (names.size < 2) names.firstOrNull().orEmpty() else names.dropLast(1).joinToString(", ") + " and " + names.last()

    /** What the voice says: a greeting, who is online, who isn't, and the phone. */
    fun speech(parts: List<Pair<String, Health>>, phone: BootPhone, now: LocalTime): String {
        val off = parts.filter { it.second == Health.DOWN || it.second == Health.UNKNOWN }.map { it.first }
        val troubled = parts.filter { it.second == Health.TROUBLED }.map { it.first }
        val s = mutableListOf("${greeting(now)}. Yggdrasil is online.")
        s += if (off.isEmpty()) "All systems are running." else "${list(off)} ${if (off.size == 1) "is" else "are"} not responding."
        if (troubled.isNotEmpty()) s += "${list(troubled)} could use a look."
        phone.batteryPercent?.let { b -> s += if (b < 20) "The battery is low." else if (phone.hot) "The phone is running hot." else "The phone is fine." }
        return s.joinToString(" ")
    }

    /** Which boot an open gets: the full one when today's is due, else the quick one when it's on. */
    fun kind(choice: BootChoice, lastDay: LocalDate?, today: LocalDate): BootKind = when {
        choice.on && lastDay != today -> BootKind.FULL
        choice.quick -> BootKind.QUICK
        else -> BootKind.NONE
    }
}

/** The boot's choices and the day it last ran, in SharedPreferences. */
class BootSettings(context: Context) {
    private val prefs = context.getSharedPreferences("boot", Context.MODE_PRIVATE)

    var choice: BootChoice
        get() = BootChoice(prefs.getBoolean("on", true), prefs.getBoolean("voice", true), prefs.getBoolean("quick", true))
        set(c) = prefs.edit { putBoolean("on", c.on); putBoolean("voice", c.voice); putBoolean("quick", c.quick) }

    /** The day the full boot last ran or was skipped. */
    var lastDay: LocalDate?
        get() = prefs.getString("last_day", null)?.let(LocalDate::parse)
        set(d) = prefs.edit { putString("last_day", d?.toString()) }
}
