package io.github.sebbejohansson.yggdrasil.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcsTest {
    @Test fun cpuTicksCountFromTheLastParenthesis() {
        // comm "(my (odd) name)" holds spaces and parentheses; utime 250, stime 50.
        val stat = "4242 (my (odd) name) S 1 4242 4242 0 -1 4194560 100 0 0 0 250 50 0 0 20 0 3 0 12345 1000 200"
        assertEquals(300L, Procs.cpuTicks(stat))
    }

    @Test fun statusLines() {
        val status = "Name:\tclaude\nUid:\t10234\t10234\t10234\t10234\nThreads:\t17\nVmRSS:\t  204800 kB\n"
        assertEquals(10234, Procs.uid(status))
        assertEquals(204800L, Procs.statusValue(status, "VmRSS"))
        assertEquals(17L, Procs.statusValue(status, "Threads"))
        assertEquals(null, Procs.statusValue(status, "VmSwap"))
    }

    @Test fun groupsByWhatRuns() {
        assertEquals("Ygg", Procs.group("io.github.sebbejohansson.yggdrasil", isApp = true))
        assertEquals("Linux layer (proot)", Procs.group("/data/app/lib/arm64/libproot.so --kill-on-exit -0 -r /data/user/0/x python3 /root/ygg-bridge/ygg_bridge.py serve", false))
        assertEquals("Claude bridge", Procs.group("python3 /root/ygg-bridge/ygg_bridge.py serve", false))
        assertEquals("herdr", Procs.group("/root/.local/bin/herdr server", false))
        assertEquals("Claude Code", Procs.group("claude", false))
        assertEquals("Claude Code", Procs.group("script -qfec claude --cloud task /dev/null", false))
        assertEquals("git", Procs.group("git fetch -q --prune origin", false))
        assertEquals("apt", Procs.group("/usr/bin/apt-get update", false))
        assertEquals("Shell", Procs.group("/bin/bash -l", false))
        assertEquals("Other", Procs.group("sleep 10", false))
    }

    @Test fun cpuIsTheTicksBetweenTwoReadings() {
        val before = listOf(ProcSample(1, "claude", "Claude Code", 100_000, 1_000, 10), ProcSample(2, "bash", "Shell", 4_000, 50, 1))
        val after = listOf(
            ProcSample(1, "claude", "Claude Code", 120_000, 1_150, 10), // 150 ticks in 3 s: half a core
            ProcSample(2, "bash", "Shell", 4_000, 50, 1),
            ProcSample(3, "node", "Claude Code", 30_000, 900, 4), // new since: counts from zero
        )
        val g = Procs.groups(before, after, 3_000)
        assertEquals("Claude Code", g[0].group)
        assertEquals(50.0, g[0].cpuPercent, 0.01)
        assertEquals(150_000L, g[0].rssKb)
        assertEquals(2, g[0].processes)
        assertEquals(0.0, g[1].cpuPercent, 0.01)
    }
}
