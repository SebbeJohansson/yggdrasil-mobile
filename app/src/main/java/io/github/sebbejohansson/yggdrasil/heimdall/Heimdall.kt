package io.github.sebbejohansson.yggdrasil.heimdall

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import io.github.sebbejohansson.yggdrasil.gjallar.Gjallar
import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.LocalDate

/** One thing Heimdall found. severity: low, medium, high or critical; check: what found it. */
data class Finding(
    val id: String,
    val check: String,
    val severity: String,
    val title: String,
    val detail: String = "",
    val link: String = "",
)

data class HeimdallStatus(
    val findings: List<Finding> = emptyList(),
    val checkedAt: Instant? = null,
    /** Why the repo's findings couldn't be read this time (the last ones are kept). */
    val repoError: String? = null,
    val repoCheckedAt: Instant? = null,
)

/**
 * Heimdall, the security watchman. The desktop's watches the machine it runs
 * on; the phone app ships as an APK built on GitHub, so the repo's checks run
 * there (.github/workflows/heimdall.yml: vulnerable dependencies, keys in
 * commits, the app's own settings) and land in one issue labelled heimdall,
 * which this reads. It adds the phone's own: USB debugging, an old security
 * patch, no screen lock, the Claude bridge answering beyond the phone itself,
 * and the bridge's key and token files readable by others.
 *
 * As the desktop's: findings that matter (high, critical) raise one alert per
 * check, <check>:summary, warning, or critical when one of them is; a check
 * whose findings are gone has its alert resolved. Nothing else is told.
 */
