package io.github.sebbejohansson.yggdrasil.norn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.llm.litert.LiteRtLmProvider
import io.github.sebbejohansson.yggdrasil.model.DownloadState
import io.github.sebbejohansson.yggdrasil.model.LocalModel
import io.github.sebbejohansson.yggdrasil.model.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderRow(val name: String, val status: ProviderStatus, val leavesDevice: Boolean)

data class ChatUiState(
    val messages: List<LlmMessage> = emptyList(),
    /** The answer being written right now. */
    val pending: String? = null,
    val providers: List<ProviderRow> = emptyList(),
    val download: DownloadState? = null,
    val allowClaude: Boolean = false,
    val backend: String? = null,
    val error: String? = null,
) {
    val busy get() = pending != null
}

private const val SYSTEM = """You are Norn, a personal assistant that runs on the user's phone.
Answer briefly and plainly. Say so when you don't know."""

class ChatViewModel(
    private val llm: LlmRouter,
    private val local: LiteRtLmProvider,
    private val model: LocalModel,
    private val downloader: ModelDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generation: Job? = null
    private var downloadJob: Job? = null

    init {
        refreshProviders()
    }

    fun refreshProviders() = viewModelScope.launch {
        val rows = llm.statuses().map { (p, s) -> ProviderRow(p.displayName, s, p.leavesDevice) }
        _state.update { it.copy(providers = rows, backend = local.backendName) }
    }

    fun setAllowClaude(allow: Boolean) = _state.update { it.copy(allowClaude = allow) }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        val history = _state.value.messages + LlmMessage(LlmRole.USER, trimmed)
        _state.update { it.copy(messages = history, pending = "", error = null) }

        val request = LlmRequest(
            messages = history,
            system = SYSTEM,
            tier = LlmTier.LARGE,
            privacy = if (_state.value.allowClaude) Privacy.MAY_LEAVE_DEVICE else Privacy.ON_DEVICE_ONLY,
        )
        generation = viewModelScope.launch {
            try {
                llm.stream(request).collect { delta ->
                    _state.update { it.copy(pending = (it.pending ?: "") + delta) }
                }
                _state.update {
                    it.copy(
                        messages = it.messages + LlmMessage(LlmRole.ASSISTANT, it.pending.orEmpty()),
                        pending = null,
                        backend = local.backendName,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { keepPartial(it) }
                throw e
            } catch (e: Exception) {
                _state.update { keepPartial(it).copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun stop() {
        generation?.cancel()
    }

    fun clear() {
        stop()
        _state.update { it.copy(messages = emptyList(), pending = null, error = null) }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(model).collect { s -> _state.update { it.copy(download = s) } }
                _state.update { it.copy(download = null) }
                refreshProviders()
                local.warmUp()
                _state.update { it.copy(backend = local.backendName) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(download = null, error = "Download: ${e.message}") }
            }
        }
    }

    // A stopped answer stays in the chat, so the next message has the same history.
    private fun keepPartial(s: ChatUiState): ChatUiState {
        val partial = s.pending
        return if (partial.isNullOrEmpty()) s.copy(pending = null)
        else s.copy(messages = s.messages + LlmMessage(LlmRole.ASSISTANT, partial), pending = null)
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(c.llm, c.localLlm, c.defaultModel, c.modelDownloader) }
        }
    }
}
