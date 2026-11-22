package io.github.sebbejohansson.yggdrasil.llm.claude

import android.annotation.SuppressLint
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.linux.OwnLinux
import io.github.sebbejohansson.yggdrasil.linux.LinuxState
import io.github.sebbejohansson.yggdrasil.linux.LinuxLogin
import io.github.sebbejohansson.yggdrasil.linux.LinuxShell
import io.github.sebbejohansson.yggdrasil.linux.LinuxBridgeService
import androidx.core.net.toUri
import androidx.compose.runtime.rememberCoroutineScope
import android.os.PowerManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.sebbejohansson.yggdrasil.ui.HudFonts
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
    /** The Claude accounts the bridge signs in as; the first is claude's own. */
    val accounts: List<BridgeAccount> = emptyList(),
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
                // An older bridge has no accounts: one, unnamed.
                val accounts = runCatching { bridge.accounts() }.getOrDefault(emptyList())
                _state.update { it.copy(sessions = sessions, repos = repos, accounts = accounts) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun setKey(text: String): Boolean = bridge.setKey(text).also { if (it) refresh() }
    fun removeKey() { bridge.removeKey(); refresh() }

    fun setGitHub(token: String) = viewModelScope.launch {
        runCatching { bridge.setGitHub(token) }
            .onSuccess { _state.update { it.copy(error = null) } }
            .onFailure { e -> _state.update { it.copy(error = "GitHub token not kept: ${e.message}") } }
        refresh()
    }

    fun start(repo: String, task: String, account: String = "default") = viewModelScope.launch {
        _state.update { it.copy(starting = repo, error = null) }
        runCatching { bridge.start(repo, task, account = account) }
            .onSuccess { s -> _state.update { st -> st.copy(sessions = listOf(s) + st.sessions.filter { it.id != s.id }) } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't start: ${e.message}") } }
        _state.update { it.copy(starting = null) }
        refresh()
    }

    fun addAccount(name: String) = viewModelScope.launch {
        runCatching { bridge.addAccount(name) }.onFailure { e -> _state.update { it.copy(error = "Account not added: ${e.message}") } }
        refresh()
    }

    fun renameAccount(id: String, name: String) = viewModelScope.launch {
        runCatching { bridge.renameAccount(id, name) }.onFailure { e -> _state.update { it.copy(error = "Not renamed: ${e.message}") } }
        refresh()
    }

    fun removeAccount(id: String) = viewModelScope.launch {
        runCatching { bridge.removeAccount(id) }.onFailure { e -> _state.update { it.copy(error = "Not removed: ${e.message}") } }
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
                Text("Start cloud sessions from here, with no computer on: Ygg's Claude bridge runs Claude Code on this phone, in Ygg's own Linux.", style = HudText.small)
                Text("Not running. Set it up and turn on Always on under Settings → Claude bridge.", style = HudText.meta)
                HudButton("Set it up", onSetUp, kind = HudButtonKind.SMALL)
            }
            s.health.claude.isEmpty() -> Text("The bridge runs, but Claude Code isn't installed in it: run its installer again.", style = HudText.meta.copy(color = Hud.amber))
            !s.hasKey -> {
                Text("The bridge runs. Paste its key (ygg-bridge key) under Settings → Claude bridge.", style = HudText.meta)
                HudButton("Set it up", onSetUp, kind = HudButtonKind.SMALL)
            }
            else -> {
                val usable = s.accounts.filter { it.signedIn }
                var account by remember { mutableStateOf("") }
                if (usable.none { it.id == account }) account = usable.firstOrNull()?.id ?: "default"
                HudLabel("New cloud session")
                if (s.accounts.size > 1) {
                    // Which account the session is made in: only signed-in ones can.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.accounts.forEach { a ->
                            HudButton(a.name, { account = a.id },
                                kind = if (a.id == account) HudButtonKind.PRIMARY else HudButtonKind.SMALL, enabled = a.signedIn)
                        }
                    }
                }
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
                        { vm.start(repo, task.trim(), account); task = "" },
                        kind = HudButtonKind.PRIMARY,
                        enabled = s.starting == null && task.isNotBlank() && repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")),
                    )
                }
                if (s.starting != null) Text("Getting ${s.starting} ready and waiting for the session's link: a minute or two.", style = HudText.meta)
                if (s.sessions.isNotEmpty()) {
                    Text("A session's work and results are in Claude: tap one to open it there.", style = HudText.meta)
                }
                // By account, in the order the accounts are listed; one account needs no headings.
                val byAccount = s.sessions.groupBy { it.account }
                val order = s.accounts.map { it.id } + byAccount.keys.filter { k -> s.accounts.none { it.id == k } }
                order.filter { it in byAccount }.forEach { id ->
                    val a = s.accounts.firstOrNull { it.id == id }
                    if (s.accounts.size > 1 || a == null) {
                        HudLabel(listOfNotNull(a?.name ?: id, a?.email?.ifEmpty { null }).joinToString(" · "), modifier = Modifier.padding(top = 6.dp))
                    }
                    byAccount.getValue(id).forEach { c -> PhoneSession(c, s, vm, now) { uri.openUri(c.url) } }
                }
            }
        }
        s.error?.let { Text(it, style = HudText.meta.copy(color = Hud.amber)) }
    }
}