class Heimdall(
    context: Context,
    private val gjallar: Gjallar,
    /** The GitHub token Ygg keeps for updates (the repo is private). */
    private val token: () -> String?,
    private val repo: String,
    /** Ubuntu's files in Ygg's own Linux, for the bridge's key and token. */
    private val rootfs: File,
    /** The bridge's port on the phone. */
    private val bridgePort: Int = 8765,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("heimdall", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(HeimdallStatus())
    val status: StateFlow<HeimdallStatus> = _status

    suspend fun round(now: Instant = Instant.now()): HeimdallStatus = withContext(Dispatchers.IO) {
        val repoRead = runCatching { repoFindings() }
        // A check that can't be read keeps what it found last (as on the desktop).
        val fromRepo = repoRead.getOrNull() ?: kept()
        if (repoRead.isSuccess) keep(fromRepo)
        val all = (fromRepo + phoneFindings(now)).sortedWith(compareBy({ order(it.severity) }, { it.check }, { it.id }))
        syncAlerts(all)
        HeimdallStatus(
            findings = all,
            checkedAt = now,
            repoError = repoRead.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            repoCheckedAt = if (repoRead.isSuccess) now else _status.value.repoCheckedAt,
        ).also { _status.value = it }
    }

    /** The open heimdall issue's findings; none when there's no open issue (all clear). */
    private fun repoFindings(): List<Finding> {
        val c = (URL("https://api.github.com/repos/$repo/issues?labels=heimdall&state=open&per_page=5").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "yggdrasil-heimdall")
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            if (c.responseCode != 200) error("GitHub answered ${c.responseCode}")
            val issues = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val issue = (0 until issues.length()).map { issues.getJSONObject(it) }
                .firstOrNull { it.optString("title") == TITLE && !it.has("pull_request") } ?: return emptyList()
            return parseIssue(issue.optString("body"))
        } finally {
            c.disconnect()
        }
    }

    /** The phone's own checks. */
    private fun phoneFindings(now: Instant): List<Finding> = buildList {
        val cr = app.contentResolver
        if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1) {
            add(Finding("phone:adb", "phone", "medium", "USB debugging is on",
                "Anything plugged in and allowed can read Ygg's data. Turn it off in Developer options when you're not using it."))
        }
        patchAge(Build.VERSION.SECURITY_PATCH, now.atZone(java.time.ZoneId.systemDefault()).toLocalDate())?.let { days ->
            if (days > 90) add(Finding("phone:patch", "phone", if (days > 180) "high" else "medium",
                "The phone's security patch is $days days old", "Android ${Build.VERSION.RELEASE}, patch ${Build.VERSION.SECURITY_PATCH}: look for a system update."))
        }
        if (!app.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            add(Finding("phone:lock", "phone", "high", "The phone has no screen lock",
                "Ygg keeps tokens and your memory on the phone; a lock keeps them yours."))
        }
        exposedOn().forEach { ip ->
            add(Finding("phone:bridge-exposed:$ip", "phone", "critical", "The Claude bridge answers on $ip",
                "It should listen on 127.0.0.1 only: anything on this network could reach it. Restart it from Settings > Claude bridge."))
        }
        for (name in listOf("key", "github")) {
            val f = File(rootfs, "root/.ygg-bridge/$name")
            val perms = runCatching { Files.getPosixFilePermissions(f.toPath()) }.getOrNull() ?: continue
            val others = perms.any { it == PosixFilePermission.OTHERS_READ || it == PosixFilePermission.OTHERS_WRITE }
            val group = perms.any { it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.GROUP_WRITE }
            if (others || group) add(Finding("phone:bridge-file:$name", "phone", if (others) "high" else "medium",
                "The bridge's ${if (name == "key") "key" else "GitHub token"} file is readable by ${if (others) "others" else "its group"}",
                "chmod 600 /root/.ygg-bridge/$name in the Shell"))
        }
    }

    /** The phone's own addresses the bridge's port answers on, beyond loopback. */
    private fun exposedOn(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { a ->
                val ip = a.hostAddress ?: return@mapNotNull null
                runCatching { Socket().use { s -> s.connect(InetSocketAddress(a, bridgePort), 300) }; ip }.getOrNull()
            }
    }.getOrDefault(emptyList())

    private suspend fun syncAlerts(findings: List<Finding>) {
        val wanted = summaries(findings)
        val raised = prefs.getStringSet("raised", null) ?: runCatching {
            // First round: adopt what Heimdall has open in Gjallar from before.
            (gjallar as? io.github.sebbejohansson.yggdrasil.gjallar.GjallarService)?.active(1000)
                ?.filter { it.source == SOURCE }?.map { it.key }?.toSet()
        }.getOrNull() ?: emptySet()
        val now = mutableSetOf<String>()
        wanted.forEach { s ->
            if (runCatching { gjallar.raise(SOURCE, s.key, s.severity, s.title, s.detail) }.isSuccess) now += s.key
        }
        (raised - wanted.map { it.key }.toSet()).forEach { k ->
            if (runCatching { gjallar.resolve(SOURCE, k) }.isFailure) now += k
        }
        prefs.edit { putStringSet("raised", now) }
    }

    private fun keep(f: List<Finding>) = prefs.edit { putString("repo", encode(f)) }
    private fun kept(): List<Finding> = runCatching { decode(prefs.getString("repo", "[]") ?: "[]") }.getOrDefault(emptyList())

    data class Summary(val key: String, val severity: Severity, val title: String, val detail: String)

    companion object {
        const val SOURCE = "heimdall"
        const val TITLE = "Heimdall: security findings"
        private val MARKER = Regex("<!--\\s*heimdall-findings\\s*\\n(.*?)\\n\\s*-->", RegexOption.DOT_MATCHES_ALL)

        fun order(severity: String) = when (severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }

        /** The findings in the issue's JSON block. Pure, for tests. */
        fun parseIssue(body: String): List<Finding> {
            val json = MARKER.find(body)?.groupValues?.get(1) ?: return emptyList()
            return decode(json).map { it.copy(check = if (it.check.isNotEmpty()) it.check else "repo") }
        }

        fun decode(json: String): List<Finding> {
            val a = JSONArray(json)
            return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Finding(o.getString("id"), o.optString("kind", o.optString("check")), o.optString("severity", "medium"),
                    o.optString("title"), o.optString("detail"), o.optString("link"))
            }
        }

        fun encode(f: List<Finding>): String = JSONArray(f.map {
            org.json.JSONObject().put("id", it.id).put("kind", it.check).put("severity", it.severity).put("title", it.title)
                .put("detail", it.detail).put("link", it.link)
        }).toString()

        /** Days since the patch level's date, or null when Android doesn't give one. */
        fun patchAge(patch: String?, today: LocalDate): Long? = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(patch), today)
        }.getOrNull()

        /**
         * One alert per check for its findings that matter (high, critical), as
         * the desktop's <check>:summary: warning, critical when one is.
         * Pure, for tests.
         */
        fun summaries(findings: List<Finding>): List<Summary> =
            findings.filter { it.severity == "high" || it.severity == "critical" }.groupBy { it.check }.map { (check, fs) ->
                val sorted = fs.sortedBy { order(it.severity) }
                val first = sorted.first().title
                val title = if (fs.size == 1) "Heimdall: $first" else "Heimdall: ${fs.size} $check findings, among them $first"
                val lines = sorted.take(10).map { "- ${it.title}" } + listOfNotNull((fs.size - 10).takeIf { it > 0 }?.let { "… and $it more on Heimdall's report." })
                Summary("$check:summary", if (fs.any { it.severity == "critical" }) Severity.CRITICAL else Severity.WARNING,
                    title.take(200), lines.joinToString("\n").take(2000))
            }
    }
}
