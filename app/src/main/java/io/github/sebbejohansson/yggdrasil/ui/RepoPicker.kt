package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val ownerRepo = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

/** Every option once, the picked ones first: the order the dropdown shows, fixed while it's open. */
internal fun pickerOrder(options: List<String>, picked: List<String>): List<String> {
    val all = (picked + options).distinctBy { it.lowercase() }
    return all.filter { it in picked } + all.filter { it !in picked }
}

/** Those of [order] whose name holds [query] (any case); all of them for an empty one. */
internal fun pickerMatches(order: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) order else order.filter { it.contains(q, ignoreCase = true) }
}

/** A typed owner/repo that isn't among [order]: offered as a row of its own. */
internal fun pickerTyped(order: List<String>, query: String): String? {
    val q = query.trim()
    return q.takeIf { it.matches(ownerRepo) && order.none { o -> o.equals(it, ignoreCase = true) } }
}

/** [picked] with [name] ticked or unticked; with [max] 1 it replaces the pick. Never more than [max]. */
internal fun pickerToggle(picked: List<String>, name: String, max: Int): List<String> = when {
    name in picked -> picked - name
    max == 1 -> listOf(name)
    picked.size >= max -> picked
    else -> picked + name
}

/**
 * Desktop Yggdrasil's ProjectPicker: the picked repos as chips (× drops
 * one), and a dropdown with a search box and a tick list of every known
 * repo, picked first. Typing a whole owner/repo nobody knows yet offers it
 * too. With [max] 1 it's a plain pick-one that closes when you pick. With
 * several, the first is where Claude starts ([firstNote] says so on it), and
 * tapping another chip moves it first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepoPicker(
    options: List<String>,
    picked: List<String>,
    onPick: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 1,
    placeholder: String = "Pick a repo",
    firstNote: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(listOf<String>()) }
    fun show() {
        order = pickerOrder(options, picked)
        query = ""
        open = true
    }
    fun pick(name: String) {
        val next = pickerToggle(picked, name, max)
        onPick(next)
        if (name !in order) order = order + name
        if (max == 1 && next.isNotEmpty()) open = false
    }
    val shape = RoundedCornerShape(2.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Hud.field, shape)
                .border(1.dp, if (open) Hud.cyan else Hud.surface1, shape)
                .clickable(role = Role.DropdownList) { if (open) open = false else show() }
                .heightIn(min = 42.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (picked.isEmpty()) Text(placeholder, style = HudText.body.copy(color = Hud.overlay0), modifier = Modifier.padding(start = 4.dp))
                picked.forEachIndexed { i, r ->
                    val lead = i == 0 && picked.size > 1
                    Row(
                        Modifier
                            .border(1.dp, if (lead) Hud.cyan else Hud.surface2, RoundedCornerShape(50))
                            .clickable(enabled = picked.size > 1 && !lead) { onPick(listOf(r) + (picked - r)) }
                            .padding(start = 9.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            r.substringAfter('/') + if (lead && firstNote != null) " · $firstNote" else "",
                            style = HudText.small.copy(color = if (lead || picked.size == 1) Hud.cyan else Hud.subtext1),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("×", style = HudText.body.copy(color = Hud.subtext0),
                            modifier = Modifier.clickable(role = Role.Button) { onPick(picked - r) }.padding(horizontal = 6.dp))
                    }
                }
            }
            Text(if (open) "▴" else "▾", style = HudText.body.copy(color = Hud.cyan))
        }
        if (!open) return@Column
        Column(
            Modifier.fillMaxWidth().background(Hud.base2, shape).border(1.dp, Hud.surface1, shape).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val matches = pickerMatches(order, query)
            val typed = pickerTyped(order, query)
            HudTextField(
                query, { query = it }, placeholder = "Type to filter, or a new owner/repo", maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { (typed ?: matches.firstOrNull())?.let(::pick) }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (max > 1) "${picked.size}/$max picked" else "${options.size} known",
                    style = HudText.meta, modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                )
                if (picked.isNotEmpty()) HudButton("Clear", { onPick(emptyList()) }, kind = HudButtonKind.SMALL)
                Box(Modifier.padding(start = 6.dp)) { HudButton("Done", { open = false }, kind = HudButtonKind.SMALL) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                if (typed != null) item(key = "+$typed") { PickRow("+ $typed", on = false, enabled = true) { pick(typed) } }
                items(matches, key = { it }) { r ->
                    val on = r in picked
                    PickRow(r, on = on, enabled = on || max == 1 || picked.size < max) { pick(r) }
                }
                if (matches.isEmpty() && typed == null) item { Text("No repo matches.", style = HudText.meta, modifier = Modifier.padding(6.dp)) }
            }
        }
    }
}

@Composable
private fun PickRow(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(16.dp)
                .background(if (on) Hud.cyan else Hud.field, RoundedCornerShape(2.dp))
                .border(1.dp, if (on) Hud.cyan else Hud.surface2, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", style = HudText.meta.copy(color = Hud.onCyan)) }
        Text(text, style = HudText.small.copy(color = if (enabled) Hud.subtext1 else Hud.overlay0), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
