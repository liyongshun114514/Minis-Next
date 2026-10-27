package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261028-PROOT-GROUPS] Naming the host supplementary group ids PRoot
 * passes into the Ubuntu guest.
 *
 * The fixture is the shape Ubuntu 24.04 base ships (`root`, `daemon`, …,
 * `nogroup`, a comment row and a trailing newline). The ids 3003, 9997, 20368
 * and 50368 are the numbers the original report happened to show; they are
 * dynamic app-range gids, not names of fixed system groups. They are test
 * *inputs* only — production reads the live process list, which is why the
 * arbitrary 4242/7777 ids are used too.
 */
class GuestGroupNamesTest {
    private val reported = listOf(3003, 9997, 20368, 50368)

    private val baseGroup = """
        root:x:0:
        daemon:x:1:
        bin:x:2:
        sys:x:3:
        adm:x:4:syslog
        tty:x:5:
        nogroup:x:65534:
        # a comment an admin left behind
    """.trimIndent() + "\n"

    private fun fixture(groupText: String = baseGroup): File {
        val root = Files.createTempDirectory("kairos-group-names-").toFile()
        File(root, "etc").mkdirs()
        File(root, "etc/group").writeText(groupText)
        return root
    }

    private fun <T> withFixture(groupText: String = baseGroup, block: (File) -> T): T {
        val root = fixture(groupText)
        try {
            return block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── the actual report: dynamic ids ─────────────────────────────────────

    @Test fun `every reported id gets a legal unique name`() = withFixture { root ->
        val out = GuestGroupNames.ensure(root, reported)
        assertTrue(out.error.orEmpty(), out.ok)
        assertFalse(out.created)
        assertEquals(listOf(3003, 9997, 20368, 50368), out.requested)
        assertEquals(reported.toSet(), out.added.keys)
        assertEquals(emptyList<Int>(), out.alreadyNamed)
        for ((gid, name) in out.added) {
            assertEquals(GuestGroupNames.groupName(gid), name)
            assertTrue("legal: $name", GuestGroupNames.isLegalName(name))
        }
        assertEquals(reported.size, out.added.values.toSet().size)

        val text = File(root, "etc/group").readText()
        // The whole original file is still the exact prefix: no row rewritten.
        assertTrue(text.startsWith(baseGroup))
        for (gid in reported) {
            assertTrue("row for $gid", text.contains("kairos_g$gid:x:$gid:\n"))
        }
    }

    @Test fun `a second run is byte-identical and writes nothing`() = withFixture { root ->
        GuestGroupNames.ensure(root, reported)
        val group = File(root, "etc/group")
        val before = group.readBytes()
        val again = GuestGroupNames.ensure(root, reported)
        assertTrue(again.ok)
        assertTrue(again.added.isEmpty())
        assertFalse(again.created)
        assertEquals(reported.toSet(), again.alreadyNamed.toSet())
        assertTrue(before.contentEquals(group.readBytes()))
    }

    @Test fun `existing records and their members are never overwritten`() = withFixture(
        baseGroup + "kairos_g3003:x:3003:alice,bob\n" + "docker:x:4242:carol\n",
    ) { root ->
        val before = File(root, "etc/group").readText()
        val out = GuestGroupNames.ensure(root, listOf(3003, 4242, 7777))
        assertTrue(out.ok)
        assertEquals(listOf(7777), out.added.keys.toList())
        assertEquals(listOf(3003, 4242), out.alreadyNamed)
        val after = File(root, "etc/group").readText()
        assertTrue(after.startsWith(before))
        assertTrue(after.contains("kairos_g3003:x:3003:alice,bob\n"))
        assertTrue(after.contains("docker:x:4242:carol\n"))
        assertTrue(after.contains("kairos_g7777:x:7777:\n"))
        // The existing name for 3003 was kept, not replaced by kairos_g3003_2.
        assertEquals(1, Regex("(?m)^kairos_g3003:").findAll(after).count())
    }

    @Test fun `a taken generated name gets a suffix, still legal and unique`() = withFixture(
        baseGroup + "kairos_g4242:x:777:someone\n",
    ) { root ->
        val out = GuestGroupNames.ensure(root, listOf(4242))
        assertTrue(out.ok)
        val name = out.added.getValue(4242)
        assertNotEquals("kairos_g4242", name)
        assertEquals("kairos_g4242_2", name)
        assertTrue(GuestGroupNames.isLegalName(name))
        val text = File(root, "etc/group").readText()
        assertTrue(text.contains("kairos_g4242:x:777:someone\n"))
        assertTrue(text.contains("kairos_g4242_2:x:4242:\n"))
    }

    @Test fun `missing group file is created with root and the missing ids`() {
        val root = Files.createTempDirectory("kairos-group-names-missing-").toFile()
        try {
            // No etc/ directory at all, like a truncated image.
            val first = GuestGroupNames.ensure(root, listOf(3003, 50368))
            assertTrue(first.error, first.ok)
            assertTrue(first.created)
            val file = File(root, "etc/group")
            assertTrue(file.isFile)
            assertEquals(
                "root:x:0:\nkairos_g3003:x:3003:\nkairos_g50368:x:50368:\n",
                file.readText(),
            )
            assertTrue(file.canRead())
            val before = file.readBytes()
            val second = GuestGroupNames.ensure(root, listOf(3003, 50368))
            assertTrue(second.ok)
            assertFalse(second.created)
            assertTrue(second.added.isEmpty())
            assertTrue(before.contentEquals(file.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `an existing tight mode such as 0600 is preserved`() = withFixture { root ->
        val group = File(root, "etc/group")
        val tight = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(group.toPath(), tight)
        val out = GuestGroupNames.ensure(root, reported)
        assertTrue(out.error.orEmpty(), out.ok)
        assertEquals(tight, Files.getPosixFilePermissions(group.toPath()))
    }

    @Test fun `a newly created group file uses the conventional 0644`() {
        val root = Files.createTempDirectory("kairos-group-mode-").toFile()
        try {
            val out = GuestGroupNames.ensure(root, listOf(3003))
            assertTrue(out.error.orEmpty(), out.ok)
            assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(File(root, "etc/group").toPath()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ── failure is visible, never fatal ────────────────────────────────────

    @Test fun `unusable group path reports an error instead of throwing`() = withFixture { root ->
        val etc = File(root, "etc")
        File(etc, "group").delete()
        File(etc, "group").mkdirs()
        val out = GuestGroupNames.ensure(root, reported)
        assertFalse(out.ok)
        assertTrue("mentions the path", out.error.orEmpty().contains("group"))
        assertTrue(out.added.isEmpty())
        assertEquals(reported, out.requested)
    }

    @Test fun `a symlink outside the rootfs is refused and left untouched`() {
        val outside = Files.createTempDirectory("kairos-group-outside-").toFile()
        val root = Files.createTempDirectory("kairos-group-link-").toFile()
        try {
            val target = File(outside, "group")
            target.writeText("root:x:0:\n")
            val here = File(root, "etc").also { it.mkdirs() }
            Files.createSymbolicLink(File(here, "group").toPath(), target.toPath())
            val out = GuestGroupNames.ensure(root, listOf(3003))
            assertFalse(out.ok)
            assertTrue(out.error.orEmpty().contains("outside the rootfs"))
            assertEquals("root:x:0:\n", target.readText())
        } finally {
            outside.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test fun `a symlink inside the rootfs is followed without cutting it`() {
        val root = Files.createTempDirectory("kairos-group-link-in-").toFile()
        try {
            File(root, "usr/lib").mkdirs()
            val target = File(root, "usr/lib/group")
            target.writeText(baseGroup)
            File(root, "etc").mkdirs()
            Files.createSymbolicLink(File(root, "etc/group").toPath(), target.toPath())
            val out = GuestGroupNames.ensure(root, listOf(3003))
            assertTrue(out.error.orEmpty(), out.ok)
            assertTrue(target.readText().contains("kairos_g3003:x:3003:\n"))
            assertTrue(Files.isSymbolicLink(File(root, "etc/group").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `empty ids neither create nor modify anything`() {
        val root = Files.createTempDirectory("kairos-group-empty-").toFile()
        try {
            val out = GuestGroupNames.ensure(root, emptyList())
            assertTrue(out.ok)
            assertFalse(out.created)
            assertFalse(File(root, "etc/group").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ── pure merge rules ───────────────────────────────────────────────────

    @Test fun `no trailing newline is preserved and exactly one is added`() {
        val merged = GuestGroupNames.merge("root:x:0:", listOf(3003))
        assertEquals("root:x:0:\nkairos_g3003:x:3003:\n", merged.text)
    }

    @Test fun `CRLF file keeps every original byte`() {
        val original = "root:x:0:\r\nnogroup:x:65534:\r\n"
        val merged = GuestGroupNames.merge(original, listOf(3003))
        assertTrue(merged.text.startsWith(original))
        assertEquals("kairos_g3003:x:3003:\n", merged.text.removePrefix(original))
    }

    @Test fun `comments malformed rows and duplicate gids are left alone`() {
        val text = "# header\n" + "bogusline\n" + "dup:x:3003:one\n" + "other:x:3003:two\n"
        val merged = GuestGroupNames.merge(text, listOf(3003, 3004))
        assertEquals(text, merged.text.substring(0, text.length))
        assertEquals(setOf(3004), merged.added.keys)
        assertEquals(listOf(3003), merged.alreadyNamed)
    }

    @Test fun `proc status supplies the live id list`() {
        val status = """
            Name:	com.openminis.app
            Umask:	0077
            Groups:	3003 9997	20368 50368
            VmRSS:	  123 kB
        """.trimIndent() + "\n"
        assertEquals(reported, GuestGroupNames.parseProcStatus(status))
    }

    @Test fun `proc status without a groups line is empty`() {
        assertEquals(emptyList<Int>(), GuestGroupNames.parseProcStatus("Name:\tapp\n"))
        assertEquals(emptyList<Int>(), GuestGroupNames.parseProcStatus(""))
        assertEquals(listOf(3003), GuestGroupNames.parseProcStatus("Groups:\t3003 junk\n"))
    }

    @Test fun `ids are positive distinct and sorted`() {
        assertEquals(listOf(1000, 3003), GuestGroupNames.normalizeIds(listOf(3003, 0, 1000, 3003, -5)))
        assertEquals(emptyList<Int>(), GuestGroupNames.normalizeIds(listOf(0, -1)))
    }

    @Test fun `generated names are legal and bounded`() {
        assertTrue(GuestGroupNames.isLegalName("kairos_g3003"))
        assertTrue(GuestGroupNames.isLegalName("kairos_g2147483647"))
        assertFalse(GuestGroupNames.isLegalName(""))
        assertFalse(GuestGroupNames.isLegalName("3003"))
        assertFalse(GuestGroupNames.isLegalName("Minis"))
        assertFalse(GuestGroupNames.isLegalName("minis g3003"))
        assertFalse(GuestGroupNames.isLegalName("minis:g3003"))
        assertTrue(GuestGroupNames.groupName(Int.MAX_VALUE).length <= GuestGroupNames.MAX_NAME_LENGTH)
    }

    @Test fun `outcome without ids reports the path but has no error`() {
        val root = fixture()
        try {
            val out = GuestGroupNames.ensure(root, emptyList())
            assertNull(out.error)
            assertTrue(out.groupFile.endsWith("etc/group"))
        } finally {
            root.deleteRecursively()
        }
    }
}
