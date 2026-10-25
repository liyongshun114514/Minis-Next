package com.openminis.app.sandbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Regression cover for the archive-root member (`./` / `.`).
 *
 * GNU tar — including the Ubuntu base image shipped in the APK — writes the
 * root as a directory member named `./` (type `5`). The production
 * [TarExtractor] used to reject it through
 * [SandboxDistribution.sanitizeArchivePath], so a perfectly valid image failed
 * at the first 512-byte header and the terminal could not be initialised.
 *
 * These tests drive the *production* extractor (no copy of the algorithm) with
 * valid archives only: a root directory member followed by ordinary prefixed
 * files, plus the GNU long-name / PAX / ustar-prefix encodings of the same
 * shape. [SandboxDistribution.sanitizeArchivePath] and its callers are
 * unchanged; only a directory member may name the archive root.
 */
class TarExtractorRootEntryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "tar-root-entry-${System.nanoTime()}")
        root.mkdirs()
        assertTrue(root.isDirectory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── root member forms ───────────────────────────────────────────────────

    @Test
    fun `root directory entry dot slash is accepted`() {
        val tar = buildTar {
            dir("./")
            file("./etc/passwd", "root:x:0:0:root:/root:/bin/bash\n")
            file("./usr/bin/hello", "hi\n")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("root:x:0:0:root:/root:/bin/bash\n", File(root, "etc/passwd").readText())
        assertEquals("hi\n", File(root, "usr/bin/hello").readText())
        assertTrue(File(root, "etc").isDirectory)
        // The root member names the extraction root itself, not a "." entry.
        assertFalse(File(root, ".").isFile)
    }

    @Test
    fun `root directory entry dot is accepted`() {
        val tar = buildTar {
            dir(".")
            file("./usr/bin/bash", "#!/bin/sh\n")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("#!/bin/sh\n", File(root, "usr/bin/bash").readText())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `root entry via pax path record is accepted`() {
        val tar = buildTar {
            pax("path", "./")
            dir("pax-root-placeholder")
            file("./etc/hostname", "minis\n")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("minis\n", File(root, "etc/hostname").readText())
    }

    @Test
    fun `root entry via gnu long name is accepted`() {
        val tar = buildTar {
            longName("./")
            dir("gnu-root-placeholder")
            file("./etc/os-release", "ID=ubuntu\n")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("ID=ubuntu\n", File(root, "etc/os-release").readText())
    }

    @Test
    fun `ordinary files keep their prefix after a root entry`() {
        val tar = buildTar {
            dir("./")
            fileWithPrefix("passwd", "./etc", "pw\n")
            file("./usr/lib/ld-linux-aarch64.so.1", "")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("pw\n", File(root, "etc/passwd").readText())
        assertTrue(File(root, "usr/lib").isDirectory)
    }

    @Test
    fun `hard link members with dot slash names are materialised`() {
        val tar = buildTar {
            dir("./")
            dir("./usr/bin/")
            file("./usr/bin/perl", "perl-bytes")
            hardLink("./usr/bin/perl5.38.2", "./usr/bin/perl")
        }

        TarExtractor.extract(ByteArrayInputStream(tar), root)

        assertEquals("perl-bytes", File(root, "usr/bin/perl").readText())
        assertEquals("perl-bytes", File(root, "usr/bin/perl5.38.2").readText())
    }

    // ── archive builder (valid headers only) ───────────────────────────────

    private inner class Builder {
        val out = ByteArrayOutputStream()

        fun dir(name: String) = writeHeader(name, 0, '5', "", "")

        fun file(name: String, content: String) = payload(name, content.toByteArray(), '0')

        fun fileWithPrefix(name: String, prefix: String, content: String) {
            val data = content.toByteArray()
            writeHeader(name, data.size.toLong(), '0', "", prefix)
            writeData(data)
        }

        fun hardLink(name: String, target: String) {
            writeHeader(name, 0, '1', target, "")
        }

        fun longName(name: String) {
            val data = (name + "\u0000").toByteArray()
            writeHeader("././@LongLink", data.size.toLong(), 'L', "", "")
            writeData(data)
        }

        fun pax(key: String, value: String) {
            val data = paxRecord(key, value).toByteArray()
            writeHeader("PaxHeaders/root", data.size.toLong(), 'x', "", "")
            writeData(data)
        }

        private fun payload(name: String, data: ByteArray, type: Char) {
            writeHeader(name, data.size.toLong(), type, "", "")
            writeData(data)
        }


        private fun writeData(data: ByteArray) {
            out.write(data)
            val remainder = data.size % 512
            if (remainder != 0) out.write(ByteArray(512 - remainder))
        }

        private fun writeHeader(
            name: String,
            size: Long,
            type: Char,
            link: String,
            prefix: String,
        ) {
            val h = ByteArray(512)
            put(h, 0, 100, name)
            put(h, 100, 8, if (type == '5') "0000755" else "0000644")
            put(h, 108, 8, "0000000")
            put(h, 116, 8, "0000000")
            put(h, 124, 12, String.format("%011o", size))
            put(h, 136, 12, "00000000000")
            h[156] = type.code.toByte()
            put(h, 157, 100, link)
            put(h, 257, 6, "ustar")
            h[262] = 0
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            put(h, 345, 155, prefix)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            var sum = 0L
            for (b in h) sum += (b.toInt() and 0xFF)
            put(h, 148, 8, String.format("%06o\u0000 ", sum))
            out.write(h)
        }

        private fun put(buf: ByteArray, offset: Int, width: Int, value: String) {
            val bytes = value.toByteArray()
            bytes.copyInto(buf, offset, 0, minOf(bytes.size, width))
        }

        fun build(): ByteArray {
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }

    private fun paxRecord(key: String, value: String): String {
        val body = "$key=$value\n"
        var length = body.toByteArray().size + 2
        while (true) {
            val candidate = "$length $body"
            val actual = candidate.toByteArray().size
            if (actual == length) return candidate
            length = actual
        }
    }

    private fun buildTar(block: Builder.() -> Unit): ByteArray =
        Builder().apply(block).build()
}
