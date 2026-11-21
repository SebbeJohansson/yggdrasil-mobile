package io.github.sebbejohansson.yggdrasil.norn

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PromisesViewModel(private val promises: PromiseService) : ViewModel() {
    val state = promises.changes.flow.mapLatest { promises.listed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun done(p: Promise) = viewModelScope.launch { promises.done(p.id) }
    fun drop(p: Promise) = viewModelScope.launch { promises.drop(p.id) }
    fun reopen(p: Promise) = viewModelScope.launch { promises.reopen(p.id) }
    fun setDue(p: Promise, due: LocalDate?) = viewModelScope.launch { promises.setDue(p.id, due) }
    fun add(what: String, to: String, whenWords: String) = viewModelScope.launch { promises.add(what, to, whenWords) }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { PromisesViewModel(c.promises) } }

        /** The open promises due by tomorrow: the Inbox page's count. */
        fun dueSoon(list: List<Promise>, today: LocalDate = LocalDate.now()) =
            list.count { it.state == PromiseState.OPEN && it.due != null && !it.due.isAfter(today.plusDays(1)) }
    }
}

/**
 * Inbox → Promises: what you said you'd do, as the dashboard's ledger.
 * Open ones first, soonest day first, each with Done, Drop and a new day;
 * the closed ones under them for 60 days, with Reopen.
 */
@Composable
fun PromisesScreen(vm: PromisesViewModel, modifier: Modifier = Modifier) {
    val list by vm.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val open = list.filter { it.state == PromiseState.OPEN }
    val closed = list.filter { it.state != PromiseState.OPEN }
    var adding by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Promises", style = HudText.title)
                HudLabel("${open.size} open · ${PromisesViewModel.dueSoon(list, today)} due by tomorrow")
            }
            HudButton(if (adding) "Cancel" else "Add", { adding = !adding }, kind = HudButtonKind.SMALL)
        }
        Text(
            "What you said you'd do for someone. Found in what you write to Yggdrasil and in text you share to Ygg " +
                "(Share → Ygg: keep promises), read by the model on this phone only. You're reminded the working day before.",
            style = HudText.meta,
        )
        if (adding) AddPromise { what, to, whenWords -> vm.add(what, to, whenWords); adding = false }
        if (open.isEmpty() && !adding) Text("Nothing promised right now.", style = HudText.meta)
        open.forEach { PromiseRow(it, vm, today) }
        if (closed.isNotEmpty()) {
            HudSection("Done and dropped", Modifier.padding(top = 8.dp))
            closed.forEach { PromiseRow(it, vm, today) }
        }
    }
}

@Composable
private fun PromiseRow(p: Promise, vm: PromisesViewModel, today: LocalDate) {
    val context = LocalContext.current
    val open = p.state == PromiseState.OPEN
    val late = open && p.due != null && p.due.isBefore(today)
    val soon = open && p.due != null && !p.due.isAfter(today.plusDays(1))
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = late).padding(12.dp).alpha(if (open) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.what, style = HudText.body, modifier = Modifier.weight(1f))
            HudLabel(
                when {
                    !open -> p.state.name.lowercase()
                    p.due == null -> "no day"
                    else -> PromiseText.dayWords(p.due, today)
                },
                color = when {
                    late -> Hud.red
                    soon -> Hud.amber
                    else -> Hud.subtext0
                },
            )
        }
        Text(
            listOfNotNull(
                p.to.takeIf { it.isNotEmpty() }?.let { "to $it" },
                when (p.source) {
                    "chat" -> "said to Yggdrasil"
                    "shared" -> "from a shared message"
                    else -> "added by you"
                },
            ).joinToString(" · "),
            style = HudText.meta,
        )
        p.said?.let { Text("“$it”", style = HudText.meta.copy(color = Hud.overlay0), maxLines = 2) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (open) {
                HudButton("Done", { vm.done(p) }, kind = HudButtonKind.SMALL)
                HudButton("Drop", { vm.drop(p) }, kind = HudButtonKind.SMALL)
                HudButton("Day", {
                    val d = p.due ?: today
                    DatePickerDialog(context, { _, y, m, day -> vm.setDue(p, LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }, kind = HudButtonKind.SMALL)
                if (p.due != null) HudButton("No day", { vm.setDue(p, null) }, kind = HudButtonKind.SMALL)
            } else {
                HudButton("Reopen", { vm.reopen(p) }, kind = HudButtonKind.SMALL)
            }
        }
    }
}

@Composable
private fun AddPromise(onAdd: (String, String, String) -> Unit) {
    var what by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var whenWords by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudTextField(what, { what = it }, "What you'll do", maxLines = 2, modifier = Modifier.fillMaxWidth())
        HudTextField(to, { to = it }, "For whom (optional)", maxLines = 1, modifier = Modifier.fillMaxWidth())
        HudTextField(whenWords, { whenWords = it }, "When: Friday, tomorrow, 24/11 (optional)", maxLines = 1, modifier = Modifier.fillMaxWidth())
        HudButton("Keep it", { onAdd(what, to, whenWords) }, enabled = what.isNotBlank(), kind = HudButtonKind.PRIMARY)
    }
}
