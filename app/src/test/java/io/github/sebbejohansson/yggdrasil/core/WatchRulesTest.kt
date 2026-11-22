package io.github.sebbejohansson.yggdrasil.core

import io.github.sebbejohansson.yggdrasil.gjallar.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRulesTest {
    @Test fun downAfterTwoFailuresUpAfterTwoPasses() {
        val fail = Check("down:x", false, Severity.CRITICAL, "X is down")
        val pass = Check("down:x", true)
        var s = WatchRules.observe(emptyMap(), listOf(fail), 1)
        assertFalse("one slow answer raises nothing", s.getValue("down:x").down)
        s = WatchRules.observe(s, listOf(fail), 2)
        assertTrue(s.getValue("down:x").down)
        assertEquals("X is down", s.getValue("down:x").title)
        assertEquals(listOf("down:x"), WatchRules.plan(s).first.map { it.key })
        s = WatchRules.observe(s, listOf(pass), 3)
        assertTrue("still down after one pass", s.getValue("down:x").down)
        s = WatchRules.observe(s, listOf(Check("down:x", false, skip = true)), 4)
        assertEquals("a skip changes nothing", 1, s.getValue("down:x").passes)
        s = WatchRules.observe(s, listOf(pass), 5)
        assertFalse(s.getValue("down:x").down)
    }

    @Test fun planRaisesDownAndResolvesUpButNeverOutOfBand() {
        val states = mapOf(
            "a" to WatchState("a", down = true, raised = false),
            "b" to WatchState("b", down = false, raised = true),
            "c" to WatchState("c", down = true, raised = true),
            "d" to WatchState("d", down = true, raised = false),
        )
        val (raise, resolve) = WatchRules.plan(states, outOfBand = setOf("d"))
        assertEquals(listOf("a"), raise.map { it.key })
        assertEquals(listOf("b"), resolve.map { it.key })
    }

    @Test fun aTrickEndsOnItsOwnAndOneAtATime() {
        val now = 1_000_000L
        Tricks.clear()
        Tricks.set(Trick("dellingr", "loop", "dellingr.tick", now + 60_000), now)
        assertTrue(Tricks.loopTricked("dellingr.tick", now + 1))
        assertFalse(Tricks.loopTricked("saga.sync", now + 1))
        assertTrue(runCatching { Tricks.set(Trick("saga", "health", until = now + 60_000), now) }.isFailure)
        assertFalse("ended at its time", Tricks.loopTricked("dellingr.tick", now + 60_001))
        assertTrue(runCatching { Tricks.set(Trick("saga", "health", until = now + 11 * 60_000), now) }.isFailure)
        Tricks.clear()
    }
}
