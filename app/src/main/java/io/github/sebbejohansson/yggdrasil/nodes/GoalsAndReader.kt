package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A session to read: on which machine, its id, and where it runs (which finds its transcript there). */
data class ReadTarget(val machine: String, val machineLabel: String, val session: String, val directory: String?, val name: String)

/**
 * A machine's goals in play, under its sessions in the Code tab: what each
 * needs next, its parts (one agent per project, each readable), Start for a
 * draft, Resume for one waiting on Claude usage, and a new draft.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineGoals(d: DeviceSessions, goals: List<MachineGoal>, vm: CodeViewModel, onRead: (ReadTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember(d.device) { mutableStateOf<String?>(null) }
    var title by remember(d.device) { mutableStateOf("") }
    var repos by remember(d.device) { mutableStateOf(listOf<String>()) }
    var adding by remember(d.device) { mutableStateOf(false) }
    val label = d.title

    HudLabel("Goals")
    if (goals.isEmpty()) Text("No goals in play there.", style = HudText.small.copy(color = Hud.overlay0))
    goals.forEach { g ->
        Column(Modifier.fillMaxWidth().leftStripe(goalColor(g.state)).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(g.title, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Goals.LABEL[g.state] ?: g.state, style = HudText.meta.copy(color = goalColor(g.state)))
            }
            val where = g.repos.joinToString(" · ").ifEmpty { "no project yet" }
            Text(listOf(where, g.nextText).filter { it.isNotBlank() }.joinToString(" — "), style = HudText.meta)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.startable) HudButton("Start", {
                    scope.launch { note = vm.onGoal(d.device) { it.startGoal(g.id) } }
                }, kind = HudButtonKind.SMALL)
                if (g.resumable) HudButton("Resume", {
                    scope.launch { note = vm.onGoal(d.device) { it.resumeGoal(g.id) } }
                }, kind = HudButtonKind.SMALL)
                g.parts.forEach { p ->
                    HudButton("Read ${p.repo ?: p.label ?: "part"} · ${p.status}", {
                        onRead(ReadTarget(d.device, label, p.agent, null, "${g.title} · ${p.repo ?: ""}".trimEnd(' ', '·')))
                    }, kind = HudButtonKind.SMALL)
                }
            }
        }
    }
    note?.let { HudLabel(it, color = Hud.amber) }
    if (adding) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(title, { title = it }, placeholder = "New goal on $label: what should be done?", maxLines = 3)
            if (d.projects.isNotEmpty()) {
                HudLabel("Projects (up to 4)", color = Hud.overlay2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.projects.forEach { p ->
                        val on = p.name in repos
                        HudButton(p.name, { repos = if (on) repos - p.name else repos + p.name },
                            kind = if (on) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudButton("Create draft", {
                    val problem = Goals.checkNew(title, repos)
                    if (problem != null) {
                        note = problem
                    } else {
                        val t = title
                        val r = repos
                        scope.launch {
                            note = vm.onGoal(d.device) { it.createGoal(t, r) }
                            if (note == null) { title = ""; repos = emptyList(); adding = false }
                        }
                    }
                }, kind = HudButtonKind.SMALL)
                HudButton("Cancel", { adding = false; note = null }, kind = HudButtonKind.SMALL)
            }
        }
    } else {
        HudButton("New goal", { adding = true }, kind = HudButtonKind.SMALL)
    }
}

private fun goalColor(state: String) = when (state) {
    "needs-you" -> Hud.yellow
    "running" -> Hud.green
    "done" -> Hud.cyan
    else -> Hud.overlay0
}

/** How much of the end of a transcript is read: hours of a session, a few hundred KB. */
private const val TAIL_BYTES = 400_000

/**
 * A session on another machine, read: your prompts, its replies, and each
 * tool call with what came back, following along every 3 seconds while open.
 * Only what changed is sent again (`since`), so an idle session costs a few
 * bytes over the tailnet.
 */
@Composable
fun TranscriptDialog(target: ReadTarget, vm: CodeViewModel, onDismiss: () -> Unit) {
    var entries by remember(target) { mutableStateOf<List<TranscriptEntry>>(emptyList()) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var loaded by remember(target) { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(target) {
        var since: String? = null
        while (true) {
            runCatching { vm.transcript(target.machine, target.session, target.directory, TAIL_BYTES, since) }
                .onSuccess { t ->
                    if (!t.unchanged) {
                        entries = Transcript.read(t.text)
                        since = t.stamp
                        if (entries.isNotEmpty()) list.scrollToItem(entries.size - 1)
                    }
                    error = null
                }
                .onFailure { error = it.message ?: "could not read it" }
            loaded = true
            delay(3_000)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 20.dp).background(Hud.base).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(target.name, style = HudText.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HudLabel("on ${target.machineLabel} · read only · over the tailnet")
                }
                HudButton("Close", onDismiss, kind = HudButtonKind.SMALL)
            }
            error?.let { HudLabel(it, color = Hud.amber) }
            if (loaded && entries.isEmpty() && error == null) Text("Nothing said yet.", style = HudText.small.copy(color = Hud.overlay0))
            LazyColumn(state = list, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries) { e -> EntryView(e) }
            }
        }
    }
}

@Composable
private fun EntryView(e: TranscriptEntry) {
    when (e) {
        is TranscriptEntry.Prompt -> Text("› ${e.text}", style = HudText.body.copy(color = Hud.cyan))
        is TranscriptEntry.Reply -> Text(e.text, style = HudText.body)
        is TranscriptEntry.Thinking -> Text(e.text, style = HudText.meta.copy(color = Hud.overlay0), maxLines = 6, overflow = TextOverflow.Ellipsis)
        is TranscriptEntry.Tool -> Column(Modifier.fillMaxWidth().leftStripe(if (e.failed) Hud.yellow else Hud.overlay0).padding(start = 8.dp)) {
            Text("${e.name}  ${e.detail}", style = HudText.meta.copy(color = Hud.subtext0), maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.output?.takeIf { it.isNotBlank() }?.let { Text(it, style = HudText.meta.copy(color = Hud.overlay2), maxLines = 8, overflow = TextOverflow.Ellipsis) }
        }
    }
}
