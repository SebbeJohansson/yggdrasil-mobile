package io.github.sebbejohansson.yggdrasil.saga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ArchiveTest {
    private val at = Instant.parse("2026-11-27T17:00:00Z")
    private val archived = mapOf(Archive.key("vps", "s1") to at)

    @Test
    fun anArchivedSessionStaysHiddenUntilSomethingHappensInIt() {
        assertTrue(Archive.hidden(archived, "vps|s1", at.minusSeconds(60)))
        assertTrue(Archive.hidden(archived, "vps|s1", null))
        assertFalse(Archive.hidden(archived, "vps|s1", at.plusSeconds(5)))
    }

    @Test
    fun otherSessionsShow() {
        assertFalse(Archive.hidden(archived, "vps|s2", null))
        assertFalse(Archive.hidden(archived, "laptop|s1", null))
    }
}
