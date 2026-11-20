package io.github.sebbejohansson.yggdrasil.hermodr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class QuietHoursTest {
    private fun t(s: String) = LocalTime.parse(s)
    private val overnight = QuietHours(true, t("22:00"), t("07:00"))

    @Test fun overnightWindowWrapsMidnight() {
        assertTrue(overnight.contains(t("22:00")))
        assertTrue(overnight.contains(t("23:59")))
        assertTrue(overnight.contains(t("03:00")))
        assertFalse(overnight.contains(t("07:00")))
        assertFalse(overnight.contains(t("12:00")))
    }

    @Test fun daytimeWindow() {
        val q = QuietHours(true, t("13:00"), t("14:00"))
        assertTrue(q.contains(t("13:30")))
        assertFalse(q.contains(t("14:00")))
        assertFalse(q.contains(t("12:59")))
    }

    @Test fun offOrEmptyNeverContains() {
        assertFalse(overnight.copy(enabled = false).contains(t("23:00")))
        assertFalse(QuietHours(true, t("09:00"), t("09:00")).contains(t("09:00")))
    }

    @Test fun endBeforeMidnightIsNextMorning() {
        val now = Instant.parse("2026-11-20T23:15:00Z")
        assertEquals(Instant.parse("2026-11-21T07:00:00Z"), overnight.endAfter(now, ZoneOffset.UTC))
    }

    @Test fun endAfterMidnightIsSameMorning() {
        val now = Instant.parse("2026-11-21T02:00:00Z")
        assertEquals(Instant.parse("2026-11-21T07:00:00Z"), overnight.endAfter(now, ZoneOffset.UTC))
    }

    @Test fun noEndOutsideTheWindow() {
        assertNull(overnight.endAfter(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC))
    }

    @Test fun usesTheGivenZone() {
        // 21:30 UTC is 22:30 in Stockholm (UTC+1 in November): quiet.
        val now = Instant.parse("2026-11-20T21:30:00Z")
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        assertTrue(overnight.contains(now, stockholm))
        assertEquals(Instant.parse("2026-11-21T06:00:00Z"), overnight.endAfter(now, stockholm))
    }
}
