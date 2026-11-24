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
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What GET /health says. */
data class BridgeHealth(val claude: String, val github: Boolean, val herdr: Boolean = false)

/** A Claude account the bridge signs in as: its own sign-in and settings. */
data class BridgeAccount(
    val id: String,
    val name: String,
    val signedIn: Boolean,
    /** Who it's signed in as; empty when claude didn't say or it isn't. */
    val email: String,
    /** Its claude config folder in the bridge's Linux; empty for the first, which is claude's own. */
    val configDir: String,
)

/** A cloud session the bridge started. */
data class BridgeSession(
    val id: String,
    val url: String,
    val repo: String,
    val task: String,
    val at: Instant,
    /** The account it was started in. */
    val account: String = "default",
    val lastSentAt: Instant? = null,
    val lastError: String? = null,
)

/** A Claude Code dialog in a local session, waiting for you: a question and its numbered choices. */
data class LocalDialog(val question: String, val detail: String, val choices: List<LocalChoice>)

data class LocalChoice(val key: String, val label: String, val selected: Boolean)

/** One line of a local session's chat: yours, Claude's, or a tool Claude used. */
data class ChatLine(val role: String, val text: String)

/** Claude Code on this phone, in herdr, in the phone's checkout of a repo. */
data class LocalSession(
    val name: String,
    val account: String,
    val repo: String,
    val task: String,
    val at: Instant,
    /** herdr's word: idle, working, blocked, done, unknown (starting), gone (its pane closed). */
    val status: String,
    val dialog: LocalDialog?,
    /** As the desktop's sessions: busy, turn-ended (your move), blocked (a question waits), ended, starting. */
    val phase: String = "starting",
    /** The tool Claude is in while busy. */
    val tool: String = "",
    /** Claude's last words when its turn ended, up to 600 characters. */
    val lastText: String = "",
    /** The session's title as Claude keeps it; empty until it has one. */
    val title: String = "",
    val lastActivity: Instant? = null,
    /** An ended session with a conversation to go on with. */
    val resumable: Boolean = false,
    /** How the checkout was brought up to date when the session started: "pulled 3 new commits from origin/main", or why not. */
    val refreshed: String = "",
    /** Every repo the session spans, its first (where Claude starts) first. */
    val repos: List<String> = emptyList(),
    /** The chat, when asked for one session; and its screen while there's no chat to show. */
    val messages: List<ChatLine> = emptyList(),
    val screen: String = "",
)

/** Where a local session's checkout stands. */
data class RepoState(
    val branch: String,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
    val files: List<RepoFile>,
    val log: List<RepoCommit>,
)

/** A changed file: git's two-letter status (M, A, D, ??…) and lines added and removed. */
data class RepoFile(val path: String, val status: String, val added: Int, val removed: Int)

data class RepoCommit(val hash: String, val at: Instant, val subject: String)

/** The bridge said no, with its reason. */
class BridgeException(val status: Int, message: String) : IOException(message)

