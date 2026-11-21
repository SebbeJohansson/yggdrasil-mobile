package io.github.sebbejohansson.yggdrasil.boot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel

import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel

/** Settings → Phone: the boot sequence's switches, as the dashboard's Settings → Boot sequence. */
@Composable
fun BootPanel(settings: BootSettings, onRunNow: () -> Unit) {
    var choice by remember { mutableStateOf(settings.choice) }
    fun set(c: BootChoice) {
        choice = c
        settings.choice = c
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch("Daily boot", "The first open each day waits for a tap, then brings every part online.", choice.on) { set(choice.copy(on = it)) }
            Switch("Voice", "Says how it went, with a voice that runs on this phone only. None installed, no voice.", choice.voice) { set(choice.copy(voice = it)) }
            Switch("Quick check", "Every other open: the same check in about a second, see-through.", choice.quick) { set(choice.copy(quick = it)) }
        }
        HudButton("Run it now", onRunNow, kind = HudButtonKind.GHOST)
    }
}

@Composable
private fun Switch(title: String, line: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            HudLabel(title)
            Text(line, style = HudText.meta)
        }
        HudToggle(on, onChange)
    }
}
