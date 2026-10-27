package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261025-CHROOT account] `/etc/passwd` + `/etc/group` provisioning for
 * the app's own uid under the chroot backend.
 *
 * The fixtures are byte-exact copies of what Ubuntu 24.04 base ships (the
 * `nobody` row and its "no login" GECOS, the trailing blank the image has in
 * /etc/group) plus the two rows the report came from: bash printing
 * "I have no name!" and `groups` printing "cannot find name for group ID
 * 10368". App uids are 10xxx+; 10368 is used verbatim as the example id.
 */
class GuestAccountTest {
    private val uid = 10368
    private val gid = 10368

    private val basePasswd = """
        root:x:0:0:root:/root:/bin/bash
        daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
        bin:x:2:2:bin:/bin:/usr/sbin/nologin
        sys:x:3:3:sys:/dev:/usr/sbin/nologin
        sync:x:4:65534:sync:/bin:/bin/sync
        nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
        _apt:x:100:65534::/nonexistent:/usr/sbin/nologin

    """.trimIndent() + "\n"

    private val baseGroup = """
        root:x:0:
        daemon:x:1:
        bin:x:2:
        sys:x:3:
        nogroup:x:65534:
        _apt:x:100:
        # a comment line an admin left behind
    """.trimIndent() + "\n"

    private fun fixture(): File {
        val root = Files.createTempDirectory("minis-guest-account-").toFile()
        File(root, "etc").mkdirs()
        File(root, "etc/passwd").writeText(basePasswd)
        File(root, "etc/group").writeText(baseGroup)
        File(root, "etc/shadow").writeText("root:*:19000:0:99999:7:::\n")
        return root
    }

