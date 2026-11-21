package io.github.sebbejohansson.yggdrasil.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

class PerfTest {
    private val at = Instant.parse("2026-11-21T12:00:00Z")
    private fun step(kind: String, ms: Long, ok: Boolean = true, failure: String? = null, who: String? = "gemma", amount: Long = 0) =
        PerfStep("model", kind, at, ms, ok, failure, who, amount)

    @Test fun percentilesAreNearestRank() {
        val ms = (1L..100L).toList()
        assertEquals(50L, PerfMath.percentile(ms, 50.0))
        assertEquals(95L, PerfMath.percentile(ms, 95.0))
        assertEquals(7L, PerfMath.percentile(listOf(7L), 95.0))
        assertEquals(0L, PerfMath.percentile(emptyList(), 50.0))
    }

    @Test fun rowsGroupByPurposeAndModelBusiestFirst() {
        val rows = PerfMath.rows(
            listOf(
                step("chat", 4000, amount = 800), step("chat", 6000, amount = 1200),
                step("memory", 900), step("memory", 1100, ok = false, failure = "timeout"), step("memory", 1000, ok = false, failure = "timeout"),
                step("chat", 2000, who = "claude"),
            ),
        )
        assertEquals(listOf("chat" to "gemma", "memory" to "gemma", "chat" to "claude"), rows.map { it.kind to it.who })
        val chat = rows.first()
        assertEquals(2, chat.count)
        assertEquals(10_000L, chat.totalMs)
        assertEquals(2000L, chat.amount)
        val memory = rows[1]
        assertEquals(2, memory.failed)
        assertEquals("timeout" to 2, memory.topFailure)
    }

    @Test fun failuresAreKindsNotMessages() {
        assertEquals("timeout", PerfMath.failureKind(SocketTimeoutException("Read timed out")))
        assertEquals("HTTP 502", PerfMath.failureKind(IOException("Mímir answered HTTP 502: bad gateway")))
        assertEquals("IllegalStateException", PerfMath.failureKind(IllegalStateException("x")))
    }

    @Test fun timeRecordsSuccessAndFailureButNotCancelling() = runTest {
        val store = InMemoryPerfStore()
        val perf = Perf(store, { at }, writer = { it.run() })
        assertEquals(42, perf.time("vedrfolnir", "check") { 42 })
        try {
            perf.time("saga", "sync") { throw IOException("no") }
            fail()
        } catch (_: IOException) {
        }
        try {
            perf.time("model", "chat") { throw CancellationException("stopped") }
            fail()
        } catch (_: CancellationException) {
        }
        val steps = store.since(Instant.EPOCH)
        assertEquals(listOf("check" to true, "sync" to false), steps.map { it.kind to it.ok })
        perf.bytes("model", "gemma.litertlm", 0)
        perf.bytes("updates", "apk", 27_000_000)
        assertEquals(27_000_000L, store.since(Instant.EPOCH).last().amount)
        assertTrue(PerfMath.bytes(27_000_000) == "27.0 MB" && PerfMath.time(4200) == "4.2 s")
    }
}
