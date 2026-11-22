package io.github.sebbejohansson.yggdrasil.munin

import io.github.sebbejohansson.yggdrasil.core.Check
import io.github.sebbejohansson.yggdrasil.core.Watch
import io.github.sebbejohansson.yggdrasil.core.WatchState
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Something outside the phone a part depends on. kind: http (any answer is up) or statuspage (its own incidents too). */
data class Target(val name: String, val kind: String, val url: String, val local: Boolean = false)

data class TargetSeen(val target: Target, val up: Boolean, val detail: String? = null, val checkedAt: Instant? = null)

data class MuninStatus(val online: Boolean = true, val targets: List<TargetSeen> = emptyList(), val checks: List<WatchState> = emptyList(), val checkedAt: Instant? = null)

/**
 * Munin, ported from the desktop's munin.go: whether the systems outside
 * the phone that Ygg's parts depend on answer. It fixes nothing and holds
 * no credentials. Reachability is Munin's; a sign-in is the part's that
 * holds it; Ygg's own parts are Ratatoskr's.
 *
 * - down:<name>, warning, "<name> doesn't answer": any HTTP answer counts as
 *   up (even 404 or 500); only no answer at all is down. 5 s (2 s on the phone itself).
 * - incident:<name> for a status page: ok only when it reports none; minor is
 *   info, anything worse a warning.
 * While the phone is offline, the internet's checks are left as they were
 * (skipped), and, unlike the desktop, being offline raises nothing: a phone
 * is offline often and on purpose.
 */
class Munin(
    private val watch: Watch,
    /** The targets now: Mímir only once Sága is set up, Anthropic only with the Claude bridge. */
    private val targets: () -> List<Target>,
    /** The phone has a network that reaches the internet (Android validated it). */
    private val online: () -> Boolean,
    private val fetch: suspend (String, Int) -> Pair<Int, String> = ::get,
) {
    private val _status = MutableStateFlow(MuninStatus())
    val status: StateFlow<MuninStatus> = _status

    suspend fun round(now: Instant = Instant.now()): MuninStatus = coroutineScope {
        val isOnline = runCatching { online() }.getOrDefault(true)
        val list = targets()
        val seen = list.map { t ->
            async {
                if (!t.local && !isOnline) return@async Triple(t, null as Pair<Int, String>?, "offline")
                val r = runCatching { fetch(t.url, if (t.local) 2_000 else 5_000) }
                Triple(t, r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            }
        }.awaitAll()
        val checks = seen.flatMap { (t, answer, error) -> checks(t, answer, error, skip = error == "offline") }
        val states = watch.round(checks, now.toEpochMilli())
        MuninStatus(
            online = isOnline,
            targets = seen.map { (t, answer, error) -> TargetSeen(t, answer != null, error ?: answer?.let { "answered ${it.first}" }, now) },
            checks = states,
            checkedAt = now,
        ).also { _status.value = it }
    }

    companion object {
        /** A target's checks from its answer. Pure, for tests. */
        fun checks(t: Target, answer: Pair<Int, String>?, error: String?, skip: Boolean = false): List<Check> {
            val down = Check("down:${t.name}", answer != null, Severity.WARNING, "${t.name} doesn't answer", error, skip = skip)
            if (t.kind != "statuspage") return listOf(down)
            val incident = if (answer == null) Check("incident:${t.name}", true, skip = true) else statuspage(t.name, answer.second)
            return listOf(down, incident)
        }

        /** A statuspage.io status.json: ok when its indicator is none. */
        fun statuspage(name: String, body: String): Check {
            val status = runCatching { JSONObject(body).getJSONObject("status") }.getOrNull()
                ?: return Check("incident:$name", false, Severity.WARNING, "$name's status page could not be read")
            val indicator = status.optString("indicator", "none")
            return Check(
                "incident:$name", indicator == "none",
                if (indicator == "minor") Severity.INFO else Severity.WARNING,
                "$name reports an incident: ${status.optString("description").ifEmpty { indicator }}",
            )
        }

        /** A GET with Munin's name on it; any HTTP status is an answer. At most 4 MB read. */
        suspend fun get(url: String, timeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "yggdrasil-munin")
            }
            try {
                val code = c.responseCode
                val stream = if (code in 200..399) c.inputStream else c.errorStream
                code to (stream?.use { s -> readAtMost(s, 4 shl 20) } ?: "")
            } finally {
                c.disconnect()
            }
        }

        private fun readAtMost(s: java.io.InputStream, max: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 shl 10)
            while (out.size() < max) {
                val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** What the phone's parts depend on. */
        fun targets(mimirUrl: String?, claude: Boolean): List<Target> = listOfNotNull(
            mimirUrl?.takeIf { it.isNotBlank() }?.let { Target("mimir", "http", it.trimEnd('/') + "/") },
            Target("github", "http", "https://api.github.com/"),
            Target("github-status", "statuspage", "https://www.githubstatus.com/api/v2/status.json"),
            Target("huggingface", "http", "https://huggingface.co/"),
            Target("anthropic", "http", "https://api.anthropic.com/").takeIf { claude },
            Target("anthropic-status", "statuspage", "https://status.anthropic.com/api/v2/status.json").takeIf { claude },
        )
    }
}
