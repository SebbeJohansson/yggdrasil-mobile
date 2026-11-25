package io.github.sebbejohansson.yggdrasil.norn

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import io.github.sebbejohansson.yggdrasil.ui.leftStripe
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun PageHead(title: String, line: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HudText.title)
            HudLabel(line)
        }
        trailing()
        Spacer(Modifier.width(6.dp))
        HudButton("Back", onBack, kind = HudButtonKind.SMALL)
    }
}

/** Your chats with Norn: pinned ones in their own group on top, then the rest, newest first. */
@Composable
fun ChatsPage(s: ChatUiState, onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, onPin: (Long, Boolean) -> Unit, onNew: () -> Unit, onBack: () -> Unit) {
    val pinned = s.conversations.filter { it.pinned }
    val rest = s.conversations.filterNot { it.pinned }
    @Composable
    fun row(c: Conversation) {
        val open = c.id == s.conversationId
        Row(
            Modifier.fillMaxWidth().leftStripe(if (open) Hud.cyan else Hud.surface1).clickable { onOpen(c.id) }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = HudText.small.copy(color = if (open) Hud.bright else Hud.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenFmt.format(c.updatedAt) + if (open) " · open" else "", style = HudText.meta)
            }
            HudButton(if (c.pinned) "Unpin" else "Pin", { onPin(c.id, !c.pinned) }, kind = HudButtonKind.SMALL)
            Spacer(Modifier.width(6.dp))
            HudButton("Delete", { onDelete(c.id) }, kind = HudButtonKind.SMALL)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHead("Chats", "${s.conversations.size} kept on this phone", onBack) {
            HudButton("New", onNew, kind = HudButtonKind.SMALL)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.conversations.isEmpty()) item { Text("No chats yet.", style = HudText.small.copy(color = Hud.overlay0)) }
            if (pinned.isNotEmpty()) {
                item(key = "pinned-head") { HudSection("Pinned") }
                items(pinned, key = { it.id }) { row(it) }
                if (rest.isNotEmpty()) item(key = "rest-head") { HudSection("Chats", Modifier.padding(top = 4.dp)) }
            }
            items(rest, key = { it.id }) { row(it) }
        }
    }
}

/**
 * What Norn remembers about you, as on the desktop: learned from your own
 * words (never its answers), or told to it. Found by meaning once
 * EmbeddingGemma is on the phone; by newest until then.
 */
@Composable
fun MemoryPage(s: ChatUiState, vm: ChatViewModel, onBack: () -> Unit) {
    var fact by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHead(
            "Memory",
            "${s.memories.size} remembered · " + if (s.embedderReady) "found by meaning" else "newest first",
            onBack,
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudTextField(fact, { fact = it }, placeholder = "Remember that…", modifier = Modifier.weight(1f), maxLines = 3)
                    Spacer(Modifier.width(8.dp))
                    HudButton("Save", { vm.remember(fact); fact = "" }, kind = HudButtonKind.GHOST, enabled = fact.isNotBlank())
                }
            }
            item { HudSection("Remembered", Modifier.padding(top = 4.dp)) }
            if (s.memories.isEmpty()) item { Text("Nothing yet. Tell Yggdrasil about yourself, or save a fact above.", style = HudText.small.copy(color = Hud.overlay0)) }
            items(s.memories, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().leftStripe(if (m.source == "manual") Hud.cyan else Hud.green).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = HudText.small)
                        HudLabel(
                            (if (m.source == "manual") "told" else "learned") + " · " + whenFmt.format(m.createdAt),
                            color = Hud.overlay0, style = HudText.label.copy(fontSize = 10.sp),
                        )
                    }
                    HudButton("Forget", { vm.forget(m) }, kind = HudButtonKind.SMALL)
                }
            }
        }
    }
}
