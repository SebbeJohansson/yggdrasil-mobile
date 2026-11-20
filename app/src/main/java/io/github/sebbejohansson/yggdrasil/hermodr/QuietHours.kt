package io.github.sebbejohansson.yggdrasil.hermodr

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A daily window when only urgent messages go out. It may cross midnight
 * (22:00–07:00). Start and end equal means no quiet hours.
 */
data class QuietHours(
    val enabled: Boolean = true,
    val start: LocalTime = LocalTime.of(22, 0),
    val end: LocalTime = LocalTime.of(7, 0),
) {
    fun contains(t: LocalTime): Boolean = when {
        !enabled || start == end -> false
        start < end -> t >= start && t < end
        else -> t >= start || t < end
    }

    fun contains(now: Instant, zone: ZoneId) = contains(now.atZone(zone).toLocalTime())

    /** When the quiet hours that [now] falls in end. Null when [now] isn't in them. */
    fun endAfter(now: Instant, zone: ZoneId): Instant? {
        if (!contains(now, zone)) return null
        val local = now.atZone(zone)
        var end = local.with(this.end).withSecond(0).withNano(0)
        if (!end.isAfter(local)) end = end.plusDays(1)
        return end.toInstant()
    }

    override fun toString() = if (enabled && start != end) "$start–$end" else "off"
}
