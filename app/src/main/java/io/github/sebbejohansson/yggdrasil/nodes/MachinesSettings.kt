package io.github.sebbejohansson.yggdrasil.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.saga.CodeViewModel
import io.github.sebbejohansson.yggdrasil.saga.clipboardText
import io.github.sebbejohansson.yggdrasil.saga.parsePairingResult
import io.github.sebbejohansson.yggdrasil.saga.PairScanner
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.hudPanel

/**
 * Settings → Connection → Machines: the machines the Code tab reaches
 * directly over the tailnet, each with its tailnet name and its owner token
 * (Yggdrasil's docs/tailscale.md). A token is never shown again once saved.
 */
@Composable
fun MachinesSettings(vm: CodeViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf(vm.machineList()) }
    var editing by remember { mutableStateOf<Machine?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { vm.refreshMachines() } }

    Column(Modifier.fillMaxWidth().hudPanel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Your machines on the tailnet (Tailscale on this phone): their coding sessions live in the Code tab, " +
                "and what you ask is done at once. A work machine is never added.",
            style = HudText.meta,
        )
        if (list.isEmpty() && !adding) Text("None yet.", style = HudText.small.copy(color = Hud.overlay0))
        list.forEach { machine ->
            val status = s.machines.firstOrNull { it.machine.id == machine.id }
            if (editing?.id == machine.id) {
                MachineForm(machine, onDone = { editing = null; list = vm.machineList() }, vm = vm)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(machine.label, style = HudText.body)
                        val line = when {
                            status == null -> machine.host
                            status.refused -> "left out: it says it is a work machine"
                            status.error != null && status.health == null -> "not answering: ${status.error}"
                            else -> Machines.summary(status, s.latest)
                        }
                        val warn = status != null && (status.refused || status.error != null || Machines.needsLook(status, s.latest))
                        Text(line, style = HudText.meta.copy(color = if (warn) Hud.amber else Hud.overlay2))
                    }
                    HudButton("Edit", { editing = machine }, kind = HudButtonKind.SMALL, modifier = Modifier.padding(end = 6.dp))
                    HudButton("Remove", { vm.removeMachine(machine.id); list = list.filter { it.id != machine.id } }, kind = HudButtonKind.SMALL)
                }
            }
        }
        if (adding) {
            MachineForm(null, onDone = { adding = false; list = vm.machineList() }, vm = vm)
        } else {
            HudButton("Add a machine", { adding = true }, kind = HudButtonKind.SMALL)
        }
        if (list.isNotEmpty()) CheckForYggUpdates(vm, s.latest)
    }
}

@Composable
private fun MachineForm(machine: Machine?, onDone: () -> Unit, vm: CodeViewModel) {
    var label by remember { mutableStateOf(machine?.label ?: "") }
    var host by remember { mutableStateOf(machine?.host ?: "") }
    var token by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // A scanned or pasted `ygg pair` link fills the fields; a work machine's is refused.
    val fill = { raw: String ->
        parsePairingResult(raw).fold(
            onSuccess = { p ->
                host = p.host
                token = p.token
                if (label.isBlank()) label = p.label.orEmpty()
                problem = null
            },
            onFailure = { problem = it.message },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Scan QR", { scanning = true }, kind = HudButtonKind.SMALL)
            HudButton("Paste code", { fill(clipboardText(context)) }, kind = HudButtonKind.SMALL)
        }
        HudTextField(label, { label = it }, placeholder = "Name (VPS, Home PC)", maxLines = 1)
        HudTextField(host, { host = it }, placeholder = "Tailnet name: vps.tail1234.ts.net", maxLines = 1)
        HudTextField(
            token, { token = it },
            placeholder = if (machine != null) "Owner token (blank keeps the saved one)" else "Owner token (~/.config/yggdrasil/owner.token there)",
            maxLines = 1,
        )
        problem?.let { HudLabel(it, color = Hud.amber) }
        if (scanning) PairScanner(onText = { scanning = false; fill(it) }, onClose = { scanning = false; if (it != null) problem = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Save", {
                problem = vm.saveMachine(label, host, token, keepToken = machine != null)
                if (problem == null) onDone()
            }, kind = HudButtonKind.SMALL)
            HudButton("Cancel", onDone, kind = HudButtonKind.SMALL)
        }
    }
}
