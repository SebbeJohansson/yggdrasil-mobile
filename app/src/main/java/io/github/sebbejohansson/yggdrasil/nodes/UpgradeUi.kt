package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often a running upgrade is read again, and for how long at most (Sindri calls one dead after 15 minutes). */
private const val UPGRADE_POLL_MS = 5_000L
private const val UPGRADE_POLL_MAX_MS = 16 * 60_000L

/**
 * Upgrade on a machine reached directly, at the top of its card: shown when
 * it runs an older release than the newest, or while an upgrade from a
 * dashboard runs or has just ended there. While it runs, the machine is read
 * again every few seconds; Sindri restarts on the way, so a read that fails
 * meanwhile is waited out rather than shown.
 */
@Composable
fun MachineUpgrade(d: DeviceSessions, status: MachineStatus, latest: String?, vm: CodeViewModel) {
    val scope = rememberCoroutineScope()
    var run by remember(d.device) { mutableStateOf<UpgradeRun?>(null) }
    var now by remember(d.device) { mutableStateOf<String?>(null) }
    var note by remember(d.device) { mutableStateOf<String?>(null) }
    var starting by remember(d.device) { mutableStateOf(false) }
    val behind = YggReleases.standing(status.health?.version, latest) == YggReleases.Standing.BEHIND

    // What the machine says about its last upgrade, once; a run started from the desktop shows too.
    LaunchedEffect(d.device) {
        vm.upgradeStatus(d.device).onSuccess { run = it.last; now = it.version }
    }
    // Follow a running upgrade until the Sindri that comes back says how it went.
    LaunchedEffect(d.device, run?.running, run?.version) {
        if (run?.running != true) return@LaunchedEffect
        val until = System.currentTimeMillis() + UPGRADE_POLL_MAX_MS
        while (System.currentTimeMillis() < until) {
            delay(UPGRADE_POLL_MS)
            val read = vm.upgradeStatus(d.device).getOrNull() ?: continue
            val last = read.last ?: continue
            if (!last.running) {
                run = last
                now = read.version
                runCatching { vm.refreshMachines() }
                return@LaunchedEffect
            }
        }
        note = "No word from ${d.title} for a while: check it there with `ygg status`."
    }

    val current = run
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        current?.let {
            val color = when {
                it.running -> Hud.overlay2
                it.ok == true -> Hud.green
                else -> Hud.amber
            }
            Text(Upgrades.line(it, now ?: status.health?.version), style = HudText.meta.copy(color = color))
        }
        note?.let { Text(it, style = HudText.meta.copy(color = Hud.amber)) }
        if (behind && latest != null && current?.running != true) {
            HudButton(if (starting) "Starting…" else "Upgrade ${d.title} to $latest", {
                starting = true
                note = null
                scope.launch {
                    // "latest": the machine asks GitHub itself, so a release newer than the phone knows is not missed.
                    vm.startUpgrade(d.device, Upgrades.LATEST)
                        .onSuccess { run = it }
                        .onFailure { note = Upgrades.refusal(it.message) }
                    starting = false
                }
            }, kind = HudButtonKind.SMALL, enabled = !starting)
        }
        if (current?.running != true) CheckForYggUpdates(vm, latest)
    }
}

/**
 * Check for Yggdrasil updates: the newest release asked of GitHub now, past
 * the 30-minute cache, and every machine read again, so a card shows Upgrade
 * at once when a release came out since the last look.
 */
@Composable
fun CheckForYggUpdates(vm: CodeViewModel, latest: String?) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudButton(if (checking) "Checking…" else "Check for Yggdrasil updates", {
            checking = true
            scope.launch {
                val found = runCatching { vm.checkForUpdates() }.getOrNull()
                said = if (found != null) "Newest Yggdrasil: $found, checked just now." else "GitHub did not answer; ${latest ?: "no release"} is the newest known."
                checking = false
            }
        }, kind = HudButtonKind.SMALL, enabled = !checking)
        said?.let { Text(it, style = HudText.meta) }
    }
}
