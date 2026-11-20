package io.github.sebbejohansson.yggdrasil.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class BootLogicTest {
    private val phone = BootPhone(16L * 1024 * 1024 * 1024, 80, hot = false)
    private val allUp = listOf("Norn" to Health.UP, "Gjallar" to Health.UP, "Hermóðr" to Health.UP)

    @Test fun linesComeInOrderPartsOutsidePhone() {
        val lines = BootLogic.lines(allUp, listOf("Network" to true), phone)
        assertEquals(listOf(LineGroup.PARTS, LineGroup.PARTS, LineGroup.PARTS, LineGroup.OUTSIDE, LineGroup.MACHINE), lines.map { it.group })
        assertEquals("PHONE · 16 GB · BATTERY 80 %", lines.last().label)
        assertEquals("HERMÓÐR", lines[2].label)
    }

    @Test fun verdictCountsOnlyThePartsThatAreOff() {
        assertEquals(BootLogic.Verdict("ALL SYSTEMS ONLINE", true), BootLogic.verdict(BootLogic.lines(allUp, listOf("Network" to false), phone)))
        val oneDown = listOf("Norn" to Health.DOWN, "Gjallar" to Health.TROUBLED, "Hermóðr" to Health.UP)
        val v = BootLogic.verdict(BootLogic.lines(oneDown, emptyList(), phone))
        assertEquals("2 OF 3 SYSTEMS ONLINE", v.words)
        assertFalse(v.calm)
    }

    @Test fun speechSaysWhoIsntThere() {
        val parts = listOf("Norn" to Health.DOWN, "Claude" to Health.DOWN, "Gjallar" to Health.TROUBLED)
        assertEquals(
            "Good morning. Yggdrasil is online. Norn and Claude are not responding. Gjallar could use a look. The phone is fine.",
            BootLogic.speech(parts, phone, LocalTime.of(8, 0)),
        )
        assertTrue(BootLogic.speech(allUp, phone.copy(batteryPercent = 10), LocalTime.of(2, 0)).startsWith("Working late"))
    }

    @Test fun fullOnceADayQuickOtherwise() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals(BootKind.FULL, BootLogic.kind(BootChoice(), null, today))
        assertEquals(BootKind.QUICK, BootLogic.kind(BootChoice(), today, today))
        assertEquals(BootKind.NONE, BootLogic.kind(BootChoice(quick = false), today, today))
        assertEquals(BootKind.QUICK, BootLogic.kind(BootChoice(on = false), null, today))
    }
}
