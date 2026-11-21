package io.github.sebbejohansson.yggdrasil.core

import android.content.Context
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant

enum class LoopState(val words: String) {
    OK("on time"),
    LATE("late"),
    STALLED("stalled"),
    WAITING("hasn't run yet"),
    ERROR("failed last run"),
}

/** One loop a part runs on a timer, and how its last run went. */
data class LoopRun(val name: String, val every: Duration, val lastRunAt: Instant? = null, val lastError: String? = null)

/**
 * Ratatoskr's rule, as the dashboard's `loopState`: stalled when it hasn't
 * run within three intervals and a minute; late, short of that, once past
 * twice its interval. A loop that hasn't run yet waits until the app has
 * been up that long.
 */
object LoopRules {
    fun state(loop: LoopRun, startedAt: Instant, now: Instant): LoopState {
        val stall = loop.every.multipliedBy(3).plusMinutes(1)
        val last = loop.lastRunAt ?: return if (Duration.between(startedAt, now) > stall) LoopState.STALLED else LoopState.WAITING
        val since = Duration.between(last, now)
        return when {
            since > stall -> LoopState.STALLED
            loop.lastError != null -> LoopState.ERROR
            since > loop.every.multipliedBy(2).plusSeconds(5) -> LoopState.LATE
            else -> LoopState.OK
        }
    }

    /** "every 15 min", "every 6 h". */
    fun every(d: Duration): String = if (d.toMinutes() % 60 == 0L) "every ${d.toHours()} h" else "every ${d.toMinutes()} min"
}

/**
 * The parts' timed loops, the phone's stand-in for each part's `/health`:
 * when each last ran, and whether it failed. Kept in preferences, because a
 * loop often runs in a worker after the app's own process has gone.
 */
class Loops(context: Context, val startedAt: Instant = Instant.now()) {
    private val prefs = context.getSharedPreferences("loops", Context.MODE_PRIVATE)
    val changes = Changes()

    fun record(name: String, every: Duration, error: String? = null, at: Instant = Instant.now()) {
        prefs.edit {
            putLong("$name.every", every.seconds)
            putLong("$name.at", at.toEpochMilli())
            if (error == null) remove("$name.error") else putString("$name.error", error.take(200))
        }
        changes.bump()
    }

    fun get(name: String, every: Duration): LoopRun = LoopRun(
        name,
        Duration.ofSeconds(prefs.getLong("$name.every", every.seconds)),
        prefs.getLong("$name.at", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        prefs.getString("$name.error", null),
    )

    /** Runs one round of a loop and records it; a failure is recorded, never thrown. */
    suspend fun run(name: String, every: Duration, block: suspend () -> Unit) {
        try {
            block()
            record(name, every)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            record(name, every, e.message ?: e.javaClass.simpleName)
        }
    }
}
