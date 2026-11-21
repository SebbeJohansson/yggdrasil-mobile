package io.github.sebbejohansson.yggdrasil.llm.litert

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.sebbejohansson.yggdrasil.llm.ConversationReuse
import io.github.sebbejohansson.yggdrasil.llm.ConversationSnapshot
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmProvider
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.model.LocalModel
import io.github.sebbejohansson.yggdrasil.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LiteRtLm"

/**
 * Gemma 4 (or any .litertlm model) on the phone through LiteRT-LM.
 *
 * - The engine loads on first use (a few seconds) and stays loaded until [release].
 * - It tries the GPU first and falls back to the CPU.
 * - One generation at a time: the engine has one KV cache.
 * - When a request continues the last conversation, that conversation is
 *   reused, so the history isn't prefilled again.
 */
class LiteRtLmProvider(
    private val model: LocalModel,
    private val store: ModelStore,
    private val cacheDir: File,
) : LlmProvider {

    override val id = "litert:${model.id}"
    override val displayName = "${model.displayName} (on this phone)"
    override val leavesDevice = false
    override val tiers: Set<LlmTier> = model.tiers

    private val lock = Mutex()
    private var engine: Engine? = null

    /** Whether the model is in memory now (it takes about as much RAM as its file). */
    val loaded: Boolean get() = engine != null
    private var conversation: Conversation? = null
    private var seen: ConversationSnapshot? = null

    /** Which backend the engine ended up on, for the status line. */
    @Volatile var backendName: String? = null
        private set

    override suspend fun status(): ProviderStatus = when {
        !store.fitsDevice(model) -> ProviderStatus.Unavailable(
            "${model.displayName} needs ${model.minDeviceRamBytes.gib()} GB RAM; this phone has ${store.totalDeviceRamBytes().gib()} GB",
        )
        !store.isDownloaded(model) -> ProviderStatus.NeedsSetup(
            "download ${model.displayName} (${model.sizeBytes.gib()} GB)",
        )
        else -> ProviderStatus.Ready
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        if (request.side) {
            lock.withLock { emitAll(sideConversation(request)) }
            return@flow
        }
        lock.withLock {
            val conv = conversationFor(request)
            val answer = StringBuilder()
            var finished = false
            try {
                conv.sendMessageAsync(request.messages.last().text).collect { msg ->
                    // Emissions are deltas. If a version ever sends the
                    // text so far instead, keep only what's new.
                    val chunk = msg.text()
                    val delta = if (answer.isNotEmpty() && chunk.length > answer.length && chunk.startsWith(answer)) {
                        chunk.substring(answer.length)
                    } else {
                        chunk
                    }
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        emit(delta)
                    }
                }
                finished = true
            } finally {
                if (finished) {
                    seen = ConversationReuse.after(request, answer.toString())
                } else {
                    // Cancelled or failed half way: the KV cache holds a partial
                    // answer, so this conversation can't be continued.
                    runCatching { conv.cancelProcess() }
                    dropConversation()
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Runs [request] in a conversation of its own and closes it, keeping the
     * chat's open. If the engine can't hold two, the chat's is let go first
     * (the next chat message then reads the history again). Callers hold [lock].
     */
    private suspend fun sideConversation(request: LlmRequest): Flow<String> {
        val config = configFor(request)
        val conv = try {
            engine().createConversation(config)
        } catch (e: Exception) {
            Log.i(TAG, "a side conversation needs the chat's to close first", e)
            dropConversation()
            engine().createConversation(config)
        }
        val answer = StringBuilder()
        try {
            conv.sendMessageAsync(request.messages.last().text).collect { msg -> answer.append(msg.text()) }
        } finally {
            conv.close()
        }
        return flowOf(answer.toString())
    }

    private fun configFor(request: LlmRequest) = ConversationConfig(
        systemInstruction = request.system?.let { Contents.of(it) },
        initialMessages = request.messages.dropLast(1).map { it.toLiteRt() },
        samplerConfig = SamplerConfig(
            topK = if (request.temperature == 0.0) 1 else 64,
            topP = 0.95,
            temperature = request.temperature ?: 1.0,
        ),
        maxOutputToken = request.maxOutputTokens,
    )

    /** Loads the engine now, so the first message doesn't wait for it. */
    suspend fun warmUp() = lock.withLock { engine() }

    /** Frees the model's memory. It loads again on the next request. */
    suspend fun release() = lock.withLock {
        dropConversation()
        engine?.close()
        engine = null
        backendName = null
    }

    // Callers hold [lock].

    private suspend fun conversationFor(request: LlmRequest): Conversation {
        conversation?.let { if (ConversationReuse.canReuse(seen, request)) return it }
        dropConversation()
        return engine().createConversation(configFor(request)).also { conversation = it }
    }

    private fun dropConversation() {
        conversation?.close()
        conversation = null
        seen = null
    }

    private suspend fun engine(): Engine {
        engine?.let { return it }
        check(store.isDownloaded(model)) { "${model.displayName} isn't downloaded" }
        return withContext(Dispatchers.IO) {
            val path = store.fileFor(model).absolutePath
            val attempts = listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
            var lastError: Throwable? = null
            for ((name, backend) in attempts) {
                try {
                    val e = Engine(EngineConfig(modelPath = path, backend = backend(), cacheDir = cacheDir.absolutePath))
                    e.initialize()
                    backendName = name
                    Log.i(TAG, "${model.displayName} loaded on $name")
                    return@withContext e.also { engine = it }
                } catch (t: Throwable) {
                    Log.w(TAG, "${model.displayName} failed on $name", t)
                    lastError = t
                }
            }
            throw IllegalStateException("could not load ${model.displayName}", lastError)
        }
    }
}

private fun LlmMessage.toLiteRt(): Message = when (role) {
    LlmRole.USER -> Message.user(text)
    LlmRole.ASSISTANT -> Message.model(text)
}

private fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

private fun Long.gib(): String = "%.1f".format(this / (1024.0 * 1024 * 1024))
