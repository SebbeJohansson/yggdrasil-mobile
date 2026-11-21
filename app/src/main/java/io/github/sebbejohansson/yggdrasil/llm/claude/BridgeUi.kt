package io.github.sebbejohansson.yggdrasil.llm.claude

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class BridgeUiState(
    /** Null: not looked yet. */
    val checked: Boolean = false,
    /** Null when the bridge isn't running. */
    val health: BridgeHealth? = null,
    val hasKey: Boolean = false,
    val sessions: List<BridgeSession> = emptyList(),
    val repos: List<String> = emptyList(),
    /** The repo a start is under way for. */
    val starting: String? = null,
    /** Sessions a follow-up is on its way to. */
    val sending: Set<String> = emptySet(),
    /** The last thing that went wrong, and what it was about. */
    val error: String? = null,
    /** Follow-ups that went, by session: when. */
    val sent: Map<String, Instant> = emptyMap(),
) {
    val ready: Boolean get() = health != null && health.claude.isNotEmpty() && hasKey
}

class BridgeViewModel(private val bridge: BridgeClient) : ViewModel() {
    private val _state = MutableStateFlow(BridgeUiState(hasKey = bridge.hasKey))
    val state: StateFlow<BridgeUiState> = _state

    fun refresh() = viewModelScope.launch {
        val health = runCatching { bridge.health() }.getOrNull()
        val hasKey = bridge.hasKey
        _state.update { it.copy(checked = true, health = health, hasKey = hasKey) }
        if (health != null && health.claude.isNotEmpty() && hasKey) {
            runCatching {
                val sessions = bridge.sessions()
                val repos = bridge.repos()
                _state.update { it.copy(sessions = sessions, repos = repos) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun setKey(text: String): Boolean = bridge.setKey(text).also { if (it) refresh() }
    fun removeKey() { bridge.removeKey(); refresh() }

    fun start(repo: String, task: String) = viewModelScope.launch {
        _state.update { it.copy(starting = repo, error = null) }
        runCatching { bridge.start(repo, task) }
            .onSuccess { s -> _state.update { st -> st.copy(sessions = listOf(s) + st.sessions.filter { it.id != s.id }) } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't start: ${e.message}") } }
        _state.update { it.copy(starting = null) }
        refresh()
    }

    fun message(id: String, text: String) = viewModelScope.launch {
        _state.update { it.copy(sending = it.sending + id, error = null) }
        runCatching { bridge.message(id, text) }
            .onSuccess { _state.update { it.copy(sent = it.sent + (id to Instant.now())) } }
            .onFailure { e -> _state.update { it.copy(error = "Didn't go: ${e.message}") } }
        _state.update { it.copy(sending = it.sending - id) }
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { BridgeViewModel(c.bridge) } }
    }
}

/** Looks again on every return and every 30 s while shown: the bridge may have started meanwhile. */
@Composable
fun FollowBridge(vm: BridgeViewModel) {
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            vm.refresh()
        }
    }
}

private fun ago(t: Instant, now: Instant): String {
    val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

/**
 * The Code tab's "This phone": cloud sessions started through the bridge
 * here, with no machine needed. Start one for a repo, follow up on it, open
 * it in Claude.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneCloudPanel(vm: BridgeViewModel, onSetUp: () -> Unit) {
    val s = vm.state.collectAsStateWithLifecycle().value
    val uri = LocalUriHandler.current
    val now = Instant.now()
    var repo by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    if (repo.isEmpty() && s.repos.isNotEmpty()) repo = s.repos.first()
    Column(Modifier.fillMaxWidth().hudPanel(warn = s.error != null).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !s.checked -> Text("Looking for the bridge…", style = HudText.meta)
            s.health == null -> {
                Text("Start cloud sessions from here, with no computer on: Ygg's Claude bridge runs Claude Code on this phone, in Android's Linux Terminal.", style = HudText.small)
                Text("Not running. Open Terminal (it starts with it), or ygg-bridge start. Not installed yet: Settings → Claude bridge.", style = HudText.meta)
                HudButton("Set it up", onSetUp, kind = HudButtonKind.SMALL)
            }
            s.health.claude.isEmpty() -> Text("The bridge runs, but Claude Code isn't installed in it: run its installer again.", style = HudText.meta.copy(color = Hud.amber))
            !s.hasKey -> {
                Text("The bridge runs. Paste its key (ygg-bridge key) under Settings → Claude bridge.", style = HudText.meta)
                HudButton("Set it up", onSetUp, kind = HudButtonKind.SMALL)
            }
            else -> {
                HudLabel("New cloud session")
                if (s.repos.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.repos.forEach { r -> HudButton(r.substringAfter('/'), { repo = r }, kind = if (r == repo) HudButtonKind.GHOST else HudButtonKind.SMALL) }
                    }
                }
                HudTextField(repo, { repo = it.trim() }, placeholder = "owner/repo", maxLines = 1, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(task, { task = it }, placeholder = "What the cloud session should do", maxLines = 4, modifier = Modifier.weight(1f))
                    HudButton(
                        if (s.starting != null) "Starting…" else "Start",
                        { vm.start(repo, task.trim()); task = "" },
                        kind = HudButtonKind.PRIMARY,
                        enabled = s.starting == null && task.isNotBlank() && repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")),
                    )
                }
                if (s.starting != null) Text("Getting ${s.starting} ready and waiting for the session's link: a minute or two.", style = HudText.meta)
                s.sessions.forEach { c ->
                    var draft by remember(c.id) { mutableStateOf("") }
                    Column(Modifier.fillMaxWidth().leftStripe(Hud.cyan).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.task, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${c.repo.substringAfter('/')} · ${ago(c.at, now)}", style = HudText.meta)
                        }
                        c.lastError?.let { Text("Last message didn't go: $it", style = HudText.meta.copy(color = Hud.amber)) }
                        FollowUp(c.id, s, vm, draft) { draft = it }
                        HudButton("Open in Claude", { uri.openUri(c.url) }, kind = HudButtonKind.SMALL)
                    }
                }
            }
        }
        s.error?.let { Text(it, style = HudText.meta.copy(color = Hud.amber)) }
    }
}

/** A follow-up box that sends through the bridge, for any cloud session. */
@Composable
fun FollowUp(id: String, s: BridgeUiState, vm: BridgeViewModel, draft: String, onDraft: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudTextField(draft, onDraft, placeholder = "Follow up", maxLines = 3, modifier = Modifier.weight(1f))
        HudButton(
            if (id in s.sending) "Sending…" else "Send",
            { vm.message(id, draft.trim()); onDraft("") },
            kind = HudButtonKind.SMALL,
            enabled = draft.isNotBlank() && id !in s.sending,
        )
    }
    s.sent[id]?.let { Text("Sent ${ago(it, Instant.now())} ago from this phone.", style = HudText.meta.copy(color = Hud.green)) }
}

/**
 * Settings → Claude bridge: where it runs (Android's Linux Terminal, or
 * Termux), whether it does, its key, and the install command to paste.
 */
@Composable
fun BridgePanel(vm: BridgeViewModel, settings: BridgeSettings) {
    val s = vm.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var place by remember { mutableStateOf(settings.place) }
    FollowBridge(vm)
    Text(
        "Claude Code on this phone, with your own subscription: start and follow up cloud sessions from the Code tab " +
            "with no computer on, and let Claude answer when you allow it. Reached only from the phone, and only with its key.",
        style = HudText.meta,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BridgePlace.entries.forEach { p ->
            HudButton(p.label, { place = p; settings.place = p; note = null }, kind = if (p == place) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
        }
    }
    Column(Modifier.fillMaxWidth().hudPanel(warn = s.checked && !s.ready).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel(
            when {
                !s.checked -> "Looking…"
                s.health == null -> "Not running"
                s.health.claude.isEmpty() -> "Running, Claude Code missing"
                !s.hasKey -> "Running, key missing"
                else -> "Ready · ${s.health.claude}"
            },
            color = if (s.ready) Hud.green else Hud.amber,
        )
        if (s.health != null && !s.health.github) Text("No GitHub token: public repos only (ygg-bridge github <token>).", style = HudText.meta)
        Text(
            when (place) {
                BridgePlace.LINUX_TERMINAL ->
                    "1. Turn on Settings → System → Developer options → Linux development environment, and open Terminal once.\n" +
                        "2. Copy the install command, paste it into Terminal and run it.\n" +
                        "3. Allow port 8765 when Terminal asks (or its settings → Port control).\n" +
                        "4. Sign Claude Code in: ygg-bridge login\n" +
                        "5. Paste the key it shows (ygg-bridge key) below.\n" +
                        "It runs while Terminal's Linux is on: keep Terminal open in the background."
                BridgePlace.TERMUX ->
                    "1. Install Termux from F-Droid (and Termux:Boot to start with the phone).\n" +
                        "2. Copy the install command, paste it into Termux and run it.\n" +
                        "3. Sign Claude Code in: ygg-bridge login\n" +
                        "4. Paste the key it shows (ygg-bridge key) below."
            },
            style = HudText.small,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Copy install command", {
                copy(context, BridgeClient.installCommand(context, place))
                note = "Copied. Paste it into ${place.label}."
            }, kind = HudButtonKind.SMALL)
            HudButton("Open ${place.label}", {
                val launch = context.packageManager.getLaunchIntentForPackage(place.app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                when {
                    launch != null -> context.startActivity(launch)
                    place == BridgePlace.LINUX_TERMINAL -> {
                        note = "Turn on Linux development environment in Developer options first."
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    else -> note = "Termux isn't installed: get it from F-Droid."
                }
            }, kind = HudButtonKind.SMALL)
        }
        if (s.hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key set, kept encrypted.", style = HudText.meta, modifier = Modifier.weight(1f))
                HudButton("Remove", { vm.removeKey() }, kind = HudButtonKind.SMALL)
            }
        } else {
            HudTextField(key, { key = it }, placeholder = "The bridge's key", maxLines = 1, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            HudButton("Save key", {
                if (vm.setKey(key)) { key = ""; note = null } else note = "That doesn't look like the bridge's key."
            }, enabled = key.isNotBlank(), kind = HudButtonKind.GHOST)
        }
        note?.let { Text(it, style = HudText.meta.copy(color = Hud.cyan)) }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ygg bridge install", text))
}
