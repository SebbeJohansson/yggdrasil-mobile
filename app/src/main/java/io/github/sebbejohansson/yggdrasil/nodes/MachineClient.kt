package io.github.sebbejohansson.yggdrasil.nodes

import io.github.sebbejohansson.yggdrasil.core.NetMeter
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One machine's Sindri over the tailnet (https://<host>:8772), with that
 * machine's owner token. Blocking: call it off the main thread.
 */
open class MachineClient(private val machine: Machine, private val token: String) {
    private val base = Machines.sindri(machine)

    /** The body of a 2xx answer; anything else is an IOException with Sindri's words. */
    protected open fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 8_000): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
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
                throw IOException("${machine.label} answered $status${if (said.isNotBlank()) ": $said" else ""}")
            }
            return text
        } finally {
            NetMeter.count("nodes", machine.id, moved, (System.nanoTime() - start) / 1_000_000)
            connection.disconnect()
        }
    }

    fun health(): MachineHealth = Machines.parseHealth(call("GET", "/health", timeoutMs = 4_000))

    fun sessions(me: String, now: java.time.Instant) = Machines.deviceSessions(
        machine = machine,
        me = me,
        now = now,
        sessionsJson = call("GET", "/v1/sessions"),
        // Cloud and projects are extras: the sessions show without them.
        cloudJson = runCatching { call("GET", "/v1/cloud") }.getOrNull(),
        projectsJson = runCatching { call("GET", "/v1/projects") }.getOrNull(),
    )

    /** Does one call; for a new cloud session, its link. */
    fun send(c: Machines.Call): String? {
        val answer = call(c.method, c.path, c.body, timeoutMs = 15_000)
        return runCatching { JSONObject(answer).optString("url").ifBlank { null } }.getOrNull()
    }
}
