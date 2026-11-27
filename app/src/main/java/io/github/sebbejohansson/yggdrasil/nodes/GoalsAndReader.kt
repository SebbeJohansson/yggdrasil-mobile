package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import io.github.sebbejohansson.yggdrasil.ui.rememberFollowBottom
import io.github.sebbejohansson.yggdrasil.ui.Markdown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
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
data class ReadTarget(
    val machine: String,
    val machineLabel: String,
    val session: String,
    val directory: String?,
    val name: String,
    /** The session as listed, when it can be typed into from the reader (in a herdr pane there, and that machine takes your requests). */
    val typeInto: io.github.sebbejohansson.yggdrasil.saga.RemoteSession? = null,
)

/**
 * A machine's goals in play, under its sessions in the Code tab: what each
 * needs next, its parts (one agent per project, each readable), Start for a
 * draft, Resume for one waiting on Claude usage, a new draft, and importing
 * drafts from TODO, Linear or GitHub (MachineGoalImport).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineGoals(d: DeviceSessions, goals: List<MachineGoal>, vm: CodeViewModel, onRead: (ReadTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember(d.device) { mutableStateOf<String?>(null) }
    var title by remember(d.device) { mutableStateOf("") }
    var repos by remember(d.device) { mutableStateOf(listOf<String>()) }
    var adding by remember(d.device) { mutableStateOf(false) }
    var importing by remember(d.device) { mutableStateOf(false) }
    var cloning by remember(d.device) { mutableStateOf(false) }
    var repo by remember(d.device) { mutableStateOf("") }
    var busy by remember(d.device) { mutableStateOf(false) }
    var opened by remember(d.device) { mutableStateOf<Long?>(null) }
    var showDone by remember(d.device) { mutableStateOf(false) }
    val label = d.title
    val inPlay = Goals.inPlay(goals)
    val finished = Goals.finished(goals)

    opened?.let { id -> GoalDialog(d, id, vm, onRead) { opened = null } }

    HudLabel("Goals")
    if (inPlay.isEmpty()) Text("No goals in play there.", style = HudText.small.copy(color = Hud.overlay0))
    inPlay.forEach { g -> GoalRow(g, onOpen = { opened = g.id }) {
        if (g.startable) HudButton("Start", { scope.launch { note = vm.onGoal(d.device) { it.startGoal(g.id) } } }, kind = HudButtonKind.SMALL)
        if (g.resumable) HudButton("Resume", { scope.launch { note = vm.onGoal(d.device) { it.resumeGoal(g.id) } } }, kind = HudButtonKind.SMALL)
    } }
    if (finished.isNotEmpty()) {
        HudButton(if (showDone) "Hide done" else "Done (${finished.size})", { showDone = !showDone }, kind = HudButtonKind.SMALL)
        if (showDone) finished.take(20).forEach { g -> GoalRow(g, onOpen = { opened = g.id }) {} }
    }
    note?.let { HudLabel(it, color = Hud.amber) }
    if (importing) {
        MachineGoalImport(d, vm) { done -> importing = false; note = done }
    } else if (adding) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(title, { title = it }, placeholder = "New goal on $label: what should be done?", maxLines = 3)
            if (d.projects.isEmpty()) {
                Text(Goals.noProjects(label, d.projectsAnswered), style = HudText.small.copy(color = Hud.amber))
            } else {
                HudLabel("Projects on $label (up to 4)", color = Hud.overlay2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.projects.forEach { p ->
                        val on = p.name in repos
                        HudButton(p.name, { repos = if (on) repos - p.name else repos + p.name },
                            kind = if (on) HudButtonKind.GHOST else HudButtonKind.SMALL)
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
    } else if (cloning) {
        // Into Sindri's projects folder there, so it shows as a project here, on the desktop and in goals.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(repo, { repo = it }, placeholder = "owner/repo or GitHub link, to clone on $label", maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudButton(if (busy) "Cloning…" else "Clone", {
                    val r = Goals.gitHubRepoOf(repo)
                    if (r == null) {
                        note = "Type owner/repo or a github.com link"
                    } else {
                        busy = true
                        note = "Cloning $r on $label…"
                        scope.launch {
                            val failed = vm.onGoal(d.device) { it.cloneProject(r) }
                            busy = false
                            note = failed ?: "Cloned $r on $label."
                            if (failed == null) { repo = ""; cloning = false }
                        }
                    }
                }, kind = HudButtonKind.SMALL, enabled = !busy && repo.isNotBlank())
                HudButton("Cancel", { cloning = false; note = null }, kind = HudButtonKind.SMALL, enabled = !busy)
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("New goal", { adding = true }, kind = HudButtonKind.SMALL)
            HudButton("Import", { importing = true; note = null }, kind = HudButtonKind.SMALL)
            HudButton("Clone from GitHub", { cloning = true; note = null }, kind = HudButtonKind.SMALL)
        }
    }
}

/**
 * A goal in the list: its title and state, its parts (repo · what each is
 * doing), the next step, and Open, which shows all of it (GoalDialog).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalRow(g: MachineGoal, onOpen: () -> Unit, quick: @Composable () -> Unit) {
    val color = if (g.closed) Hud.overlay0 else goalStateColor(g.state)
    Column(Modifier.fillMaxWidth().leftStripe(color).clickable(onClick = onOpen).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.title, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (g.closed) "Closed" else if (g.state == "merged") "Merged" else Goals.LABEL[g.state] ?: g.state, style = HudText.meta.copy(color = color))
        }
        val parts = if (g.parts.isEmpty()) g.repos.joinToString(" · ").ifEmpty { "no project yet" }
            else g.parts.joinToString(" · ") { p -> "${p.repo ?: p.label ?: "part"} ${Goals.partLabel(p.status)}${if (p.merged) " ✓" else ""}" }
        Text(parts, style = HudText.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (g.nextText.isNotBlank() && !g.closed && g.state != "merged") Text(g.nextText, style = HudText.meta.copy(color = color), maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Open", onOpen, kind = HudButtonKind.SMALL)
            quick()
        }
    }
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
    var entries by remember(target.session) { mutableStateOf<List<TranscriptEntry>>(emptyList()) }
    var error by remember(target.session) { mutableStateOf<String?>(null) }
    var loaded by remember(target.session) { mutableStateOf(false) }
    var draft by remember(target.session) { mutableStateOf("") }
    // Read again at once after you send, not on the next 3-second tick.
    var poke by remember(target.session) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val follow = rememberFollowBottom(list, entries.lastOrNull()?.let { it.hashCode() to entries.size } ?: 0)
    val state by vm.state.collectAsStateWithLifecycle()
    // What you sent from here and how it went: a message is typed in by the machine, then shows in the transcript.
    val sent = state.asked.filter { it.ask.origin == target.machine && it.ask.session == target.session }.take(2)
    // Read faster while a message you sent is on its way; the loop below reads this as it changes.
    val waiting by rememberUpdatedState(sent.any { it.state == io.github.sebbejohansson.yggdrasil.saga.AskState.WAITING })
    LaunchedEffect(target.session, poke) {
        var since: String? = null
        while (true) {
            runCatching { vm.transcript(target.machine, target.session, target.directory, TAIL_BYTES, since) }
                .onSuccess { t ->
                    if (!t.unchanged) {
                        entries = Transcript.read(t.text)
                        since = t.stamp
                    }
                    error = null
                }
                .onFailure {
                    // Claude writes a session's transcript at its first message: none yet is not an error.
                    if (Transcript.notYet(it.message)) { entries = emptyList(); error = null }
                    else error = it.message ?: "could not read it"
                }
            loaded = true
            delay(if (waiting) 1_500 else 3_000)
        }
    }
    val canType = target.typeInto?.pane != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().imePadding().padding(horizontal = 8.dp, vertical = 20.dp).background(Hud.base).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(target.name, style = HudText.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HudLabel("on ${target.machineLabel} · ${if (canType) "type below to reply" else "read only"} · over the tailnet")
                }
                HudButton("Close", onDismiss, kind = HudButtonKind.SMALL)
            }
            error?.let { HudLabel(it, color = Hud.amber) }
            if (loaded && entries.isEmpty() && error == null) Text("Nothing said yet.", style = HudText.small.copy(color = Hud.overlay0))
            Box(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries) { e -> EntryView(e) }
                }
                if (!follow.following) {
                    HudButton(if (follow.missed) "↓ New" else "↓ Latest", { scope.launch { follow.jump() } },
                        kind = HudButtonKind.SMALL, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
            sent.forEach { a -> Text(a.line, style = HudText.meta.copy(color = if (a.state == io.github.sebbejohansson.yggdrasil.saga.AskState.FAILED) Hud.amber else Hud.overlay2)) }
            val session = target.typeInto
            if (session != null && canType) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(draft, { draft = it }, placeholder = "Reply on ${target.machineLabel}", maxLines = 5, modifier = Modifier.weight(1f))
                    HudButton("Send", {
                        vm.prompt(target.machine, session, draft.trim())
                        draft = ""
                        scope.launch { follow.jump() }
                        poke++
                    }, kind = HudButtonKind.SMALL, enabled = draft.isNotBlank())
                }
            } else if (target.typeInto != null) {
                Text("Not in a herdr pane there, so it can't be typed into: Resume there from its card first.", style = HudText.meta)
            }
        }
    }
}

@Composable
private fun EntryView(e: TranscriptEntry) {
    when (e) {
        is TranscriptEntry.Prompt -> Text("› ${e.text}", style = HudText.body.copy(color = Hud.cyan))
        // Claude answers in Markdown: headings, lists, bold, code, tables.
        is TranscriptEntry.Reply -> Markdown(e.text, Modifier.fillMaxWidth(), style = HudText.body.copy(color = Hud.text))
        is TranscriptEntry.Thinking -> Text(e.text, style = HudText.meta.copy(color = Hud.overlay0), maxLines = 6, overflow = TextOverflow.Ellipsis)
        is TranscriptEntry.Tool -> Column(Modifier.fillMaxWidth().leftStripe(if (e.failed) Hud.yellow else Hud.overlay0).padding(start = 8.dp)) {
            Text("${e.name}  ${e.detail}", style = HudText.meta.copy(color = Hud.subtext0), maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.output?.takeIf { it.isNotBlank() }?.let { Text(it, style = HudText.meta.copy(color = Hud.overlay2), maxLines = 8, overflow = TextOverflow.Ellipsis) }
        }
    }
}
