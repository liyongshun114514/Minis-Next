package com.openminis.app.sandbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Runs the **production** [TarExtractor] against synthetic archives that model
 * the two things the Ubuntu base image needs and the three things an attacker
 * would try.
 *
 * Synthetic (fast, always runs): traversal, absolute paths, symlink-parent
 * writes, symlink-at-file-path, GNU/PAX long names, hard links to symlinks,
 * checksum/truncation failures.
 *
 * Real image (opt-in): set `MINIS_UBUNTU_ROOTFS=/path/ubuntu-base-arm64.tar.gz`
 * to extract the actual 24.04.5 arm64 tarball and assert the paths the guest
 * needs (glibc loader, bash, apt) are present. It writes ~100 MB to a temp dir,
 * so it is skipped unless explicitly requested.
 */
class UbuntuTarExtractionTest {

    private lateinit var target: File

    @Before
    fun setUp() {
        target = File(System.getProperty("java.io.tmpdir"), "ubuntu-tar-${System.nanoTime()}")
        target.mkdirs()
    }

    @After
    fun tearDown() {
        target.deleteRecursively()
    }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    fun `extracts directories files and preserves the exec bit`() {
        val tar = TarBuilder()
            .directory("usr/")
            .directory("usr/bin/")
            .file("usr/bin/apt", "#!/bin/sh\n", mode = 0b111_101_101)
            .file("etc/os-release", "ID=ubuntu\n")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)

