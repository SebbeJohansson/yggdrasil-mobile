package io.github.sebbejohansson.yggdrasil.linux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What a shell has printed, as lines on a screen: colours and cursor moves
 * dropped, a carriage return starting the line over, a backspace taking one
 * character back. Enough for a prompt, apt's progress and `claude`'s output;
 * full-screen programs (vim, top) aren't drawn. Pure, for tests.
 */
class ShellScreen(private val keep: Int = 400) {
    private val lines = ArrayDeque<String>().apply { add("") }
    private val line = StringBuilder()
    private var col = 0
    private var esc = 0 // 0 none, 1 after ESC, 2 in CSI, 3 in OSC

    fun append(text: String) {
        for (c in text) {
            when (esc) {
                1 -> esc = when (c) { '[' -> 2; ']' -> 3; else -> 0 }
                2 -> if (c in '@'..'~') {
                    // Erase to the end of the line is what readline sends when it redraws.
                    if (c == 'K') line.setLength(col)
                    esc = 0
                }
                3 -> if (c == '\u0007') esc = 0
                else -> when (c) {
                    '\u001B' -> esc = 1
                    '\r' -> col = 0
                    '\n' -> newline()
                    '\b' -> if (col > 0) col--
                    '\u0007' -> Unit
                    '\t' -> repeat(8 - col % 8) { put(' ') }
                    else -> if (c >= ' ') put(c)
                }
            }
        }
        lines[lines.lastIndex] = line.toString()
    }

    private fun put(c: Char) {
        if (col < line.length) line.setCharAt(col, c) else line.append(c)
        col++
    }

    private fun newline() {
        lines[lines.lastIndex] = line.toString()
        lines.addLast("")
        line.setLength(0)
        col = 0
        while (lines.size > keep) lines.removeFirst()
    }

    fun text(): List<String> = lines.toList()
}

/**
 * A shell in Ygg's own Linux, for anything the app has no button for: bash
 * as root in the guest, under util-linux's `script` so it has a terminal
 * (a prompt, Ctrl-C, programs that want a tty). It keeps going while you're
 * elsewhere in the app, until you end it or Ygg stops.
 */
class LinuxShell(private val linux: OwnLinux) {
    data class State(val running: Boolean = false, val lines: List<String> = emptyList(), val exit: Int? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (process?.isAlive == true) return
        val screen = ShellScreen()
        val p = linux.start(COMMAND)
        process = p
        _state.value = State(running = true)
        Thread({
            val reader = p.inputStream.reader()
            val chunk = CharArray(4096)
            while (true) {
                val n = runCatching { reader.read(chunk) }.getOrDefault(-1)
                if (n < 0) break
                synchronized(screen) {
                    screen.append(String(chunk, 0, n))
                    _state.update { it.copy(lines = screen.text()) }
                }
            }
            val code = runCatching { p.waitFor() }.getOrNull()
            _state.update { it.copy(running = false, exit = code) }
        }, "ygg-linux-shell").apply { isDaemon = true }.start()
    }

    /** A line typed in, Enter included. */
    fun send(line: String) = write(line + "\n")

    /** Ctrl-C: the terminal turns it into SIGINT for what's running. */
    fun interrupt() = write("\u0003")

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun write(text: String) {
        val out = process?.outputStream ?: return
        runCatching { out.write(text.toByteArray()); out.flush() }
    }

    companion object {
        /** A login bash with a terminal; wide, so lines wrap here and not in the guest. */
        val COMMAND = listOf("script", "-qfec", "stty cols 200 rows 50 2>/dev/null; exec bash -l", "/dev/null")

        /** One tap for the usual errands. */
        val SHORTCUTS = listOf(
            "Claude version" to "claude --version",
            "Bridge running?" to "pgrep -af ygg_bridge.py || echo 'not running'",
            "GitHub token?" to "test -s ~/.ygg-bridge/github && echo 'kept' || echo 'none'",
            "Disk" to "df -h / | tail -1",
            "Update packages" to "apt-get update && apt-get -y upgrade",
        )

        /** Fills the input to keep a GitHub token for the bridge; you paste the token after it. */
        const val GITHUB_PREFIX = "python3 /root/ygg-bridge/ygg_bridge.py github "
    }
}
