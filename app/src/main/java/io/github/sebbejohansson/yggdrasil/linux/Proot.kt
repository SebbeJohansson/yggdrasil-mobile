package io.github.sebbejohansson.yggdrasil.linux

/**
 * How Ygg runs a program in its own Linux: proot, shipped as the app's
 * native library (Android runs no program written into app storage), with
 * Ubuntu's files as the root. proot fakes root and chroot with ptrace, so
 * no root on the phone is needed. Everything here is pure, for tests.
 */
data class ProotPaths(
    /** libproot.so in the app's nativeLibraryDir. */
    val proot: String,
    /** libproot-loader.so beside it; empty for a proot that finds its own (a desktop's, in tests). */
    val loader: String,
    /** The folder proot's libraries are in; empty in tests. */
    val libDir: String,
    /** Ubuntu's files. */
    val rootfs: String,
    /** proot's own scratch space, outside the rootfs. */
    val tmp: String,
    /**
     * Termux's proot, as Ygg ships: it has --kill-on-exit and --link2symlink
     * (Android's app storage takes no hard links). A desktop's proot (tests) has neither.
     */
    val termux: Boolean = true,
)

object Proot {
    /** The guest's environment: nothing of Android's leaks in. */
    val GUEST_ENV = listOf(
        "HOME=/root",
        // Updates come from running setup again: the updater's self-replace fails under proot.
        "DISABLE_AUTOUPDATER=1",
        "USER=root",
        "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
    )

    /**
     * Android's own /dev, /proc and /sys, plus the /dev entries Android
     * doesn't have where Linux programs look for them (as proot-distro binds them).
     */
    private val BINDS = listOf(
        "/dev", "/proc", "/sys",
        "/proc/self/fd:/dev/fd",
        "/proc/self/fd/0:/dev/stdin",
        "/proc/self/fd/1:/dev/stdout",
        "/proc/self/fd/2:/dev/stderr",
    )

    /** The command line that runs [argv] in the guest, in [cwd], with [env] on top of [GUEST_ENV]. */
    fun command(p: ProotPaths, argv: List<String>, cwd: String = "/root", env: Map<String, String> = emptyMap()): List<String> = buildList {
        add(p.proot)
        if (p.termux) {
            add("--kill-on-exit")
            // Hard links Android won't make become symlinks: dpkg and apt need them.
            add("--link2symlink")
        }
        // Root inside: apt and dpkg want it.
        add("-0")
        add("-r"); add(p.rootfs)
        BINDS.forEach { add("-b"); add(it) }
        add("-w"); add(cwd)
        add("/usr/bin/env"); add("-i")
        addAll(GUEST_ENV.filter { e -> env.keys.none { e.startsWith("$it=") } })
        env.forEach { (k, v) -> add("$k=$v") }
        addAll(argv)
    }

    /** What proot itself needs in its environment on Android. */
    fun hostEnv(p: ProotPaths): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", p.tmp)
        if (p.loader.isNotEmpty()) put("PROOT_LOADER", p.loader)
        if (p.libDir.isNotEmpty()) put("LD_LIBRARY_PATH", p.libDir)
    }

    /** A shell line in the guest. */
    fun bash(p: ProotPaths, script: String, env: Map<String, String> = emptyMap()) = command(p, listOf("/bin/bash", "-lc", script), env = env)
}

/** The guest's setup: what Claude Code and the bridge need. Idempotent: run again to update. */
object GuestSetup {
    /** Files written into the rootfs before anything runs. */
    val FILES = mapOf(
        // Android has no /etc/resolv.conf for the guest to read.
        "etc/resolv.conf" to "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
        "etc/hosts" to "127.0.0.1 localhost\n::1 localhost ip6-localhost\n",
        // apt drops to its _apt user, which proot's fake root can't become.
        "etc/apt/apt.conf.d/01-ygg" to "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n",
    )

    /** The setup script, in the app's assets: packages, Claude Code, the bridge's key. */
    const val SCRIPT_ASSET = "linux/setup.sh"

    /** Where the setup script goes in the guest. */
    const val SCRIPT_PATH = "root/ygg-setup.sh"

    /** The bridge, as the always-on service runs it. proot shares the phone's network, so 127.0.0.1 is the phone's. */
    val BRIDGE = listOf("python3", "/root/ygg-bridge/ygg_bridge.py", "serve")
    val BRIDGE_ENV = mapOf("YGG_BRIDGE_HOST" to "127.0.0.1")

    /** `claude auth login` wants a terminal: util-linux's `script` gives it one, and passes your code through. */
    val LOGIN = listOf("script", "-qfec", "stty cols 4000 rows 50 2>/dev/null; claude auth login", "/dev/null")
}
