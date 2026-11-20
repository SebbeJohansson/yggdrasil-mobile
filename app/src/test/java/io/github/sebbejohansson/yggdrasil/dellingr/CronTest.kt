package io.github.sebbejohansson.yggdrasil.dellingr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The desktop's dellingr_test.go cases, ported. */
class CronTest {
    @Test fun wordsBecomeCron() {
        mapOf(
            "every weekday at 8" to "0 8 * * 1-5",
            "every day at 07:30" to "30 7 * * *",
            "every monday and friday at 9" to "0 9 * * 1,5",
            "the 1st of each month at 10" to "0 10 1 * *",
            "every 15 minutes" to "*/15 * * * *",
            "every hour" to "0 * * * *",
            "every friday at 4pm" to "0 16 * * 5",
            "0 8 * * 1-5" to "0 8 * * 1-5",
            "every weekend at 10am" to "0 10 * * 0,6",
        ).forEach { (words, want) -> assertEquals(words, want, Cron.words(words)) }
        for (bad in listOf("sometimes", "every day at 25", "the 31st of each month at 10")) {
            try {
                fail("\"$bad\" gave ${Cron.words(bad)}")
            } catch (_: CronException) {
            }
        }
    }

    @Test fun saidAndNext() {
        assertEquals("Monday to Friday at 08:00", Cron.said("0 8 * * 1-5"))
        assertEquals("the 1st of each month at 10:00", Cron.said("0 10 1 * *"))
        assertEquals("Mondays and Fridays at 09:00", Cron.said("0 9 * * 1,5"))
        val stockholm = ZoneId.of("Europe/Stockholm")
        val friday = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, stockholm)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, stockholm), Cron.parse("0 8 * * 1-5").next(friday, stockholm))
        assertEquals(ZonedDateTime.of(2026, 12, 1, 10, 0, 0, 0, stockholm), Cron.parse("0 10 1 * *").next(friday, stockholm))
    }

    @Test fun sundayIsZeroOrSeven() {
        val utc = ZoneId.of("UTC")
        val saturday = ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, utc), Cron.parse("0 9 * * 7").next(saturday, utc))
    }

    @Test fun nextIsAfterNotAt() {
        val utc = ZoneId.of("UTC")
        val eight = ZonedDateTime.of(2026, 11, 20, 8, 0, 0, 0, utc)
        assertEquals(eight.plusDays(1), Cron.parse("0 8 * * *").next(eight, utc))
    }
}
