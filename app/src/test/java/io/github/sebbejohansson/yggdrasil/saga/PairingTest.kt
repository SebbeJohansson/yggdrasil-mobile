package io.github.sebbejohansson.yggdrasil.saga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric because org.json is a stub on the plain JVM unit-test classpath.
@RunWith(RobolectricTestRunner::class)
class PairingTest {
    @Test fun readsALink() {
        val p = parsePairing("ygg://pair?url=https%3A%2F%2Fvps.tailnet.ts.net&token=abc%2B123&device=my%20phone")
        assertEquals(Pairing("https://vps.tailnet.ts.net", "abc+123", "my phone"), p)
    }

    @Test fun readsJsonAndTrims() {
        val p = parsePairing("""  {"url":"https://vps.ts.net/","token":" t ","device":""}  """)
        assertEquals(Pairing("https://vps.ts.net", "t", null), p)
    }

    @Test fun refusesPlainHttpAndMissingParts() {
        assertNull(parsePairing("ygg://pair?url=http%3A%2F%2Fvps&token=t"))
        assertNull(parsePairing("ygg://pair?url=https%3A%2F%2Fvps"))
        assertNull(parsePairing("ygg://pair?token=t"))
        assertNull(parsePairing("ygg://pair"))
    }

    @Test fun ignoresOtherCodes() {
        assertNull(parsePairing("https://example.com/?url=https://x&token=t"))
        assertNull(parsePairing("WIFI:S:home;T:WPA;P:pw;;"))
        assertNull(parsePairing("{not json"))
    }
}
