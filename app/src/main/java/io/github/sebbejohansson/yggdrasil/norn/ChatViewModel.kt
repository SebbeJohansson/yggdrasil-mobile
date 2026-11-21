package io.github.sebbejohansson.yggdrasil.norn

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtEmbedder
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtLmProvider
import io.github.sebbejohansson.yggdrasil.model.DownloadState
import io.github.sebbejohansson.yggdrasil.model.LocalModel
import io.github.sebbejohansson.yggdrasil.model.ModelCatalog
import io.github.sebbejohansson.yggdrasil.model.ModelDownloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderRow(val name: String, val status: ProviderStatus, val leavesDevice: Boolean)

data class ChatUiState(
    /** What you see: your words and Norn's answers. */
    val messages: List<LlmMessage> = emptyList(),
    /** The answer being written right now. */
    val pending: String? = null,
    val providers: List<ProviderRow> = emptyList(),
    val download: DownloadState? = null,
    val allowClaude: Boolean = false,
    val backend: String? = null,
    val error: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val conversationId: Long? = null,
    val memories: List<Memory> = emptyList(),
    /** Facts learned from the last message, shown for a moment under it. */
    val learned: List<String> = emptyList(),
    val learning: Boolean = true,
    val embedderReady: Boolean = false,
    val embedderDownload: DownloadState? = null,
    /** The download waits for a network connection. */
    val downloadWaiting: Boolean = false,
) {
    val busy get() = pending != null
}

/** Norn's own switches: learning from what you say. */
class NornSettings(context: Context) {
    private val prefs = context.getSharedPreferences("norn", Context.MODE_PRIVATE)
    var learning: Boolean
        get() = prefs.getBoolean("learning", true)
        set(v) = prefs.edit { putBoolean("learning", v) }
}

class ChatViewModel(
    private val llm: LlmRouter,
    private val local: LiteRtLmProvider,
    private val model: LocalModel,
    private val downloads: ModelDownloads,
    private val chat: NornChat,
    private val brain: Brain,
    private val embedder: LiteRtEmbedder,
    private val settings: NornSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(learning = settings.learning, embedderReady = embedder.ready()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generation: Job? = null

    init {
        refreshProviders()
        viewModelScope.launch {
            chat.openLatest()
            showChat()
            refreshMemory()
        }
        viewModelScope.launch { brain.changes.flow.collect { refreshMemory() } }
        // The downloads run as background jobs; the screen only follows them.
        viewModelScope.launch {
            // WorkManager remembers a finished download: load the model only after one this screen saw run.
            var sawRunning = false
            downloads.state(model).collect { d ->
                if (d?.state is DownloadState.Downloading || d?.state == DownloadState.Verifying) sawRunning = true
                _state.update { it.copy(download = d?.state?.takeIf { s -> s != DownloadState.Done }, downloadWaiting = d?.waiting == true) }
                d?.error?.let { e -> _state.update { it.copy(error = "Download: $e") } }
                if (d?.state == DownloadState.Done && sawRunning) {
                    sawRunning = false
                    refreshProviders()
                    local.warmUp()
                    _state.update { it.copy(backend = local.backendName) }
                }
            }
        }
        viewModelScope.launch {
            downloads.state(ModelCatalog.EMBEDDING_GEMMA_2).collect { d ->
                _state.update { it.copy(embedderDownload = d?.state?.takeIf { s -> s != DownloadState.Done }) }
                if (d?.state == DownloadState.Done && !_state.value.embedderReady) {
                    _state.update { it.copy(embedderReady = embedder.ready()) }
                    brain.reembed()
                }
            }
        }
    }

    fun refreshProviders() = viewModelScope.launch {
        val rows = llm.statuses().map { (p, s) -> ProviderRow(p.displayName, s, p.leavesDevice) }
        _state.update { it.copy(providers = rows, backend = local.backendName, embedderReady = embedder.ready()) }
    }

    fun setAllowClaude(allow: Boolean) = _state.update { it.copy(allowClaude = allow) }

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.busy) return
        _state.update { it.copy(messages = it.messages + LlmMessage(io.github.sebbejohansson.yggdrasil.llm.LlmRole.USER, question), pending = "", error = null, learned = emptyList()) }
        val privacy = if (_state.value.allowClaude) Privacy.MAY_LEAVE_DEVICE else Privacy.ON_DEVICE_ONLY

        generation = viewModelScope.launch {
            var answer = ""
            try {
                val request = chat.ask(question, privacy)
                _state.update { it.copy(conversationId = chat.conversationId) }
                llm.stream(request).collect { delta ->
                    answer += delta
                    _state.update { it.copy(pending = answer) }
                }
                chat.answered(answer)
                showChat()
                _state.update { it.copy(backend = local.backendName) }
                learn(question)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A stopped answer stays in the chat, so the next message has the same history.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { chat.answered(answer) }
                showChat()
                throw e
            } catch (e: Exception) {
                chat.answered(answer)
                showChat()
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** After the answer, on the side: facts from your words, never the answer's. */
    private fun learn(question: String) {
        if (!settings.learning) return
        viewModelScope.launch {
            val learned = chat.learnFrom(question)
            if (learned.isNotEmpty()) _state.update { it.copy(learned = learned.map { m -> m.text }) }
        }
    }

    fun stop() {
        generation?.cancel()
    }

    fun newChat() {
        stop()
        chat.startNew()
        showChat()
        _state.update { it.copy(error = null, learned = emptyList()) }
    }

    /** The list of chats, read again (opening the Chats page). */
    fun refreshChats() = viewModelScope.launch { _state.update { it.copy(conversations = chat.conversations()) } }

    fun openChat(id: Long) = viewModelScope.launch {
        stop()
        chat.open(id)
        showChat()
    }

    fun deleteChat(id: Long) = viewModelScope.launch {
        chat.delete(id)
        showChat()
    }

    fun remember(text: String) = viewModelScope.launch { brain.remember(text) }
    fun forget(m: Memory) = viewModelScope.launch { brain.forget(m.id) }

    fun setLearning(on: Boolean) {
        settings.learning = on
        _state.update { it.copy(learning = on) }
    }

    /** Starts the model download in the background; it goes on when you leave the app. */
    fun downloadModel() = downloads.start(model)

    /** EmbeddingGemma, so memories are found by meaning; once in, the old ones get vectors too. */
    fun downloadEmbedder() = downloads.start(ModelCatalog.EMBEDDING_GEMMA_2)

    private fun showChat() {
        _state.update {
            it.copy(
                messages = chat.lines.map { l -> LlmMessage(l.role, l.shown) },
                pending = null,
                conversationId = chat.conversationId,
            )
        }
        viewModelScope.launch { _state.update { it.copy(conversations = chat.conversations()) } }
    }

    private suspend fun refreshMemory() {
        _state.update { it.copy(memories = brain.memories(), embedderReady = embedder.ready()) }
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(c.llm, c.localLlm, c.defaultModel, c.modelDownloads, c.nornChat, c.brain, c.embedder, c.nornSettings)
            }
        }
    }
}
