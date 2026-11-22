package io.github.sebbejohansson.yggdrasil.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TarTest {
    /** A tar made by Python's tarfile: a file, an executable, a long name, a symlink, a hard link, and one that climbs out. */
    private fun archive(dir: File): File {
        val script = """
            import io, tarfile, sys
            t = tarfile.open(sys.argv[1], "w", format=tarfile.GNU_FORMAT)
            def add(name, data=b"", mode=0o644, kind=tarfile.REGTYPE, link=""):
                i = tarfile.TarInfo(name); i.mode = mode; i.type = kind; i.linkname = link; i.size = len(data) if kind == tarfile.REGTYPE else 0
                t.addfile(i, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
            add("./usr", kind=tarfile.DIRTYPE, mode=0o755)
            add("./usr/bin", kind=tarfile.DIRTYPE, mode=0o555)
            add("./usr/bin/hello", b"#!/bin/sh\necho hi\n", 0o755)
            add("./bin", kind=tarfile.SYMTYPE, link="usr/bin")
            add("./usr/bin/hi", kind=tarfile.LNKTYPE, link="./usr/bin/hello", mode=0o755)
            add("./usr/share/" + "x" * 120 + "/notes.txt", b"long")
            add("../evil", b"no")
            t.close()
        """.trimIndent()
        val tar = File(dir, "t.tar")
        val p = ProcessBuilder("python3", "-c", script, tar.path).redirectErrorStream(true).start()
        assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
        return tar
    }

    @Test fun unpacksFilesLinksModesAndNothingOutside() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val dir = Files.createTempDirectory("tar").toFile()
        val out = File(dir, "root")
        val count = archive(dir).inputStream().use { Tar.extract(it, out, NioFileOps) }
        assertEquals(6, count)
        assertEquals("#!/bin/sh\necho hi\n", File(out, "usr/bin/hello").readText())
        assertTrue(File(out, "usr/bin/hello").canExecute())
        assertTrue(Files.isSymbolicLink(File(out, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(out, "bin").toPath()).toString())
        assertEquals("#!/bin/sh\necho hi\n", File(out, "usr/bin/hi").readText())
        assertEquals("long", File(out, "usr/share/" + "x".repeat(120) + "/notes.txt").readText())
        assertFalse(File(dir, "evil").exists())
        // A read-only folder in the archive stays writable for its owner, so a later update can write.
        assertTrue(File(out, "usr/bin").canWrite())
    }
}

class ProotTest {
    private val phone = ProotPaths("/lib/libproot.so", "/lib/libproot-loader.so", "/lib", "/data/linux/ubuntu", "/cache/proot")

    @Test fun commandLineForThePhone() {
        val cmd = Proot.command(phone, listOf("python3", "x.py"), env = mapOf("YGG_BRIDGE_HOST" to "127.0.0.1", "HOME" to "/root"))
        assertEquals(listOf("/lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", "/data/linux/ubuntu"), cmd.take(6))
        assertTrue(cmd.containsAll(listOf("-b", "/dev", "/proc/self/fd:/dev/fd", "-w", "/root", "/usr/bin/env", "-i")))
        assertEquals(listOf("python3", "x.py"), cmd.takeLast(2))
        // Given env wins over the defaults, once.
        assertEquals(1, cmd.count { it.startsWith("HOME=") })
        assertTrue("DISABLE_AUTOUPDATER=1" in cmd && "YGG_BRIDGE_HOST=127.0.0.1" in cmd)
        assertEquals(
            mapOf("PROOT_TMP_DIR" to "/cache/proot", "PROOT_LOADER" to "/lib/libproot-loader.so", "LD_LIBRARY_PATH" to "/lib"),
            Proot.hostEnv(phone),
        )
    }

    @Test fun aDesktopProotGetsNoTermuxFlags() {
        val cmd = Proot.command(phone.copy(proot = "/usr/bin/proot", loader = "", libDir = "", termux = false), listOf("true"))
        assertFalse("--kill-on-exit" in cmd || "--link2symlink" in cmd)
        assertEquals(mapOf("PROOT_TMP_DIR" to "/cache/proot"), Proot.hostEnv(phone.copy(loader = "", libDir = "")))
    }
}