/** One cloud session started from the phone: tap to see it in Claude, or follow it up. */
@Composable
private fun PhoneSession(c: BridgeSession, s: BridgeUiState, vm: BridgeViewModel, now: Instant, open: () -> Unit) {
    var draft by remember(c.id) { mutableStateOf("") }
    var following by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().leftStripe(Hud.cyan).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = open), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.task, style = HudText.small.copy(color = Hud.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${c.repo.substringAfter('/')} · ${ago(c.at, now)}", style = HudText.meta)
        }
        c.lastError?.let { Text("Last message didn't go: $it", style = HudText.meta.copy(color = Hud.amber)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("See results in Claude", open, kind = HudButtonKind.GHOST)
            HudButton(if (following) "Close" else "Follow up", { following = !following }, kind = HudButtonKind.SMALL)
        }
        if (following || c.id in s.sending) FollowUp(c.id, s, vm, draft) { draft = it }
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
 * Settings → Claude bridge: where it runs, and setting it up there. Ygg's own
 * Linux (the default) is set up, signed in and kept on from here, no other
 * app; UserLAnd and Termux get an install command to paste.
 */
@Composable
fun BridgePanel(vm: BridgeViewModel, settings: BridgeSettings, linux: OwnLinux, login: LinuxLogin, shell: LinuxShell) {
    val s = vm.state.collectAsStateWithLifecycle().value
    var place by remember { mutableStateOf(settings.place) }
    FollowBridge(vm)
    Text(
        "Claude Code on this phone, with your own subscription: start and follow up cloud sessions from the Code tab " +
            "with no computer on, and let Claude answer when you allow it. Reached only from the phone, and only with its key.",
        style = HudText.meta,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BridgePlace.entries.forEach { p ->
            HudButton(p.label, { place = p; settings.place = p }, kind = if (p == place) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
        }
    }
    Column(Modifier.fillMaxWidth().hudPanel(warn = s.checked && !s.ready).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel(
            when {
                !s.checked -> "Looking…"
                s.health == null -> "Bridge not running"
                s.health.claude.isEmpty() -> "Bridge running, Claude Code missing"
                !s.hasKey -> "Bridge running, key missing"
                else -> "Ready · ${s.health.claude}"
            },
            color = if (s.ready) Hud.green else Hud.amber,
        )
        if (s.health != null && !s.health.github) GitHubTokenField(vm, s.hasKey)
        if (place == BridgePlace.OWN_LINUX) OwnLinuxSetup(vm, linux, login, shell) else PastedSetup(vm, s, place)
    }
}

/** The bridge clones over https; private repos need a GitHub token that can read them. */
@Composable
private fun GitHubTokenField(vm: BridgeViewModel, hasKey: Boolean) {
    var token by remember { mutableStateOf("") }
    Text(
        "No GitHub token: public repos only. Ygg's update token is handed over when a private clone is refused, " +
            "or paste one (Contents: read) here.",
        style = HudText.meta,
    )
    if (!hasKey) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudTextField(token, { token = it }, placeholder = "GitHub token", maxLines = 1, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f))
        HudButton("Keep", { vm.setGitHub(token); token = "" }, kind = HudButtonKind.SMALL, enabled = token.isNotBlank())
    }
}

