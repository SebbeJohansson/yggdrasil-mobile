package io.github.sebbejohansson.yggdrasil.nodes

import io.github.sebbejohansson.yggdrasil.core.NetMeter
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One machine's parts over the tailnet (https://<host>:<the part's port>,
 * Machines.PORTS), with that machine's owner token. Blocking: call it off
 * the main thread.
 */
open class MachineClient(private val machine: Machine, private val token: String) {
    /** The body of a 2xx answer from [part]; anything else is an IOException with the part's words. */
    protected open fun call(part: String, method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 8_000): String {
        val connection = URL(Machines.part(machine, part) + path).openConnection() as HttpURLConnection
        val start = System.nanoTime()
        var moved = (body?.toString()?.length ?: 0).toLong()
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs * 2
            if (path != "/health") connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            moved += text.length
            if (status !in 200..299) {
                val said = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: text.take(200)
                throw IOException("${machine.label}'s $part answered $status${if (said.isNotBlank()) ": $said" else ""}")
            }
            return text
        } finally {
            NetMeter.count("nodes", machine.id, moved, (System.nanoTime() - start) / 1_000_000)
            connection.disconnect()
        }
    }

    /** Sindri's /health: every machine that publishes anything runs Sindri. */
    fun health(): MachineHealth = Machines.parseHealth(call("sindri", "GET", "/health", timeoutMs = 4_000))

    fun sessions(me: String, now: java.time.Instant) = Machines.deviceSessions(
        machine = machine,
        me = me,
        now = now,
        sessionsJson = call("sindri", "GET", "/v1/sessions"),
        // Cloud and projects are extras: the sessions show without them.
        cloudJson = runCatching { call("sindri", "GET", "/v1/cloud") }.getOrNull(),
        projectsJson = runCatching { call("sindri", "GET", "/v1/projects") }.getOrNull(),
    )

    /** Does one call on Sindri; for a new cloud session, its link. */
    fun send(c: Machines.Call): String? {
        val answer = call("sindri", c.method, c.path, c.body, timeoutMs = 15_000)
        return runCatching { JSONObject(answer).optString("url").ifBlank { null } }.getOrNull()
    }

    /**
     * A message for its Norn (POST /v1/ask), which answers it or hands it on,
     * as the desktop's ask window does. [session] keeps the conversation going
     * there. A model can take a while, so this waits up to two minutes.
     */
    fun ask(question: String, session: String): String {
        val body = JSONObject().put("question", question.take(4000)).put("session", session)
        return JSONObject(call("norn", "POST", "/v1/ask", body, timeoutMs = 60_000)).optString("answer")
    }

    /** Its goals (Sindri's GET /v1/goals). */
    fun goals(): List<MachineGoal> = Goals.parse(call("sindri", "GET", "/v1/goals"))

    /** A new draft goal in [repos]; [source] and [context] are an imported candidate's. */
    fun createGoal(title: String, repos: List<String>, source: String = "manual", context: String = ""): MachineGoal? {
        val body = JSONObject().put("title", title.trim()).put("repos", org.json.JSONArray(repos)).put("source", source)
        if (context.isNotBlank()) body.put("context", context.trim())
        return Goals.parseOne(call("sindri", "POST", "/v1/goals", body))
    }

    /**
     * What could become a goal there (Sindri's GET /v1/goals/candidates):
     * [from] is todo, linear or github; [project], [query] and [assignee] narrow it.
     */
    fun candidates(from: String, project: String?, query: String, assignee: String): List<GoalCandidate> {
        val params = mutableListOf("from" to from)
        project?.let { params += "project" to it }
        if (from != "todo") {
            query.trim().ifEmpty { null }?.let { params += "query" to it }
            params += "assignee" to assignee
        }
        val path = "/v1/goals/candidates?" + params.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        return Goals.parseCandidates(call("sindri", "GET", path, timeoutMs = 15_000))
    }

    /** Starts a draft: one coding agent per project. */
    fun startGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/start", JSONObject(), timeoutMs = 15_000) }

    /** A goal whose parts wait on Claude usage tries again now. */
    fun resumeGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/resume", JSONObject()) }

    /**
     * The end of a Claude Code session's transcript there (Yggdrasil's
     * GET /v1/sessions/claude/{id}/transcript). With [since] as the last read
     * said and the file unchanged, the text is left out: a reader polling it
     * moves a few bytes while nothing happens.
     */
    fun transcript(session: String, directory: String?, tail: Int, since: String?): RemoteTranscript {
        val query = buildString {
            append("?tail=").append(tail)
            directory?.let { append("&dir=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            since?.let { append("&since=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
        val o = JSONObject(call("sindri", "GET", "/v1/sessions/claude/${java.net.URLEncoder.encode(session, "UTF-8")}/transcript$query", timeoutMs = 15_000))
        return RemoteTranscript(o.optLong("size"), o.optLong("modified"), o.optString("text"), o.optBoolean("unchanged"))
    }

    /** Every part, as its Ratatoskr sees them. */
    fun parts(): List<PartState> = Machines.parseStatus(call("ratatoskr", "GET", "/v1/status"))

    /** Its open alerts, from its Gjallar. */
    fun alerts(): List<DirectAlert> = Machines.parseAlerts(call("gjallar", "GET", "/v1/alerts"))

    fun ackAlert(id: Long) { call("gjallar", "POST", "/v1/alerts/$id/ack", JSONObject()) }

    fun snoozeAlert(id: Long, until: java.time.Instant) {
        call("gjallar", "POST", "/v1/alerts/$id/snooze", JSONObject().put("until", until.toString()))
    }

    fun muteAlert(source: String, key: String, muted: Boolean) {
        call("gjallar", "POST", "/v1/mutes", JSONObject().put("source", source).put("key", key).put("muted", muted))
    }
}
