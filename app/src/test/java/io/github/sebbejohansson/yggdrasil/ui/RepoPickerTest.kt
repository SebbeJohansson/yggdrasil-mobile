package io.github.sebbejohansson.yggdrasil.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoPickerTest {
    private val all = listOf("me/yggdrasil-mobile", "me/yggdrasil", "me/my-widget", "other/api")

    @Test fun pickedFirstThenTheRestOnce() {
        assertEquals(listOf("other/api", "me/yggdrasil-mobile", "me/yggdrasil", "me/my-widget"), pickerOrder(all, listOf("other/api")))
        assertEquals(listOf("new/one") + all, pickerOrder(all, listOf("new/one")))
    }

    @Test fun typingFilters() {
        assertEquals(all, pickerMatches(all, " "))
        assertEquals(listOf("me/yggdrasil-mobile", "me/yggdrasil"), pickerMatches(all, "YGG"))
    }

    @Test fun aWholeUnknownOwnerRepoIsOffered() {
        assertEquals("new/repo", pickerTyped(all, " new/repo "))
        assertNull(pickerTyped(all, "ME/YGGDRASIL"))
        assertNull(pickerTyped(all, "ygg"))
    }

    @Test fun toggleRespectsMax() {
        assertEquals(listOf("b"), pickerToggle(listOf("a"), "b", 1))
        assertEquals(listOf("a", "b"), pickerToggle(listOf("a"), "b", 6))
        assertEquals(listOf("a"), pickerToggle(listOf("a", "b"), "b", 6))
        assertEquals(listOf("a", "b"), pickerToggle(listOf("a", "b"), "c", 2))
    }
}