        assertTrue(File(target, "usr/bin").isDirectory)
        assertTrue(File(target, "usr/bin/apt").canExecute())
        assertEquals("ID=ubuntu\n", File(target, "etc/os-release").readText())
        assertFalse(File(target, "usr/bin/apt").isDirectory)
    }

    @Test
    fun `creates symlinks including absolute and dot-dot targets`() {
        val tar = TarBuilder()
            .directory("etc/")
            .directory("usr/lib/")
            .symlink("etc/rmt", "/usr/sbin/rmt")
            .symlink("usr/lib/ld.so", "../lib/ld-linux-aarch64.so.1")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)

        assertTrue(Files.isSymbolicLink(File(target, "etc/rmt").toPath()))
        assertEquals("/usr/sbin/rmt", Files.readSymbolicLink(File(target, "etc/rmt").toPath()).toString())
        assertEquals(
            "../lib/ld-linux-aarch64.so.1",
            Files.readSymbolicLink(File(target, "usr/lib/ld.so").toPath()).toString(),
        )
    }

    // ── tar-slip defences ───────────────────────────────────────────────────

    @Test
    fun `rejects parent traversal`() {
        val tar = TarBuilder().file("../evil.txt", "pwned").build()
        expectFailure(tar, "refusing unsafe entry path")
        assertFalse(File(target.parentFile, "evil.txt").exists())
    }

    @Test
    fun `rejects absolute paths`() {
        val tar = TarBuilder().file("/data/local/tmp/evil.txt", "pwned").build()
        expectFailure(tar, "refusing unsafe entry path")
        assertFalse(File("/data/local/tmp/evil.txt").exists())
    }

    @Test
    fun `rejects writes through a symlinked parent directory`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "outside-${System.nanoTime()}")
        outside.mkdirs()
        try {
            val tar = TarBuilder()
                .symlink("etc", outside.absolutePath)
                .file("etc/passwd", "pwned")
                .build()
            expectFailure(tar, "refusing to traverse symlinked directory")
            assertFalse(File(outside, "passwd").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `replaces an existing symlink at a file path instead of following it`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "outside-file-${System.nanoTime()}")
        outside.writeText("original")
        try {
            val tar = TarBuilder()
                .symlink("etc", "/tmp") // placeholder, replaced below
                .symlink("victim", outside.absolutePath)
                .file("victim", "from-archive")
                .build()

            TarExtractor.extract(ByteArrayInputStream(tar), target)

            assertEquals("from-archive", File(target, "victim").readText())
            assertFalse("outside file must be untouched", Files.isSymbolicLink(File(target, "victim").toPath()))
            assertEquals("original", outside.readText())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `rejects a symlink entry that collides with a directory`() {
        val tar = TarBuilder()
            .directory("etc/")
            .symlink("etc", "/tmp")
            .build()
        expectFailure(tar, "collides with a directory")
    }

    // ── long names / pax ────────────────────────────────────────────────────

    @Test
    fun `honours pax path and linkpath`() {
        val deep = "usr/lib/aarch64-linux-gnu/" + "x".repeat(120) + ".so"
        val tar = TarBuilder()
            .pax(mapOf("path" to deep))
            .file("short-name", "long")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)
        assertEquals("long", File(target, deep).readText())
    }

    @Test
    fun `honours gnu long name header`() {
        val deep = "usr/share/" + "y".repeat(140)
        val tar = TarBuilder()
            .gnuLongName(deep)
            .file("truncated-name", "gnu")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)
        assertEquals("gnu", File(target, deep).readText())
    }

    // ── hard links ──────────────────────────────────────────────────────────

    @Test
    fun `hard link to a symlink yields a usable entry`() {
        val tar = TarBuilder()
            .directory("usr/")
            .directory("usr/bin/")
            .symlink("usr/bin/perl", "perl5.38.2")
            .hardLink("usr/bin/perl5.38.2", "usr/bin/perl")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)

        val path = File(target, "usr/bin/perl5.38.2").toPath()
        assertTrue("entry must exist", Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `hard link to a regular file shares the content`() {
        val tar = TarBuilder()
            .file("usr/bin/gunzip", "gz", mode = 0b111_101_101)
            .hardLink("usr/bin/uncompress", "usr/bin/gunzip")
            .build()

        TarExtractor.extract(ByteArrayInputStream(tar), target)
        assertEquals("gz", File(target, "usr/bin/uncompress").readText())
    }

    // ── corruption ──────────────────────────────────────────────────────────

    @Test
    fun `rejects a corrupted header checksum`() {
        val tar = TarBuilder().file("ok.txt", "ok").build()
        // Corrupt the name without fixing the checksum.
        tar[0] = 'X'.code.toByte()
        expectFailure(tar, "checksum")
    }

    @Test
    fun `throws on truncated file data`() {
        val tar = TarBuilder().file("big.txt", "x".repeat(4096)).build()
        val truncated = tar.copyOf(tar.size - 4096) // drop the payload, keep the header
        val stream = ByteArrayInputStream(truncated)
        try {
            TarExtractor.extract(stream, target)
            fail("expected IOException")
        } catch (expected: IOException) {
            // either "truncated data" or a checksum failure on the next block
        }
    }

    // ── real Ubuntu base archive (opt-in) ───────────────────────────────────

    @Test
    fun `real ubuntu base archive contains the paths the guest needs`() {
        val env = System.getenv("MINIS_UBUNTU_ROOTFS") ?: ""
        val archive = File(env.ifEmpty { "src/main/assets/ubuntu-base-arm64.tar.gz" })
        assumeTrue("set MINIS_UBUNTU_ROOTFS to run this test", env.isNotEmpty() && archive.isFile)

        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            TarExtractor.extract(gz, target)
        }

        assertTrue("glibc loader", File(target, "lib/ld-linux-aarch64.so.1").exists())
        assertTrue("/bin/sh", File(target, "bin/sh").exists())
        assertTrue("bash", File(target, "usr/bin/bash").exists())
        assertTrue("apt", File(target, "usr/bin/apt").exists())
        assertTrue("dpkg status", File(target, "var/lib/dpkg/status").isFile)
        assertTrue("ubuntu keyring", File(target, "usr/share/keyrings/ubuntu-archive-keyring.gpg").isFile)
        assertFalse("symlink not followed into /tmp", File(target, "etc/rmt").isDirectory)

        // 194 symlinks, 2 hard links, 655 directories, 2562 files in 24.04.5.
        assertTrue("archive should be substantial", File(target, "usr/bin").listFiles()!!.size > 100)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun expectFailure(tar: ByteArray, messagePart: String) {
        try {
            TarExtractor.extract(ByteArrayInputStream(tar), target)
            fail("expected IOException containing '$messagePart'")
        } catch (e: IOException) {
            assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains(messagePart))
        }
    }

    /** Minimal ustar writer; every header carries a valid checksum. */
    private class TarBuilder {
        private val out = ByteArrayOutputStream()
        private var pendingPax: Map<String, String>? = null
        private var pendingGnuName: String? = null

        fun directory(name: String): TarBuilder {
            header(name, 0, '5', mode = 0b111_101_101)
            return this
        }

        fun file(name: String, content: String, mode: Int = 0b110_100_100): TarBuilder {
            val data = content.toByteArray(Charsets.UTF_8)
            emitPaxIfPending()
            header(name, data.size.toLong(), '0', mode = mode)
            out.write(data)
            pad(data.size.toLong())
            return this
        }

        fun symlink(name: String, target: String): TarBuilder {
            emitPaxIfPending()
            header(name, 0, '2', linkName = target)
            return this
        }

        fun hardLink(name: String, target: String): TarBuilder {
            emitPaxIfPending()
            header(name, 0, '1', linkName = target)
            return this
        }

        fun pax(records: Map<String, String>): TarBuilder {
            pendingPax = records
            return this
        }

        fun gnuLongName(name: String): TarBuilder {
            pendingGnuName = name
            return this
        }

        private fun emitPaxIfPending() {
            pendingPax?.let { records ->
                val data = paxRecords(records)
                header("PaxHeaders/entry", data.size.toLong(), 'x')
                out.write(data)
                pad(data.size.toLong())
            }
            pendingPax = null
            pendingGnuName?.let { name ->
                val data = (name + "\u0000").toByteArray(Charsets.UTF_8)
                header("././@LongLink", data.size.toLong(), 'L')
                out.write(data)
                pad(data.size.toLong())
            }
            pendingGnuName = null
        }

        fun build(): ByteArray {
            emitPaxIfPending()
            out.write(ByteArray(1024))
            return out.toByteArray()
        }

        private fun pad(size: Long) {
            val remainder = (size % 512L).toInt()
            if (remainder != 0) out.write(ByteArray(512 - remainder))
        }

        private fun header(
            name: String,
            size: Long,
            typeFlag: Char,
            linkName: String = "",
            mode: Int = 0b110_100_100,
        ) {
            val h = ByteArray(512)
            write(h, 0, 100, name)
            write(h, 100, 8, String.format("%07o", mode))
            write(h, 108, 8, "0000000")
            write(h, 116, 8, "0000000")
            write(h, 124, 12, String.format("%011o", size))
            write(h, 136, 12, "00000000000")
            h[156] = typeFlag.code.toByte()
            if (linkName.isNotEmpty()) write(h, 157, 100, linkName)
            write(h, 257, 6, "ustar")
            h[262] = 0
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            var sum = 0L
            for (b in h) sum += (b.toInt() and 0xFF)
            write(h, 148, 8, String.format("%06o", sum) + "\u0000 ")
            out.write(h)
        }

        private fun write(buf: ByteArray, offset: Int, maxLen: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            bytes.copyInto(buf, offset, 0, minOf(bytes.size, maxLen))
        }

        /** PAX record: "<len> <key>=<value>\n" with `<len>` in bytes. */
        private fun paxRecords(records: Map<String, String>): ByteArray {
            val sb = StringBuilder()
            for ((k, v) in records) {
                val body = "$k=$v\n"
                var len = body.toByteArray(Charsets.UTF_8).size + 2
                while (true) {
                    val candidate = "$len $body"
                    val actual = candidate.toByteArray(Charsets.UTF_8).size
                    if (actual == len) break
                    len = actual
                }
                sb.append("$len ").append(body)
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
