package io.github.sebbejohansson.yggdrasil.nodes

import android.app.Application
import io.github.sebbejohansson.yggdrasil.saga.AskState
import io.github.sebbejohansson.yggdrasil.saga.SessionAsk
import io.github.sebbejohansson.yggdrasil.saga.SessionKinds
import io.github.sebbejohansson.yggdrasil.update.TokenVault
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MachinesTest {
    private val vps = Machine("vps", "VPS", "vps.tail1234.ts.net")
    private val now = Instant.parse("2026-11-24T12:00:00Z")

    // What Sindri's GET /v1/sessions answers: a Claude Code session in a
    // herdr pane with a dialog up, one outside herdr, and a herdr pane of its own.
    private val sessions = """
        {"claude":{"sessions":[
            {"sessionId":"s1","pid":41,"directory":"/home/ygg/projects/site","name":"site","lastActivity":2000,
             "state":{"phase":"busy","tool":"Bash","lastText":"Running tests"}},
            {"sessionId":"s2","pid":42,"directory":"/home/ygg/projects/api","name":"api","lastActivity":3000,
             "permission":{"tool":""},"bridgeSessionId":"b2"}
         ],"degraded":false,"from":"/home/ygg/.claude"},
         "opencode":{"sessions":null,"from":"off"},
         "herdr":{"from":"herdr","sessions":[
            {"paneId":"w1:p1","pids":[41],"status":"blocked","kind":"claude",
             "dialogScreen":{"question":"Run npm test?","choices":[{"key":"1","label":"Yes"},{"key":"2","label":"No"}]}},
            {"paneId":"w2:p1","name":"codex","kind":"codex","status":"working","cwd":"/home/ygg/projects/cli"}
         ]},
         "at":1}
    """.trimIndent()

    @Test
    fun `maps a machine's sessions as its own Saga does`() {
        val d = Machines.deviceSessions(vps, "phone", now, sessions, """{"sessions":[{"id":"c1","at":5,"cwd":"/p","task":"fix","url":"https://claude.ai/code/c1"}]}""", """[{"name":"site","path":"/home/ygg/projects/site"},{"name":"bad","path":"relative"}]""")
        assertEquals("vps", d.device)
        assertEquals("VPS", d.title)
        assertTrue(d.direct)
        assertTrue(d.drivableBy("phone"))
        assertFalse(d.quiet(now.plusSeconds(3600)))
        assertTrue(d.herdr)
        assertEquals(listOf("s2", "s1", "herdr:w2:p1"), d.sessions.map { it.id })

        val site = d.sessions.first { it.id == "s1" }
        assertEquals("w1:p1", site.pane)
        assertEquals("blocked", site.phase)
        assertEquals(listOf("Yes", "No"), site.dialog?.choices?.map { it.label })
        assertEquals("Running tests", site.lastText)

        val api = d.sessions.first { it.id == "s2" }
        assertEquals("a tool", api.permission)
        assertNull(api.pane)
        assertEquals("https://claude.ai/code/b2", api.claudeLink)

        val codex = d.sessions.first { it.id == "herdr:w2:p1" }
        assertEquals("busy", codex.phase)
        assertEquals("codex", codex.kind)

        assertEquals(listOf("c1"), d.cloud.map { it.id })
        assertEquals(listOf("/home/ygg/projects/site"), d.projects.map { it.path })
    }

    @Test
    fun `turns each ask into the call the machine's Saga would make`() {
        fun ask(kind: String, session: String? = null, text: String? = null, cwd: String? = null, task: String? = null) =
            SessionAsk(id = "a", kind = kind, origin = "vps", session = session, text = text, cwd = cwd, task = task, question = "Run npm test?", key = "1", label = "Yes")
        val prompt = Machines.call(ask(SessionKinds.PROMPT, "s1", text = "go on"), pane = "w1:p1").getOrThrow()
        assertEquals("/v1/sessions/herdr/w1%3Ap1/prompt", prompt.path)
        assertEquals("go on", prompt.body?.getString("text"))
        assertTrue(Machines.call(ask(SessionKinds.PROMPT, "s2", text = "go on"), pane = null).isFailure)

        val choose = Machines.call(ask(SessionKinds.CHOOSE, "s1"), pane = "w1:p1").getOrThrow()
        assertEquals("/v1/sessions/herdr/w1%3Ap1/choose", choose.path)
        assertEquals("1", choose.body?.getString("key"))

        val resume = Machines.call(ask(SessionKinds.RESUME, "s2", cwd = "/home/ygg/projects/api"), pane = null).getOrThrow()
        assertEquals("/v1/sessions/herdr", resume.path)
        assertEquals("s2", resume.body?.getString("resume"))

        assertEquals("/v1/cloud", Machines.call(ask(SessionKinds.CLOUD_START, cwd = "/p", task = "fix"), null).getOrThrow().path)
        assertEquals("/v1/cloud/c1/message", Machines.call(ask(SessionKinds.CLOUD_MESSAGE, "c1", text = "hi"), null).getOrThrow().path)
        assertEquals("/v1/cloud/c1/teleport", Machines.call(ask(SessionKinds.CLOUD_TELEPORT, "c1"), null).getOrThrow().path)
        assertTrue(Machines.call(ask(SessionKinds.CLOUD_START, cwd = "relative", task = "fix"), null).isFailure)
    }

    @Test
    fun `checks what is typed for a machine`() {
        assertEquals(Machine("home-pc", "Home PC", "home.tail1234.ts.net"),
            Machines.check(" Home PC ", "https://Home.tail1234.ts.net./", "tok").getOrThrow())
        assertTrue(Machines.check("", "vps.tail1234.ts.net", "tok").isFailure)
        assertTrue(Machines.check("VPS", "vps/../evil", "tok").isFailure)
        assertTrue(Machines.check("VPS", "vps.tail1234.ts.net", " ").isFailure)
        assertEquals(MachineHealth("v0.2.0", work = true), Machines.parseHealth("""{"ok":true,"version":"v0.2.0","work":true}"""))
        assertEquals(MachineHealth("v0.2.0", work = false), Machines.parseHealth("""{"ok":true,"version":"v0.2.0"}"""))
    }

    private class MemoryVault : TokenVault {
        var value: String? = null
        override fun get() = value
        override fun set(token: String?) { value = token }
    }

    /** A machine's Sindri, answered from memory: what was asked is kept. */
    private class FakeClient(machine: Machine, private val health: String) : MachineClient(machine, "t") {
        val calls = mutableListOf<String>()
        override fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): String {
            calls += "$method $path"
            return when (path) {
                "/health" -> health
                "/v1/sessions" -> """{"claude":{"sessions":[{"sessionId":"s1","pid":41,"directory":"/p","lastActivity":1}]},"herdr":{"from":"herdr","sessions":[{"paneId":"w1:p1","pids":[41],"status":"idle"}]}}"""
                "/v1/cloud" -> throw IOException("off")
                "/v1/projects" -> "[]"
                else -> "{}"
            }
        }
    }

    private fun service(health: String, clients: MutableList<FakeClient>): MachineService {
        val vaults = mutableMapOf<String, MemoryVault>()
        val settings = MachineSettings(RuntimeEnvironment.getApplication()) { id -> vaults.getOrPut(id) { MemoryVault() } }
        settings.save(vps, "vps-token")
        return MachineService(settings, me = { "phone" }, clock = Clock.fixed(now, ZoneOffset.UTC), io = kotlinx.coroutines.Dispatchers.Unconfined) { m, token ->
            assertEquals("vps-token", token)
            FakeClient(m, health).also { clients += it }
        }
    }

    @Test
    fun `reads a machine, and does what is asked of it at once`() = runTest {
        val clients = mutableListOf<FakeClient>()
        val machines = service("""{"version":"v0.2.0"}""", clients)
        machines.refresh()
        assertEquals("v0.2.0", machines.status.value.single().health?.version)
        assertTrue(machines.isDirect("vps"))
        assertEquals("w1:p1", machines.sessions.value.single().sessions.single().pane)

        machines.ask(SessionAsk(id = "a1", kind = SessionKinds.PROMPT, origin = "vps", session = "s1", text = "go on"))
        assertEquals(AskState.DONE, machines.asked.value.single().state)
        assertTrue(clients.any { "POST /v1/sessions/herdr/w1%3Ap1/prompt" in it.calls })
    }

    @Test
    fun `never reads or drives a work machine`() = runTest {
        val clients = mutableListOf<FakeClient>()
        val machines = service("""{"version":"v0.2.0","work":true}""", clients)
        machines.refresh()
        assertTrue(machines.status.value.single().refused)
        assertFalse(machines.isDirect("vps"))
        assertTrue(machines.sessions.value.isEmpty())
        assertTrue(clients.flatMap { it.calls }.none { it.startsWith("GET /v1/sessions") })

        machines.ask(SessionAsk(id = "a1", kind = SessionKinds.PROMPT, origin = "vps", session = "s1", text = "go on"))
        assertEquals(AskState.FAILED, machines.asked.value.single().state)
        assertTrue(clients.flatMap { it.calls }.none { it.startsWith("POST") })
    }
}
