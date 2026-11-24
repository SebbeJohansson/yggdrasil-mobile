package io.github.sebbejohansson.yggdrasil.saga

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Coding sessions on your machines (Mímir's docs/events.md, "Coding
 * sessions"). Each machine that runs Sindri and Sága sends its sessions as
 * `sindri.sessions`; the phone only reads them, and asks things of them.
 */
object SessionKinds {
    const val SESSIONS = "sindri.sessions"
    const val PROMPT = "sindri.session.prompt"
    const val CHOOSE = "sindri.session.choose"
    const val RESUME = "sindri.session.resume"
    const val CLOUD_START = "sindri.cloud.start"
    const val CLOUD_MESSAGE = "sindri.cloud.message"
    const val CLOUD_TELEPORT = "sindri.cloud.teleport"
    const val DONE = "sindri.request.done"
}

data class RemoteChoice(val key: String, val label: String)

data class RemoteDialog(val question: String, val detail: String?, val choices: List<RemoteChoice>)

data class RemoteSession(
    val id: String,
    val kind: String,
    val name: String,
    val directory: String?,
    val lastActivity: Instant?,
    /** busy, turn-ended or blocked. */
    val phase: String?,
    val tool: String?,
    val lastText: String?,
    /** The tool a permission prompt waits on. */
    val permission: String?,
    /** Set when Remote Control is on: [claudeLink] opens it in the Claude app. */
    val bridgeSessionId: String?,
    /** Its herdr pane: only a session in one can be typed into from here. */
    val pane: String?,
    val dialog: RemoteDialog?,
) {
    val waiting: Boolean get() = permission != null || dialog != null || phase == "blocked"
    val working: Boolean get() = !waiting && phase == "busy"
    val claudeLink: String? get() = bridgeSessionId?.let { "https://claude.ai/code/$it" }
    val folder: String get() = directory?.trimEnd('/')?.substringAfterLast('/') ?: ""
}

data class RemoteCloud(
    val id: String,
    val at: Instant,
    val cwd: String,
    val task: String,
    val url: String,
    val lastError: String?,
    val teleportedAt: Instant?,
)

data class RemoteProject(val name: String, val path: String)

/** What one machine last sent: the whole truth about its sessions. */
data class DeviceSessions(
    val device: String,
    /** When it last sent them. It sends at least every 5 minutes while on. */
    val heardAt: Instant,
    val sessions: List<RemoteSession>,
    val cloud: List<RemoteCloud>,
    val projects: List<RemoteProject>,
    val driveFrom: List<String>,
    val herdr: Boolean,
    val errors: Map<String, String>,
    /** How to name it, for a machine reached directly (nodes/Machines.kt); [device] otherwise. */
    val label: String? = null,
    /** Read just now from the machine itself over the tailnet, not what it last sent through Mímir. */
    val direct: Boolean = false,
) {
    /** Whether it carries out what [me] asks. */
    fun drivableBy(me: String) = me in driveFrom

    fun quiet(now: Instant) = !direct && Duration.between(heardAt, now) > QUIET_AFTER

    /** The name the Code tab shows. */
    val title: String get() = label ?: device.replaceFirstChar { it.uppercase() }

    companion object {
        val QUIET_AFTER: Duration = Duration.ofMinutes(12)

        /** Null when it isn't a body this app understands. */
        fun parse(device: String, json: String, eventAt: Long): DeviceSessions? = runCatching {
            val o = JSONObject(json)
            val at = o.optLong("at").takeIf { it > 0 } ?: eventAt
            DeviceSessions(
                device = device,
                heardAt = Instant.ofEpochMilli(at),
                sessions = o.optJSONArray("sessions").objects().mapNotNull(::session),
                cloud = o.optJSONArray("cloud").objects().mapNotNull(::cloud),
                projects = o.optJSONArray("projects").objects().mapNotNull { p ->
                    val path = p.str("path") ?: return@mapNotNull null
                    if (!path.startsWith("/")) null else RemoteProject(p.str("name") ?: path.substringAfterLast('/'), path)
                },
                driveFrom = o.optJSONArray("driveFrom").strings(),
                herdr = o.optBoolean("herdr"),
                errors = o.optJSONObject("errors")?.let { e -> e.keys().asSequence().mapNotNull { k -> e.str(k)?.let { k to it } }.toMap() } ?: emptyMap(),
            )
        }.getOrNull()

        private fun session(s: JSONObject): RemoteSession? {
            val id = s.str("id") ?: return null
            val directory = s.str("directory")
            return RemoteSession(
                id = id,
                kind = s.str("kind") ?: "claude",
                name = s.str("name") ?: directory?.substringAfterLast('/') ?: id,
                directory = directory,
                lastActivity = s.optLong("lastActivity").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                phase = s.str("phase"),
                tool = s.str("tool"),
                lastText = s.str("lastText"),
                permission = s.str("permission"),
                bridgeSessionId = s.str("bridgeSessionId"),
                pane = s.str("pane"),
                dialog = s.optJSONObject("dialog")?.let { d ->
                    val question = d.str("question") ?: return@let null
                    RemoteDialog(
                        question,
                        d.str("detail"),
                        d.optJSONArray("choices").objects().mapNotNull { c ->
                            val key = c.str("key") ?: return@mapNotNull null
                            val label = c.str("label") ?: return@mapNotNull null
                            RemoteChoice(key, label)
                        },
                    )
                },
            )
        }

        private fun cloud(c: JSONObject): RemoteCloud? {
            val id = c.str("id") ?: return null
            return RemoteCloud(
                id = id,
                at = Instant.ofEpochMilli(c.optLong("at")),
                cwd = c.str("cwd") ?: "",
                task = c.str("task") ?: "",
                url = c.str("url") ?: "https://claude.ai/code/$id",
                lastError = c.str("lastError"),
                teleportedAt = c.optLong("teleportedAt").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            )
        }
    }
}

/** What you ask of another machine's session. Only [origin] carries it out. */
data class SessionAsk(
    val id: String,
    val kind: String,
    val origin: String,
    val session: String? = null,
    val text: String? = null,
    val question: String? = null,
    val key: String? = null,
    val label: String? = null,
    val cwd: String? = null,
    val task: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("origin", origin)
        session?.let { put("session", it) }
        text?.let { put("text", it) }
        question?.let { put("question", it) }
        key?.let { put("key", it) }
        label?.let { put("label", it) }
        cwd?.let { put("cwd", it) }
        task?.let { put("task", it) }
    }

    companion object {
        fun parse(kind: String, json: String): SessionAsk? = runCatching {
            val o = JSONObject(json)
            SessionAsk(
                id = o.getString("id"), kind = kind, origin = o.getString("origin"),
                session = o.str("session"), text = o.str("text"), question = o.str("question"),
                key = o.str("key"), label = o.str("label"), cwd = o.str("cwd"), task = o.str("task"),
            )
        }.getOrNull()
    }
}

