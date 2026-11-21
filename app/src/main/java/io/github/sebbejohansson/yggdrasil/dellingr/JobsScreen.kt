package io.github.sebbejohansson.yggdrasil.dellingr

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
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
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class JobsUiState(
    val jobs: List<Job> = emptyList(),
    val runs: List<Run> = emptyList(),
    val exactAllowed: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModel(private val dellingr: DellingrService, private val alarm: AndroidJobAlarm) : ViewModel() {
    private val recheck = MutableStateFlow(0)
    private val error = MutableStateFlow<String?>(null)

    val state = combine(dellingr.changes.flow, recheck, error) { _, _, e -> e }
        .mapLatest { e -> JobsUiState(dellingr.jobs(), dellingr.runs(20), alarm.exactAllowed(), e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobsUiState())

    /** Back from Android's settings: exact alarms may be allowed now, so set the alarm again. */
    fun recheck() {
        recheck.value++
        viewModelScope.launch { dellingr.reschedule() }
    }

    fun preview(words: String): Result<Pair<String, Instant?>> = runCatching { dellingr.preview(words) }

    fun create(name: String, words: String, kind: ActionKind, text: String, push: Boolean, done: () -> Unit) = viewModelScope.launch {
        try {
            dellingr.create(name, words, kind, text, push)
            error.value = null
            done()
        } catch (e: IllegalArgumentException) {
            error.value = e.message
        }
    }

    fun setEnabled(j: Job, on: Boolean) = viewModelScope.launch { dellingr.setEnabled(j.id, on) }
    fun runNow(j: Job) = viewModelScope.launch { dellingr.runNow(j.id) }
    fun delete(j: Job) = viewModelScope.launch { dellingr.delete(j.id) }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { JobsViewModel(c.dellingr, c.jobAlarm) } }
    }
}

private val whenFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault())

private fun RunState.color(): Color = when (this) {
    RunState.DONE -> Hud.green
    RunState.FAILED -> Hud.red
    RunState.RUNNING -> Hud.cyan
    RunState.SKIPPED -> Hud.overlay0
}

/** Dellingr: the jobs you put on a clock, a form for a new one, and the last runs. */
@Composable
fun JobsScreen(vm: JobsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val next = s.jobs.filter { it.enabled && it.nextRunAt != null }.minByOrNull { it.nextRunAt!! }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Dellingr", style = HudText.title)
                HudLabel(
                    listOfNotNull(
                        "${s.jobs.size} ${if (s.jobs.size == 1) "job" else "jobs"}",
                        next?.let { "next ${it.name} ${whenFmt.format(it.nextRunAt)}" },
                    ).joinToString(" · "),
                )
            }
        }
        if (!s.exactAllowed) {
            item {
                Row(Modifier.fillMaxWidth().hudPanel(warn = true).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HudLabel("Exact timing off", color = Hud.amber)
                        Text("Jobs may run some minutes late while the phone sleeps.", style = HudText.meta)
                    }
                    HudButton("Allow", {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                        )
                    }, kind = HudButtonKind.SMALL)
                }
            }
        }
        item { HudSection("Jobs", Modifier.padding(top = 4.dp)) }
        if (s.jobs.isEmpty()) item { Text("Nothing on the clock yet.", style = HudText.small.copy(color = Hud.overlay0)) }
        items(s.jobs, key = { it.id }) { j -> JobCard(j, vm) }

        item { HudSection("New job", Modifier.padding(top = 8.dp)) }
        item { NewJob(vm, s.error) }

        if (s.runs.isNotEmpty()) {
            item { HudSection("Runs", Modifier.padding(top = 8.dp)) }
            items(s.runs, key = { it.id }) { r -> RunRow(r) }
        }
    }
}

