package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.Markdown
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often an open goal's agents are read again. */
private const val AGENT_READ_MS = 4_000L

/** How much of a diff is drawn; the rest is on the desktop. */
private const val DIFF_LINES = 400

/**
 * One goal on a machine, opened from its row: its state and next step, and
 * each part (one agent per repo) with what it last said, what it waits on
 * you for (questions to answer, a tool to allow), a reply box, its branch
 * to review with Merge and Open PR, Restart, and the goal's Start, Resume,
 * Close and Reopen.
 */
@Composable
fun GoalDialog(d: DeviceSessions, goalId: Long, vm: CodeViewModel, onRead: (ReadTarget) -> Unit, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val goal = state.goals[d.device]?.firstOrNull { it.id == goalId }
    val scope = rememberCoroutineScope()
    var note by remember(goalId) { mutableStateOf<String?>(null) }
    var busy by remember(goalId) { mutableStateOf(false) }
    var confirmClose by remember(goalId) { mutableStateOf(false) }

    fun act(doing: String, done: String, block: (MachineClient) -> Unit) {
        busy = true
        note = doing
        scope.launch {
            note = vm.onMachine(d.device, block).fold({ done }, { it.message ?: "it did not work" })
            vm.refreshAfter()
            busy = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().imePadding().padding(horizontal = 8.dp, vertical = 20.dp).background(Hud.base).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(goal?.title ?: "Goal $goalId", style = HudText.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HudLabel(listOfNotNull("on ${d.title}", goal?.let { Goals.LABEL[it.state] ?: it.state }, goal?.repos?.joinToString(" · ")?.ifEmpty { null })
                        .joinToString(" · "))
                }
                HudButton("Close", onDismiss, kind = HudButtonKind.SMALL)
            }
            if (goal == null) {
                Text("Not on ${d.title} any more.", style = HudText.small.copy(color = Hud.overlay0))
                return@Column
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (goal.nextText.isNotBlank()) {
                    Text("Next: ${goal.nextText}", style = HudText.small.copy(color = goalStateColor(goal.state)))
                }
                GoalActions(goal, busy, confirmClose, onConfirmClose = { confirmClose = it }, act = ::act)
                note?.let { Text(it, style = HudText.meta.copy(color = Hud.cyan)) }
                if (goal.parts.isEmpty()) {
                    Text(if (goal.repos.isEmpty()) "A draft with no project: give it one on the desktop, or make a new goal with one."
                        else "Not started: Start runs one agent per project.", style = HudText.small.copy(color = Hud.overlay0))
                }
                goal.parts.forEach { p ->
                    PartCard(d, goal, p, vm, focus = p.agent == goal.nextAgent, onRead = onRead, act = ::act)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalActions(goal: MachineGoal, busy: Boolean, confirmClose: Boolean, onConfirmClose: (Boolean) -> Unit,
    act: (String, String, (MachineClient) -> Unit) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (goal.startable) HudButton("Start", { act("Starting…", "Started: one agent per project.") { it.startGoal(goal.id) } },
            kind = HudButtonKind.SMALL, enabled = !busy)
        if (goal.resumable) HudButton("Resume", { act("Resuming…", "Resumed.") { it.resumeGoal(goal.id) } }, kind = HudButtonKind.SMALL, enabled = !busy)
        when {
            goal.closed -> HudButton("Reopen", { act("Reopening…", "Reopened.") { it.reopenGoal(goal.id) } }, kind = HudButtonKind.SMALL, enabled = !busy)
            goal.state != "merged" && goal.parts.isNotEmpty() -> HudButton(
                if (confirmClose) "Press again: close, its unmerged branches go" else "Close goal",
                {
                    if (!confirmClose) onConfirmClose(true)
                    else { onConfirmClose(false); act("Closing…", "Closed. Reopen brings it back.") { it.closeGoal(goal.id) } }
                },
                kind = HudButtonKind.SMALL, enabled = !busy, accent = Hud.amber,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartCard(
    d: DeviceSessions,
    goal: MachineGoal,
    p: GoalPart,
    vm: CodeViewModel,
    focus: Boolean,
    onRead: (ReadTarget) -> Unit,
    act: (String, String, (MachineClient) -> Unit) -> Unit,
) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var agent by remember(p.agent) { mutableStateOf<AgentDetail?>(null) }
    var reads by remember(p.agent) { mutableIntStateOf(0) }
    var draft by remember(p.agent) { mutableStateOf("") }
    var reviewing by remember(p.agent) { mutableStateOf(false) }
    var review by remember(p.agent) { mutableStateOf<GoalReview?>(null) }
    var reviewError by remember(p.agent) { mutableStateOf<String?>(null) }
    var confirmRestart by remember(p.agent) { mutableStateOf(false) }
    var said by remember(p.agent) { mutableStateOf<String?>(null) }

    LaunchedEffect(p.agent, reads) {
        while (true) {
            vm.onMachine(d.device) { it.agent(p.agent) }.onSuccess { agent = it }
            delay(AGENT_READ_MS)
        }
    }
    LaunchedEffect(p.agent, reviewing, reads) {
        if (!reviewing) return@LaunchedEffect
        vm.onMachine(d.device) { it.review(p.agent) }
            .onSuccess { review = it; reviewError = null }
            .onFailure { reviewError = it.message ?: "no review" }
    }
    fun send(doing: String, block: (MachineClient) -> Unit) {
        said = doing
        scope.launch {
            said = vm.onMachine(d.device, block).fold({ null }, { it.message ?: "it did not go" })
            reads++
            vm.refreshAfter()
        }
    }

    val color = partColor(p.status)
    Column(Modifier.fillMaxWidth().leftStripe(if (focus) Hud.yellow else color).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.repo ?: p.label ?: "part", style = HudText.body.copy(color = Hud.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Goals.partLabel(p.status), style = HudText.meta.copy(color = color))
        }
        p.branch?.let { Text("${it}${if (p.merged) " · merged" else ""}", style = HudText.meta.copy(fontFamily = HudFonts.mono)) }
        val a = agent
        a?.lastError?.let { Text(it, style = HudText.meta.copy(color = Hud.amber), maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (a?.working == true) Text(a.tool?.let { "Running $it…" } ?: "Working…", style = HudText.meta.copy(color = Hud.cyan))
        (a?.result ?: a?.lastText ?: p.result)?.takeIf { it.isNotBlank() }?.let { words ->
            Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).background(Hud.crust).verticalScroll(rememberScrollState()).padding(8.dp)) {
                Markdown(words, Modifier.fillMaxWidth())
            }
        }

        // What it waits on you for.
        a?.pending?.forEach { pending ->
            if (pending.isQuestion) Questions(pending, onSend = { answers -> send("Answering…") { it.answerQuestions(p.agent, pending.requestId, answers) } })
            else Column(Modifier.fillMaxWidth().background(Hud.surface0).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Wants to use ${pending.toolName}", style = HudText.small.copy(color = Hud.yellow))
                if (pending.summary.isNotBlank()) Text(pending.summary, style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 8, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudButton("Allow", { send("Allowing…") { it.allowTool(p.agent, pending.requestId, true) } }, kind = HudButtonKind.SMALL)
                    HudButton("Deny", { send("Denying…") { it.allowTool(p.agent, pending.requestId, false) } }, kind = HudButtonKind.SMALL)
                }
            }
        }

        // A reply: waits out a busy turn, resumes a finished one.
        if (a?.pending.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(draft, { draft = it }, placeholder = if (a?.working == true) "Reply (sent when its turn ends)" else "Reply to the agent",
                maxLines = 4, modifier = Modifier.weight(1f))
            HudButton("Send", { val text = draft.trim(); draft = ""; send("Sending…") { it.promptAgent(p.agent, text) } },
                kind = HudButtonKind.SMALL, enabled = draft.isNotBlank())
        }
        said?.let { Text(it, style = HudText.meta.copy(color = Hud.cyan)) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Read", { onRead(ReadTarget(d.device, d.title, p.agent, null, "${goal.title} · ${p.repo ?: ""}".trimEnd(' ', '·'))) }, kind = HudButtonKind.SMALL)
            if (p.branch != null) HudButton(if (reviewing) "Hide review" else "Review", { reviewing = !reviewing }, kind = HudButtonKind.SMALL)
            a?.remoteUrl?.let { link -> HudButton("Open in Claude", { uri.openUri(link) }, kind = HudButtonKind.SMALL) }
            if (p.repo != null && !p.merged && goal.state != "draft") HudButton(
                if (confirmRestart) "Press again to restart" else "Restart",
                {
                    if (!confirmRestart) confirmRestart = true
                    else { confirmRestart = false; act("Restarting ${p.repo}…", "Restarted ${p.repo}: a new agent from the newest base.") { it.restartPart(goal.id, p.repo) } }
                },
                kind = HudButtonKind.SMALL, accent = Hud.amber,
            )
        }

        if (reviewing) ReviewView(review, reviewError,
            onMerge = { act("Merging ${p.branch}…", "Merged ${p.branch} into ${review?.base ?: "its base"}.") { it.mergePart(p.agent) }; reads++ },
            onPullRequest = {
                scope.launch {
                    said = "Opening a pull request…"
                    vm.onMachine(d.device) { it.pullRequest(p.agent) }
                        .onSuccess { url -> said = url?.let { "Pull request: $it" } ?: "Opened."; url?.let(uri::openUri) }
                        .onFailure { said = it.message ?: "it did not open" }
                }
            })
    }
}

/** An agent's questions (AskUserQuestion): each one's choices as buttons, or your own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Questions(pending: AgentPending, onSend: (Map<String, String>) -> Unit) {
    var picked by remember(pending.requestId) { mutableStateOf(mapOf<String, Set<String>>()) }
    var typed by remember(pending.requestId) { mutableStateOf(mapOf<String, String>()) }
    Column(Modifier.fillMaxWidth().background(Hud.surface0).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("It asks you", style = HudText.small.copy(color = Hud.yellow))
        pending.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(q.question, style = HudText.small.copy(color = Hud.text))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.options.forEach { option ->
                        val on = option in picked[q.question].orEmpty()
                        HudButton(option, {
                            val now = picked[q.question].orEmpty()
                            picked = picked + (q.question to when {
                                q.multiSelect && on -> now - option
                                q.multiSelect -> now + option
                                else -> setOf(option)
                            })
                            typed = typed - q.question
                        }, kind = if (on) HudButtonKind.GHOST else HudButtonKind.SMALL)
                    }
                }
                HudTextField(typed[q.question].orEmpty(), { typed = typed + (q.question to it) }, placeholder = "Or say it in your own words", maxLines = 3)
            }
        }
        val answers = GoalWork.answers(pending.questions, picked, typed)
        HudButton("Send answers", { answers?.let(onSend) }, kind = HudButtonKind.SMALL, enabled = answers != null)
    }
}

/** A part's branch: what is in it, the diff, and Merge or Open PR. */
@Composable
private fun ReviewView(review: GoalReview?, error: String?, onMerge: () -> Unit, onPullRequest: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Hud.crust).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            error != null -> Text(error, style = HudText.meta.copy(color = Hud.amber))
            review == null -> Text("Reading the branch…", style = HudText.meta)
            else -> {
                Text("${review.branch} → ${review.base}${if (review.merged) " · merged" else ""}", style = HudText.meta.copy(fontFamily = HudFonts.mono))
                if (review.conflicts.isNotEmpty()) Text("Conflicts with ${review.base}: ${review.conflicts.joinToString(", ")}. Ask the agent to rebase, or merge on the desktop.",
                    style = HudText.meta.copy(color = Hud.amber))
                if (review.uncommitted.isNotEmpty()) Text("Not committed yet: ${review.uncommitted.size} file${if (review.uncommitted.size == 1) "" else "s"}.", style = HudText.meta.copy(color = Hud.yellow))
                review.commits.forEach { c -> Text("${c.hash}  ${c.subject}", style = HudText.meta.copy(fontFamily = HudFonts.mono), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                review.files.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(f.path, style = HudText.meta.copy(color = Hud.text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(GoalWork.fileChange(f), style = HudText.meta.copy(fontFamily = HudFonts.mono))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (review.mergeable) HudButton("Merge", onMerge, kind = HudButtonKind.SMALL)
                    if (!review.merged && review.commits.isNotEmpty()) HudButton("Open PR", onPullRequest, kind = HudButtonKind.SMALL)
                }
                if (review.diff.isNotBlank()) Diff(review.diff, review.diffTruncated)
            }
        }
    }
}

@Composable
private fun Diff(diff: String, truncated: Boolean) {
    val lines = remember(diff) { diff.lines() }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        lines.take(DIFF_LINES).forEach { line ->
            val color = when (GoalWork.lineKind(line)) {
                '+' -> Hud.green
                '-' -> Hud.red
                'h' -> Hud.cyan
                'f' -> Hud.subtext0
                else -> Hud.overlay2
            }
            Text(line.ifEmpty { " " }, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = color, fontSize = 11.sp), softWrap = false)
        }
        if (lines.size > DIFF_LINES || truncated) Text("… the rest of the diff is on the desktop.", style = HudText.meta)
    }
}

private fun partColor(status: String): Color = when (status) {
    "needs-you", "failed", "stopped" -> Hud.yellow
    "running", "scheduled" -> Hud.green
    "done" -> Hud.cyan
    else -> Hud.overlay0
}

internal fun goalStateColor(state: String): Color = when (state) {
    "needs-you" -> Hud.yellow
    "running" -> Hud.green
    "done" -> Hud.cyan
    "merged" -> Hud.green
    else -> Hud.overlay0
}
