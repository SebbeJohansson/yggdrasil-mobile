package io.github.sebbejohansson.yggdrasil.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Ygg's own Linux, for real, on this machine: Ubuntu's base (amd64 here,
 * arm64 on the phone) unpacked by [Tar], the guest set up by [GuestSetup]
 * through a desktop proot with [Proot]'s command lines, then the bridge
 * started in it and reached on 127.0.0.1. What it can't cover is Android's
 * proot build and the phone.
 *
 * Downloads ~30 MB and installs packages: runs only with YGG_LINUX_IT=1 and a
 * proot on the PATH.
 */
class LinuxIntegrationTest {
    private val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz"
    private val sha = "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86"

    private fun run(cmd: List<String>, env: Map<String, String>, timeoutS: Long = 900): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); println("  | $it") } }.apply { start() }
        val done = p.waitFor(timeoutS, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        reader.join(2_000)
        return (if (done) p.exitValue() else -1) to out.toString()
    }

    @Test fun ubuntuUnpacksSetsUpAndRunsTheBridge() {
        assumeTrue("set YGG_LINUX_IT=1 to run", System.getenv("YGG_LINUX_IT") == "1")
        val proot = listOf("/usr/bin/proot", "/usr/local/bin/proot").firstOrNull { File(it).canExecute() }
        assumeTrue("needs proot", proot != null)

        val work = File(System.getProperty("java.io.tmpdir"), "ygg-linux-it").apply { mkdirs() }
        val tarball = File(work, "base.tar.gz")
        if (!tarball.exists()) URL(url).openStream().use { i -> tarball.outputStream().use { i.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(tarball.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(sha, digest)

        val rootfs = File(work, "ubuntu").apply { deleteRecursively() }
        val count = GZIPInputStream(tarball.inputStream().buffered()).use { Tar.extract(it, rootfs, NioFileOps) }
        println("unpacked $count entries")
        assertTrue(File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(rootfs, "bin").toPath()))

        GuestSetup.FILES.forEach { (path, text) -> File(rootfs, path).apply { parentFile.mkdirs() }.writeText(text) }
        File(rootfs, "root/ygg-bridge").mkdirs()
        File("src/main/assets/bridge/ygg_bridge.py").copyTo(File(rootfs, "root/ygg-bridge/ygg_bridge.py"), overwrite = true)

        val paths = ProotPaths(proot!!, "", "", rootfs.path, File(work, "tmp").apply { mkdirs() }.path, termux = false)
        val env = Proot.hostEnv(paths)
        // This machine reaches the internet through a proxy with its own CA; the phone doesn't.
        val ca = File("/root/.ccr/ca-bundle.crt").takeIf { it.exists() }?.also { it.copyTo(File(rootfs, "etc/ygg-ca.crt"), overwrite = true) }
        val guest = buildMap {
            listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy").forEach { k -> System.getenv(k)?.let { put(k, it) } }
            if (ca != null) listOf("SSL_CERT_FILE", "CURL_CA_BUNDLE", "NODE_EXTRA_CA_CERTS", "GIT_SSL_CAINFO").forEach { put(it, "/etc/ygg-ca.crt") }
        }
        File("src/main/assets/${GuestSetup.SCRIPT_ASSET}").copyTo(File(rootfs, GuestSetup.SCRIPT_PATH), overwrite = true)
        val (code, out) = run(Proot.command(paths, listOf("/bin/bash", "/${GuestSetup.SCRIPT_PATH}"), env = guest), env)
        assertEquals("setup failed:\n${out.takeLast(2000)}", 0, code)
        assertTrue(out.contains("Claude Code"))
        assertTrue(File(rootfs, "root/.ygg-bridge/key").readText().isNotBlank())

        // The bridge, as the service runs it, on a free port.
        val port = ServerSocket(0).use { it.localPort }
        val bridge = ProcessBuilder(Proot.command(paths, GuestSetup.BRIDGE, env = GuestSetup.BRIDGE_ENV + ("YGG_BRIDGE_PORT" to "$port")))
            .redirectErrorStream(true).apply { environment().putAll(env) }.start()
        try {
            var health = ""
            repeat(30) {
                if (health.isEmpty()) {
                    Thread.sleep(1_000)
                    health = runCatching {
                        (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).run {
                            connectTimeout = 1_000
                            inputStream.bufferedReader().readText()
                        }
                    }.getOrDefault("")
                }
            }
            println("health: $health")
            assertTrue(health, health.contains("\"ok\": true") && health.contains("Claude Code"))
        } finally {
            bridge.destroy()
        }
    }
}
