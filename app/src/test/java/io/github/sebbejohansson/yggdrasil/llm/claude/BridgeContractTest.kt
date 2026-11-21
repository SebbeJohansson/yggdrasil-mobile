package io.github.sebbejohansson.yggdrasil.llm.claude

import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.llm.complete
import io.github.sebbejohansson.yggdrasil.update.InMemoryTokenVault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * The app's side of the bridge against the real bridge (assets/bridge/ygg_bridge.py),
 * run here with Python and a fake `claude`. Skipped where there's no python3.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeContractTest {
    private lateinit var dir: File
    private lateinit var bridge: Process
    private var port = 0

    private val fakeClaude = """
        #!/usr/bin/env python3
        import json, sys
        a = sys.argv[1:]
        if a == ["--version"]: print("2.9.0 (Claude Code)"); sys.exit(0)
        if a[0] == "-p" and "--cloud" in a:
            sid = a[a.index("--cloud") + 1]
            print(json.dumps({"ok": sid != "session_gone", "session_id": sid, "error": "not found"})); sys.exit(0)
        if a[0] == "-p": print(json.dumps({"is_error": False, "result": "Claude says: " + a[1]})); sys.exit(0)
        sys.exit(3)
    """.trimIndent()

    private fun python(): Boolean = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Before fun startBridge() {
        assumeTrue("python3 is needed to run the bridge", python())
        dir = Files.createTempDirectory("bridge").toFile()
        File(dir, "claude").apply { writeText(fakeClaude); setExecutable(true) }
        val script = File("src/main/assets/bridge/ygg_bridge.py").absoluteFile
        port = ServerSocket(0).use { it.localPort }
        bridge = ProcessBuilder("python3", script.path, "serve").apply {
            environment().putAll(mapOf("YGG_BRIDGE_HOME" to "${dir.path}/home", "YGG_BRIDGE_PORT" to "$port", "CLAUDE_BIN" to "${dir.path}/claude"))
            redirectErrorStream(true)
        }.start()
        // Up once it prints its line.
        bridge.inputStream.bufferedReader().readLine()
    }

    @After fun stopBridge() {
        if (::bridge.isInitialized) bridge.destroy()
    }

    private fun key() = File(dir, "home/key").readText().trim()

    @Test fun theAppAndTheBridgeAgree() = runBlocking {
        val vault = InMemoryTokenVault()
        val client = BridgeClient(vault, "http://127.0.0.1:$port")
        val provider = ClaudeBridgeProvider(client)

        assertEquals("2.9.0 (Claude Code)", client.health()!!.claude)
        // Running, but no key yet: the provider says what to do, and the bridge refuses.
        assertTrue((provider.status() as ProviderStatus.NeedsSetup).reason.contains("key"))
        val refused = runCatching { client.sessions() }.exceptionOrNull() as BridgeException
        assertEquals(401, refused.status)

        assertTrue(client.setKey(key()))
        assertEquals(ProviderStatus.Ready, provider.status())
        assertEquals(emptyList<BridgeSession>(), client.sessions())
        client.message("session_abc", "Also add tests")
        val gone = runCatching { client.message("session_gone", "hi") }.exceptionOrNull() as BridgeException
        assertTrue(gone.message!!.contains("not found"))
        val answer = provider.complete(LlmRequest(listOf(LlmMessage(LlmRole.USER, "What is Ygg?"))))
        assertEquals("Claude says: What is Ygg?", answer)
    }

    @Test fun noBridgeIsNotAnError() = runBlocking {
        val client = BridgeClient(InMemoryTokenVault("k".repeat(32)), "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}")
        assertEquals(null, client.health())
        assertTrue((ClaudeBridgeProvider(client).status() as ProviderStatus.NeedsSetup).reason.contains("ygg-bridge start"))
    }

    @Test fun theInstallCommandWritesTheBridgeAsItIs() {
        val command = BridgeClient.installCommand(RuntimeEnvironment.getApplication()).replace("bash install.sh\n", "")
        val home = Files.createTempDirectory("termux").toFile()
        val run = ProcessBuilder("bash", "-c", command).apply { environment()["HOME"] = home.path }.start()
        assertEquals(0, run.waitFor())
        for (name in listOf("ygg_bridge.py", "install.sh")) {
            assertEquals(File("src/main/assets/bridge/$name").readText(), File(home, "ygg-bridge/$name").readText())
        }
    }
}
