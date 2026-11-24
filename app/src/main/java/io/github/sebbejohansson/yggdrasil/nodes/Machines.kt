package io.github.sebbejohansson.yggdrasil.nodes

import io.github.sebbejohansson.yggdrasil.saga.DeviceSessions
import io.github.sebbejohansson.yggdrasil.saga.RemoteChoice
import io.github.sebbejohansson.yggdrasil.saga.RemoteCloud
import io.github.sebbejohansson.yggdrasil.saga.RemoteDialog
import io.github.sebbejohansson.yggdrasil.saga.RemoteProject
import io.github.sebbejohansson.yggdrasil.saga.RemoteSession
import io.github.sebbejohansson.yggdrasil.saga.SessionAsk
import io.github.sebbejohansson.yggdrasil.saga.SessionKinds
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/**
 * Your machines reached directly over the tailnet, as the desktop widget's
 * nodes (my-widget's nodes.json, Yggdrasil's docs/tailscale.md): each one's
 * parts at https://<its tailnet name>:<port>, published there by
 * scripts/tailscale-serve.sh, asked with that machine's owner token.
 *
 * Mímir carries what a machine last sent and what you ask, for when it is
 * off; a machine reached directly answers now, and does what you ask now.
 * Pure: the HTTP is in [MachineClient], the storage in [MachineSettings].
 */
data class Machine(
    /** Short and stable, as the widget's ids: letters, digits and dashes. */
    val id: String,
    /** How the Code tab names it: "VPS", "Home PC". */
    val label: String,
    /** Its name on the tailnet: vps.tail1234.ts.net. */
    val host: String,
)

/** What a machine's /health says, for the list under Settings → Connection. */
data class MachineHealth(val version: String?, val work: Boolean)

/** How a machine answered the last time it was asked. */
data class MachineStatus(
    val machine: Machine,
    val health: MachineHealth? = null,
    /** Null when it answered. */
    val error: String? = null,
    /** Left out: it says it is a work machine, which is never driven from here. */
    val refused: Boolean = false,
)

object Machines {
    /** The parts' ports, as Yggdrasil's: only Sindri's is asked from the phone so far. */
    const val SINDRI_PORT = 8772

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val HOST = Regex("^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** The id for a label: "Home PC" → "home-pc". */
    fun idFor(label: String): String =
        label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "machine" }

    /** A machine as typed, or why it can't be one. */
    fun check(label: String, host: String, token: String): Result<Machine> {
        val name = label.trim()
        val id = idFor(name)
        val h = host.trim().lowercase().removePrefix("https://").substringBefore('/').substringBefore(':').trimEnd('.')
        return when {
            name.isEmpty() -> Result.failure(IllegalArgumentException("Give it a name, like VPS"))
            !ID.matches(id) -> Result.failure(IllegalArgumentException("The name needs a letter or digit"))
            !HOST.matches(h) -> Result.failure(IllegalArgumentException("The tailnet name looks like vps.tail1234.ts.net"))
            token.isBlank() -> Result.failure(IllegalArgumentException("Its owner token is needed (~/.config/yggdrasil/owner.token there)"))
            else -> Result.success(Machine(id, name, h))
        }
    }

    fun sindri(machine: Machine): String = "https://${machine.host}:$SINDRI_PORT"

    fun parseHealth(json: String): MachineHealth = JSONObject(json).let {
        MachineHealth(version = it.str("version"), work = it.optBoolean("work"))
    }

    private const val LAST_TEXT_MAX = 600

