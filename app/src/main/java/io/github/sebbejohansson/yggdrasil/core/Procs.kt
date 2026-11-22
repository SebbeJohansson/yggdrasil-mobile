package io.github.sebbejohansson.yggdrasil.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The app's processes as the kernel has them, Ygg's own Linux included:
 * proot and everything in Ubuntu (the bridge, Claude Code, herdr, git) are
 * separate processes, which Android's per-app memory and CPU leave out.
 * Android lets an app read /proc for its own uid's processes.
 */
data class ProcSample(
    val pid: Int,
    /** What it runs, as its command line says. */
    val command: String,
    val group: String,
    /** Resident memory. Shared libraries count in each process that maps them. */
    val rssKb: Long,
    /** CPU time used since it started, in clock ticks (100 a second on Android). */
    val cpuTicks: Long,
    val threads: Int,
)

/** A group's share right now: CPU over the last interval, memory, how many processes. */
data class ProcGroup(val group: String, val cpuPercent: Double, val rssKb: Long, val processes: Int, val commands: List<String>)

object Procs {
    const val TICKS_PER_SECOND = 100.0

    /** Utime + stime from /proc/<pid>/stat; the name in parentheses may hold spaces, so fields count from the last ')'. */
    fun cpuTicks(stat: String): Long {
        val rest = stat.substringAfterLast(')').trim().split(' ')
        // rest[0] is the state (field 3); utime is field 14, stime 15.
        return (rest.getOrNull(11)?.toLongOrNull() ?: 0L) + (rest.getOrNull(12)?.toLongOrNull() ?: 0L)
    }

    /** A line of /proc/<pid>/status, like VmRSS or Threads, as a number. */
    fun statusValue(status: String, key: String): Long? =
        status.lineSequence().firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    /** The real uid, the first number on the Uid line. */
    fun uid(status: String): Int? = statusValue(status, "Uid")?.toInt()

    /** What a process is, from its command line (NUL-separated). */
    fun group(command: String, isApp: Boolean): String {
        val c = command.lowercase()
        return when {
            isApp -> "Ygg"
            "libproot" in c -> "Linux layer (proot)"
            "ygg_bridge.py" in c -> "Claude bridge"
            Regex("""(^|/|\s)herdr(\s|$)""").containsMatchIn(c) -> "herdr"
            "claude" in c -> "Claude Code"
            Regex("""(^|/)(git|git-[a-z-]+)(\s|$)""").containsMatchIn(c) -> "git"
            Regex("""(^|/)(apt|apt-get|dpkg|http)(\s|$)""").containsMatchIn(c) -> "apt"
            Regex("""(^|/)(node|npm|npx)(\s|$)""").containsMatchIn(c) -> "Node"
            Regex("""(^|/)(python3?)(\s|$)""").containsMatchIn(c) -> "Python"
            Regex("""(^|/)(bash|sh|script)(\s|$)""").containsMatchIn(c) -> "Shell"
            else -> "Other"
        }
    }

    /**
     * Groups two readings [interval] apart: CPU is the ticks used between
     * them, as a share of one core (100% is one core flat out).
     */
    fun groups(before: List<ProcSample>, after: List<ProcSample>, intervalMs: Long): List<ProcGroup> {
        val prev = before.associateBy { it.pid }
        val seconds = (intervalMs / 1000.0).coerceAtLeast(0.001)
        return after.groupBy { it.group }.map { (g, ps) ->
            // A process that started since the last reading counts from zero.
            val ticks = ps.sumOf { p -> (p.cpuTicks - (prev[p.pid]?.cpuTicks ?: p.cpuTicks)).coerceAtLeast(0) }
            ProcGroup(g, ticks / TICKS_PER_SECOND / seconds * 100.0, ps.sumOf { it.rssKb }, ps.size, ps.map { it.command }.distinct().take(6))
        }.sortedWith(compareByDescending<ProcGroup> { it.cpuPercent }.thenByDescending { it.rssKb })
    }

