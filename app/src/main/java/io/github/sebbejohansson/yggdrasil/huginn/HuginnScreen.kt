package io.github.sebbejohansson.yggdrasil.huginn

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.norn.CalendarReader
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HuginnUiState(
    val rules: List<Rule> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val notificationsAllowed: Boolean = false,
    val calendarAllowed: Boolean = false,
    val people: Set<String> = emptySet(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HuginnViewModel(
    // The application, never an activity: it outlives screens without leaking one.
    private val app: Application,
    private val huginn: HuginnService,
    private val people: PeopleSettings,
    private val calendar: CalendarReader,
) : ViewModel() {
    private val recheck = MutableStateFlow(0)
    private val error = MutableStateFlow<String?>(null)

    val state = combine(huginn.changes.flow, recheck, error) { _, _, e -> e }
        .mapLatest { e ->
            HuginnUiState(
                huginn.rules(), huginn.reactions(30), HuginnListener.allowed(app), calendar.allowed(), people.names, e,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HuginnUiState())

    fun recheck() {
        recheck.value++
    }

    fun setEnabled(r: Rule, on: Boolean) = viewModelScope.launch { huginn.setEnabled(r.id, on) }
    fun delete(r: Rule) = viewModelScope.launch { huginn.delete(r.id) }

    fun addPerson(name: String) {
        if (name.isBlank()) return
        people.names = people.names + name.trim()
        recheck()
    }

    fun removePerson(name: String) {
        people.names = people.names - name
        recheck()
    }

    fun create(name: String, on: Trigger, kind: ActionKind, text: String, push: Boolean, hold: Boolean, done: () -> Unit) =
        viewModelScope.launch {
            try {
                huginn.create(name, on, kind, text, push, hold)
                error.value = null
                done()
            } catch (e: IllegalArgumentException) {
                error.value = e.message
            }
        }

    companion object {
        fun factory(c: AppContainer, context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { HuginnViewModel(context.applicationContext as Application, c.huginn, c.people, c.calendar) }
        }
    }
}

private val whenFmt = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())

private fun ReactionState.color(): Color = when (this) {
    ReactionState.DELIVERED -> Hud.green
    ReactionState.FAILED -> Hud.red
    ReactionState.SKIPPED -> Hud.overlay0
}

/** Huginn: what it listens to, who matters, its rules, and what it did. */
@Composable
fun HuginnScreen(vm: HuginnViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Huginn", style = HudText.title)
                val today = s.reactions.count { it.state == ReactionState.DELIVERED }
                HudLabel("${s.rules.count { it.enabled }} rules on · $today told in the last 7 days")
            }
        }
        item { Listening(s) }
        item { People(s, vm) }
        item { HudSection("Rules", Modifier.padding(top = 8.dp)) }
        items(s.rules, key = { it.id }) { r -> RuleCard(r, vm) }
        item { HudSection("New rule", Modifier.padding(top = 8.dp)) }
        item { NewRule(vm, s.error) }
        if (s.reactions.isNotEmpty()) {
            item { HudSection("What it did", Modifier.padding(top = 8.dp)) }
            items(s.reactions, key = { it.id }) { r -> ReactionRow(r) }
        }
    }
}

@Composable
private fun Listening(s: HuginnUiState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().hudPanel(warn = !s.notificationsAllowed).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel("Listening to", color = Hud.group)
        Source(
            "Notifications", s.notificationsAllowed,
            if (s.notificationsAllowed) "Messages from your other apps. Read on this phone only."
            else "Allow it to read other apps' notifications: that's the phone's inbox. Read on this phone only.",
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Source("Calendar", s.calendarAllowed, if (s.calendarAllowed) "Meetings starting soon, and just ended." else "Allow it from Messages → Meetings.", null)
        Source("Gjallar", true, "Alerts as they open, get worse and heal.", null)
    }
}

