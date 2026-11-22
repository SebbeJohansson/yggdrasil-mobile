package io.github.sebbejohansson.yggdrasil.linux

import java.io.File
import java.io.IOException
import java.io.InputStream

/** What unpacking needs from the file system beyond plain files: links and modes. */
interface FileOps {
    fun symlink(target: String, link: File)
    fun chmod(file: File, mode: Int)
}

/**
 * Unpacks a tar stream (ustar, GNU long names, pax paths) into [dest]:
 * files, folders, symlinks, and hard links as copies (Android's app storage
 * doesn't take hard links everywhere). Devices and fifos are skipped; proot
 * binds /dev. Nothing lands outside [dest].
 */
object Tar {
    private const val BLOCK = 512

    private class Header(val name: String, val mode: Int, val size: Long, val type: Char, val link: String)

    fun extract(input: InputStream, dest: File, ops: FileOps, onFile: (String) -> Unit = {}): Int {
        val root = dest.canonicalFile
        root.mkdirs()
        val block = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        val dirModes = mutableListOf<Pair<File, Int>>()
        var count = 0
        while (true) {
            if (!readFully(input, block)) break
            if (block.all { it.toInt() == 0 }) break
            val h = header(block)
            when (h.type) {
                'L' -> { longName = String(readBody(input, h.size)).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readBody(input, h.size)).trimEnd('\u0000'); continue }
                'x' -> {
                    pax(String(readBody(input, h.size))).let { paxPath = it["path"]; paxLink = it["linkpath"] }
                    continue
                }
                'g' -> { skip(input, h.size); continue }
            }
            val name = paxPath ?: longName ?: h.name
            val link = paxLink ?: longLink ?: h.link
            longName = null; longLink = null; paxPath = null; paxLink = null
            val out = inside(root, name) ?: run { skip(input, h.size); null } ?: continue
            when (h.type) {
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isLink(out)) out.delete()
                    out.outputStream().use { o -> copy(input, o, h.size) }
                    skipPadding(input, h.size)
                    ops.chmod(out, h.mode)
                }
                '5' -> {
                    out.mkdirs()
                    ops.chmod(out, 0b111_101_101) // writable while unpacking; the real mode at the end
                    dirModes += out to h.mode
                    skip(input, h.size)
                }
                '2' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isLink(out)) out.delete()
                    ops.symlink(link, out)
                    skip(input, h.size)
                }
                '1' -> {
                    val target = inside(root, link)
                    out.parentFile?.mkdirs()
                    if (target != null && target.isFile) {
                        if (out.exists() || isLink(out)) out.delete()
                        target.copyTo(out, overwrite = true)
                        ops.chmod(out, h.mode)
                    }
                    skip(input, h.size)
                }
                else -> skip(input, h.size)
            }
            count++
            onFile(name)
        }
        dirModes.asReversed().forEach { (dir, mode) -> ops.chmod(dir, mode or 0b111_000_000) }
        return count
    }

    private fun isLink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** The entry's place under [root], or null when it would climb out. */
    private fun inside(root: File, name: String): File? {
        val clean = name.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        return File(root, clean)
    }

    private fun header(b: ByteArray): Header {
        fun str(from: Int, len: Int) = String(b, from, len, Charsets.UTF_8).substringBefore('\u0000')
        val prefix = if (str(257, 5) == "ustar") str(345, 155) else ""
        val name = str(0, 100).let { if (prefix.isNotEmpty()) "$prefix/$it" else it }
        return Header(name, octal(b, 100, 8).toInt(), size(b), b[156].toInt().toChar(), str(157, 100))
    }

    private fun octal(b: ByteArray, from: Int, len: Int): Long {
        val s = String(b, from, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    /** Octal, or base-256 for big files (GNU). */
    private fun size(b: ByteArray): Long {
        if (b[124].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        return octal(b, 124, 12)
    }

    private fun pax(text: String): Map<String, String> = buildMap {
        var rest = text
        while (rest.isNotEmpty()) {
            val space = rest.indexOf(' ')
            if (space < 0) break
            val len = rest.substring(0, space).toIntOrNull() ?: break
            val record = rest.substring(space + 1, minOf(len, rest.length)).trimEnd('\n')
            record.indexOf('=').takeIf { it > 0 }?.let { put(record.substring(0, it), record.substring(it + 1)) }
            rest = rest.substring(minOf(len, rest.length))
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw IOException("the archive ends inside a header")
            }
            off += n
        }
        return true
    }

    private fun readBody(input: InputStream, size: Long): ByteArray {
        val body = ByteArray(size.toInt())
        if (size > 0) readFully(input, body)
        skipPadding(input, size)
        return body
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("the archive ends inside a file")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - size % BLOCK) % BLOCK
        skipBytes(input, pad)
    }

    private fun skip(input: InputStream, size: Long) {
        skipBytes(input, size)
        skipPadding(input, size)
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("the archive ends early")
            left -= r
        }
    }
}

/** On the JVM (tests) and on Android 8+: java.nio does links and modes. */
object NioFileOps : FileOps {
    override fun symlink(target: String, link: File) {
        java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
    }

    override fun chmod(file: File, mode: Int) {
        val perms = mutableSetOf<java.nio.file.attribute.PosixFilePermission>()
        val all = java.nio.file.attribute.PosixFilePermission.entries
        // OWNER_READ is bit 8 (0400) down to OTHERS_EXECUTE at bit 0.
        all.forEachIndexed { i, p -> if (mode and (1 shl (8 - i)) != 0) perms += p }
        runCatching { java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms) }
    }
}
