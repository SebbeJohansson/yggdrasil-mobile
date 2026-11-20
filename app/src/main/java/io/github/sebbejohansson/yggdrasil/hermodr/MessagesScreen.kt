package io.github.sebbejohansson.yggdrasil.hermodr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MessagesUiState(
    val messages: List<Message> = emptyList(),
    val quietHours: QuietHours = QuietHours(),
    /** Why notifications can't be shown right now, if they can't. */
    val blocked: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(
    private val hermodr: HermodrService,
    private val settings: HermodrSettings,
    private val notifier: Notifier,
) : ViewModel() {

    private val quiet = MutableStateFlow(settings.quietHours)
    private val recheck = MutableStateFlow(0)

    val state = combine(hermodr.changes.flow, quiet, recheck) { _, q, _ -> q }
        .mapLatest { q -> MessagesUiState(hermodr.list(), q, notifier.blockedReason(Priority.NORMAL)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun markSeen(m: Message) = viewModelScope.launch { hermodr.markSeen(m.id) }

    fun setQuietHours(enabled: Boolean) {
        settings.quietHours = settings.quietHours.copy(enabled = enabled)
        quiet.value = settings.quietHours
        // Turning them off releases what they held.
        viewModelScope.launch { hermodr.flushHeld() }
    }

    /** After the permission dialog, or coming back from Android's settings. */
    fun recheckPermission() {
        recheck.value++
    }

    fun sendTest(priority: Priority) = viewModelScope.launch {
        hermodr.send(MessageRequest(sender = "test", title = "Test ${priority.name.lowercase()} message", text = "Sent from the Messages tab", priority = priority))
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessagesViewModel(c.hermodr, c.hermodrSettings, c.notifier) }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun MessagesScreen(vm: MessagesViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.blocked?.let { why ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("Messages stay in this list only: $why.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quiet hours", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${s.quietHours.start}–${s.quietHours.end}: only urgent messages go out",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.quietHours.enabled, onCheckedChange = vm::setQuietHours)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Test:", style = MaterialTheme.typography.labelMedium)
                AssistChip(onClick = { vm.sendTest(Priority.NORMAL) }, label = { Text("normal") })
                AssistChip(onClick = { vm.sendTest(Priority.URGENT) }, label = { Text("urgent") })
            }
        }
        if (s.messages.isEmpty()) item { Text("No messages in the last 7 days.", style = MaterialTheme.typography.bodyMedium) }
        items(s.messages, key = { it.id }) { m -> MessageCard(m, onSeen = { vm.markSeen(m) }) }
    }
}

@Composable
private fun MessageCard(m: Message, onSeen: () -> Unit) {
    val unseen = m.seenAt == null
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (unseen) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall)
            m.text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val status = when (m.state) {
                MessageState.HELD -> "held until the quiet ends"
                MessageState.SENT -> "sent"
                MessageState.LISTED -> "listed only"
                MessageState.FAILED -> "not shown: ${m.failure}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${m.sender} · ${m.priority.name.lowercase()} · $status · ${timeFmt.format(m.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (unseen) TextButton(onClick = onSeen) { Text("Seen") }
            }
        }
    }
}
