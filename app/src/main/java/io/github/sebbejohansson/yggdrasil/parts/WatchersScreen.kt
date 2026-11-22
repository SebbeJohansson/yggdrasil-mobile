package io.github.sebbejohansson.yggdrasil.parts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.core.LoopState
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.launch
import java.time.Instant

private fun severityColor(s: String) = when (s) {
    "critical" -> Hud.red
    "high" -> Hud.amber
    "medium" -> Hud.yellow
    else -> Hud.overlay0
}

/**
 * The watchers, as the desktop's: Ratatoskr (are the parts up, do their loops
 * run), Munin (does what's outside the phone answer), Heimdall (security
 * findings, the repo's and the phone's) and Loki (blind spots, and tricks to
 * see whether the others notice). What they find goes to Gjallar as alerts;
 * this is where to look at them and ask for a round now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchersScreen(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val dog by c.watchdog.status.collectAsStateWithLifecycle()
    val munin by c.munin.status.collectAsStateWithLifecycle()
    val heimdall by c.heimdall.status.collectAsStateWithLifecycle()
    val loki by c.loki.status.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    fun now(what: suspend () -> Unit) {
        busy = true
        scope.launch { runCatching { what() }; busy = false }
    }
    // A first look on opening, for the ones that haven't looked since the app started.
    LaunchedEffect(Unit) {
        if (dog.checkedAt == null) runCatching { c.watchdog.round() }
        if (munin.checkedAt == null) runCatching { c.munin.round() }
        if (heimdall.checkedAt == null) runCatching { c.heimdall.round() }
        if (loki.planAt == null || loki.blindSpots.isEmpty()) runCatching { c.loki.plan() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Watchers", style = HudText.title)
                HudLabel("who notices when something breaks")
            }
            HudButton(if (busy) "Looking…" else "Look now", { now { c.watchRounds() } }, kind = HudButtonKind.SMALL, enabled = !busy)
        }

        HudSection("Ratatoskr", Modifier.padding(top = 6.dp)) { HudLabel(dog.checkedAt?.let { "looked ${Parts.ago(it, Instant.now())}" } ?: "not yet") }
        Column(Modifier.fillMaxWidth().hudPanel(warn = dog.checks.any { it.down }).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Is each part up, and do its loops run on time. Down after two looks in a row, up after two.", style = HudText.meta)
            dog.parts.forEach { p ->
                val bad = p.loops.filter { it.second == LoopState.STALLED || it.second == LoopState.ERROR }
                Text(
                    (if (!p.up) "✕ " else if (bad.isNotEmpty()) "! " else "✓ ") + (Parts.INFO.firstOrNull { it.first == p.name }?.second ?: p.name) +
                        (p.error?.let { " · $it" } ?: "") +
                        bad.joinToString("") { " · ${it.first.name.substringAfter('.')} ${it.second.words}" },
                    style = HudText.small.copy(color = if (!p.up) Hud.red else if (bad.isNotEmpty()) Hud.amber else Hud.text),
                )
            }
            dog.checks.filter { it.down }.forEach { Text("raised: ${it.title}", style = HudText.meta.copy(color = Hud.amber)) }
        }

        HudSection("Munin", Modifier.padding(top = 6.dp)) { HudLabel(if (munin.online) "online" else "offline") }
        Column(Modifier.fillMaxWidth().hudPanel(warn = munin.checks.any { it.down }).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What the parts depend on outside the phone. Any answer counts as up; offline, the internet's checks wait.", style = HudText.meta)
            munin.targets.forEach { t ->
                Text((if (t.up) "✓ " else "✕ ") + t.target.name + (t.detail?.let { " · $it" } ?: ""),
                    style = HudText.small.copy(color = if (t.up) Hud.text else Hud.amber), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            munin.checks.filter { it.down && it.key.startsWith("incident:") }.forEach { Text(it.title, style = HudText.meta.copy(color = Hud.amber)) }
        }

        HudSection("Heimdall", Modifier.padding(top = 6.dp)) { HudLabel("${heimdall.findings.size} findings") }
        Column(Modifier.fillMaxWidth().hudPanel(warn = heimdall.findings.any { it.severity == "critical" || it.severity == "high" }).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The repo's findings (a GitHub Action, nightly: vulnerable dependencies, keys in commits, the app's settings) and the phone's own.",
                style = HudText.meta)
            heimdall.repoError?.let { Text("The repo's last findings are kept: $it", style = HudText.meta.copy(color = Hud.amber)) }
            if (heimdall.findings.isEmpty()) Text("All clear.", style = HudText.small.copy(color = Hud.green))
            heimdall.findings.forEach { f ->
                Column(Modifier.fillMaxWidth().leftStripe(severityColor(f.severity)).padding(start = 8.dp)) {
                    Text("${f.severity} · ${f.title}", style = HudText.small.copy(color = Hud.text))
                    if (f.detail.isNotBlank()) Text(f.detail, style = HudText.meta, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            HudButton("The heimdall issue", { uri.openUri("https://github.com/${io.github.sebbejohansson.yggdrasil.update.Updates.REPO}/issues?q=label%3Aheimdall") },
                kind = HudButtonKind.SMALL)
        }

        HudSection("Loki", Modifier.padding(top = 6.dp)) { HudLabel("${loki.blindSpots.size} blind spots") }
        Column(Modifier.fillMaxWidth().hudPanel(warn = loki.strikes.firstOrNull()?.state == "missed").padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tricks a part for a few minutes and checks whether Ratatoskr and Gjallar notice. Every trick ends on time; it leaves Gjallar and Ratatoskr alone.",
                style = HudText.meta)
            loki.running?.let { s ->
                Text("Tricking ${s.trick.part}: ${s.trick.label} · ${s.state}", style = HudText.small.copy(color = Hud.cyan))
                s.checks.forEach { e -> Text("${e.watcher} ${e.key}: " + if (e.noticedAt != null) "noticed" else "not yet", style = HudText.meta) }
                HudButton("Stop the trick", { c.loki.stop() }, kind = HudButtonKind.SMALL)
            }
            loki.blindSpots.forEach { b ->
                Column(Modifier.fillMaxWidth().leftStripe(severityColor(b.severity)).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.text, style = HudText.small.copy(color = Hud.text))
                    b.trick?.let { t ->
                        HudButton("Trick it: ${t.label} (3 min)", { c.loki.start(t) }, kind = HudButtonKind.SMALL, enabled = loki.running == null)
                    }
                }
            }
            if (loki.strikes.isNotEmpty()) {
                HudLabel("last strikes")
                loki.strikes.take(10).forEach { s ->
                    Text("${s.trick.part} · ${s.trick.label} · ${s.state}",
                        style = HudText.meta.copy(color = when (s.state) { "caught" -> Hud.green; "missed" -> Hud.amber; else -> Hud.overlay0 }))
                }
            }
            var all by remember { mutableStateOf(false) }
            HudButton(if (all) "Hide the tricks" else "Every trick", { all = !all }, kind = HudButtonKind.SMALL)
            if (all) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.loki.tricks().forEach { t ->
                    HudButton("${t.part}: ${t.label}", { c.loki.start(t) }, kind = HudButtonKind.SMALL, enabled = loki.running == null)
                }
            }
        }
    }
}
