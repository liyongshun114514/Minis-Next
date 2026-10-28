package com.openminis.app.tools

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * [T-chroot-file-owner-propagation] Failure reporting for file_write / file_edit.
 *
 * Why this exists: the write path called `parent.mkdirs()` and dropped its
 * boolean, then called `writeText`. When the app's UID cannot create a
 * directory (the CHROOT case: a sharing directory created by a root shell is
 * owned by root:root with mode 0755, so the App UID may read and traverse it
 * but not create entries in it) the only thing the agent saw was
 *
 *     Error writing file: /srv/share/new/a.txt: open failed: ENOENT
 *
 * which reads as "the file vanished" and hides both the directory that failed
 * and the fact that mkdirs() had already returned false.
 *
 * The directory is now created with [Files.createDirectories] and a failure
 * stops the call right there: no write is attempted afterwards, so a failed
 * mkdir can never be re-labelled as an ENOENT for the file. NIO raises
 * [FileSystemException] carrying the path that failed and the errno text
 * (reason), which is passed through verbatim. uid/gid/mode are printed only
 * when stat can report them; when the platform cannot, the hint stays
 * conditional instead of naming an owner it never saw.
 */
internal object FileToolErrors {

    /** The parent directory could not be created; the write was not attempted. */
    class ParentDirFailure(
        val path: String,
        /** The directory the tool needed for [path]. */
        val parent: File,
        /** The path [Files.createDirectories] first failed on. */
        val failedPath: File,
        /** The exception's own reason text for [failedPath]. */
        val reason: String,
        val errorCause: Throwable?,
    ) : IOException("cannot create parent directory ${parent.path} for $path: $reason", errorCause)

    /** Reading or writing [file] itself threw. [stage] is "write", "append to" or "read". */
    class AccessFailure(
        val path: String,
        val file: File,
        val stage: String,
        val errorCause: Throwable,
    ) : IOException("cannot $stage $path: ${errorCause.javaClass.simpleName}: ${errorCause.message}", errorCause)

