package io.github.sebbejohansson.yggdrasil.ratatoskr

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel

/** Settings: watching you on or off, as a notification or listed only, and what it sees now. */
@Composable
fun YouPanel(you: YouService) {
    val status by you.status.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(you.settings.on) }
    var push by remember { mutableStateOf(you.settings.push) }
    Text(
        "One quiet note a day when your rhythm runs hot: ${you.rhythm.stretch.toHours()} h of meetings back to back, the phone in use " +
            "past ${you.rhythm.late}, or a week that's mostly meetings. Times only: meeting titles and people never go in a note.",
        style = HudText.meta,
    )
    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch("Watch my rhythm", on) { on = it; you.settings.on = it }
        Switch("As a notification", push) { push = it; you.settings.push = it }
    }
    if (on) {
        You.Pattern.entries.forEach { p ->
            val active = p in status.active
            HudLabel(
                p.title + if (active) " · now" + (status.active[p]?.let { " $it" } ?: "") else " · no",
                color = if (active) Hud.amber else Hud.overlay2,
            )
        }
    }
}

@Composable
private fun Switch(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HudLabel(title, modifier = Modifier.weight(1f))
        HudToggle(on, onChange)
    }
}
