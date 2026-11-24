package io.github.sebbejohansson.yggdrasil.norn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.model.DownloadState
import io.github.sebbejohansson.yggdrasil.model.ModelCatalog
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudProgress
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudToggle
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings → Assistant: the model on the phone, Claude, and how memory learns and recalls. */
@Composable
fun AssistantSettings(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnsweredByPanel(vm, s)
        ModelPanel(s, onDownload = vm::downloadModel, onRefresh = { vm.refreshProviders() })
        s.providers.firstOrNull { it.leavesDevice }?.let { claude ->
            Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        HudLabel("Claude", color = if (claude.status == ProviderStatus.Ready) Hud.green else Hud.overlay0)
                        Text(
                            if (claude.status == ProviderStatus.Ready) "Allowed to answer. Text leaves the phone."
                            else "Bridge not running. Text would leave the phone.",
                            style = HudText.meta,
                        )
                    }
                    HudToggle(s.allowClaude, vm::setAllowClaude)
                }
                if (s.allowClaude) NamesLine(s.namesOffUntil, vm::sendNamesFor)
            }
        }
        MemoryPanel(s, vm)
    }
}

/**
 * Who answers the Ask tab: this phone, or one of your machines' Norn, as the
 * desktop dashboard's "Assistant: <machine>" picker. Only machines whose Norn
 * is up are offered (Settings → Connection → Machines); a work machine never is.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnsweredByPanel(vm: ChatViewModel, s: ChatUiState) {
    var choices by remember { mutableStateOf(vm.answerers()) }
    var chosen by remember { mutableStateOf(vm.answeredBy()) }
    LaunchedEffect(Unit) {
        runCatching { vm.refreshMachines() }
        choices = vm.answerers()
    }
    if (choices.isEmpty() && chosen == null) return
    Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudLabel("Answered by", color = Hud.overlay2)
        Text(
            if (chosen == null) "This phone: its own model, memory and chats."
            else "${s.answeredBy ?: chosen}'s Norn, over the tailnet: its models, memory and inbox. Chats are kept here too.",
            style = HudText.meta,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("This phone", { vm.setAnsweredBy(null); chosen = null },
                kind = if (chosen == null) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
            choices.forEach { m ->
                HudButton(m.label, { vm.setAnsweredBy(m.id); chosen = m.id },
                    kind = if (chosen == m.id) HudButtonKind.PRIMARY else HudButtonKind.SMALL)
            }
        }
    }
}

/** The model on this phone: whether it's in, and getting it if not. */
@Composable
private fun ModelPanel(s: ChatUiState, onDownload: () -> Unit, onRefresh: () -> Unit) {
    val local = s.providers.firstOrNull { !it.leavesDevice } ?: return
    val ready = local.status == ProviderStatus.Ready
    Column(
        Modifier.fillMaxWidth().hudPanel(warn = !ready).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudLabel("Model on this phone", color = if (ready) Hud.green else Hud.amber)
        val line = when (val st = local.status) {
            ProviderStatus.Ready -> listOfNotNull(local.name, s.backend?.lowercase()?.let { "on $it" }).joinToString(" · ")
            is ProviderStatus.NeedsSetup -> st.reason
            is ProviderStatus.Unavailable -> st.reason
        }
        Text(line.replaceFirstChar { it.uppercase() }, style = HudText.small)
        when (val d = s.download) {
            is DownloadState.Downloading -> {
                HudProgress(d.fraction, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.0f%%".format(d.fraction * 100), style = HudText.number.copy(color = Hud.cyan))
                    Spacer(Modifier.width(8.dp))
                    HudLabel(
                        if (s.downloadWaiting) "waiting for a network" else "%.2f / %.2f GB · goes on in the background".format(d.bytes / 1e9, d.total / 1e9),
                    )
                }
            }
            DownloadState.Verifying -> HudLabel("Checking the file…", color = Hud.cyan)
            DownloadState.Done, null -> if (local.status is ProviderStatus.NeedsSetup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("Download model", onDownload, kind = HudButtonKind.PRIMARY)
                    HudButton("Check again", onRefresh, kind = HudButtonKind.GHOST)
                }
            }
        }
    }
}

/** Learning from what you say, and finding memories by meaning. */
@Composable
private fun MemoryPanel(s: ChatUiState, vm: ChatViewModel) {
    Column(Modifier.fillMaxWidth().hudPanel(warn = !s.embedderReady).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                HudLabel("Learn from what I say", color = Hud.group)
                Text("After each answer, Yggdrasil picks lasting facts out of your message. Never out of its own answers.", style = HudText.meta)
            }
            HudToggle(s.learning, vm::setLearning)
        }
        HudLabel("Recall by meaning", color = if (s.embedderReady) Hud.green else Hud.amber)
        when (val d = s.embedderDownload) {
            is DownloadState.Downloading -> {
                HudProgress(d.fraction, Modifier.fillMaxWidth())
                HudLabel("%.0f of %.0f MB".format(d.bytes / 1e6, d.total / 1e6))
            }
            DownloadState.Verifying -> HudLabel("Checking the file…", color = Hud.cyan)
            DownloadState.Done, null -> if (s.embedderReady) {
                Text("EmbeddingGemma finds the memories that bear on each question, on this phone.", style = HudText.meta)
            } else {
                Text(
                    "Without it, Yggdrasil gets your newest memories. EmbeddingGemma 2 (%.0f MB) finds the ones that matter.".format(ModelCatalog.EMBEDDING_GEMMA_2.sizeBytes / 1e6),
                    style = HudText.meta,
                )
                HudButton("Download", vm::downloadEmbedder, kind = HudButtonKind.PRIMARY)
            }
        }
    }
}

private val untilFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Whether names are swapped before Claude sees them, and the desktop's way round it for a while. */
@Composable
private fun NamesLine(offUntil: Long, onSendNames: (Int) -> Unit) {
    val off = offUntil > System.currentTimeMillis()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (off) "Names go to Claude as they are until ${untilFmt.format(Instant.ofEpochMilli(offUntil))}."
            else "Names are swapped for placeholders on this phone first. No local model, nothing sent.",
            style = HudText.meta.copy(color = if (off) Hud.amber else Hud.overlay0),
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        if (off) HudButton("Swap again", { onSendNames(0) }, kind = HudButtonKind.SMALL)
        else HudButton("Send names 2 h", { onSendNames(2) }, kind = HudButtonKind.SMALL)
    }
}