    /**
     * A machine's sessions from its own Sindri, in the shape Mímir carries
     * them (`sindri.sessions`), so the Code tab draws both alike. The same
     * mapping as the desktop's Sága (saga/sessions.go readSessions): Claude
     * Code's sessions, each joined to the herdr pane it runs in, then herdr's
     * panes with no Claude session, newest first. [me] is in driveFrom: the
     * owner token drives it, with no SAGA_DRIVE_FROM between.
     */
    fun deviceSessions(
        machine: Machine,
        me: String,
        now: Instant,
        sessionsJson: String,
        cloudJson: String?,
        projectsJson: String?,
    ): DeviceSessions {
        val view = JSONObject(sessionsJson)
        val errors = linkedMapOf<String, String>()
        val claude = view.optJSONObject("claude") ?: JSONObject()
        val herdr = view.optJSONObject("herdr") ?: JSONObject()
        claude.str("error")?.let { errors["claude"] = it }
        val herdrFrom = herdr.str("from")
        val herdrError = herdr.str("error")
        if (herdrError != null && herdrFrom != "off") errors["herdr"] = herdrError

        val panes = herdr.optJSONArray("sessions").objects()
        val claimed = mutableSetOf<String>()
        val sessions = mutableListOf<RemoteSession>()
        for (c in claude.optJSONArray("sessions").objects()) {
            val id = c.str("sessionId") ?: continue
            val pid = c.optInt("pid")
            val state = c.optJSONObject("state")
            val permission = c.optJSONObject("permission")?.let { it.str("tool") ?: "a tool" }
            var session = RemoteSession(
                id = id,
                kind = "claude",
                name = c.str("name") ?: c.str("directory")?.substringAfterLast('/') ?: id,
                directory = c.str("directory"),
                lastActivity = c.optLong("lastActivity").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                phase = state?.str("phase"),
                tool = state?.str("tool"),
                lastText = state?.str("lastText")?.let { clip(it, LAST_TEXT_MAX) },
                permission = permission,
                bridgeSessionId = c.str("bridgeSessionId"),
                pane = null,
                dialog = null,
            )
            val pane = panes.firstOrNull { h ->
                val paneId = h.str("paneId") ?: return@firstOrNull false
                paneId !in claimed && (h.str("sessionId") == id || (pid != 0 && h.optJSONArray("pids").ints().contains(pid)))
            }
            if (pane != null) {
                claimed += pane.str("paneId")!!
                session = withDialog(session.copy(pane = pane.str("paneId")), pane)
            }
            sessions += session
        }
        for (h in panes) {
            val paneId = h.str("paneId") ?: continue
            if (paneId in claimed) continue
            sessions += withDialog(
                RemoteSession(
                    id = "herdr:$paneId",
                    kind = h.str("kind") ?: "agent",
                    name = h.str("name") ?: h.str("title") ?: paneId,
                    directory = h.str("cwd"),
                    lastActivity = null,
                    phase = herdrPhase(h.str("status")),
                    tool = null,
                    lastText = null,
                    permission = null,
                    bridgeSessionId = null,
                    pane = paneId,
                    dialog = null,
                ),
                h,
            )
        }
        sessions.sortByDescending { it.lastActivity ?: Instant.EPOCH }

        val cloud = cloudJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            o.str("off")?.let { errors["cloud"] = it }
            o.optJSONArray("sessions").objects().mapNotNull { c ->
                val id = c.str("id") ?: return@mapNotNull null
                RemoteCloud(
                    id = id,
                    at = Instant.ofEpochMilli(c.optLong("at")),
                    cwd = c.str("cwd") ?: "",
                    task = c.str("task") ?: "",
                    url = c.str("url") ?: "https://claude.ai/code/$id",
                    lastError = c.str("lastError"),
                    teleportedAt = c.optLong("teleportedAt").takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                )
            }
        } ?: emptyList()
        val projects = projectsJson?.let { runCatching { JSONArray(it) }.getOrNull() }.objects().mapNotNull { p ->
            val path = p.str("path") ?: return@mapNotNull null
            if (!path.startsWith("/")) null else RemoteProject(p.str("name") ?: path.substringAfterLast('/'), path)
        }

