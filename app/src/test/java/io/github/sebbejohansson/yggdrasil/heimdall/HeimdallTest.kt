package io.github.sebbejohansson.yggdrasil.heimdall

import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

// Robolectric for Android's real org.json; plain unit tests get a stub.
@RunWith(RobolectricTestRunner::class)
class HeimdallTest {
    @Test fun readsTheIssuesBlock() {
        val body = """
            Heimdall's last look: **2** findings.

            | Severity | What | Where |
            <!-- heimdall-findings
            [{"id":"dep:okio:GHSA-1","kind":"dependency","severity":"medium","title":"okio 3.2.0: GHSA-1","detail":"x","link":"https://osv.dev/vulnerability/GHSA-1"},{"id":"secret:abc","kind":"secret","severity":"critical","title":"A github-pat in a.txt:3","detail":"","link":""}]
            -->
        """.trimIndent()
        val f = Heimdall.parseIssue(body)
        assertEquals(listOf("dependency", "secret"), f.map { it.check })
        assertEquals("critical", f[1].severity)
        assertEquals(emptyList<Finding>(), Heimdall.parseIssue("no block here"))
    }

    @Test fun oneAlertPerCheckForWhatMatters() {
        val s = Heimdall.summaries(listOf(
            Finding("a", "dependency", "medium", "minor thing"),
            Finding("b", "dependency", "high", "okio: GHSA-2"),
            Finding("c", "dependency", "high", "gson: GHSA-3"),
            Finding("d", "secret", "critical", "A key in x"),
            Finding("e", "phone", "low", "nothing much"),
        )).associateBy { it.key }
        assertEquals(setOf("dependency:summary", "secret:summary"), s.keys)
        assertEquals(Severity.WARNING, s.getValue("dependency:summary").severity)
        assertEquals("Heimdall: 2 dependency findings, among them okio: GHSA-2", s.getValue("dependency:summary").title)
        assertEquals("- okio: GHSA-2\n- gson: GHSA-3", s.getValue("dependency:summary").detail)
        assertEquals(Severity.CRITICAL, s.getValue("secret:summary").severity)
        assertEquals("Heimdall: A key in x", s.getValue("secret:summary").title)
    }

    @Test fun patchAge() {
        assertEquals(100L, Heimdall.patchAge("2026-08-14", LocalDate.parse("2026-11-22")))
        assertEquals(null, Heimdall.patchAge("", LocalDate.parse("2026-11-22")))
    }
}