    /**
     * Create [file]'s missing parent (the tool auto-creates it even without
     * create_dirs, see FileWriteTool) and then write [content].
     *
     * Parent creation either succeeds or throws [ParentDirFailure]; the write
     * is not attempted in that case.
     */
    fun writeText(file: File, path: String, content: String, append: Boolean, createDirs: Boolean) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && (createDirs || !parent.isDirectory)) {
            ensureDirectories(parent, path)
        }

        try {
            if (append) file.appendText(content) else file.writeText(content)
        } catch (e: Exception) {
            throw AccessFailure(path, file, if (append) "append to" else "write", e)
        }
    }

    /**
     * NIO's reason text (the errno string on platforms that fill it), or a
     * precise fallback from the exception type: JDK and Android both raise
     * [AccessDeniedException] with a null reason, which is still EACCES.
     */
    private fun fsReason(e: FileSystemException): String =
        e.reason?.takeIf { it.isNotBlank() } ?: when (e) {
            is AccessDeniedException -> "permission denied (AccessDeniedException)"
            is NoSuchFileException -> "no such file or directory (NoSuchFileException)"
            else -> e.javaClass.simpleName
        }

    /** Create [dir] and every missing ancestor, stopping at the first failure. */
    private fun ensureDirectories(dir: File, path: String) {
        if (dir.isDirectory) return
        try {
            Files.createDirectories(dir.toPath())
        } catch (e: FileSystemException) {
            throw ParentDirFailure(
                path, dir,
                failedPath = e.file?.let { File(it) } ?: dir,
                reason = "createDirectories failed: " + fsReason(e),
                errorCause = e,
            )
        } catch (e: Exception) {
            throw ParentDirFailure(
                path, dir, dir,
                reason = "createDirectories failed: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
                errorCause = e,
            )
        }
        if (!dir.isDirectory) {
            throw ParentDirFailure(
                path, dir, dir,
                reason = "createDirectories returned without creating the directory",
                errorCause = null,
            )
        }
    }

    /** Agent-facing text for a failure raised by this object, else null. */
    fun messageFor(e: Throwable): String? = when (e) {
        is ParentDirFailure -> parentMessage(e)
        is AccessFailure -> accessMessage(e)
        else -> null
    }

    /** Agent-facing text for an exception raised while writing/reading, whatever its type. */
    fun failureMessage(file: File, path: String, stage: String, e: Throwable): String =
        messageFor(e) ?: accessMessage(AccessFailure(path, file, stage, e))

    /**
     * Agent-facing text for a file the tool could not find. The check is a real
     * stat: only [NoSuchFileException] means "not there". A denial on the file
     * or on a directory above it ([AccessDeniedException]) is reported as a
     * permission problem instead of being guessed as absence from
     * `File.exists()` (which is false for both).
     */
    fun missingMessage(file: File, path: String): String {
        val failure = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            null
        } catch (e: NoSuchFileException) {
            return "Error: File not found: $path"
        } catch (e: Exception) {
            e
        }
        // It exists now: the tool's pre-check raced a writer (a shell command in
        // the same turn, most often). Not "not found" - say so and retry.
        if (failure == null) {
            return "Error: $path exists now (it appeared between the existence check and this stat) - " +
                "re-run the tool."
        }
        val parent = file.absoluteFile.parentFile
        return buildString {
            append("Error: cannot access ").append(path)
            append(": stat failed: ").append(describeThrowable(failure))
            if (parent != null) {
                append("\n  parent: ").append(dirFacts(parent))
                append("\n  ").append(dirWriteHint(parent))
            }
        }
    }

    fun parentMessage(f: ParentDirFailure): String = buildString {
        append("Error: cannot create parent directory ").append(f.parent.path)
        append(" for ").append(f.path).append(": ").append(f.reason).append(".\n")
        if (f.failedPath.path != f.parent.path) {
            append("  first failure: ").append(f.failedPath.path).append('\n')
        }
        append("  failed path: ").append(dirFacts(f.failedPath)).append('\n')
        nearestExisting(f.failedPath)?.let {
            append("  nearest existing ancestor: ").append(dirFacts(it)).append('\n')
        }
        if (f.errorCause != null) {
            append("  error: ").append(describeThrowable(f.errorCause)).append('\n')
        }
        val ancestor = nearestExisting(f.failedPath)
        if (ancestor != null && ancestor.isDirectory && !ancestor.canWrite()) {
            append("  ").append(dirWriteHint(ancestor)).append('\n')
        }
        append("  Nothing was written to ").append(f.path)
        append(" (the write was not attempted).")
    }

    fun accessMessage(f: AccessFailure): String = buildString {
        append("Error: cannot ").append(f.stage).append(' ').append(f.path)
        append(": ").append(describeThrowable(f.errorCause)).append('\n')
        append("  file: ").append(fileFacts(f.file)).append('\n')
        val parent = f.file.absoluteFile.parentFile
        if (parent != null) append("  parent: ").append(dirFacts(parent)).append('\n')
        when {
            isReadOnlyFilesystem(f.errorCause) ->
                append("  the filesystem is mounted read-only (EROFS): no write can succeed here, " +
                    "whatever the file's mode.\n")
            f.stage == "read" && f.file.exists() && !f.file.canRead() ->
                append("  ").append(fileReadHint(f.file)).append('\n')
            parent != null && parent.exists() && !parent.canWrite() ->
                append("  ").append(dirWriteHint(parent)).append('\n')
            f.file.exists() && !f.file.canWrite() ->
                append("  ").append(fileWriteHint(f.file)).append('\n')
        }
        if (f.stage == "read") {
            append("  Nothing was written; the file was not modified.")
        } else if (failedAtOpen(f.errorCause)) {
            append("  Nothing was written to ").append(f.path).append(" (the file could not be opened).")
        } else {
            append("  The write may be incomplete - re-read ").append(f.path).append(" before retrying.")
        }
    }

    // --- observed facts and conditional hints --------------------------------

    /**
     * "uid=0, gid=0, mode=0755" as stat'ed, or null when this platform cannot
     * report them. Never assumed.
     */
    internal fun unixFacts(file: File): String? = try {
        val p = file.toPath()
        val uid = Files.getAttribute(p, "unix:uid") as? Int
        val gid = Files.getAttribute(p, "unix:gid") as? Int
        val mode = Files.getAttribute(p, "unix:mode") as? Int
        if (uid == null) null else buildString {
            append("uid=").append(uid)
            if (gid != null) append(", gid=").append(gid)
            if (mode != null) {
                append(", mode=").append(Integer.toOctalString(mode and 0xFFF).padStart(4, '0'))
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun notPermitted(file: File, kind: String, can: String, adjective: String): String = buildString {
        append("this ").append(kind).append(" is not ").append(adjective)
        append(" by the app's UID (File.").append(can).append("() = false")
        unixFacts(file)?.let { append("; stat: ").append(it) }
        append("). ")
    }

    private fun dirWriteHint(dir: File): String = buildString {
        append(notPermitted(dir, "directory", "canWrite", "writable"))
        if (isRootOwned(dir)) {
            append("It is owned by uid=0 (root): a directory created by a root shell inside the CHROOT is " +
                "the usual cause - the App UID can read and traverse it but not create entries. Create it " +
                "from the app side, or chown it to the App UID.")
        } else {
            append("The App UID needs write permission on the directory (owner or group). A directory " +
                "created by a root shell inside the CHROOT looks the same way when stat reports uid=0 above.")
        }
    }

    private fun fileWriteHint(file: File): String = buildString {
        append(notPermitted(file, "file", "canWrite", "writable"))
        if (isRootOwned(file)) {
            append("It is owned by uid=0 (root): a file created by a root shell inside the CHROOT is the " +
                "usual cause - the App UID can read it but not modify it.")
        } else {
            append("The App UID needs write permission on the file (owner or group).")
        }
    }

    private fun fileReadHint(file: File): String = buildString {
        append(notPermitted(file, "file", "canRead", "readable"))
        if (isRootOwned(file)) {
            append("It is owned by uid=0 (root): a file created by a root shell inside the CHROOT with an " +
                "owner-only mode is readable by root only.")
        } else {
            append("The App UID needs read permission on the file (owner or group).")
        }
    }

    /** True only when stat actually reported uid=0. */
    private fun isRootOwned(file: File): Boolean =
        unixFacts(file)?.substringAfter("uid=")?.substringBefore(",") == "0"

    private fun dirFacts(dir: File): String = buildString {
        append("path=").append(dir.path)
        append(", exists=").append(dir.exists())
        append(", isDirectory=").append(dir.isDirectory)
        append(", canRead=").append(dir.canRead())
        append(", canWrite=").append(dir.canWrite())
        unixFacts(dir)?.let { append(", stat: ").append(it) }
    }

    private fun fileFacts(file: File): String = buildString {
        val isDir = file.isDirectory
        append("exists=").append(file.exists())
        append(", isDirectory=").append(isDir)
        append(", canRead=").append(file.canRead())
        append(", canWrite=").append(file.canWrite())
        append(", length=").append(if (isDir) "-" else file.length())
        unixFacts(file)?.let { append(", stat: ").append(it) }
    }

    /** Deepest existing ancestor of [dir] (the directory itself counts). */
    private fun nearestExisting(dir: File): File? {
        var cur: File? = dir.absoluteFile
        while (cur != null && !cur.exists()) cur = cur.parentFile
        return cur
    }

    private fun describeThrowable(t: Throwable): String {
        val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
        val msg = t.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        return "$name: $msg"
    }

    /** The errno text, when the exception carries one (Android libcore includes it). */
    internal fun isReadOnlyFilesystem(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        return m.contains("EROFS") || m.contains("Read-only file system", ignoreCase = true)
    }

    /**
     * True only for exceptions raised by open(2) itself
     * ([FileNotFoundException], [AccessDeniedException], [NoSuchFileException]):
     * the target was never truncated, so nothing can have been written. A
     * plain IOException may have written part of the file - ENOSPC included.
     */
    internal fun failedAtOpen(e: Throwable): Boolean =
        e is FileNotFoundException || e is AccessDeniedException || e is NoSuchFileException
}