        return DeviceSessions(
            device = machine.id,
            heardAt = now,
            sessions = sessions,
            cloud = cloud,
            projects = projects,
            driveFrom = listOf(me),
            herdr = herdrFrom != "off" && herdrError == null,
            errors = errors,
            label = machine.label,
            direct = true,
        )
    }

    /** One request against a machine's Sindri, as its own Sága carries it out (saga/sessions.go carryOut). */
    data class Call(val method: String, val path: String, val body: JSONObject?)

    /**
     * The call that does [ask] on the machine, or why it can't be done.
     * [pane] is the herdr pane the asked session runs in, as the machine
     * last listed it: only a session in one can be typed into or answered.
     */
    fun call(ask: SessionAsk, pane: String?): Result<Call> {
        fun bad(why: String) = Result.failure<Call>(IllegalArgumentException(why))
        return when (ask.kind) {
            SessionKinds.PROMPT -> {
                val target = pane ?: return bad("this session is not in a herdr pane there, so it can't be typed into from here; open it with Remote Control instead")
                val text = ask.text?.takeIf { it.isNotBlank() } ?: return bad("nothing to send")
                Result.success(Call("POST", "/v1/sessions/herdr/${enc(target)}/prompt", JSONObject().put("text", text)))
            }
            SessionKinds.CHOOSE -> {
                val target = pane ?: return bad("only a dialog in a herdr pane can be answered from here")
                Result.success(Call("POST", "/v1/sessions/herdr/${enc(target)}/choose",
                    JSONObject().put("question", ask.question ?: "").put("key", ask.key ?: "").put("label", ask.label ?: "")))
            }
            SessionKinds.RESUME -> {
                val cwd = ask.cwd?.takeIf { it.startsWith("/") } ?: return bad("no folder to resume in")
                val id = ask.session ?: return bad("no session to resume")
                Result.success(Call("POST", "/v1/sessions/herdr", JSONObject().put("kind", "claude").put("cwd", cwd).put("resume", id)))
            }
            SessionKinds.CLOUD_START -> {
                val cwd = ask.cwd?.takeIf { it.startsWith("/") } ?: return bad("no project to start in")
                val task = ask.task?.takeIf { it.isNotBlank() } ?: return bad("no task")
                Result.success(Call("POST", "/v1/cloud", JSONObject().put("cwd", cwd).put("task", task)))
            }
            SessionKinds.CLOUD_MESSAGE -> {
                val id = ask.session ?: return bad("no cloud session")
                val text = ask.text?.takeIf { it.isNotBlank() } ?: return bad("nothing to send")
                Result.success(Call("POST", "/v1/cloud/${enc(id)}/message", JSONObject().put("text", text)))
            }
            SessionKinds.CLOUD_TELEPORT -> {
                val id = ask.session ?: return bad("no cloud session")
                Result.success(Call("POST", "/v1/cloud/${enc(id)}/teleport", JSONObject()))
            }
            else -> bad("not something a machine does: ${ask.kind}")
        }
    }

    private fun withDialog(session: RemoteSession, pane: JSONObject): RemoteSession {
        val screen = pane.optJSONObject("dialogScreen")
        if (pane.str("status") != "blocked" || screen == null) return session
        val question = clip(screen.str("question") ?: "", 300)
        return session.copy(
            phase = "blocked",
            dialog = RemoteDialog(
                question = question,
                detail = screen.str("detail")?.let { clip(it, 600) },
                choices = screen.optJSONArray("choices").objects().mapNotNull { c ->
                    val key = c.str("key") ?: return@mapNotNull null
                    RemoteChoice(key, clip(c.str("label") ?: key, 120))
                },
            ),
        )
    }

    private fun herdrPhase(status: String?): String? = when (status) {
        "working" -> "busy"
        "blocked" -> "blocked"
        "idle", "done" -> "turn-ended"
        else -> null
    }

    private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) (opt(k) as? String)?.ifEmpty { null } else null
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { optInt(it) }
}
