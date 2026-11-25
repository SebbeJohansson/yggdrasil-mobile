package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.launch

/**
 * Import goals on a machine, as the desktop's GoalImport: list the open lines
 * of a project's TODO.md, your Linear issues or the GitHub issues of its
 * projects, tick the ones wanted and make a draft goal of each. Ones already
 * a goal are marked and can't be ticked. A Linear issue names no project, so
 * the projects picked here go on those goals.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineGoalImport(d: DeviceSessions, vm: CodeViewModel, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var from by remember(d.device) { mutableStateOf("github") }
    var project by remember(d.device) { mutableStateOf<String?>(null) }
    var query by remember(d.device) { mutableStateOf("") }
    var assignee by remember(d.device) { mutableStateOf("mine") }
    var found by remember(d.device) { mutableStateOf<List<GoalCandidate>?>(null) }
    var ticked by remember(d.device) { mutableStateOf(setOf<String>()) }
    var repos by remember(d.device) { mutableStateOf(listOf<String>()) }
    var busy by remember(d.device) { mutableStateOf(false) }
    var note by remember(d.device) { mutableStateOf<String?>(null) }

    fun list() {
        if (busy || !Goals.canList(from, project)) return
        busy = true
        note = null
        val f = from
        val p = project
        val q = query
        val a = assignee
        scope.launch {
            var got: List<GoalCandidate>? = null
            note = vm.onGoal(d.device) { got = it.candidates(f, p, q, a) }
            found = got
            ticked = emptySet()
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudLabel("Import goals", color = Hud.overlay2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Goals.SOURCES.forEach { (key, name) ->
                HudButton(name, {
                    if (key != from) { from = key; project = null; found = null; ticked = emptySet(); note = null }
                }, kind = if (key == from) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
            }
        }
        if (from != "linear" && d.projects.isNotEmpty()) {
            HudLabel(if (from == "todo") "Project" else "Project (none: every one on GitHub)", color = Hud.overlay2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.projects.forEach { p ->
                    val on = p.name == project
                    HudButton(p.name, { project = if (on) null else p.name }, kind = if (on) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
                }
            }
        }
        if (from == "linear") HudTextField(project ?: "", { project = it.ifBlank { null } }, placeholder = "Linear project (optional)", maxLines = 1)
        if (from != "todo") {
            HudTextField(query, { query = it }, placeholder = "Search titles (optional)", maxLines = 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Goals.ASSIGNEES.forEach { (key, name) ->
                    HudButton(name, { assignee = key }, kind = if (key == assignee) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton(if (busy && found == null) "Listing…" else "List", { list() }, kind = HudButtonKind.SMALL, enabled = !busy && Goals.canList(from, project))
            HudButton("Cancel", { onDone(null) }, kind = HudButtonKind.SMALL)
        }

        val list = found
        if (list != null && list.isEmpty()) Text("Nothing open to import.", style = HudText.small.copy(color = Hud.overlay0))
        list?.forEach { c ->
            val on = c.source in ticked
            val has = c.goal != null
            Row(
                Modifier.fillMaxWidth()
                    .leftStripe(if (on) Hud.cyan else Hud.overlay0)
                    .clickable(enabled = !has) { ticked = if (on) ticked - c.source else ticked + c.source }
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (has) "✓" else if (on) "■" else "□", style = HudText.small.copy(color = if (on) Hud.cyan else Hud.overlay2))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = HudText.small.copy(color = if (has) Hud.overlay0 else Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(Goals.candidateTag(c.source), c.repos.joinToString(" · ").ifEmpty { null },
                        c.labels.joinToString(" · ").ifEmpty { null }, if (has) "already a goal" else null)
                    if (meta.isNotEmpty()) Text(meta.joinToString(" — "), style = HudText.meta)
                }
            }
        }
        if (from == "linear" && !list.isNullOrEmpty() && d.projects.isNotEmpty()) {
            HudLabel("Projects for the Linear issues (up to 4)", color = Hud.overlay2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.projects.forEach { p ->
                    val on = p.name in repos
                    HudButton(p.name, { repos = if (on) repos - p.name else if (repos.size < 4) repos + p.name else repos },
                        kind = if (on) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
                }
            }
        }
        if (!list.isNullOrEmpty()) {
            HudButton(if (busy) "Importing…" else "Import ${ticked.size} as goals", {
                val chosen = list.filter { it.source in ticked && it.goal == null }
                val picked = repos
                busy = true
                note = null
                scope.launch {
                    val failed = mutableListOf<String>()
                    var problem: String? = null
                    for (c in chosen) {
                        vm.onGoal(d.device) { it.createGoal(c.title, Goals.importRepos(c, picked), c.source, c.context) }?.let {
                            failed += c.title
                            problem = it
                        }
                    }
                    busy = false
                    if (failed.isEmpty()) {
                        onDone("${chosen.size} goal${if (chosen.size == 1) "" else "s"} imported")
                    } else {
                        note = "$problem. Not created: ${failed.joinToString(", ")}"
                    }
                }
            }, kind = HudButtonKind.SMALL, enabled = !busy && ticked.isNotEmpty())
        }
        note?.let { HudLabel(it, color = Hud.amber) }
    }
}