/** Ygg's own Linux: set up, sign in, always on. */
// Ygg is sideloaded, not on the Play Store: asking to run in the background is
// what keeping the bridge always on needs, and you're asked by Android itself.
@SuppressLint("BatteryLife")
@Composable
private fun OwnLinuxSetup(vm: BridgeViewModel, linux: OwnLinux, login: LinuxLogin, shell: LinuxShell) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = linux.state.collectAsStateWithLifecycle().value
    val on = LinuxBridgeService.runningState.collectAsStateWithLifecycle().value
    val status = LinuxBridgeService.status.collectAsStateWithLifecycle().value
    val lastExit = LinuxBridgeService.lastExit.collectAsStateWithLifecycle().value

    // The bridge's key is in Ygg's own storage: Ygg takes it, no pasting.
    LaunchedEffect(state) {
        if (state == LinuxState.Ready) withContext(Dispatchers.IO) { linux.bridgeKey() }?.let { vm.setKey(it) }
        withContext(Dispatchers.IO) { login.refresh() }
    }

    HudLabel("1 · Linux")
    when (state) {
        LinuxState.NotInstalled -> {
            Text("Ubuntu 24.04 inside Ygg, with Claude Code. About 30 MB to download and 1 GB once set up; Wi-Fi is best.", style = HudText.small)
            HudButton("Set up", { scope.launch { linux.install() } }, kind = HudButtonKind.PRIMARY)
        }
        is LinuxState.Downloading -> Text("Downloading Ubuntu: ${state.bytes / 1_000_000} of ${state.total / 1_000_000} MB", style = HudText.small)
        is LinuxState.Unpacking -> Text("Unpacking: ${state.files} files", style = HudText.small)
        is LinuxState.SettingUp -> {
            Text("Setting up (a few minutes):", style = HudText.small)
            Text(state.line, style = HudText.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LinuxState.Ready -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ubuntu with Claude Code, set up.", style = HudText.small, modifier = Modifier.weight(1f))
            HudButton("Update", { scope.launch { linux.install() } }, kind = HudButtonKind.SMALL)
            HudButton("Remove", { shell.stop(); LinuxBridgeService.stop(context); scope.launch { linux.remove() } }, kind = HudButtonKind.SMALL)
        }
        is LinuxState.Failed -> {
            Text("Setup stopped: ${state.message}", style = HudText.small.copy(color = Hud.amber))
            state.log.takeLast(6).forEach { Text(it, style = HudText.meta, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            HudButton("Try again", { scope.launch { linux.install() } }, kind = HudButtonKind.PRIMARY)
        }
    }
    val ready = state == LinuxState.Ready

    HudLabel("2 · Claude accounts", modifier = Modifier.padding(top = 4.dp))
    if (!ready) Text("After the Linux is set up.", style = HudText.meta)
    else ClaudeAccounts(vm, linux, login)

    HudLabel("3 · Always on", modifier = Modifier.padding(top = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(if (on) status else "Off. On, it runs with a quiet notification, and starts with the phone.", style = HudText.meta)
            lastExit?.takeIf { on }?.let { Text("Last stop: $it", style = HudText.meta.copy(color = Hud.amber), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        HudToggle(on, { want -> if (want) LinuxBridgeService.start(context) else LinuxBridgeService.stop(context) })
    }
    val power = context.getSystemService(PowerManager::class.java)
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
        Text("For it to stay on, let Ygg run in the background (battery: Unrestricted).", style = HudText.meta)
        HudButton("Allow in the background", {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, kind = HudButtonKind.SMALL)
    }
    Text("If Android still stops it: Developer options → Disable child process restrictions.", style = HudText.meta.copy(color = Hud.overlay0))

    HudLabel("4 · Shell", modifier = Modifier.padding(top = 4.dp))
    if (ready) LinuxShellPanel(shell) else Text("After the Linux is set up.", style = HudText.meta)
}

/**
 * The Claude accounts the bridge signs in as (a personal and a work one, say):
 * each its own sign-in, made here. Sessions start in the one picked on the Code tab.
 */
@Composable
private fun ClaudeAccounts(vm: BridgeViewModel, linux: OwnLinux, login: LinuxLogin) {
    val s = vm.state.collectAsStateWithLifecycle().value
    val signIn = login.state.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var code by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf("") }
    // Without the bridge (or an older one) there's the first account only, claude's own.
    val accounts = s.accounts.ifEmpty { listOf(BridgeAccount("default", "Personal", signIn.done, "", "")) }
    // A sign-in that just ended changes who's signed in.
    LaunchedEffect(signIn.running) { if (!signIn.running) vm.refresh() }

    Text("One sign-in each, kept on this phone. Pick the account when you start a session.", style = HudText.meta)
    accounts.forEach { a ->
        // The bridge says, and the files say so too while the bridge is off.
        val kept = remember(a.configDir, signIn.running) { linux.signedIn(a.configDir) }
        val signed = a.signedIn || kept
        Column(Modifier.fillMaxWidth().leftStripe(if (signed) Hud.green else Hud.overlay0).padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = HudText.small.copy(color = Hud.text))
                    Text(if (signed) a.email.ifEmpty { "Signed in" } else "Not signed in",
                        style = HudText.meta.copy(color = if (signed) Hud.green else Hud.amber))
                }
                if (!(signIn.running && signIn.account == a.id)) {
                    HudButton(if (signed) "Sign in again" else "Sign in", { scope.launch { login.start(a.id, a.configDir) } },
                        kind = if (signed) HudButtonKind.SMALL else HudButtonKind.GHOST, enabled = !signIn.running)
                }
            }
            if (signIn.running && signIn.account == a.id) {
                Text("Open the link, sign in with this account, and paste the code back here.", style = HudText.meta)
                signIn.url?.let { link -> HudButton("Open the sign-in link", { uri.openUri(link) }, kind = HudButtonKind.PRIMARY) }
                    ?: Text("Starting…", style = HudText.meta)
                signIn.lines.takeLast(3).forEach { Text(it, style = HudText.meta, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudTextField(code, { code = it }, placeholder = "The code the browser shows", maxLines = 1, modifier = Modifier.weight(1f))
                    HudButton("Send", { login.send(code); code = "" }, kind = HudButtonKind.SMALL, enabled = code.isNotBlank())
                }
                HudButton("Cancel", { login.cancel() }, kind = HudButtonKind.SMALL)
            } else if (!signIn.running && signIn.account == a.id && signIn.code != null && signIn.code != 0 && !signed) {
                Text("Sign-in ended (${signIn.code}): ${signIn.lines.lastOrNull().orEmpty()}", style = HudText.meta.copy(color = Hud.amber))
            }
            if (s.accounts.isNotEmpty()) {
                if (renaming == a.id) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudTextField(rename, { rename = it }, placeholder = "Name", maxLines = 1, modifier = Modifier.weight(1f))
                        HudButton("Save", { vm.renameAccount(a.id, rename); renaming = null }, kind = HudButtonKind.SMALL, enabled = rename.isNotBlank())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudButton("Rename", { renaming = a.id; rename = a.name }, kind = HudButtonKind.SMALL)
                        if (a.id != "default") HudButton("Remove", { vm.removeAccount(a.id) }, kind = HudButtonKind.SMALL)
                    }
                }
            }
        }
    }
    if (s.ready) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudTextField(newName, { newName = it }, placeholder = "Another account, like Work", maxLines = 1, modifier = Modifier.weight(1f))
            HudButton("Add", { vm.addAccount(newName); newName = "" }, kind = HudButtonKind.SMALL, enabled = newName.isNotBlank())
        }
    } else {
        Text("To add another account, turn on Always on (3) first: the bridge keeps the accounts.", style = HudText.meta)
    }
}

