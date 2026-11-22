package io.github.sebbejohansson.yggdrasil.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellScreenTest {
    @Test
    fun linesColoursAndCarriageReturns() {
        val s = ShellScreen()
        s.append("\u001B[01;32mroot@ygg\u001B[0m:~# ls\r\n")
        s.append("a  b\r\nDownloading 10%\rDownloading 55%\r\n")
        s.append("\u001B]0;title\u0007root@ygg:~# ")
        assertEquals(listOf("root@ygg:~# ls", "a  b", "Downloading 55%", "root@ygg:~# "), s.text())
    }

    @Test
    fun backspaceAndEraseRedrawTheLine() {
        val s = ShellScreen()
        s.append("# lss\b\u001B[K")
        assertEquals(listOf("# ls"), s.text())
    }

    @Test
    fun keepsOnlyTheLastLines() {
        val s = ShellScreen(keep = 3)
        repeat(10) { s.append("$it\n") }
        assertEquals(listOf("8", "9", ""), s.text())
    }
}
