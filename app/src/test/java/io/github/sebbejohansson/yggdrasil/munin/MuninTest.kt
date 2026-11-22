package io.github.sebbejohansson.yggdrasil.munin

import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class MuninTest {
    private val page = Target("github-status", "statuspage", "https://www.githubstatus.com/api/v2/status.json")

    @Test fun anyAnswerIsUp() {
        val c = Munin.checks(Target("mimir", "http", "https://mimir/"), 404 to "nope", null)
        assertEquals(listOf("down:mimir" to true), c.map { it.key to it.ok })
        val down = Munin.checks(Target("mimir", "http", "https://mimir/"), null, "timeout").single()
        assertEquals(false, down.ok)
        assertEquals("mimir doesn't answer", down.title)
        assertEquals("timeout", down.detail)
    }

    @Test fun statusPages() {
        assertEquals(true, Munin.checks(page, 200 to """{"status":{"indicator":"none","description":"All Systems Operational"}}""", null)[1].ok)
        val minor = Munin.statuspage("github-status", """{"status":{"indicator":"minor","description":"Partial outage"}}""")
        assertEquals(false to Severity.INFO, minor.ok to minor.severity)
        assertEquals("github-status reports an incident: Partial outage", minor.title)
        assertEquals(Severity.WARNING, Munin.statuspage("x", """{"status":{"indicator":"major"}}""").severity)
        assertEquals("x's status page could not be read", Munin.statuspage("x", "<html>").title)
        // Down: the incident check waits.
        assertEquals(true, Munin.checks(page, null, "timeout")[1].skip)
    }

    @Test fun offlineSkipsAndTargetsFollowWhatIsSetUp() {
        assertEquals(true, Munin.checks(Target("github", "http", "u"), null, "offline", skip = true).single().skip)
        assertEquals(listOf("github", "github-status", "huggingface"), Munin.targets(null, claude = false).map { it.name })
        assertEquals("mimir", Munin.targets("https://mimir.example:8443", claude = true).first().name)
        assertEquals(true, Munin.targets(null, claude = true).any { it.name == "anthropic-status" })
    }
}
