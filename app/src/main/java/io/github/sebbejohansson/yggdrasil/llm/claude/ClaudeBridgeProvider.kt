package io.github.sebbejohansson.yggdrasil.llm.claude

import io.github.sebbejohansson.yggdrasil.llm.LlmProvider
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Claude, through the Claude Code CLI running on this phone (Android's Linux
 * Terminal, or Termux), with your own Claude subscription.
 *
 * The app never talks to Anthropic itself and never holds a token: it asks a
 * small bridge on localhost, and the bridge runs `claude -p`. See
 * docs/claude-bridge.md for the contract.
 *
 * The text leaves the phone, so the router only uses this for requests marked
 * [io.github.sebbejohansson.yggdrasil.llm.Privacy.MAY_LEAVE_DEVICE].
 */
class ClaudeBridgeProvider(private val bridge: BridgeClient) : LlmProvider {

    override val id = "claude-bridge"
    override val displayName = "Claude (Claude Code bridge)"
    override val leavesDevice = true
    override val tiers = setOf(LlmTier.LARGE)

    override suspend fun status(): ProviderStatus = try {
        val h = bridge.health()
        when {
            h == null -> ProviderStatus.NeedsSetup("start the Claude bridge: Settings → Claude bridge (ygg-bridge start)")
            h.claude.isEmpty() -> ProviderStatus.NeedsSetup("Claude Code isn't installed in the bridge: run its installer again")
            !bridge.hasKey -> ProviderStatus.NeedsSetup("paste the bridge's key in Settings (ygg-bridge key)")
            else -> ProviderStatus.Ready
        }
    } catch (e: IOException) {
        ProviderStatus.NeedsSetup(e.message ?: "the bridge isn't answering")
    }

    // The bridge answers in one piece for now; `claude -p --output-format
    // stream-json` would let it stream later without changing this interface.
    override fun stream(request: LlmRequest): Flow<String> = flow {
        val body = JSONObject()
            .put("system", request.system ?: JSONObject.NULL)
            .put("maxOutputTokens", request.maxOutputTokens ?: JSONObject.NULL)
            .put(
                "messages",
                JSONArray(
                    request.messages.map {
                        JSONObject()
                            .put("role", if (it.role == LlmRole.USER) "user" else "assistant")
                            .put("text", it.text)
                    },
                ),
            )
        emit(bridge.complete(body))
    }.flowOn(Dispatchers.IO)
}