/**
 * Ygg's Claude bridge (bridge/, `app/src/main/assets/bridge/ygg_bridge.py`):
 * the real `claude` CLI in Termux's Ubuntu, on 127.0.0.1:8765. It starts
 * cloud sessions, sends follow-ups to any of them, and answers for the
 * Claude provider. Everything but /health is signed with its key
 * ([signature]); the key itself never goes over the port, so an app that
 * took 127.0.0.1:8765 while the bridge was down learns nothing it can use.
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
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = timeoutMs
            if (key) vault.get()?.let { k ->
                val stamp = (System.currentTimeMillis() / 1000).toString()
                val nonce = hex(ByteArray(16).also { random.nextBytes(it) })
                setRequestProperty("Authorization", "Ygg-HMAC " + signature(k, method, path, stamp, nonce, bytes))
                setRequestProperty("X-Ygg-Time", stamp)
                setRequestProperty("X-Ygg-Nonce", nonce)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(bytes) }
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
            BridgeHealth(h.optString("claude"), h.optBoolean("github"), h.optBoolean("herdr"))
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

    suspend fun accounts(): List<BridgeAccount> = withContext(Dispatchers.IO) {
        val list = call("GET", "/v1/accounts").optJSONArray("accounts") ?: JSONArray()
        (0 until list.length()).map { account(list.getJSONObject(it)) }
    }

    /** A new account, named like Work; sign it in afterwards. */
    suspend fun addAccount(name: String): BridgeAccount = withContext(Dispatchers.IO) {
        account(call("POST", "/v1/accounts", JSONObject().put("name", name.trim()))).also { changes.bump() }
    }

    suspend fun renameAccount(id: String, name: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/accounts/$id", JSONObject().put("name", name.trim()))
        changes.bump()
    }

    /** Forgets the account and its sign-in on this phone. */
    suspend fun removeAccount(id: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/accounts/$id/remove", JSONObject())
        changes.bump()
    }

    suspend fun localSessions(): List<LocalSession> = withContext(Dispatchers.IO) {
        val list = call("GET", "/v1/local").optJSONArray("sessions") ?: JSONArray()
        (0 until list.length()).map { local(list.getJSONObject(it)) }
    }

    /** Claude Code on the phone for [repo], as [account], with [task] typed in. Cloning first time takes a while. */
    suspend fun startLocal(repo: String, task: String, account: String = "default", also: List<String> = emptyList(), branch: String = ""): LocalSession = withContext(Dispatchers.IO) {
        // [also]: one session over several repos, each its own worktree on the same branch; Claude starts in [repo]'s.
        val body = JSONObject().put("repo", repo).put("task", task).put("account", account).put("repos", JSONArray(also)).put("branch", branch.trim())
        local(call("POST", "/v1/local", body, timeoutMs = 300_000)).also { changes.bump() }
    }

    /** One local session with its chat. */
    suspend fun localChat(name: String): LocalSession = withContext(Dispatchers.IO) { local(call("GET", "/v1/local/$name")) }

    suspend fun promptLocal(name: String, text: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/prompt", JSONObject().put("text", text), timeoutMs = 60_000)
    }

    /** Picks [choice] in [dialog], only while that same dialog still shows. */
    suspend fun chooseLocal(name: String, dialog: LocalDialog, choice: LocalChoice) = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/choose", JSONObject().put("question", dialog.question).put("key", choice.key).put("label", choice.label))
    }

    /** Presses keys in its pane: esc interrupts Claude. */
    suspend fun keysLocal(name: String, vararg keys: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/keys", JSONObject().put("keys", JSONArray(keys.toList())))
    }

    suspend fun repoLocal(name: String, repo: String? = null): RepoState = withContext(Dispatchers.IO) {
        val o = call("GET", "/v1/local/$name/repo" + (repo?.let { "?repo=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))
        val files = o.optJSONArray("files") ?: JSONArray()
        val log = o.optJSONArray("log") ?: JSONArray()
        RepoState(
            branch = o.optString("branch"),
            upstream = o.optString("upstream"),
            ahead = o.optInt("ahead"),
            behind = o.optInt("behind"),
            files = (0 until files.length()).map { files.getJSONObject(it).let { f -> RepoFile(f.optString("path"), f.optString("status"), f.optInt("added"), f.optInt("removed")) } },
            log = (0 until log.length()).map { log.getJSONObject(it).let { c -> RepoCommit(c.optString("hash"), Instant.ofEpochMilli(c.optLong("at")), c.optString("subject")) } },
        )
    }

    /** One file's diff against HEAD. */
    suspend fun diffLocal(name: String, path: String, repo: String? = null): String = withContext(Dispatchers.IO) {
        call("GET", "/v1/local/$name/diff?path=" + java.net.URLEncoder.encode(path, "UTF-8") + (repo?.let { "&repo=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")).optString("diff")
    }

    /** pull (fast-forward only) or push the checkout's branch; git's last words. */
    suspend fun gitLocal(name: String, action: String, repo: String? = null): String = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/git", JSONObject().put("action", action).put("repo", repo ?: ""), timeoutMs = 120_000).optString("output")
    }

    /** An ended session again, with its conversation: claude --resume in a new herdr workspace. */
    suspend fun resumeLocal(name: String): LocalSession = withContext(Dispatchers.IO) {
        local(call("POST", "/v1/local/$name/resume", JSONObject(), timeoutMs = 90_000)).also { changes.bump() }
    }

    /** Ends it; the checkout and its work stay on the phone. */
    /**
     * Removes an ended session and its own worktree (its branch on GitHub
     * stays). Refused while that would lose work: changes not committed, or
     * commits not pushed; the reason says which.
     */
    suspend fun removeLocal(name: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/remove", JSONObject())
        changes.bump()
    }

    suspend fun stopLocal(name: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/local/$name/stop", JSONObject())
        changes.bump()
    }

    /**
     * True when what answers on the port knows the key: it signs a fresh nonce
     * ([proof]). Checked before a token is handed over, so an app posing as the
     * bridge never gets one.
     */
    fun isRealBridge(): Boolean {
        val k = vault.get() ?: return false
        val nonce = hex(ByteArray(16).also { random.nextBytes(it) })
        val answer = runCatching { call("GET", "/v1/proof?nonce=$nonce").optString("proof") }.getOrDefault("")
        return MessageDigest.isEqual(answer.toByteArray(), proof(k, nonce).toByteArray())
    }

    /** Keeps [token] in the bridge for cloning private repos; blank forgets it. Only ever when you press Keep. */
    suspend fun setGitHub(token: String) = withContext(Dispatchers.IO) {
        if (!isRealBridge()) throw BridgeException(0, "what answers on 127.0.0.1:8765 couldn't show it has the bridge's key, so the token wasn't sent. Update the bridge (run its install again) and check the key.")
        call("POST", "/v1/github", JSONObject().put("token", token.trim()))
        changes.bump()
    }

    /**
     * Starts a cloud session for [repo] (owner/name). Takes a minute or two: it readies the repo, then waits for the link.
     * When GitHub refuses the clone, the bridge's error says to add a token under Settings → Claude bridge:
     * Ygg never hands its own token over on its own.
     */
    suspend fun start(repo: String, task: String, branch: String? = null, account: String = "default"): BridgeSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("repo", repo).put("task", task).put("branch", branch ?: "").put("account", account)
        val s = call("POST", "/v1/cloud", body, timeoutMs = 420_000)
        session(s).also { changes.bump() }
    }

    /** Queues [text] in any cloud session of your account; returns once it's queued. */
    suspend fun message(id: String, text: String) = withContext(Dispatchers.IO) {
        call("POST", "/v1/cloud/$id/message", JSONObject().put("text", text), timeoutMs = 100_000)
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
        account = o.optString("account").ifEmpty { "default" },
    )

    private fun local(o: JSONObject): LocalSession {
        val d = o.optJSONObject("dialog")
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        return LocalSession(
            name = o.getString("name"),
            account = o.optString("account").ifEmpty { "default" },
            repo = o.optString("repo"),
            task = o.optString("task"),
            at = Instant.ofEpochMilli(o.optLong("at")),
            status = o.optString("status").ifEmpty { "unknown" },
            dialog = d?.let {
                val cs = it.optJSONArray("choices") ?: JSONArray()
                LocalDialog(it.optString("question"), it.optString("detail"),
                    (0 until cs.length()).map { i -> cs.getJSONObject(i).let { c -> LocalChoice(c.optString("key"), c.optString("label"), c.optBoolean("selected")) } })
            },
            phase = o.optString("phase").ifEmpty { "starting" },
            tool = o.optString("tool"),
            lastText = o.optString("lastText"),
            title = o.optString("title"),
            lastActivity = o.optLong("lastActivity").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            resumable = o.optBoolean("resumable"),
            refreshed = o.optString("refreshed"),
            repos = o.optJSONArray("repos")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
            messages = (0 until msgs.length()).map { msgs.getJSONObject(it).let { m -> ChatLine(m.optString("role"), m.optString("text")) } },
            screen = o.optString("screen"),
        )
    }

    private fun account(o: JSONObject) = BridgeAccount(
        id = o.getString("id"),
        name = o.optString("name").ifEmpty { o.getString("id") },
        signedIn = o.optBoolean("signedIn"),
        email = o.optString("email"),
        configDir = o.optString("configDir"),
    )

    companion object {
        private val random = SecureRandom()

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun hmac(key: String, text: String): String =
            hex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }.doFinal(text.toByteArray()))

        /** As the bridge's `signature`: method, path with its query, time, nonce and the body's SHA-256. */
        fun signature(key: String, method: String, path: String, stamp: String, nonce: String, body: ByteArray): String =
            hmac(key, listOf(method, path, stamp, nonce, hex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n"))

        /** As the bridge's `proof`. */
        fun proof(key: String, nonce: String): String = hmac(key, "bridge\n$nonce")

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
