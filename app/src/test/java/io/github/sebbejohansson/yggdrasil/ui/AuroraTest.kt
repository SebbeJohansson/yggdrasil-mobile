package io.github.sebbejohansson.yggdrasil.ui

import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class AuroraTest {
    private val now = Instant.parse("2026-11-20T21:00:00Z")
    private fun seen(s: Severity, at: Instant = now, muted: Boolean = false) = Aurora.SeenAlert(s, at, muted)

    @Test fun aQuietDayIsCalm() {
        assertEquals(0.0, Aurora.storminess(emptyList(), 0, now, ZoneOffset.UTC), 1e-9)
    }

    @Test fun alertsTodayMakeItStormierAndItSaturates() {
        val one = Aurora.storminess(listOf(seen(Severity.WARNING)), 0, now, ZoneOffset.UTC)
        val many = Aurora.storminess(List(20) { seen(Severity.CRITICAL) }, 3, now, ZoneOffset.UTC)
        assertTrue(one > 0.2 && one < 0.3)
        assertTrue(many > one && many < 1.0)
    }

    @Test fun mutedAndYesterdaysAlertsDontCount() {
        val yesterday = now.minusSeconds(86_400)
        assertEquals(0.0, Aurora.storminess(listOf(seen(Severity.CRITICAL, muted = true), seen(Severity.CRITICAL, yesterday)), 0, now, ZoneOffset.UTC), 1e-9)
    }

    @Test fun calmIsGreenStormyIsVioletNeverRed() {
        val calm = Aurora.params(0.0).palette[0]
        val stormy = Aurora.params(1.0).palette[0]
        assertTrue(calm.green > calm.red && calm.green > calm.blue)
        assertTrue(stormy.blue > stormy.green && stormy.blue > stormy.red)
    }

    @Test fun showsSoonerAfterHours() {
        assertTrue(Aurora.afterHours(LocalDateTime.parse("2026-11-20T21:00")))
        assertFalse(Aurora.afterHours(LocalDateTime.parse("2026-11-20T10:00")))
        assertTrue(Aurora.afterHours(LocalDateTime.parse("2026-11-21T10:00"))) // Saturday
        assertTrue(Aurora.due(20_000, afterHours = true))
        assertFalse(Aurora.due(20_000, afterHours = false))
        assertTrue(Aurora.due(5 * 60_000, afterHours = false))
    }
}
