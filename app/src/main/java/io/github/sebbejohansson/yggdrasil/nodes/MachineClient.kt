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
