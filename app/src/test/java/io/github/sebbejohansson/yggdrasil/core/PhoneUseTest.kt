package io.github.sebbejohansson.yggdrasil.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneUseTest {
    @Test fun appsJoinedByPackageAndRankedByScreenTime() {
        val apps = PhoneUse.merge(
            foreground = mapOf("com.maps" to 600_000L, "com.chat" to 3_600_000L, "com.idle" to 0L),
            mobile = mapOf("com.maps" to 50_000_000L, "(hotspot)" to 1_000L),
            wifi = mapOf("com.chat" to 2_000_000L, "com.sync" to 900_000_000L),
            label = { it.removePrefix("com.").replaceFirstChar(Char::uppercase) },
        )
        assertEquals(listOf("Chat", "Maps", "Sync", "(hotspot)"), apps.map { it.label })
        assertEquals(50_000_000L, apps[1].dataBytes)
        assertEquals(900_000_000L, apps.first { it.packageName == "com.sync" }.wifiBytes)
    }
}
