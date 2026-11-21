package io.github.sebbejohansson.yggdrasil.saga

import io.github.sebbejohansson.yggdrasil.core.NetMeter
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One event read back from Mímir. [body] is JSON text; [at] is when the sending device made it, epoch millis. */
data class MimirEvent(val pos: Long, val device: String, val seq: Long, val kind: String, val body: String, val at: Long = 0)

/** One event waiting to go to Mímir. [body] is JSON text. */
data class Outgoing(val seq: Long, val at: Long, val kind: String, val body: String)

sealed interface Appended {
    /** Mímir holds our events up to [lastSeq]. */
    data class Ok(val lastSeq: Long) : Appended

    /** Mímir holds fewer of ours than we sent after: send on from [lastSeq] + 1. */
    data class Gap(val lastSeq: Long) : Appended
}

sealed interface Page {
    data class Events(val events: List<MimirEvent>, val last: Long, val more: Boolean) : Page

    /** Our mark is outside what Mímir holds: start again from its snapshot. */
    data object Gone : Page
}

/** Mímir, the event log on the VPS (github.com/SebbeJohansson/mimir, api/openapi.yaml). */
interface MimirClient {
    fun lastSeq(): Long
    fun append(events: List<Outgoing>): Appended
    fun read(since: Long, from: String): Page

    /** Where the snapshot leaves off; 0 when there is none. */
    fun snapshotPos(): Long
}

/** Over HTTPS with [token]. Blocking: call it off the main thread. */
class HttpMimirClient(baseUrl: String, private val token: String) : MimirClient {
    private val base = baseUrl.trimEnd('/')

    private class Answer(val status: Int, val body: String)

    private fun call(method: String, path: String, body: String? = null): Answer {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        val start = System.nanoTime()
        var moved = (body?.length ?: 0).toLong()
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            moved += text.length
            if (status >= 300 && status != 409 && status != 410 && status != 404) {
                throw IOException("Mímir answered $status: ${text.take(200)}")
            }
            return Answer(status, text)
        } finally {
            NetMeter.count("saga", "mimir", moved, (System.nanoTime() - start) / 1_000_000)
            connection.disconnect()
        }
    }

    override fun lastSeq(): Long = JSONObject(call("GET", "/v1/me").body).getLong("lastSeq")

    override fun append(events: List<Outgoing>): Appended {
        // Bodies are already JSON; put them in as they are.
        val list = events.joinToString(",", "[", "]") {
            """{"seq":${it.seq},"at":${it.at},"kind":${JSONObject.quote(it.kind)},"body":${it.body}}"""
        }
        val answer = call("POST", "/v1/events", """{"events":$list}""")
        val lastSeq = JSONObject(answer.body).getLong("lastSeq")
        return if (answer.status == 409) Appended.Gap(lastSeq) else Appended.Ok(lastSeq)
    }

    override fun read(since: Long, from: String): Page {
        val answer = call("GET", "/v1/events?since=$since&limit=500&from=${URLEncoder.encode(from, "UTF-8")}")
        if (answer.status == 410) return Page.Gone
        if (answer.status == 404) throw IOException("Mímir has no /v1/events here: check the URL")
        val o = JSONObject(answer.body)
        val list: JSONArray = o.getJSONArray("events")
        val events = (0 until list.length()).map { i ->
            val e = list.getJSONObject(i)
            val body = e.get("body")
            MimirEvent(
                pos = e.getLong("pos"),
                device = e.getString("device"),
                seq = e.getLong("seq"),
                kind = e.getString("kind"),
                body = if (body is String) JSONObject.quote(body) else body.toString(),
                at = e.optLong("at"),
            )
        }
        return Page.Events(events, o.getLong("last"), o.getBoolean("more"))
    }

    override fun snapshotPos(): Long {
        val answer = call("GET", "/v1/snapshot")
        return if (answer.status == 404) 0 else JSONObject(answer.body).getLong("pos")
    }
}
