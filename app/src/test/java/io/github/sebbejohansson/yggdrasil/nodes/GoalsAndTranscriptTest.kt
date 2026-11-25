package io.github.sebbejohansson.yggdrasil.nodes

import android.app.Application
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoalsAndTranscriptTest {
    private val vps = Machine("vps", "VPS", "vps.tail1234.ts.net")

    // A tail that starts mid-line, as Sindri's ?tail= can.
    private val transcript = listOf(
        """ial line cut off"}""",
        """{"type":"user","timestamp":"2026-11-24T10:00:00Z","message":{"content":"Fix the build<system-reminder>be careful</system-reminder>"}}""",
        """{"type":"user","isMeta":true,"message":{"content":"meta"}}""",
        """{"type":"assistant","timestamp":"2026-11-24T10:00:05Z","message":{"content":[{"type":"thinking","thinking":"Look at CI first."},{"type":"text","text":"Looking at it."},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"npm test","description":"run"}}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":[{"type":"text","text":"1 failing"}]}]}}""",
        """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"a subagent"}]}}""",
        """{"type":"user","message":{"content":"<command-name>/compact</command-name><command-args> keep tests </command-args>"}}""",
        """{"type":"user","message":{"content":"<local-command-stdout>x</local-command-stdout>"}}""",
    ).joinToString("\n")

    @Test
    fun `reads a transcript as the desktop's reader does`() {
        val entries = Transcript.read(transcript)
        assertEquals(5, entries.size)
        assertEquals(TranscriptEntry.Prompt("Fix the build", Instant.parse("2026-11-24T10:00:00Z")), entries[0])
        assertEquals("Look at CI first.", (entries[1] as TranscriptEntry.Thinking).text)
        assertEquals("Looking at it.", (entries[2] as TranscriptEntry.Reply).text)
        val tool = entries[3] as TranscriptEntry.Tool
        assertEquals("Bash", tool.name)
        assertEquals("npm test", tool.detail)
        assertEquals("1 failing", tool.output)
        assertTrue(tool.failed)
        assertEquals("/compact keep tests", (entries[4] as TranscriptEntry.Prompt).text)
        assertNull(Transcript.promptText("Caveat: the messages below"))
    }

    @Test
    fun `lists the goals in play in the order to act on them`() {
        val goals = Goals.parse("""{"goals":[
            {"id":1,"title":"Old draft","repos":[],"state":"draft","next":{"action":"start","text":"Pick projects"},"tasks":[]},
            {"id":2,"title":"Merged","repos":["site"],"state":"merged","next":{"action":"none","text":""},"tasks":[]},
            {"id":3,"title":"Waiting","repos":["site"],"state":"running","next":{"action":"resume","text":"Waits for Claude usage"},
             "tasks":[{"id":9,"repo":"site","agent":"a-9","status":"waiting","label":null,"branch":null,"merged":false,"lastActivity":1,"result":null}]},
            {"id":4,"title":"Asks you","repos":["api"],"state":"needs-you","next":{"action":"answer","text":"Answer the agent"},"tasks":[]},
            {"id":5,"title":"New draft","repos":["api"],"state":"draft","next":{"action":"start","text":"Start"},"tasks":[]}]}""")
        val inPlay = Goals.inPlay(goals)
        assertEquals(listOf(4L, 3L, 5L, 1L), inPlay.map { it.id })
        assertTrue(inPlay.first { it.id == 5L }.startable)
        assertFalse(inPlay.first { it.id == 1L }.startable)
        assertTrue(inPlay.first { it.id == 3L }.resumable)
        assertEquals("a-9", inPlay.first { it.id == 3L }.parts.single().agent)
        assertEquals("What should be done?", Goals.checkNew(" ", emptyList()))
        assertEquals("At most 4 projects", Goals.checkNew("x", listOf("a", "b", "c", "d", "e")))
        assertNull(Goals.checkNew("Add CSV export", listOf("site")))
    }

    private class MemoryVault : TokenVault {
        var value: String? = null
        override fun get() = value
        override fun set(token: String?) { value = token }
    }

    private class FakeClient(machine: Machine, private val work: Boolean = false) : MachineClient(machine, "t") {
        val calls = mutableListOf<String>()
        override fun call(part: String, method: String, path: String, body: JSONObject?, timeoutMs: Int): String {
            calls += "$part $method $path${body?.let { " $it" } ?: ""}"
            return when {
                path == "/health" -> """{"version":"v0.2.1"${if (work) ""","work":true""" else ""}}"""
                path == "/v1/sessions" -> """{"claude":{"sessions":[]},"herdr":{"from":"off"}}"""
                path == "/v1/goals" && method == "GET" -> """{"goals":[{"id":5,"title":"New draft","repos":["api"],"state":"draft","next":{"action":"start","text":"Start"},"tasks":[]}]}"""
                path == "/v1/goals" -> """{"id":6,"title":"Add CSV export","repos":["site"],"state":"draft","next":{"action":"start","text":"Start"},"tasks":[]}"""
                path.startsWith("/v1/sessions/claude/") && "since=10%3A20" in path -> """{"size":10,"modified":20,"text":"","unchanged":true}"""
                path.startsWith("/v1/sessions/claude/") -> """{"size":10,"modified":20,"text":"{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}"}"""
                path == "/v1/status" -> """{"parts":[]}"""
                path == "/v1/alerts" -> """{"alerts":[]}"""
                else -> "{}"
            }
        }
    }

    private fun service(work: Boolean, clients: MutableList<FakeClient>): MachineService {
        val vaults = mutableMapOf<String, MemoryVault>()
        val settings = MachineSettings(RuntimeEnvironment.getApplication()) { id -> vaults.getOrPut(id) { MemoryVault() } }
        settings.save(vps, "t")
        return MachineService(settings, me = { "phone" }, clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), io = kotlinx.coroutines.Dispatchers.Unconfined,
            clientFor = { m, _ -> FakeClient(m, work).also { clients += it } })
    }

    @Test
    fun `reads, makes, starts and resumes a machine's goals, and reads its transcripts`() = runTest {
        val clients = mutableListOf<FakeClient>()
        val machines = service(work = false, clients)
        machines.refresh()
        assertEquals(listOf(5L), machines.goals.value["vps"]?.map { it.id })

        assertNull(machines.onGoal("vps") { it.createGoal("Add CSV export", listOf("site")) })
        assertNull(machines.onGoal("vps") { it.startGoal(5) })
        assertNull(machines.onGoal("vps") { it.resumeGoal(5) })
        val calls = clients.flatMap { it.calls }
        assertTrue(calls.any { it.startsWith("sindri POST /v1/goals {") && "\"title\":\"Add CSV export\"" in it && "\"repos\":[\"site\"]" in it })
        assertTrue(calls.any { it.startsWith("sindri POST /v1/goals/5/start") })
        assertTrue(calls.any { it.startsWith("sindri POST /v1/goals/5/resume") })

        val first = machines.transcript("vps", "s1", "/home/ygg/projects/site", 400_000, null)
        assertEquals("hi", (Transcript.read(first.text).single() as TranscriptEntry.Prompt).text)
        assertTrue(clients.flatMap { it.calls }.any { "/v1/sessions/claude/s1/transcript?tail=400000&dir=%2Fhome%2Fygg%2Fprojects%2Fsite" in it })
        assertTrue(machines.transcript("vps", "s1", null, 400_000, first.stamp).unchanged)
    }

    @Test
    fun `never touches a work machine's goals or transcripts`() = runTest {
        val clients = mutableListOf<FakeClient>()
        val machines = service(work = true, clients)
        machines.refresh()
        assertTrue(machines.goals.value.isEmpty())
        assertEquals("VPS is a work machine", machines.onGoal("vps") { it.startGoal(5) })
        assertTrue(runCatching { machines.transcript("vps", "s1", null, 1, null) }.isFailure)
        assertTrue(clients.flatMap { it.calls }.none { "/v1/goals" in it || "/transcript" in it })
    }
}