    /** Every process of [uid] now. A process gone mid-read is skipped. Call off the main thread. */
    fun read(uid: Int = android.os.Process.myUid(), appPid: Int = android.os.Process.myPid()): List<ProcSample> =
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            runCatching {
                val status = File(dir, "status").readText()
                if (uid(status) != uid) return@runCatching null
                val pid = dir.name.toInt()
                val command = File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000').replace('\u0000', ' ')
                    .ifBlank { status.lineSequence().firstOrNull { it.startsWith("Name:") }?.substringAfter(':')?.trim().orEmpty() }
                ProcSample(
                    pid = pid,
                    command = command.take(200),
                    group = group(command, pid == appPid),
                    rssKb = statusValue(status, "VmRSS") ?: 0,
                    cpuTicks = cpuTicks(File(dir, "stat").readText()),
                    threads = statusValue(status, "Threads")?.toInt() ?: 1,
                )
            }.getOrNull()
        }
}

/** The phone's battery right now: the whole phone, as Android doesn't tell an app its own share. */
data class BatteryNow(
    val percent: Int,
    val charging: Boolean,
    /** Current in milliamps: negative is drawn from the battery. Null where the phone doesn't say. */
    val currentMa: Int?,
    val temperatureC: Double?,
) {
    companion object {
        fun read(context: Context): BatteryNow {
            val bm = context.getSystemService(BatteryManager::class.java)
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val micro = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            return BatteryNow(
                percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                // Phones report microamps, a few milliamps; a value near zero or Integer.MIN_VALUE means it isn't known.
                currentMa = micro.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { if (kotlin.math.abs(it) > 20_000) it / 1000 else it },
                temperatureC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            )
        }
    }
}

/** What takes the phone's storage: the models, Ygg's Linux by part, the app's own. */
data class DiskItem(val label: String, val bytes: Long, val note: String? = null)

object DiskUse {
    /** A folder's size, links not followed (proot's hard links became symlinks), unreadable parts skipped. */
    fun size(path: File): Long {
        if (!path.exists()) return 0
        var total = 0L
        runCatching {
            Files.walkFileTree(path.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
            })
        }
        return total
    }

    /** Measures it all; seconds for a gigabyte of Ubuntu. Call off the main thread. */
    fun measure(context: Context, modelsDir: File, rootfs: File): List<DiskItem> {
        val root = File(rootfs, "root")
        val parts = listOf(
            Triple("Claude Code", listOf(File(root, ".local/share/claude-code")), "the CLI, one version kept"),
            Triple("herdr", listOf(File(root, ".local/share/herdr")), null),
            Triple("Repos to work in", listOf(File(root, ".ygg-bridge/work")), "the phone's checkouts for local sessions"),
            Triple("Repos for cloud starts", listOf(File(root, ".ygg-bridge/repos"), File(root, ".ygg-bridge/stubs")), "full checkouts and stubs"),
            Triple("Claude's transcripts and settings", listOf(File(root, ".claude"), File(root, ".claude.json"), File(root, ".ygg-bridge/accounts")), "every account"),
            Triple("apt's download cache", listOf(File(rootfs, "var/cache/apt"), File(rootfs, "var/lib/apt/lists")), "safe to clear with apt-get clean"),
        )
        val measured = parts.map { (label, dirs, note) -> DiskItem(label, dirs.sumOf { size(it) }, note) }
        val linuxAll = size(rootfs)
        return listOf(DiskItem("Models", size(modelsDir), "Gemma and the embeddings")) +
            measured +
            DiskItem("The rest of Ubuntu", (linuxAll - measured.sumOf { it.bytes }).coerceAtLeast(0), "packages: Python, git, the system") +
            DiskItem("Ygg's data", (size(File(context.filesDir.parentFile, "databases")) + size(context.filesDir) - size(File(context.filesDir, "linux")) -
                size(File(context.filesDir, "models"))).coerceAtLeast(0), "memory, alerts, settings") +
            DiskItem("Cache", size(context.cacheDir), "safe to clear")
    }
}