@Composable
private fun Source(name: String, on: Boolean, line: String, allow: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            HudLabel(name, color = if (on) Hud.green else Hud.amber, style = HudText.label.copy(fontSize = 10.sp))
            Text(line, style = HudText.meta)
        }
        if (!on && allow != null) HudButton("Allow", allow, kind = HudButtonKind.SMALL)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun People(s: HuginnUiState, vm: HuginnViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel("People who matter", color = Hud.group)
        Text("A message from one of them is told to you, higher. Your manager, family. Kept on this phone.", style = HudText.meta)
        if (s.people.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.people.sorted().forEach { p ->
                    Text(
                        "$p  ×",
                        style = HudText.small.copy(color = Hud.text),
                        modifier = Modifier
                            .background(Hud.surface0, RoundedCornerShape(2.dp))
                            .border(1.dp, Hud.surface1, RoundedCornerShape(2.dp))
                            .clickable { vm.removePerson(p) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudTextField(name, { name = it }, placeholder = "Name, as it shows in messages", modifier = Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            HudButton("Add", { vm.addPerson(name); name = "" }, kind = HudButtonKind.GHOST, enabled = name.isNotBlank())
        }
    }
}

@Composable
private fun RuleCard(r: Rule, vm: HuginnViewModel) {
    Column(
        Modifier.fillMaxWidth().hudPanel().padding(12.dp).alpha(if (r.enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = HudText.number.copy(fontSize = 17.sp))
                HudLabel(if (r.builtIn) "built in" else "yours", color = if (r.builtIn) Hud.overlay0 else Hud.cyan, style = HudText.label.copy(fontSize = 10.sp))
            }
            HudToggle(r.enabled, { vm.setEnabled(r, it) })
        }
        Text(r.on.said(), style = HudText.meta.copy(color = Hud.subtext0))
        Text(
            (if (r.kind == ActionKind.ASK) "Ask Yggdrasil: " else "Tell me: ") + r.text,
            style = HudText.meta, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(if (r.push) "notifies" else "listed only", "waits out meetings".takeIf { r.hold }).joinToString(" · "),
            style = HudText.meta,
        )
        if (!r.builtIn) HudButton("Delete", { vm.delete(r) }, kind = HudButtonKind.SMALL, modifier = Modifier.padding(top = 4.dp))
    }
}

private enum class When(val label: String, val event: String) {
    NOTIFICATION("A notification", Events.NOTIFICATION),
    SOON("Meeting soon", Events.CALENDAR_SOON),
    ENDED("Meeting ends", Events.CALENDAR_ENDED),
    ALERT("Alert opens", Events.ALERT_OPENED),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewRule(vm: HuginnViewModel, error: String?) {
    var name by rememberSaveable { mutableStateOf("") }
    var on by rememberSaveable { mutableStateOf(When.NOTIFICATION) }
    var app by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var contains by rememberSaveable { mutableStateOf("") }
    var criticalOnly by rememberSaveable { mutableStateOf(true) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var push by rememberSaveable { mutableStateOf(true) }
    var hold by rememberSaveable { mutableStateOf(false) }

    val fields = when (on) {
        When.NOTIFICATION -> "{from} {app} {summary} {text}"
        When.SOON, When.ENDED -> "{subject} {start} {end} {where} {notes}"
        When.ALERT -> "{title} {detail} {severity} {source}"
    }

    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudTextField(name, { name = it }, placeholder = "Name", maxLines = 1)
        HudLabel("When")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            When.entries.forEach { w ->
                HudButton(w.label, { on = w }, kind = if (on == w) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
            }
        }
        when (on) {
            When.NOTIFICATION -> {
                HudTextField(app, { app = it }, placeholder = "App contains (optional), e.g. Teams", maxLines = 1)
                HudTextField(from, { from = it }, placeholder = "From contains (optional)", maxLines = 1)
                HudTextField(contains, { contains = it }, placeholder = "Text contains (optional)", maxLines = 1)
            }
            When.ALERT -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Critical alerts only", style = HudText.meta, modifier = Modifier.weight(1f))
                HudToggle(criticalOnly, { criticalOnly = it })
            }
            else -> Unit
        }
        HudLabel("Then")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Tell me", { ask = false }, kind = if (!ask) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
            HudButton("Ask Yggdrasil", { ask = true }, kind = if (ask) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
        }
        HudTextField(text, { text = it }, placeholder = if (ask) "What to ask Yggdrasil" else "What to tell you", maxLines = 4)
        Text("You can use $fields", style = HudText.meta)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notify me (otherwise only in Messages)", style = HudText.meta, modifier = Modifier.weight(1f))
            HudToggle(push, { push = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wait out meetings", style = HudText.meta, modifier = Modifier.weight(1f))
            HudToggle(hold, { hold = it })
        }
        error?.let { Text(it, style = HudText.meta.copy(color = Hud.red)) }
        Row {
            Spacer(Modifier.weight(1f))
            HudButton(
                "Save",
                {
                    val trigger = Trigger(
                        event = on.event,
                        app = if (on == When.NOTIFICATION) app else "",
                        from = if (on == When.NOTIFICATION) from else "",
                        text = if (on == When.NOTIFICATION) contains else "",
                        severity = if (on == When.ALERT && criticalOnly) setOf("critical") else emptySet(),
                    )
                    vm.create(name, trigger, if (ask) ActionKind.ASK else ActionKind.NOTIFY, text, push, hold) {
                        name = ""; app = ""; from = ""; contains = ""; text = ""
                    }
                },
                kind = HudButtonKind.PRIMARY,
                enabled = name.isNotBlank() && text.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ReactionRow(r: Reaction) {
    Column(Modifier.fillMaxWidth().leftStripe(r.state.color()).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, style = HudText.small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            HudLabel(r.state.name, color = r.state.color(), style = HudText.label.copy(fontSize = 10.sp))
        }
        Text("${r.ruleName} · ${whenFmt.format(r.at)}", style = HudText.meta)
        (r.reason ?: r.text)?.let {
            Text(it, style = HudText.meta.copy(color = if (r.state == ReactionState.FAILED) Hud.red else Hud.subtext0), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