@Composable
private fun JobCard(j: Job, vm: JobsViewModel) {
    val failing = j.failures >= DellingrService.FAILING_AFTER
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = failing).padding(12.dp).alpha(if (j.enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(j.name, style = HudText.number.copy(fontSize = 18.sp))
                HudLabel(j.said, color = Hud.cyan, style = HudText.label.copy(fontSize = 10.sp))
            }
            HudToggle(j.enabled, { vm.setEnabled(j, it) })
        }
        Text(
            (if (j.kind == ActionKind.ASK) "Ask Yggdrasil: " else "Tell me: ") + j.text,
            style = HudText.meta.copy(color = Hud.subtext0), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                j.nextRunAt?.takeIf { j.enabled }?.let { "next ${whenFmt.format(it)}" } ?: "off",
                j.lastResult?.let { "last ${it.name.lowercase()}" },
                "failing".takeIf { failing },
                if (j.push) "notifies" else "listed only",
            ).joinToString(" · "),
            style = HudText.meta.copy(color = if (failing) Hud.amber else Hud.overlay0),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Run now", { vm.runNow(j) }, kind = HudButtonKind.SMALL)
            HudButton("Delete", { vm.delete(j) }, kind = HudButtonKind.SMALL)
        }
    }
}

private const val BRIEF = "Give me a short, calm start to the day: what's on my calendar, and anything worth preparing for."

@Composable
private fun NewJob(vm: JobsViewModel, error: String?) {
    var name by rememberSaveable { mutableStateOf("") }
    var words by rememberSaveable { mutableStateOf("") }
    var ask by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }
    var push by rememberSaveable { mutableStateOf(true) }
    val preview = words.takeIf { it.isNotBlank() }?.let { vm.preview(it) }

    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudLabel("Start from", modifier = Modifier.weight(1f))
            HudButton("Morning brief", {
                name = "Morning brief"; words = "every weekday at 8"; ask = true; text = BRIEF; push = true
            }, kind = HudButtonKind.SMALL)
        }
        HudTextField(name, { name = it }, placeholder = "Name", maxLines = 1)
        HudTextField(words, { words = it }, placeholder = "When, e.g. every weekday at 8", maxLines = 1)
        preview?.let { p ->
            p.fold(
                onSuccess = { (cron, next) ->
                    HudLabel(
                        Cron.said(cron) + (next?.let { " · next ${whenFmt.format(it)}" } ?: ""),
                        color = Hud.green, style = HudText.label.copy(fontSize = 10.sp),
                    )
                },
                onFailure = { Text(it.message ?: "?", style = HudText.meta.copy(color = Hud.amber)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Ask Yggdrasil", { ask = true }, kind = if (ask) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
            HudButton("Tell me", { ask = false }, kind = if (!ask) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
        }
        HudTextField(text, { text = it }, placeholder = if (ask) "What to ask Yggdrasil" else "What to tell you", maxLines = 4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notify me (otherwise only in Messages)", style = HudText.meta, modifier = Modifier.weight(1f))
            HudToggle(push, { push = it })
        }
        error?.let { Text(it, style = HudText.meta.copy(color = Hud.red)) }
        Row {
            Spacer(Modifier.weight(1f))
            HudButton(
                "Save",
                {
                    vm.create(name, words, if (ask) ActionKind.ASK else ActionKind.NOTIFY, text, push) {
                        name = ""; words = ""; text = ""
                    }
                },
                kind = HudButtonKind.PRIMARY,
                enabled = name.isNotBlank() && text.isNotBlank() && preview?.isSuccess == true,
            )
        }
    }
}

@Composable
private fun RunRow(r: Run) {
    Column(Modifier.fillMaxWidth().leftStripe(r.state.color()).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.jobName, style = HudText.small, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HudLabel(r.state.name, color = r.state.color(), style = HudText.label.copy(fontSize = 10.sp))
        }
        Text(
            listOfNotNull(whenFmt.format(r.startedAt), "missed, ran on waking".takeIf { r.missed }, "by hand".takeIf { r.byHand }).joinToString(" · "),
            style = HudText.meta,
        )
        (r.error ?: r.text)?.let {
            Text(it, style = HudText.meta.copy(color = if (r.error != null) Hud.red else Hud.subtext0), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