    private fun <T> withFixture(block: (File) -> T): T {
        val root = fixture()
        try {
            return block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── pure merge logic ───────────────────────────────────────────────────

    @Test fun passwdRowIsAppendedOnceAndKeepsEveryOriginalByte() = withFixture { root ->
        val passwd = File(root, "etc/passwd")
        val before = passwd.readBytes()
        val first = GuestAccount.ensure(root, uid, gid)
        assertTrue(first.passwdAdded)
        assertTrue(first.groupAdded)
        assertEquals(GuestAccount.USER_NAME, first.userName)
        val after = passwd.readBytes()
        // Every original byte is still the prefix, unchanged (including the
        // trailing newline of the last row); exactly one row was appended.
        assertTrue(after.copyOf(before.size).contentEquals(before))
        val tail = after.copyOfRange(before.size, after.size).toString(Charsets.UTF_8)
        assertEquals("minis:x:10368:10368:Minis:/home/minis:/bin/bash\n", tail)
        // ...and no other row changed.
        assertEquals(basePasswd.lines().filter { it.isNotEmpty() },
            passwd.readText().lines().filter { it.isNotEmpty() }.dropLast(1))
    }

    @Test fun secondCallAddsNothingAndLeavesFileBytesIdentical() = withFixture { root ->
        GuestAccount.ensure(root, uid, gid)
        val passwd = File(root, "etc/passwd").readBytes()
        val group = File(root, "etc/group").readBytes()
        val again = GuestAccount.ensure(root, uid, gid)
        assertFalse(again.passwdAdded)
        assertFalse(again.groupAdded)
        assertFalse(again.homeCreated)
        assertTrue(passwd.contentEquals(File(root, "etc/passwd").readBytes()))
        assertTrue(group.contentEquals(File(root, "etc/group").readBytes()))
        assertEquals(1, File(root, "etc/passwd").readText().split('\n').count { it.startsWith("minis:") })
        assertEquals(1, File(root, "etc/group").readText().split('\n').count { it.startsWith("minis:") })
    }

    @Test fun existingRowForTheSameUidIsNeverRewritten() = withFixture { root ->
        val passwd = File(root, "etc/passwd")
        passwd.writeText(basePasswd + "app10368:x:10368:10368::/data:/system/bin/sh\n")
        val group = File(root, "etc/group")
        group.writeText(baseGroup + "app10368:x:10368:\n")
        val passwdBefore = passwd.readBytes()
        val groupBefore = group.readBytes()
        val out = GuestAccount.ensure(root, uid, gid)
        assertFalse(out.passwdAdded)
        assertFalse(out.groupAdded)
        assertEquals("app10368", out.userName)
        assertTrue(passwdBefore.contentEquals(passwd.readBytes()))
        assertTrue(groupBefore.contentEquals(group.readBytes()))
    }

    @Test fun groupRowMatchesGidNotUidAndIsAppendedOnceAndKeepsEveryOriginalByte() = withFixture { root ->
        val group = File(root, "etc/group")
        val before = group.readBytes()
        GuestAccount.ensure(root, uid, gid)
        val after = group.readBytes()
        assertTrue(after.copyOf(before.size).contentEquals(before))
        assertEquals("minis:x:10368:\n", after.copyOfRange(before.size, after.size).toString(Charsets.UTF_8))
        assertEquals("minis", GuestAccount.nameForId(group.readText(), gid))
    }

    @Test fun missingTrailingNewlineStillGetsExactlyOne() {
        val text = "root:x:0:0:root:/root:/bin/bash"
        val merged = GuestAccount.merge(text, 10368, 2, GuestAccount.passwdEntry(10368, 10368))
        assertTrue(merged.startsWith("root:x:0:0:root:/root:/bin/bash\nminis:x:"))
        assertTrue(merged.endsWith("/bin/bash\n"))
        assertFalse(merged.contains("\n\n"))
        // Idempotent: merging the same id again returns the same text.
        assertEquals(merged, GuestAccount.merge(merged, 10368, 2, "minis:x:0:0::/:/bin/sh"))
    }

    @Test fun emptyFileBecomesSingleRowWithNewline() {
        val merged = GuestAccount.merge("", 10368, 2, GuestAccount.groupEntry(10368))
        assertEquals("minis:x:10368:\n", merged)
        // An empty body has no row for the id, so the row is added after nothing.
        assertEquals("root:x:0:0::/root:/bin/sh\n",
            GuestAccount.merge("", 0, 2, "root:x:0:0::/root:/bin/sh"))
    }

    @Test fun blankLinesAndCommentsArePreserved() {
        val text = "# managed\nroot:x:0:0::/root:/bin/sh\n\n"
        val merged = GuestAccount.merge(text, uid, 2, GuestAccount.passwdEntry(uid, gid))
        assertTrue(merged.startsWith(text))
        assertEquals(text.length + GuestAccount.passwdEntry(uid, gid).length + 1, merged.length)
    }

    @Test fun substringIdIsNotAMatch() {
        // 1036 must not "find" 10368 in a 3rd field.
        val text = "someone:x:10368:10368::/home:/bin/sh\n"
        assertEquals(null, GuestAccount.nameForId(text, 1036))
        assertEquals("someone", GuestAccount.nameForId(text, 10368))
    }

    @Test fun reservedIdsAreNotDeclared() {
        assertFalse(GuestAccount.isEntryCandidate(0))
        assertFalse(GuestAccount.isEntryCandidate(999))
        assertFalse(GuestAccount.isEntryCandidate(65534))
        assertTrue(GuestAccount.isEntryCandidate(1000))
        assertTrue(GuestAccount.isEntryCandidate(10368))
        assertTrue(GuestAccount.isEntryCandidate(65535 + 1))
    }

    @Test fun homeIsCreatedBesideTheFiles() = withFixture { root ->
        assertFalse(File(root, "home/minis").exists())
        val out = GuestAccount.ensure(root, uid, gid)
        assertTrue(out.homeCreated)
        assertTrue(File(root, "home/minis").isDirectory)
        assertEquals(GuestAccount.HOME, GuestAccount.WORKDIR)
    }

    @Test fun shadowIsNeverTouched() = withFixture { root ->
        val shadow = File(root, "etc/shadow")
        val before = shadow.readBytes()
        GuestAccount.ensure(root, uid, gid)
        assertTrue(before.contentEquals(shadow.readBytes()))
    }

    @Test fun noTempFilesAreLeftBehind() = withFixture { root ->
        GuestAccount.ensure(root, uid, gid)
        val leftovers = File(root, "etc").listFiles()!!.filter { it.name.startsWith(".minis-account-") }
        assertTrue(leftovers.toString(), leftovers.isEmpty())
        assertEquals(
            setOf("passwd", "group", "shadow"),
            File(root, "etc").listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test fun symlinkedPasswdIsWrittenThroughNotReplaced() = withFixture { root ->
        val real = File(root, "usr/lib/passwd").apply { parentFile.mkdirs(); writeText(basePasswd) }
        val link = File(root, "etc/passwd")
        link.delete()
        // The guest-side link is absolute (/usr/lib/passwd); on this host the
        // fixture is not at that path, so the test plants a relative link that
        // resolves to the same in-tree target.
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("../usr/lib/passwd"))
        val out = GuestAccount.ensure(root, uid, gid)
        assertTrue(out.passwdAdded)
        assertTrue("the link must survive", Files.isSymbolicLink(link.toPath()))
        assertEquals("minis", GuestAccount.nameForId(real.readText(), uid))
        // A second run finds the row through the link and writes nothing.
        assertFalse(GuestAccount.ensure(root, uid, gid).passwdAdded)
    }

    @Test fun environmentNamesTheAccountAndAWritableHome() {
        val env = GuestAccount.environment()
        assertEquals(GuestAccount.HOME, env["HOME"])
        assertEquals(GuestAccount.USER_NAME, env["USER"])
        assertEquals(GuestAccount.USER_NAME, env["LOGNAME"])
        assertNotEquals("/root", env["HOME"])
    }

    // ── wiring (source-level, so a refactor that drops the call is caught) ──

    private fun code(path: String): String {
        // Unit tests run with the module (src/android/app) as the working dir,
        // so try the module-relative path first and the repo-relative one
        // second; one of them always exists.
        val candidates = listOf(File(path), File(path.removePrefix("src/android/app/")))
        val file = candidates.firstOrNull { it.isFile }
            ?: throw java.io.FileNotFoundException(candidates.joinToString(" | ") { it.absolutePath })
        return file.readText().lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    @Test fun chrootSpawnProvisionsCompatibleAccountButUsesAuthorizedRootIdentity() {
        val kernel = code("src/android/app/src/main/java/com/openminis/app/sandbox/ChrootKernel.kt")
        // Keep the account records for shared-rootfs compatibility, while the
        // approved chroot command intentionally runs as real uid0.
        assertTrue(kernel.contains("GuestAccount.ensureForApp(context)"))
        assertTrue(kernel.contains("cwd = if (rootExec) \"/root\" else GuestAccount.WORKDIR"))
        assertTrue(kernel.contains("env.putAll(GuestAccount.environment())"))
        assertTrue(kernel.contains("env[\"HOME\"] = \"/root\""))
        assertTrue(kernel.contains("env[\"USER\"] = \"root\""))
        assertTrue(kernel.contains("RootExecPolicy.decide("))
        assertTrue(kernel.contains("throw RootExecRefusedException("))
    }

    @Test fun shellExecutorDoesNotRestoreRootHomeUnderChroot() {
        val executor = code("src/android/app/src/main/java/com/openminis/app/sandbox/ShellExecutor.kt")
        assertTrue(executor.contains("spawn.backend == SandboxBackend.CHROOT && key == \"HOME\""))
    }
}
