package io.github.sebbejohansson.yggdrasil.parts

import io.github.sebbejohansson.yggdrasil.core.LoopRules
import io.github.sebbejohansson.yggdrasil.core.LoopRun
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.gjallar.Alert
import io.github.sebbejohansson.yggdrasil.gjallar.AlertRow
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HealthTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val every = Duration.ofMinutes(15)
    private fun loop(minutesAgo: Long?, error: String? = null) =
        LoopRun("vedrfolnir.check", every, minutesAgo?.let { now.minus(Duration.ofMinutes(it)) }, error)

    @Test fun loopsFollowRatatoskrsRule() {
        val started = now.minus(Duration.ofHours(5))
        assertEquals(LoopState.OK, LoopRules.state(loop(10), started, now))
        assertEquals(LoopState.LATE, LoopRules.state(loop(31), started, now))
        assertEquals(LoopState.STALLED, LoopRules.state(loop(47), started, now))
        assertEquals(LoopState.ERROR, LoopRules.state(loop(5, "boom"), started, now))
        assertEquals(LoopState.WAITING, LoopRules.state(loop(null), now.minus(Duration.ofMinutes(10)), now))
        assertEquals(LoopState.STALLED, LoopRules.state(loop(null), started, now))
        assertEquals("every 15 min", LoopRules.every(every))
        assertEquals("every 6 h", LoopRules.every(Duration.ofHours(6)))
    }

    private fun alert(id: Long, source: String, key: String, severity: Severity = Severity.WARNING) = AlertRow(
        Alert(id, source, key, severity, "t$id", null, null, AlertState.OPEN, now, now), muted = false,
    )

    @Test fun cardsJudgeHealthAndClaimTheirAlerts() {
        val alerts = listOf(
            alert(1, "vedrfolnir", "battery"),
            alert(2, "gjallar", "stalled:dellingr"),
            alert(3, "test", "old"),
            alert(4, "huginn", "x", Severity.CRITICAL),
        )
        val cards = Parts.cards(
            listOf(
                PartReport("norn", down = true, problem = "No model"),
                PartReport("vedrfolnir", loops = listOf(loop(5))),
                PartReport("dellingr", loops = listOf(loop(60))),
                PartReport("huginn"),
                PartReport("claude", off = true),
            ),
            alerts, now.minus(Duration.ofHours(5)), now,
        ).associateBy { it.name }
        assertEquals(PartHealth.DOWN, cards.getValue("norn").health)
        assertEquals(PartHealth.UP, cards.getValue("vedrfolnir").health)
        assertEquals(listOf(1L), cards.getValue("vedrfolnir").alerts.map { it.alert.id })
        // A stalled loop needs a look, and an alert naming the part is its own.
        assertEquals(PartHealth.DEGRADED, cards.getValue("dellingr").health)
        assertEquals(listOf(2L), cards.getValue("dellingr").alerts.map { it.alert.id })
        // An open critical alert needs a look too.
        assertEquals(PartHealth.DEGRADED, cards.getValue("huginn").health)
        assertEquals(PartHealth.OFF, cards.getValue("claude").health)
        assertEquals(listOf(3L), Parts.unclaimed(cards.values.toList(), alerts).map { it.alert.id })
    }

    @Test fun agoIsShort() {
        assertEquals("just now", Parts.ago(now.minusSeconds(20), now))
        assertEquals("12 min ago", Parts.ago(now.minus(Duration.ofMinutes(12)), now))
        assertEquals("3 h ago", Parts.ago(now.minus(Duration.ofHours(3)), now))
        assertEquals("2 d ago", Parts.ago(now.minus(Duration.ofDays(2)), now))
    }
}
