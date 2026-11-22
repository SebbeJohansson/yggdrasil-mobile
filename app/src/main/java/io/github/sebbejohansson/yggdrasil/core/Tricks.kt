package io.github.sebbejohansson.yggdrasil.core

/**
 * Loki's trick switch, as the desktop's internal/part/trick.go but in the
 * app's own process: one trick at a time, on one part, for at most ten
 * minutes, and it ends at its time whatever happens (it's read lazily, so a
 * Loki that died mid-strike leaves nothing behind). It touches no data:
 *
 * - health: the part reads as down to Ratatoskr.
 * - loop: the named loop's rounds are skipped and recorded as failed, "Loki's trick".
 */
data class Trick(val part: String, val kind: String, val loop: String? = null, val until: Long)

object Tricks {
    const val MAX_MS = 10 * 60_000L + 5_000
    const val LOOP_ERROR = "Loki's trick"

    @Volatile private var current: Trick? = null

    fun set(t: Trick, now: Long = System.currentTimeMillis()) {
        require(t.until > now && t.until - now <= MAX_MS) { "a trick lasts at most 10 minutes" }
        require(t.kind == "health" || (t.kind == "loop" && !t.loop.isNullOrBlank())) { "health, or a loop by name" }
        check(active(now) == null) { "one trick at a time" }
        current = t
    }

    fun clear() { current = null }

    fun active(now: Long = System.currentTimeMillis()): Trick? = current?.takeIf { it.until > now }.also { if (it == null) current = null }

    /** Whether [loop] (as Loops names it, part.name) is tricked now. */
    fun loopTricked(loop: String, now: Long = System.currentTimeMillis()): Boolean =
        active(now)?.let { it.kind == "loop" && it.loop == loop } == true

    fun healthTricked(part: String, now: Long = System.currentTimeMillis()): Boolean =
        active(now)?.let { it.kind == "health" && it.part == part } == true
}
