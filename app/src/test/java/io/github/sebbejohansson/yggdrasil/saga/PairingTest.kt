package io.github.sebbejohansson.yggdrasil.saga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun readsALink() {
        val p = parsePairing("yggdrasil://pair?v=1&id=vps&label=My%20VPS&host=vps.tail1234.ts.net&token=abc%2B123")
        assertEquals(Pairing("vps.tail1234.ts.net", "abc+123", "My VPS"), p)
        assertEquals("https://vps.tail1234.ts.net", p?.url)
    }

    @Test fun refusesAWorkMachine() {
        assertNull(parsePairing("yggdrasil://pair?v=1&host=w.ts.net&token=t&work=1"))
        assertEquals(true, parsePairingResult("yggdrasil://pair?host=w.ts.net&token=t&work=1").exceptionOrNull()?.message?.contains("work machine"))
    }

    @Test fun refusesMissingOrOddParts() {
        assertNull(parsePairing("yggdrasil://pair?v=1&host=vps.ts.net"))
        assertNull(parsePairing("yggdrasil://pair?v=1&token=t"))
        assertNull(parsePairing("yggdrasil://pair?v=2&host=vps.ts.net&token=t"))
        assertNull(parsePairing("yggdrasil://pair?host=a/b&token=t"))
    }

    @Test fun ignoresOtherCodes() {
        assertNull(parsePairing("https://example.com/?host=x&token=t"))
        assertNull(parsePairing("WIFI:S:home;T:WPA;P:pw;;"))
    }
}
