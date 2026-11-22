package io.github.sebbejohansson.yggdrasil.ratatoskr

import io.github.sebbejohansson.yggdrasil.core.LoopRun
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WatchdogTest {
    private val now = Instant.parse("2026-11-22T12:00:00Z")

    @Test fun oneProblemOneAlert() {
        val loop = LoopRun("saga.sync", Duration.ofMinutes(15), now.minusSeconds(3600))
        val checks = Watchdog.checks(listOf(PartSeen("saga", up = false, error = "Loki's trick", loops = listOf(loop to LoopState.STALLED))), now) { "Sága" }
        assertEquals(listOf("down:saga"), checks.map { it.key })
        assertEquals(false, checks.single().ok)
        assertEquals(Severity.CRITICAL, checks.single().severity)
        assertEquals("Sága is down", checks.single().title)
    }

    @Test fun loopsAsTheDesktopWords() {
        val stalled = LoopRun("saga.sync", Duration.ofMinutes(15), now.minusSeconds(3600))
        val failing = LoopRun("dellingr.tick", Duration.ofMinutes(15), now.minusSeconds(60), "boom")
        val checks = Watchdog.checks(listOf(
            PartSeen("saga", up = true, loops = listOf(stalled to LoopState.STALLED)),
            PartSeen("dellingr", up = true, loops = listOf(failing to LoopState.ERROR)),
        ), now) { it.replaceFirstChar(Char::uppercase) }.associateBy { it.key }
        assertEquals(false, checks.getValue("stalled:saga:sync").ok)
        assertEquals("Saga's sync loop has not run in 60 min", checks.getValue("stalled:saga:sync").title)
        assertEquals(true, checks.getValue("failing:saga:sync").ok)
        assertEquals(false, checks.getValue("failing:dellingr:tick").ok)
        assertEquals("boom", checks.getValue("failing:dellingr:tick").detail)
        assertEquals(true, checks.getValue("down:dellingr").ok)
    }
}
