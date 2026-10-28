package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/**
 * [T-chroot-file-owner-propagation] file_write / file_edit must name the
 * directory and the real exception when the parent cannot be created, and must
 * stop there — a mkdirs()/createDirectories failure may not be re-labelled as
 * an ENOENT for the file (the shape of a CHROOT sharing directory created by a
 * root shell: root:root 0755, readable but not writable by the App UID).
 *
 * Every fixture lives under one temp directory owned by this test. The two
 * mode-bit fixtures assert that the fixture really denies access instead of
 * skipping: on a runner where the test user is root, chmod does not deny
 * writes and those tests fail loudly rather than reporting a green skip.
 */
class FileToolWriteFailureTest {

    private val tmp: File = Files.createTempDirectory("filetool-failure").toFile()

    @After
    fun cleanUp() {
        tmp.walkTopDown().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private fun parentFailure(target: File, createDirs: Boolean = true): FileToolErrors.ParentDirFailure =
        assertThrows(FileToolErrors.ParentDirFailure::class.java) {
            FileToolErrors.writeText(target, target.path, "body\n", append = false, createDirs = createDirs)
        }

    // --- parent directory ----------------------------------------------------

    @Test
    fun `a failed parent creation names the directory, the failing path and the real reason`() {
        // A regular file is in the way, so directory creation fails for every
        // user, including root: the same production path as a 0755 root-owned
        // directory, without depending on mode bits being enforced.
        val blocker = File(tmp, "share").apply { writeText("not a directory") }
        val parent = File(blocker, "sub")
        val target = File(parent, "a.txt")

        val e = parentFailure(target)
        val msg = FileToolErrors.messageFor(e)!!

        assertTrue(
            "must lead with the directory that failed, got:\n$msg",
            msg.startsWith("Error: cannot create parent directory ${parent.path} for ${target.path}: "),
        )
        assertTrue("the exception's own reason is kept, got:\n$msg", msg.contains("createDirectories failed:"))
        assertTrue(msg.contains("failed path: path=${parent.path}, exists=false, isDirectory=false"))
        assertTrue(
            "the nearest existing ancestor is named",
            msg.contains("nearest existing ancestor: path=${blocker.path}, exists=true, isDirectory=false"),
        )
        // The exception is a FileSystemException; NIO may raise a subclass
        // (NotDirectoryException, FileAlreadyExistsException, ...), so the
        // type is asserted, not the simple name in the text.
        assertTrue("the NIO exception is kept as the cause", e.errorCause is java.nio.file.FileSystemException)
        assertTrue("the exception is printed", Regex("error: \\w*Exception: ").containsMatchIn(msg))
        assertTrue(
            "no failure is invented: the reason is the one NIO reported",
            Regex(
                "Not a directory|File exists|Permission denied|permission denied|No such file|ENOTDIR|" +
                    "FileSystemException|NotDirectoryException|FileAlreadyExistsException",
            ).containsMatchIn(msg),
        )
        assertTrue(msg.trimEnd().endsWith("Nothing was written to ${target.path} (the write was not attempted)."))
        assertFalse("must not read as a missing file", msg.startsWith("Error: File not found"))
        assertFalse("there is no follow-up write error to explain", msg.contains("the write then failed with:"))
    }

    @Test
    fun `nothing is written and no write is attempted after the parent fails`() {
        val blocker = File(tmp, "share2").apply { writeText("untouched") }
        val target = File(File(blocker, "sub"), "a.txt")

        parentFailure(target)

        assertFalse("the target must not exist", target.exists())
        assertEquals("the blocker is untouched", "untouched", blocker.readText())
        assertEquals("no file was created anywhere under the blocker", 0, File(tmp, "share2").walkTopDown().count() - 1)
    }

    @Test
    fun `create_dirs false still creates a missing parent, as before`() {
        val target = File(tmp, "fresh/dir/note.txt")
        FileToolErrors.writeText(target, target.path, "keep me\n", append = false, createDirs = false)
        assertEquals("keep me\n", target.readText())
    }

    @Test
    fun `append keeps the existing content`() {
        val target = File(tmp, "log.txt").apply { writeText("one\n") }
        FileToolErrors.writeText(target, target.path, "two\n", append = true, createDirs = false)
        assertEquals("one\ntwo\n", target.readText())
    }

    // --- the file itself -----------------------------------------------------

    @Test
    fun `a write to a directory reports the path and the real exception, not a parent failure`() {
        val dir = File(tmp, "adir").apply { mkdirs() }
        val e = assertThrows(FileToolErrors.AccessFailure::class.java) {
            FileToolErrors.writeText(dir, dir.path, "x", append = false, createDirs = false)
        }
        val msg = FileToolErrors.messageFor(e)!!

        assertTrue("got:\n$msg", msg.startsWith("Error: cannot write ${dir.path}: FileNotFoundException:"))
        assertTrue(msg.contains("file: exists=true, isDirectory=true"))
        assertTrue(
            "open(2) failed, so nothing was written",
            msg.contains("Nothing was written to ${dir.path} (the file could not be opened)."),
        )
        assertFalse(msg.contains("cannot create parent directory"))
    }

    @Test
    fun `a read-only filesystem is named as such and not blamed on ownership`() {
        val file = File(tmp, "ro.txt").apply { writeText("x") }
        val msg = FileToolErrors.messageFor(
            FileToolErrors.AccessFailure(
                "/sys/ro.txt", file, "write",
                FileNotFoundException("/sys/ro.txt: open failed: EROFS (Read-only file system)"),
            ),
        )!!
        assertTrue(msg.contains("mounted read-only (EROFS)"))
        assertFalse("mode bits are not the cause on EROFS", msg.contains("root:root"))
        assertTrue(msg.contains("Nothing was written to /sys/ro.txt"))
    }

    @Test
    fun `a failure that may have written part of the file says so`() {
        val file = File(tmp, "partial.txt")
        val msg = FileToolErrors.messageFor(
            FileToolErrors.AccessFailure("/p/partial.txt", file, "write", IOException("write failed after 10 bytes")),
        )!!
        assertTrue(msg.contains("The write may be incomplete - re-read /p/partial.txt before retrying."))
    }

    /** Needs mode bits to be enforced; asserted, never skipped. */
    @Test
    fun `an unwritable file is reported as a permission failure naming the file`() {
        val parent = File(tmp, "owned").apply { mkdirs() }
        val file = File(parent, "root-owned.txt").apply { writeText("old\n") }
        file.setWritable(false)
        try {
            assertFalse(
                "fixture: chmod 0644 must deny writes to this app's UID (a root test user cannot build it)",
                file.canWrite(),
            )
            val e = assertThrows(FileToolErrors.AccessFailure::class.java) {
                FileToolErrors.writeText(file, file.path, "new\n", append = false, createDirs = false)
            }
            val msg = FileToolErrors.messageFor(e)!!
            assertTrue(msg.startsWith("Error: cannot write ${file.path}:"))
            assertTrue("real errno text is kept", msg.contains("EACCES") || msg.contains("Permission denied"))
            assertTrue(msg.contains("this file is not writable by the app's UID"))
            assertTrue(msg.contains("File.canWrite() = false"))
            assertTrue("stat'ed owner/mode are printed when available", msg.contains("stat: uid="))
            assertTrue(msg.contains("Nothing was written to ${file.path}"))
            assertEquals("old\n", file.readText())
        } finally {
            file.setWritable(true)
        }
    }

    /** Same fixture requirement; the CHROOT shape: a 0755 dir owned by another uid. */
    @Test
    fun `a directory this uid cannot write in is named with its stat'ed owner and a conditional hint`() {
        val parent = File(tmp, "root-0755").apply { mkdirs() }
        parent.setWritable(false)
        try {
            assertFalse(
                "fixture: chmod 0555 must deny writes to this app's UID (a root test user cannot build it)",
                parent.canWrite(),
            )
            val target = File(parent, "sub/a.txt")
            val e = parentFailure(target)
            val msg = FileToolErrors.messageFor(e)!!

            assertTrue(
                msg.startsWith("Error: cannot create parent directory ${File(parent, "sub").path} for ${target.path}:"),
            )
            assertTrue(msg.contains("createDirectories failed: permission denied"))
            assertTrue(e.errorCause is java.nio.file.AccessDeniedException)
            assertTrue(msg.contains("nearest existing ancestor: path=${parent.path}, exists=true, isDirectory=true"))
            assertTrue(msg.contains("this directory is not writable by the app's UID"))
            assertTrue(msg.contains("File.canWrite() = false"))
            assertTrue("stat'ed owner/mode are printed when available", msg.contains("stat: uid="))
            assertFalse(
                "this fixture's directory is not root-owned, so no root:root may be claimed",
                msg.contains("root:root"),
            )
            assertFalse("nothing was attempted", target.exists())
        } finally {
            parent.setWritable(true)
        }
    }

    // --- file_edit -----------------------------------------------------------

    @Test
    fun `file_edit reports a read failure with the path instead of editing`() {
        val dir = File(tmp, "edit-dir").apply { mkdirs() }
        val r = FileEditTool.edit(dir, dir.path, "a", "b", false, "t")
        assertFalse(r.success)
        assertTrue("got:\n${r.output}", r.output.startsWith("Error: cannot read ${dir.path}:"))
        assertTrue(r.output.contains("file: exists=true, isDirectory=true"))
        assertTrue(r.output.contains("FileNotFoundException"))
        assertTrue(r.output.contains("Nothing was written; the file was not modified."))
    }

    @Test
    fun `file_edit reports a write failure with the path and does not claim success`() {
        val file = File(tmp, "edit-ro.txt").apply { writeText("needle\n") }
        file.setWritable(false)
        try {
            assertFalse(
                "fixture: chmod 0644 must deny writes to this app's UID (a root test user cannot build it)",
                file.canWrite(),
            )
            val r = FileEditTool.edit(file, file.path, "needle", "pin", false, "t")
            assertFalse("the edit must fail, got: ${r.output}", r.success)
            assertTrue("got:\n${r.output}", r.output.startsWith("Error: cannot write ${file.path}:"))
            assertTrue(r.output.contains("Permission denied") || r.output.contains("EACCES"))
            assertTrue(r.output.contains("Nothing was written to ${file.path}"))
            assertEquals("the file is unchanged", "needle\n", file.readText())
        } finally {
            file.setWritable(true)
        }
    }

    @Test
    fun `file_edit calls only a stat-confirmed absence not found`() {
        val absent = File(tmp, "nope.txt")
        assertEquals("Error: File not found: ${absent.path}", FileToolErrors.missingMessage(absent, absent.path))

        // stat succeeds: the file appeared after the tool's existence check.
        val appeared = File(tmp, "appeared.txt").apply { writeText("x") }
        val msg = FileToolErrors.missingMessage(appeared, appeared.path)
        assertTrue("must not claim it is missing, got: $msg", msg.contains("exists now"))
        assertTrue(msg.contains("re-run the tool"))
    }

    // --- source wiring -------------------------------------------------------

    @Test
    fun `neither tool drops the parent-directory result any more`() {
        val write = ProductionSources.read("tools/FileWriteTool.kt")
        assertFalse("a bare parent.mkdirs() is exactly the bug", write.contains("parent.mkdirs()"))
        assertTrue(write.contains("FileToolErrors.writeText(file, path, content, append, createDirs)"))
        assertTrue(write.contains("FileToolErrors.failureMessage("))
        // The size-guard port test pins this call; keep it in place.
        assertTrue(write.contains("FileToolIO.run(\"Writing \$path\", writes = true)"))

        val edit = ProductionSources.read("tools/FileEditTool.kt")
        assertTrue(edit.contains("FileToolErrors.writeText("))
        assertTrue(edit.contains("FileToolErrors.missingMessage("))
        assertFalse("a bare file.writeText( is exactly the bug", edit.contains("        file.writeText(newContent)"))
        assertTrue(edit.contains("FileToolIO.run(\"Editing \$path\", writes = true)"))

        val helper = ProductionSources.read("tools/FileToolErrors.kt")
        assertTrue(helper.contains("Files.createDirectories(dir.toPath())"))
        // The write must come after the directory step returned successfully,
        // and the directory step must not write a probe file to learn errno.
        assertTrue(
            "the directory step runs before the write",
            helper.indexOf("ensureDirectories(parent, path)") in 0 until helper.indexOf("file.appendText(content)"),
        )
        assertFalse("no trial write to harvest errno", helper.contains("createNewFile()"))
    }
}
