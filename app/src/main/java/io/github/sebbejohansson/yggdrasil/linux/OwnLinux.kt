package io.github.sebbejohansson.yggdrasil.linux

import android.content.Context
import android.system.Os
import io.github.sebbejohansson.yggdrasil.core.NetMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Where Ygg's own Linux is in its setup. */
sealed interface LinuxState {
    data object NotInstalled : LinuxState
    data class Downloading(val bytes: Long, val total: Long) : LinuxState
    data class Unpacking(val files: Int) : LinuxState
    /** The setup script runs: packages, Claude Code, the bridge. */
    data class SettingUp(val line: String) : LinuxState
    data object Ready : LinuxState
    data class Failed(val message: String, val log: List<String> = emptyList()) : LinuxState
}

/** Android's own calls for links and modes. */
object AndroidFileOps : FileOps {
    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)
    override fun chmod(file: File, mode: Int) = runCatching { Os.chmod(file.path, mode and 0b111_111_111) }.let { }
}

/**
 * Ygg's own Linux: Ubuntu's official base, under proot shipped in the app,
 * kept in the app's storage. No other app is needed, and it runs under Ygg's
 * own foreground service ([LinuxBridgeService]), so it's on as long as Ygg is.
 *
 * Setup: download Ubuntu's base (~30 MB, checked against Ubuntu's SHA-256),
 * unpack it, then run assets/linux/setup.sh in it (packages, Claude Code from
 * Anthropic's download host, the bridge's key). About 1 GB once done.
 */
class OwnLinux(private val context: Context) {
    private val home = File(context.filesDir, "linux")
    val rootfs = File(home, "ubuntu")
    private val ready = File(home, "ready")
    private val lock = Mutex()

    private val _state = MutableStateFlow(if (ready.exists()) LinuxState.Ready else LinuxState.NotInstalled)
    val state: StateFlow<LinuxState> = _state

    val installed: Boolean get() = ready.exists()

    val paths: ProotPaths
        get() {
            val lib = context.applicationInfo.nativeLibraryDir
            return ProotPaths(
                proot = "$lib/libproot.so",
                loader = "$lib/libproot-loader.so",
                libDir = lib,
                rootfs = rootfs.path,
                tmp = File(context.cacheDir, "proot").apply { mkdirs() }.path,
            )
        }

    /** Starts a program in the guest. The caller reads its output and ends it. */
    fun start(argv: List<String>, env: Map<String, String> = emptyMap()): Process =
        ProcessBuilder(Proot.command(paths, argv, env = env)).redirectErrorStream(true)
            .apply { environment().putAll(Proot.hostEnv(paths)) }.start()

    /**
     * Puts the app's bridge into the guest when it differs (after an app
     * update). True when it changed: the running one should restart.
     */
    fun syncBridge(): Boolean {
        if (!installed) return false
        val target = File(rootfs, "root/ygg-bridge/ygg_bridge.py")
        val fresh = context.assets.open("bridge/ygg_bridge.py").use { it.readBytes() }
        if (target.isFile && target.readBytes().contentEquals(fresh)) return false
        target.parentFile?.mkdirs()
        target.writeBytes(fresh)
        return true
    }

