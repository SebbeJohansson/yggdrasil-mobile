package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.saga.ago
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.launch
import java.time.Instant

/** How often the card reads the machine again while it shows: sizes cost the machine a walk of the trees. */
private const val CLEANUP_READ_MS = 10 * 60_000L

/**
 * Cleanup on a machine reached directly: its quiet projects and what their
 * git-ignored node_modules, build output and caches take, Clean now, and per
 * project (Details) a Clean for one you are done with, however recent. Left
 * out for a Yggdrasil from before cleanup.
 */
@Composable
fun MachineCleanup(d: DeviceSessions, vm: CodeViewModel) {
    val scope = rememberCoroutineScope()
    var status by remember(d.device) { mutableStateOf<CleanupStatus?>(null) }
    var unsupported by remember(d.device) { mutableStateOf(false) }
    var note by remember(d.device) { mutableStateOf<String?>(null) }
    var busy by remember(d.device) { mutableStateOf(false) }
    var open by remember(d.device) { mutableStateOf(false) }
    var reads by remember(d.device) { mutableIntStateOf(0) }

    LaunchedEffect(d.device, reads) {
        while (true) {
            vm.cleanup(d.device)
                .onSuccess { status = it; unsupported = false }
                .onFailure { if (Cleanup.unsupported(it.message)) unsupported = true }
            kotlinx.coroutines.delay(CLEANUP_READ_MS)
        }
    }
    if (unsupported) return
    val s = status ?: return

    fun clean(project: String?) {
        busy = true
        note = "Cleaning${project?.let { " $it" } ?: ""} on ${d.title}…"
        scope.launch {
            vm.cleanNow(d.device, project)
                .onSuccess { run ->
                    note = if (run.folders == 0) "Nothing to clean." else "Removed ${run.folders} folders, ${Cleanup.size(run.bytes)} freed." +
                        if (run.errors.isNotEmpty()) " Left alone: ${run.errors.first()}" else ""
                }
                .onFailure { note = it.message ?: "cleaning did not work" }
            busy = false
            reads++
        }
    }

    val now = Instant.now()
    Column(Modifier.fillMaxWidth().leftStripe(if (s.quiet.isEmpty()) Hud.overlay0 else Hud.yellow).padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudLabel("Cleanup")
        Text(Cleanup.summary(s), style = HudText.meta.copy(color = if (s.quiet.isEmpty()) Hud.overlay2 else Hud.text))
        s.last?.takeIf { it.folders > 0 }?.let { Text("Last cleaned ${ago(it.at, now)} ago: ${it.folders} folders, ${Cleanup.size(it.bytes)}.", style = HudText.meta) }
        note?.let { Text(it, style = HudText.meta.copy(color = Hud.cyan)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.quiet.isNotEmpty()) HudButton(if (busy) "Cleaning…" else "Clean now", { clean(null) }, kind = HudButtonKind.SMALL, enabled = !busy)
            if (s.projects.any { it.folders > 0 }) HudButton(if (open) "Hide details" else "Details", { open = !open }, kind = HudButtonKind.SMALL)
        }
        if (open) s.projects.filter { it.folders > 0 }.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = HudText.small.copy(color = Hud.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(
                        "${Cleanup.size(p.bytes)} in ${p.folders} folder${if (p.folders == 1) "" else "s"}",
                        p.lastActive?.let { "quiet ${ago(it, now)}" },
                        if (p.inactive) "goes next" else null,
                    ).joinToString(" · "), style = HudText.meta.copy(color = if (p.inactive) Hud.yellow else Hud.overlay2))
                }
                HudButton("Clean", { clean(p.name) }, kind = HudButtonKind.SMALL, enabled = !busy)
            }
        }
    }
}
