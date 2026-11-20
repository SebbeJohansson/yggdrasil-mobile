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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
class ClaudeBridgeProvider(
    private val baseUrl: String = "http://127.0.0.1:8765",
) : LlmProvider {

    override val id = "claude-bridge"
    override val displayName = "Claude (Claude Code bridge)"
    override val leavesDevice = true
    override val tiers = setOf(LlmTier.LARGE)

    override suspend fun status(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val conn = open("/health", timeoutMs = 1_000)
            try {
                if (conn.responseCode == 200) ProviderStatus.Ready
                else ProviderStatus.NeedsSetup("the bridge answered HTTP ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            ProviderStatus.NeedsSetup("start the Claude bridge in the Linux Terminal")
        }
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
        val conn = open("/v1/complete", timeoutMs = 300_000).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Claude bridge: HTTP $code $text")
            emit(JSONObject(text).getString("text"))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(path: String, timeoutMs: Int) =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(timeoutMs, 2_000)
            readTimeout = timeoutMs
        }
}