    /** The bridge's key, as the guest keeps it: Ygg reads it itself, no pasting. */
    fun bridgeKey(): String? = File(rootfs, "root/.ygg-bridge/key").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** Downloads, unpacks and sets up. Again later: updates the packages, Claude Code and the bridge. */
    suspend fun install() = lock.withLock {
        withContext(Dispatchers.IO) {
            val log = ArrayDeque<String>()
            try {
                home.mkdirs()
                if (!File(rootfs, "usr/bin").isDirectory) {
                    val tarball = download()
                    _state.value = LinuxState.Unpacking(0)
                    rootfs.deleteRecursively()
                    var n = 0
                    GZIPInputStream(tarball.inputStream().buffered(1 shl 16)).use {
                        Tar.extract(it, rootfs, AndroidFileOps) { if (++n % 200 == 0) _state.value = LinuxState.Unpacking(n) }
                    }
                    tarball.delete()
                }
                GuestSetup.FILES.forEach { (path, text) -> File(rootfs, path).apply { parentFile?.mkdirs() }.writeText(text) }
                File(rootfs, "root/ygg-bridge").mkdirs()
                copyAsset("bridge/ygg_bridge.py", File(rootfs, "root/ygg-bridge/ygg_bridge.py"))
                copyAsset(GuestSetup.SCRIPT_ASSET, File(rootfs, GuestSetup.SCRIPT_PATH))
                _state.value = LinuxState.SettingUp("Starting…")
                val p = start(listOf("/bin/bash", "/${GuestSetup.SCRIPT_PATH}"))
                p.inputStream.bufferedReader().forEachLine { line ->
                    log.addLast(line)
                    if (log.size > 60) log.removeFirst()
                    if (line.isNotBlank()) _state.value = LinuxState.SettingUp(line.take(160))
                }
                val code = p.waitFor()
                if (code != 0) throw IOException("setup ended with $code")
                ready.writeText(System.currentTimeMillis().toString())
                _state.value = LinuxState.Ready
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = LinuxState.Failed(e.message ?: e.javaClass.simpleName, log.toList())
            }
        }
    }

    /** Removes it all, for a fresh start. */
    suspend fun remove() = lock.withLock {
        withContext(Dispatchers.IO) {
            ready.delete()
            home.deleteRecursively()
            _state.value = LinuxState.NotInstalled
        }
    }

    private fun copyAsset(name: String, to: File) {
        to.parentFile?.mkdirs()
        context.assets.open(name).use { i -> to.outputStream().use { i.copyTo(it) } }
    }

    private suspend fun download(): File {
        val part = File(home, "ubuntu-base.tar.gz.part")
        val done = File(home, "ubuntu-base.tar.gz")
        if (done.isFile && sha256(done) == UBUNTU_SHA256) return done
        var have = if (part.isFile) part.length() else 0L
        val conn = (URL(UBUNTU_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        val started = System.nanoTime()
        val startAt = have
        try {
            val append = when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> { have = 0; false }
                else -> throw IOException("Ubuntu's server answered HTTP ${conn.responseCode}")
            }
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - last > 512 * 1024) { last = have; _state.value = LinuxState.Downloading(have, total) }
                    }
                }
            }
        } finally {
            NetMeter.count("linux", "ubuntu base", have - startAt, (System.nanoTime() - started) / 1_000_000)
            conn.disconnect()
        }
        if (sha256(part) != UBUNTU_SHA256) {
            part.delete()
            throw IOException("Ubuntu's download didn't match its SHA-256")
        }
        part.renameTo(done)
        return done
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Ubuntu 24.04's official base for arm64, and its SHA-256 from Ubuntu's SHA256SUMS. */
        const val UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
        const val UBUNTU_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    }
}

/**
 * `claude auth login`, in Ygg's own Linux: the link to open, and a way to give
 * back the code the browser shows. It runs under `script`, which wants a terminal.
 */
class LinuxLogin(private val linux: OwnLinux) {
    data class State(val running: Boolean = false, val url: String? = null, val lines: List<String> = emptyList(), val done: Boolean = false, val code: Int? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var process: Process? = null

    private val link = Regex("""https://\S+""")
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007]*\u0007")

    suspend fun start() = withContext(Dispatchers.IO) {
        process?.destroy()
        _state.value = State(running = true)
        val p = linux.start(GuestSetup.LOGIN)
        process = p
        val buf = StringBuilder()
        val reader = p.inputStream.reader()
        val chunk = CharArray(1024)
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            buf.append(chunk, 0, n)
            val text = ansi.replace(buf.toString(), "")
            val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(12)
            _state.update { s -> s.copy(lines = lines, url = s.url ?: lines.firstNotNullOfOrNull { l -> link.find(l)?.value }) }
        }
        val code = p.waitFor()
        _state.update { it.copy(running = false, done = code == 0, code = code) }
    }

    /** The code the browser shows, typed into the login. */
    fun send(code: String) {
        process?.outputStream?.let { runCatching { it.write((code.trim() + "\n").toByteArray()); it.flush() } }
    }

    fun cancel() {
        process?.destroy()
        _state.update { it.copy(running = false) }
    }
}
