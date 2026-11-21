package io.github.sebbejohansson.yggdrasil.home

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    private fun app(label: String, profile: Long = 0) =
        AppEntry(AppEntry.keyOf("p.$label", "A", profile), label, "p.$label", "A", profile)

    private val apps = listOf(
        app("Maps"), app("Google Maps"), app("Gmail"), app("Spotify"), app("Messages"),
        app("Slack", profile = 10), app("Slack"), app("1Password"), app("Örebro Bus"), app("YouTube Music"),
    )

    private fun labels(list: List<AppEntry>) = list.map { it.label }

    @Test fun sortsByNameThenProfile() {
        assertEquals(
            listOf("1Password", "Gmail", "Google Maps", "Maps", "Messages", "Örebro Bus", "Slack", "Slack", "Spotify", "YouTube Music"),
            labels(AppCatalog.sorted(apps)),
        )
        assertEquals(listOf(0L, 10L), AppCatalog.sorted(apps).filter { it.label == "Slack" }.map { it.profile })
    }

    @Test fun searchRanksNameThenWordThenInitialsThenAnywhere() {
        assertEquals(listOf("Maps", "Messages", "Google Maps", "YouTube Music", "Gmail"), labels(AppCatalog.search(apps, "m")))
        assertEquals(listOf("Maps", "Google Maps"), labels(AppCatalog.search(apps, "map")))
        assertEquals(listOf("Gmail", "Google Maps"), labels(AppCatalog.search(apps, "gm")))
        assertEquals(listOf("YouTube Music"), labels(AppCatalog.search(apps, "tube")))
        assertEquals(listOf("YouTube Music"), labels(AppCatalog.search(apps, "ytm")))
    }

    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals(listOf("Örebro Bus"), labels(AppCatalog.search(apps, "ore")))
        assertEquals(listOf("Spotify"), labels(AppCatalog.search(apps, " SPOT ")))
        assertEquals(10, AppCatalog.search(apps, "").size)
        assertEquals(emptyList<String>(), labels(AppCatalog.search(apps, "zzz")))
    }

    @Test fun sectionsPutDigitsLast() {
        val s = AppCatalog.sections(apps)
        assertEquals(listOf("G", "M", "O", "S", "Y", "#"), s.map { it.first })
        assertEquals(listOf("Gmail", "Google Maps"), labels(s.first().second))
    }

    @Test fun pinsKeepOrderAndDropUninstalled() {
        var pins = listOf<String>()
        pins = AppCatalog.pin(pins, apps[3].key)
        pins = AppCatalog.pin(pins, apps[0].key)
        pins = AppCatalog.pin(pins, apps[0].key)
        pins = AppCatalog.pin(pins, "gone/A#0")
        assertEquals(listOf("Spotify", "Maps"), labels(AppCatalog.pinned(apps, pins)))
        assertEquals(listOf("Maps", "Spotify"), labels(AppCatalog.pinned(apps, AppCatalog.move(pins, apps[0].key, -1))))
        assertEquals(pins, AppCatalog.move(pins, apps[3].key, -1))
        assertEquals(listOf("Maps"), labels(AppCatalog.pinned(apps, AppCatalog.unpin(pins, apps[3].key))))
    }
}
