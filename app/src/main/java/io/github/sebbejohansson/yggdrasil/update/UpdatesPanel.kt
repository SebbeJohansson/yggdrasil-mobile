package io.github.sebbejohansson.yggdrasil.update

import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Parts → Settings: the version you have, the newest release, and the
 * buttons to get it. The work runs in [scope] (the app's), so it carries on
 * when you leave the page.
 */
@Composable
fun UpdatesPanel(updater: Updater, scope: CoroutineScope) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(updater.allowed()) }
    var auto by remember { mutableStateOf(updater.settings.auto) }
    var hasToken by remember { mutableStateOf(updater.hasToken) }
    var pasted by remember { mutableStateOf("") }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        allowed = updater.allowed()
        onPauseOrDispose { }
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudSection("Updates")
        HudLabel("Ygg ${updater.installed}", color = Hud.subtext0)
        val s = state
        when (s) {
            UpdateState.DevBuild -> Text("A dev build: it's updated from Android Studio, not from releases.", style = HudText.meta)
            UpdateState.Idle -> Text("Looks for a new release every few hours.", style = HudText.meta)
            UpdateState.Checking -> Text("Looking…", style = HudText.meta)
            is UpdateState.UpToDate -> Text("Up to date. Looked at ${time.format(Date(s.checkedAt))}.", style = HudText.meta)
            is UpdateState.Available -> ReleaseLines(s.release, "is out.")
            is UpdateState.Downloading -> {
                ReleaseLines(s.release, "is downloading.")
                HudLabel("${s.bytes / 1_000_000} of ${s.total / 1_000_000} MB", color = Hud.cyan)
            }
            is UpdateState.Ready -> ReleaseLines(s.release, if (auto && allowed) "is ready. It installs when the screen is off." else "is ready to install.")
            is UpdateState.Installing -> ReleaseLines(s.release, "is installing. Ygg closes and comes back updated.")
            is UpdateState.Failed -> Text(s.message, style = HudText.meta.copy(color = Hud.amber))
        }
        if (s != UpdateState.DevBuild) {
            Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        HudLabel("Install automatically")
                        Text("When the screen is off or the phone is idle, never while a model downloads.", style = HudText.meta)
                    }
                    HudToggle(auto, { auto = it; updater.settings.auto = it })
                }
            }
            if (!allowed) {
                Text("Android asks you once to let Ygg install apps. After that, updates need no taps.", style = HudText.meta)
                HudButton("Allow Ygg to install updates", { runCatching { context.startActivity(updater.allowIntent()) } }, kind = HudButtonKind.GHOST)
            }
            TokenLines(
                hasToken = hasToken,
                pasted = pasted,
                note = tokenNote,
                onPaste = { pasted = it; tokenNote = null },
                onSave = {
                    if (updater.setToken(pasted)) {
                        pasted = ""
                        hasToken = true
                        tokenNote = null
                        scope.launch { updater.check() }
                    } else {
                        tokenNote = "That doesn't look like a GitHub token (github_pat_… or ghp_…)."
                    }
                },
                onRemove = { updater.removeToken(); hasToken = false },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing
                val ready = s as? UpdateState.Ready
                when {
                    ready != null -> HudButton("Install now", { updater.install(ready.release, ready.file) }, enabled = allowed, kind = HudButtonKind.PRIMARY)
                    s is UpdateState.Available -> HudButton("Download", { scope.launch { updater.download(s.release) } }, kind = HudButtonKind.PRIMARY)
                    else -> Unit
                }
                HudButton("Check now", { scope.launch { updater.check() } }, enabled = !busy, kind = HudButtonKind.GHOST)
            }
        }
    }
}

@Composable
private fun ReleaseLines(release: Release, what: String) {
    Text("Ygg ${release.version} $what", style = HudText.small)
    release.summary(3).takeIf { it.isNotBlank() }?.let { Text(it, style = HudText.meta) }
}

/**
 * The token for a private repo: a fine-grained GitHub token with read access
 * to this repo's contents and nothing else. Shown as dots, kept encrypted.
 */
@Composable
private fun TokenLines(
    hasToken: Boolean,
    pasted: String,
    note: String?,
    onPaste: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    HudSection("GitHub token")
    if (hasToken) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Set, kept encrypted on this phone.", style = HudText.meta, modifier = Modifier.weight(1f))
            HudButton("Remove", onRemove, kind = HudButtonKind.SMALL)
        }
        return
    }
    Text(
        "The repo is private, so GitHub wants a token. On github.com: Settings → Developer settings → " +
            "Fine-grained tokens → Generate. Repository access: only yggdrasil-mobile. Permissions: Contents, read-only. Paste it here.",
        style = HudText.meta,
    )
    HudTextField(
        value = pasted,
        onValueChange = onPaste,
        placeholder = "github_pat_…",
        maxLines = 1,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        modifier = Modifier.fillMaxWidth(),
    )
    note?.let { Text(it, style = HudText.meta.copy(color = Hud.amber)) }
    HudButton("Save token", onSave, enabled = pasted.isNotBlank(), kind = HudButtonKind.GHOST)
}
