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
        val answer = call("sindri", c.method, c.path, c.body, timeoutMs = c.timeoutMs)
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

    /**
     * Clones a GitHub repo into Sindri's projects folder there (POST /v1/projects {repo}),
     * where Sindri, its goals and this tab look. Sindri gives a clone up to five minutes,
     * so this reads for six.
     */
    fun cloneProject(repo: String) {
        call("sindri", "POST", "/v1/projects", JSONObject().put("repo", repo), timeoutMs = 180_000)
    }

    /** What the machine runs and its last upgrade from a dashboard (Sindri's GET /v1/upgrade). */
    fun upgradeStatus(): UpgradeStatus = Upgrades.parseStatus(call("sindri", "GET", "/v1/upgrade"))

    /** Starts `ygg upgrade [version]` there (POST /v1/upgrade): it answers at once, the upgrade runs on. */
    fun startUpgrade(version: String): UpgradeRun =
        Upgrades.parseRun(JSONObject(call("sindri", "POST", "/v1/upgrade", JSONObject().put("version", version), timeoutMs = 15_000)))

    /** Its projects' cleanup: what can go and the last run (Sindri's GET /v1/cleanup). Sizes take a moment on big trees. */
    fun cleanup(): CleanupStatus = Cleanup.parseStatus(call("sindri", "GET", "/v1/cleanup", timeoutMs = 30_000))

    /** Cleans the quiet projects now, or [project] however recent (POST /v1/cleanup). */
    fun cleanNow(project: String? = null): CleanupRun = Cleanup.parseRun(JSONObject(call(
        "sindri", "POST", "/v1/cleanup", JSONObject().apply { project?.let { put("project", it) } }, timeoutMs = 60_000,
    )))

    /** Starts a draft: one coding agent per project. */
    fun startGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/start", JSONObject(), timeoutMs = 15_000) }

    /** A goal whose parts wait on Claude usage tries again now. */
    fun resumeGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/resume", JSONObject()) }

    /** Closes a goal without merging the rest (its unmerged branches go); reopen brings it back. */
    fun closeGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/close", JSONObject(), timeoutMs = 30_000) }

    fun reopenGoal(id: Long) { call("sindri", "POST", "/v1/goals/$id/reopen", JSONObject()) }

    /** Starts a part's repo again with a new agent, from the newest base. */
    fun restartPart(id: Long, repo: String) {
        call("sindri", "POST", "/v1/goals/$id/parts/${java.net.URLEncoder.encode(repo, "UTF-8").replace("+", "%20")}/restart", JSONObject(), timeoutMs = 30_000)
    }

    /** A goal part's agent: status, last words, what it waits on you for. */
    fun agent(id: String): AgentDetail = GoalWork.parseAgent(call("sindri", "GET", "/v1/agents/${enc(id)}"))

    /** A reply to a goal part's agent; waits out a busy turn (queue), and resumes a finished one. */
    fun promptAgent(id: String, text: String) {
        call("sindri", "POST", "/v1/agents/${enc(id)}/prompt", JSONObject().put("text", text).put("queue", true), timeoutMs = 30_000)
    }

    /** Allows or denies a tool the agent waits on. */
    fun allowTool(id: String, requestId: String, allow: Boolean) {
        call("sindri", "POST", "/v1/agents/${enc(id)}/permissions/${enc(requestId)}", JSONObject().put("allow", allow))
    }

    /** Answers the agent's questions, by question text. */
    fun answerQuestions(id: String, requestId: String, answers: Map<String, String>) {
        call("sindri", "POST", "/v1/agents/${enc(id)}/permissions/${enc(requestId)}", JSONObject().put("answers", JSONObject(answers)))
    }

    /** A part's branch: commits, files and diff. */
    fun review(id: String): GoalReview = GoalWork.parseReview(call("sindri", "GET", "/v1/agents/${enc(id)}/review", timeoutMs = 20_000))

    /** Merges the part's branch into the branch it was cut from, in that machine's checkout. */
    fun mergePart(id: String) { call("sindri", "POST", "/v1/agents/${enc(id)}/merge", JSONObject(), timeoutMs = 60_000) }

    /** Pushes the part's branch and opens a GitHub pull request; its link. */
    fun pullRequest(id: String): String? =
        JSONObject(call("sindri", "POST", "/v1/agents/${enc(id)}/pull-request", JSONObject(), timeoutMs = 90_000)).optString("url").ifBlank { null }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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
