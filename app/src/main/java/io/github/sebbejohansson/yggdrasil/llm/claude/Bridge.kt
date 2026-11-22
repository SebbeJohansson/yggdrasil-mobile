package io.github.sebbejohansson.yggdrasil.llm.claude

import androidx.core.content.edit
import android.content.Context
import android.util.Base64
import io.github.sebbejohansson.yggdrasil.core.Changes
import io.github.sebbejohansson.yggdrasil.update.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** What GET /health says. */
data class BridgeHealth(val claude: String, val github: Boolean)

/** A cloud session the bridge started. */
data class BridgeSession(
    val id: String,
    val url: String,
    val repo: String,
    val task: String,
    val at: Instant,
    val lastSentAt: Instant? = null,
    val lastError: String? = null,
)

/** The bridge said no, with its reason. */
class BridgeException(val status: Int, message: String) : IOException(message)

/**
 * Ygg's Claude bridge (bridge/, `app/src/main/assets/bridge/ygg_bridge.py`):
 * the real `claude` CLI in Termux's Ubuntu, on 127.0.0.1:8765. It starts
 * cloud sessions, sends follow-ups to any of them, and answers for the
 * Claude provider. Everything but /health carries its key.
 */
class BridgeClient(
    private val vault: TokenVault,
    private val baseUrl: String = "http://127.0.0.1:8765",
) {
    val changes = Changes()

    val hasKey: Boolean get() = vault.get() != null

    /** Keeps the key `ygg-bridge key` printed; false when it doesn't look like one. */
    fun setKey(text: String): Boolean {
        val k = text.trim()
        if (!k.matches(Regex("[A-Za-z0-9_-]{20,64}"))) return false
        vault.set(k)
        changes.bump()
        return true
    }

    fun removeKey() {
        vault.set(null)
        changes.bump()
    }

    private fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 30_000, key: Boolean = true): JSONObject {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = timeoutMs
            if (key) vault.get()?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) throw BridgeException(code, json?.optString("error")?.ifEmpty { null } ?: "the bridge answered HTTP $code")
            return json ?: throw BridgeException(code, "the bridge answered something other than JSON")
        } finally {
            conn.disconnect()
        }
    }

    /** Null when the bridge isn't running; the reason in the exception when it is but can't work. */
    suspend fun health(): BridgeHealth? = withContext(Dispatchers.IO) {
        try {
            val h = call("GET", "/health", timeoutMs = 2_000, key = false)
            BridgeHealth(h.optString("claude"), h.optBoolean("github"))
        } catch (e: BridgeException) {
            if (e.status == 503) BridgeHealth("", false) else throw e
        } catch (_: IOException) {
            null
        }
    }

    suspend fun sessions(): List<BridgeSession> = withContext(Dispatchers.IO) {
        val list = call("GET", "/v1/cloud").optJSONArray("sessions") ?: JSONArray()
        (0 until list.length()).map { session(list.getJSONObject(it)) }
    }

    suspend fun repos(): List<String> = withContext(Dispatchers.IO) {
        val list = call("GET", "/v1/repos").optJSONArray("repos") ?: JSONArray()
        (0 until list.length()).map { list.getJSONObject(it).optString("repo") }.filter { it.isNotEmpty() }
    }

    /** Starts a cloud session for [repo] (owner/name). Takes a minute or two: it clones, then waits for the link. */
    suspend fun start(repo: String, task: String, branch: String? = null): BridgeSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("repo", repo).put("task", task).put("branch", branch ?: "")
        session(call("POST", "/v1/cloud", body, timeoutMs = 240_000)).also { changes.bump() }
    }

    /** Queues [text] in any cloud session of your account; returns once it's queued. */
    suspend fun message(id: String, text: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/cloud/$id/message", JSONObject().put("text", text), timeoutMs = 100_000)
        changes.bump()
    }

    /** Gives the bridge a GitHub token to clone private repos with; empty removes it. Kept in the bridge only. */
    suspend fun setGithub(token: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/github", JSONObject().put("token", token.trim()))
        changes.bump()
    }

    /** One answer for the Claude provider (docs/claude-bridge.md). */
    fun complete(body: JSONObject): String = call("POST", "/v1/complete", body, timeoutMs = 300_000).getString("text")

    private fun session(o: JSONObject) = BridgeSession(
        id = o.getString("id"),
        url = o.optString("url").ifEmpty { "https://claude.ai/code/" + o.getString("id") },
        repo = o.optString("repo"),
        task = o.optString("task"),
        at = Instant.ofEpochMilli(o.optLong("at")),
        lastSentAt = o.optLong("lastSentAt").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastError = o.optString("lastError").ifEmpty { null },
    )

    companion object {
        /**
         * The one command to paste into the terminal: it writes the bridge and
         * its installer from the app (the repo is private, so nothing is
         * fetched from GitHub) and runs the installer for [place].
         */
        fun installCommand(context: Context, place: BridgePlace): String {
            val installer = requireNotNull(place.installer) { "${place.label} needs no install command" }
            fun b64(asset: String) = Base64.encodeToString(context.assets.open(asset).use { it.readBytes() }, Base64.DEFAULT).trim()
            return buildString {
                append("mkdir -p ~/ygg-bridge-setup && cd ~/ygg-bridge-setup && ")
                for ((asset, name) in listOf("bridge/ygg_bridge.py" to "ygg_bridge.py", "linux/setup.sh" to "setup.sh", "bridge/$installer" to installer)) {
                    append("base64 -d > $name <<'YGG_EOF'\n").append(b64(asset)).append("\nYGG_EOF\n")
                }
                append("bash $installer\n")
            }
        }
    }
}

/** Where the bridge runs, and how it's installed there. */
enum class BridgePlace(val label: String, val installer: String?, val app: String?) {
    /** Ygg's own Linux: Ubuntu under proot inside Ygg, always on with Ygg's service. The default. */
    OWN_LINUX("Ygg's own Linux", null, null),

    /** UserLAnd's Ubuntu (Play Store, free): runs while its session does. */
    USERLAND("UserLAnd", "install-userland.sh", "tech.ula"),

    /** Termux, with an Ubuntu inside it (proot-distro). */
    TERMUX("Termux", "install.sh", "com.termux"),
}

/** Which place you set the bridge up in, for the steps and the install command. */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    var place: BridgePlace
        get() = runCatching { BridgePlace.valueOf(prefs.getString("place", null) ?: "") }.getOrDefault(BridgePlace.OWN_LINUX)
        set(v) = prefs.edit { putString("place", v.name) }
}