/** A terminal into Ygg's own Linux: root's bash, one line at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinuxShellPanel(shell: LinuxShell) {
    val s = shell.state.collectAsStateWithLifecycle().value
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    if (!s.running) {
        Text("Bash as root in the Ubuntu, for anything without a button here." +
            (s.exit?.let { " Last shell ended ($it)." } ?: ""), style = HudText.meta)
        HudButton("Open shell", { shell.start() }, kind = HudButtonKind.GHOST)
        return
    }
    LaunchedEffect(s.lines.size, s.lines.lastOrNull()) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxWidth().height(320.dp).background(Hud.crust).padding(8.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState())) {
            s.lines.forEach { Text(it.ifEmpty { " " }, style = HudText.meta.copy(fontFamily = HudFonts.mono, color = Hud.text), softWrap = false) }
        }
    }
    fun send() { shell.send(input); input = "" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudTextField(
            input, { input = it }, placeholder = "Command", maxLines = 1, leading = "#", modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        HudButton("Run", { send() }, kind = HudButtonKind.PRIMARY)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudButton("Ctrl-C", { shell.interrupt() }, kind = HudButtonKind.SMALL)
        LinuxShell.SHORTCUTS.forEach { (label, cmd) -> HudButton(label, { shell.send(cmd) }, kind = HudButtonKind.SMALL) }
        HudButton("Set GitHub token…", { input = LinuxShell.GITHUB_PREFIX }, kind = HudButtonKind.SMALL)
        HudButton("End shell", { shell.stop() }, kind = HudButtonKind.SMALL)
    }
}

/** UserLAnd or Termux: an install command to paste there, and the key to paste back. */
@Composable
private fun PastedSetup(vm: BridgeViewModel, s: BridgeUiState, place: BridgePlace) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Text(
        when (place) {
            BridgePlace.USERLAND ->
                "1. Install UserLAnd (Play Store, free) and start an Ubuntu session.\n" +
                    "2. Copy the install command, paste it into the session and run it.\n" +
                    "3. Sign Claude Code in: ygg-bridge login\n" +
                    "4. Paste the key it shows (ygg-bridge key) below.\n" +
                    "It runs while UserLAnd's session does."
            else ->
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
            val launch = place.app?.let { context.packageManager.getLaunchIntentForPackage(it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) context.startActivity(launch) else note = "${place.label} isn't installed."
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
    if (s.ready) PastedAccounts(vm, s)
}

/** Accounts where the bridge runs in another app: signed in from its terminal. */
@Composable
private fun PastedAccounts(vm: BridgeViewModel, s: BridgeUiState) {
    var newName by remember { mutableStateOf("") }
    HudLabel("Claude accounts", modifier = Modifier.padding(top = 4.dp))
    s.accounts.forEach { a ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = HudText.small.copy(color = Hud.text))
                Text(if (a.signedIn) a.email.ifEmpty { "Signed in" } else "Not signed in: ygg-bridge login ${a.id}",
                    style = HudText.meta.copy(color = if (a.signedIn) Hud.green else Hud.amber))
            }
            if (a.id != "default") HudButton("Remove", { vm.removeAccount(a.id) }, kind = HudButtonKind.SMALL)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudTextField(newName, { newName = it }, placeholder = "Another account, like Work", maxLines = 1, modifier = Modifier.weight(1f))
        HudButton("Add", { vm.addAccount(newName); newName = "" }, kind = HudButtonKind.SMALL, enabled = newName.isNotBlank())
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ygg bridge install", text))
}
