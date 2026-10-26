package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FEAT-261025-CHROOT account] The guest account the app itself runs as.
 *
 * ## Why this exists
 *
 * Under chroot the helper drops to the app's own uid/gid and `execve`s the
 * shell. The rootfs was never told that uid exists, so bash cannot map it:
 *
 * ```
 * groups: cannot find name for group ID 10368
 * I have no name!@localhost:~$
 * ```
 *
 * PRoot hid the same gap: its fake root plus `-0` made the guest believe it
 * was uid 0, whose entries every image ships. Under chroot there is no fake
 * root, so the identity has to be described to the guest for real.
 *
 * ## What is done (idempotent, additive only)
 *
 *  - `/etc/passwd` gains `minis:x:<uid>:<gid>:Minis:/home/minis:/bin/bash`
 *    **only** when no line already has that uid;
 *  - `/etc/group` gains `minis:x:<gid>:` **only** when no line already has
 *    that gid;
 *  - `/home/minis` is created when missing (the app creates it, so it already
 *    belongs to the app uid);
 *  - nothing else is touched: `/etc/shadow`, every existing line and the
 *    file's original line endings stay byte-identical.
 *
 * Writes are a temp file + `rename(2)` in the same directory, so a crash or a
 * power loss never leaves a truncated passwd/group behind.
 *
 * ## Safety
 *
 * The uid/gid come from the request (the app's own), never from the guest; no
 * shell is involved (no `useradd`, no `sed -i`); the helper's uid check is
 * untouched — this only tells the guest *which name* that same uid has. A
 * symlinked `etc/passwd` is followed to its target so the write does not cut
 * the link, and the link itself is never replaced.
 */
object GuestAccount {
    private const val TAG = "GuestAccount"

    const val USER_NAME = "minis"
    const val GROUP_NAME = "minis"
    const val HOME = "/home/minis"
    const val SHELL = "/bin/bash"
    const val GECOS = "Minis"

    /** Login directory the chroot backend uses for the terminal and shells. */
    const val WORKDIR = HOME

    private const val TMP_PREFIX = ".minis-account-"
    private const val PASSWD = "etc/passwd"
    private const val GROUP = "etc/group"

    /** Result of one [ensure] run; all fields are pure data (unit-tested). */
    data class Outcome(
        val uid: Int,
        val gid: Int,
        /** True when a passwd line for [uid] was appended by this run. */
        val passwdAdded: Boolean,
        /** True when a group line for [gid] was appended by this run. */
        val groupAdded: Boolean,
        /** True when `/home/minis` was created by this run. */
        val homeCreated: Boolean,
        /** The user name the guest resolves [uid] to, or null. */
        val userName: String?,
        /** Non-null when something could not be done; the backend still runs. */
        val warning: String? = null,
    )

    // ── Pure logic (no Android API; unit-tested on a plain JVM) ────────────

    /**
     * 1000 and above is Ubuntu `useradd`'s `UID_MIN`; every human/demon row a
     * base image ships for a *login* account sits below it (`nobody` is 65534
     * and has a literal name). Android app uids are 10xxx+, so a real id
     * always wins. Below 1000 only an exact match counts — a request for 0
     * (root) must never be "declared" by us.
     */
    internal fun isEntryCandidate(id: Int): Boolean = id >= 1000 && id != 65534

    /** The name of the first non-comment line whose [index] field is [id]. */
    fun nameForId(text: String, id: Int, index: Int = 2): String? {
        for (line in text.split('\n')) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("+")) continue
            val fields = trimmed.split(':')
            if (fields.size <= index) continue
            if (fields[index].trim().toIntOrNull() == id) return fields[0].ifEmpty { null }
        }
        return null
    }

    fun passwdEntry(uid: Int, gid: Int): String =
        "$USER_NAME:x:$uid:$gid:$GECOS:$HOME:$SHELL"

    fun groupEntry(gid: Int): String = "$GROUP_NAME:x:$gid:"

    /**
     * Append [line] to a passwd/group body, or return `text` unchanged when an
     * entry for [id] already exists.
     *
     * The original bytes are never rewritten: the new line goes at the end,
     * after exactly one `\n` (added only when the file did not end with one),
     * and the result always ends with `\n`. Blank lines and a leading BOM are
     * left where they are.
     */
    fun merge(text: String, id: Int, index: Int, line: String): String {
        if (nameForId(text, id, index) != null) return text
        if (text.isEmpty()) return line + "\n"
        val sep = if (text.endsWith("\n")) "" else "\n"
        return text + sep + line + "\n"
    }

    // ── Filesystem side ────────────────────────────────────────────────────

    /**
     * Ensure the guest account exists for the app's uid/gid. Never throws: a
     * failure is logged and reported in [Outcome.warning] so a rootfs oddity
     * cannot stop the shell from starting. Cheap to call on every spawn — an
     * already-provisioned rootfs costs two reads and one stat.
     */
    fun ensure(rootfs: File, uid: Int, gid: Int): Outcome {
        var passwdAdded = false
        var groupAdded = false
        var warning: String? = null
        try {
            if (isEntryCandidate(uid)) {
                passwdAdded = ensureEntry(File(rootfs, PASSWD), uid, 2, passwdEntry(uid, gid))
            } else {
                warning = "uid $uid is a reserved id; not declared in /etc/passwd"
            }
            if (isEntryCandidate(gid)) {
                groupAdded = ensureEntry(File(rootfs, GROUP), gid, 2, groupEntry(gid))
            }
        } catch (t: Throwable) {
            warning = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "guest account incomplete under ${rootfs.absolutePath}: $warning")
        }
        val home = File(rootfs, HOME.removePrefix("/"))
        var homeCreated = false
        try {
            if (!home.isDirectory) homeCreated = home.mkdirs() || home.isDirectory
        } catch (t: Throwable) {
            Log.w(TAG, "cannot prepare $HOME: ${t.javaClass.simpleName}: ${t.message}")
        }
        val outcome = Outcome(
            uid = uid,
            gid = gid,
            passwdAdded = passwdAdded,
            groupAdded = groupAdded,
            homeCreated = homeCreated,
            userName = runCatching {
                val f = File(rootfs, PASSWD)
                if (f.isFile) nameForId(f.readText(), uid) else null
            }.getOrNull(),
            warning = warning,
        )
        if (passwdAdded || groupAdded || homeCreated) {
            Log.i(
                TAG,
                "guest account provisioned uid=$uid gid=$gid passwd=$passwdAdded " +
                    "group=$groupAdded home=$homeCreated",
            )
        }
        return outcome
    }

    /**
     * Append [line] to [file] when no line carries [id]; true when it did.
     *
     * A symlinked `etc/passwd` (some images point at `/usr/lib/...`) would be
     * replaced by `rename` with a regular file, silently cutting the link away
     * from its target, so the write goes to the link's target instead. The
     * link itself is never modified and nothing is created outside the rootfs:
     * a target that is not itself a regular file is left alone.
     */
    private fun ensureEntry(file: File, id: Int, index: Int, line: String): Boolean {
        val target = resolveLink(file)
        val current = if (target.isFile) target.readText() else ""
        val merged = merge(current, id, index, line)
        if (merged == current) return false
        target.parentFile?.mkdirs()
        atomicWrite(target, merged)
        return true
    }

    /** Follow a symlink chain (bounded) to the real file, or return [file]. */
    internal fun resolveLink(file: File): File {
        var f = file
        repeat(8) {
            if (!java.nio.file.Files.isSymbolicLink(f.toPath())) return f
            val link = runCatching { java.nio.file.Files.readSymbolicLink(f.toPath()) }.getOrNull()
                ?: return file
            f = if (link.isAbsolute) link.toFile() else File(f.parentFile, link.toString())
        }
        return f
    }

    /**
     * `write(tmp) + fsync + rename`. The temp file lives in the SAME directory
     * as [target], so `rename` is atomic. The file keeps the usual 0644; the
     * temp name is not picked up by anything that lists `/etc`.
     */
    private fun atomicWrite(target: File, text: String) {
        val dir = target.parentFile ?: throw IllegalStateException("no parent for $target")
        val tmp = File(dir, TMP_PREFIX + target.name + "." + TMP_COUNTER.incrementAndGet())
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            runCatching { tmp.setReadable(true, false) }
            // A read-only FILE does not block rename(2) — only the directory
            // matters, and /etc is a real, writable directory in the rootfs.
            // java.io.File.renameTo() is rename(2) on Android: it replaces an
            // existing [target] atomically (no delete-then-create window).
            if (!tmp.renameTo(target)) throw IllegalStateException("rename failed: $tmp -> $target")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private val TMP_COUNTER = AtomicInteger(0)

    // ── Context entry point ────────────────────────────────────────────────

    /**
     * [ensure] for the live app identity and the installed rootfs. Called from
     * [ChrootKernel] before the helper starts and before every spawn, so the
     * terminal, the agent's shells and the non-interactive commands all see
     * the same account.
     */
    fun ensureForApp(context: Context): Outcome? = runCatching {
        val uid = android.os.Process.myUid()
        // Android app processes have gid == uid (see ChrootKernel.appGid()).
        ensure(RootfsManager.getInstance(context).rootfsDir, uid, uid)
    }.onFailure {
        Log.w(TAG, "guest account probe failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * Environment the chroot backend gives every shell so bash can name the
     * user and starts in a writable directory.
     */
    fun environment(): Map<String, String> = linkedMapOf(
        "HOME" to HOME,
        "USER" to USER_NAME,
        "LOGNAME" to USER_NAME,
        "SHELL" to SHELL,
    )
}
