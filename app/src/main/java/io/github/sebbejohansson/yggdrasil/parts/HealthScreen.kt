package io.github.sebbejohansson.yggdrasil.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sebbejohansson.yggdrasil.core.LoopRules
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertItem
import io.github.sebbejohansson.yggdrasil.gjallar.AlertRow
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsViewModel
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import java.time.Instant

private fun PartHealth.color(): Color = when (this) {
    PartHealth.UP -> Hud.green
    PartHealth.DEGRADED -> Hud.amber
    PartHealth.DOWN -> Hud.red
    PartHealth.OFF -> Hud.overlay0
}

private fun LoopState.color(): Color = when (this) {
    LoopState.OK -> Hud.green
    LoopState.LATE, LoopState.WAITING -> Hud.amber
    LoopState.STALLED, LoopState.ERROR -> Hud.red
}

/**
 * Health, as the dashboard's page behind the arc reactor: every part as a
 * card, up, needing a look or down, with what it does, what it knows, its
 * loops and when they last ran, and the alerts it raised (Seen, Snooze,
 * Mute). Alerts no part claims are listed at the end.
 */
@Composable
fun HealthScreen(cards: List<PartCard>, unclaimed: List<AlertRow>, alerts: AlertsViewModel, onBack: () -> Unit) {
    val now = Instant.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health", style = HudText.title)
                val up = cards.count { it.health == PartHealth.UP }
                val look = cards.count { it.health == PartHealth.DEGRADED }
                val down = cards.count { it.health == PartHealth.DOWN }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudLabel("$up up", color = Hud.green)
                    if (look > 0) HudLabel("$look need a look", color = Hud.amber)
                    if (down > 0) HudLabel("$down down", color = Hud.red)
                }
            }
            HudButton("Back", onBack, kind = HudButtonKind.SMALL)
        }
        cards.forEach { PartCardView(it, alerts, now) }
        if (unclaimed.isNotEmpty()) {
            HudSection("Other alerts", Modifier.padding(top = 4.dp))
            Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                unclaimed.forEach { AlertItem(it, alerts) }
            }
        }
    }
}

@Composable
private fun PartCardView(card: PartCard, alerts: AlertsViewModel, now: Instant) {
    val tint = card.health.color()
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = card.health == PartHealth.DEGRADED || card.health == PartHealth.DOWN).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Box(Modifier.width(8.dp))
            Text(card.label, style = HudText.body.copy(color = Hud.bright), modifier = Modifier.weight(1f))
            HudLabel(card.health.words, color = tint)
        }
        Text(card.what, style = HudText.meta)
        card.problem?.let { Text(it, style = HudText.small.copy(color = if (card.health == PartHealth.OFF) Hud.subtext0 else Hud.amber)) }
        if (card.facts.isNotEmpty()) Text(card.facts.joinToString(" · "), style = HudText.meta.copy(color = Hud.subtext0))
        if (card.loops.isNotEmpty()) {
            HudLabel("Loops", color = Hud.overlay2, modifier = Modifier.padding(top = 4.dp))
            card.loops.forEach { (loop, state) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(state.color(), CircleShape))
                    Box(Modifier.width(8.dp))
                    Text(loop.name.substringAfter('.'), style = HudText.small, modifier = Modifier.weight(1f))
                    Text(LoopRules.every(loop.every), style = HudText.meta, modifier = Modifier.padding(end = 10.dp))
                    Text(
                        if (state == LoopState.OK) loop.lastRunAt?.let { Parts.ago(it, now) }.orEmpty() else state.words,
                        style = HudText.meta.copy(color = if (state == LoopState.OK) Hud.subtext0 else state.color()),
                    )
                }
                loop.lastError?.let { Text(it, style = HudText.meta.copy(color = Hud.red), modifier = Modifier.padding(start = 14.dp)) }
            }
        }
        if (card.alerts.isNotEmpty()) {
            HudLabel("Alerts", color = Hud.overlay2, modifier = Modifier.padding(top = 4.dp))
            card.alerts.forEach { AlertItem(it, alerts) }
        }
    }
}
