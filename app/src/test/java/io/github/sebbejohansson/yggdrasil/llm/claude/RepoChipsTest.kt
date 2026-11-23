package io.github.sebbejohansson.yggdrasil.llm.claude

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoChipsTest {
    private val all = listOf("SebbeJohansson/yggdrasil-mobile", "SebbeJohansson/yggdrasil", "SebbeJohansson/my-widget", "other/api")

    @Test fun everyKnownRepoUntilYouType() {
        assertEquals(all, repoChips(all, ""))
        // A whole known name (the one chosen) still shows them all, to switch.
        assertEquals(all, repoChips(all, "sebbejohansson/yggdrasil"))
    }

    @Test fun typingFilters() {
        assertEquals(listOf("SebbeJohansson/yggdrasil-mobile", "SebbeJohansson/yggdrasil"), repoChips(all, "ygg"))
        assertEquals(listOf("SebbeJohansson/my-widget"), repoChips(all, " WIDGET "))
        assertEquals(emptyList<String>(), repoChips(all, "nothing"))
    }
}