enum class AskState { WAITING, DONE, FAILED }

/** A request you made from this phone, and how it went. */
data class Asked(
    val ask: SessionAsk,
    val at: Instant,
    val state: AskState = AskState.WAITING,
    val error: String? = null,
    val url: String? = null,
) {
    /** "Sent "yes" · waiting for laptop". */
    val line: String get() {
        val what = when (ask.kind) {
            SessionKinds.PROMPT, SessionKinds.CLOUD_MESSAGE -> "Sent \"${short(ask.text)}\""
            SessionKinds.CHOOSE -> "Chose \"${short(ask.label)}\""
            SessionKinds.RESUME -> "Resume"
            SessionKinds.CLOUD_START -> "Cloud session \"${short(ask.task)}\""
            SessionKinds.CLOUD_TELEPORT -> "Bring home"
            else -> ask.kind
        }
        return when (state) {
            AskState.WAITING -> "$what · waiting for ${ask.origin}"
            AskState.DONE -> "$what · done"
            AskState.FAILED -> "$what · not done: ${error ?: "no reason given"}"
        }
    }

    private fun short(text: String?, max: Int = 40): String {
        val t = (text ?: "").replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max - 1) + "…" else t
    }
}

/** The body of `sindri.request.done`, sent by the origin to [to]. */
data class RequestDone(val id: String, val to: String, val ok: Boolean, val error: String?, val url: String?) {
    companion object {
        fun parse(json: String): RequestDone? = runCatching {
            val o = JSONObject(json)
            RequestDone(o.getString("id"), o.getString("to"), o.optBoolean("ok"), o.str("error"), o.str("url"))
        }.getOrNull()
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? String) }

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) (opt(k) as? String)?.ifEmpty { null } else null
