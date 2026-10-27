package com.openminis.app.sandbox

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FEAT-261028-PROOT-GROUPS] Give the guest a name for every host
 * supplementary group id its processes inherit.
 *
 * ## Why this exists
 *
 * PRoot keeps the *host* supplementary group list when it starts a guest
 * process (it does not synthesise a new one, and the chroot helper's
 * `setgroups(0)` is not involved here). Android app processes carry
 * supplementary groups whose ids depend on the device, OEM and OS version
 * (the report we fixed happened to show 3003, 9997, 20368 and 50368), and
 * Ubuntu's `/etc/group` has no rows for them. `groups`/`id` then print
 * warnings like
 *
 * ```
 * groups: cannot find name for group ID 3003
 * ```
 *
 * for whichever ids the *running* process happens to have — they differ per
 * device, per OEM and per Android release, so a fixed list is wrong. This
 * helper reads the live supplementary group list, and appends one legal,
 * unique row per id that the guest's `/etc/group` cannot name.
 *
 * ## What it writes (additive and idempotent)
 *
 *  - only `<rootfs>/etc/group`;
 *  - only ids with no row yet, one `kairos_g<gid>:x:<gid>:` line each;
 *  - existing rows, their order, their member lists and every original byte
 *    stay untouched (the new lines are appended);
 *  - a second run with the same ids is a no-op (no write at all);
 *  - a missing `etc/group` is created with the standard `root:x:0:` row plus
 *    the missing ids, so creating it does not introduce a new unresolved
 *    group 0.
 *
 * ## What it never does
 *
 * No `setgroups`, no `su`, no shell, no host file, no mode widening and no
 * write outside the rootfs. `gid 0` is not "declared" by us: the guest's root
 * group is the image's business, and PRoot's `-0` always maps it to the
 * image's existing `root` row.
 *
 * The class is plain Kotlin/JVM (no Android types) so the merge rules above
 * are unit-tested directly; [GuestSupplementaryGroups] is the thin Android
 * entry point that supplies the live ids.
 */
object GuestGroupNames {
    const val GROUP_FILE = "etc/group"

    /** Every generated name matches `[a-z_][a-z0-9_-]*` and this maximum. */
    const val PREFIX = "kairos_g"
    const val MAX_NAME_LENGTH = 32

    /** Written only when `etc/group` does not exist at all. */
    const val ROOT_ROW = "root:x:0:"

    private const val TMP_PREFIX = ".kairos-group-"
    private val NAME = Regex("^[a-z_][a-z0-9_-]*$")
    private val TMP_COUNTER = AtomicInteger(0)

    /** Result of [merge] — pure data, no filesystem involved. */
    data class Merge(
        val text: String,
        /** id → generated name, in the order the rows were appended. */
        val added: Map<Int, String>,
        /** Requested ids that already had a row and were left alone. */
        val alreadyNamed: List<Int>,
    )

    /** Result of [ensure]; [error] is non-null when nothing could be written. */
    data class Outcome(
        val groupFile: String,
        val requested: List<Int>,
        val added: Map<Int, String>,
        val alreadyNamed: List<Int>,
        /** True when this run created `etc/group`. */
        val created: Boolean,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    // ── Pure logic ─────────────────────────────────────────────────────────

    /** `kairos_g3003`; deterministic and unique per gid unless that name is taken. */
    fun groupName(gid: Int): String = "$PREFIX$gid"

    fun isLegalName(name: String): Boolean =
        name.length in 1..MAX_NAME_LENGTH && NAME.matches(name)

    /**
     * Parse a `/proc/<pid>/status` body and return its `Groups:` ids.
     *
     * The kernel writes the supplementary list tab-separated on one line;
     * empty or non-numeric tokens are ignored rather than failing the whole
     * read. A missing line returns an empty list.
     */
    fun parseProcStatus(text: String): List<Int> {
        for (line in text.lineSequence()) {
            if (!line.startsWith("Groups:")) continue
            val rest = line.removePrefix("Groups:").trim()
            if (rest.isEmpty()) return emptyList()
            return normalizeIds(rest.split(' ', '\t').mapNotNull { it.toIntOrNull() })
        }
        return emptyList()
    }

    /** Distinct, sorted, positive ids. gid 0 is deliberately not ours to name. */
    fun normalizeIds(ids: Iterable<Int>): List<Int> =
        ids.filter { it > 0 }.distinct().sorted()

    /** Every gid that already has a name (first row wins for a duplicate gid). */
    fun parseGroupFile(text: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (line in text.split('\n')) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("+")) continue
            val fields = trimmed.split(':')
            if (fields.size <= 2) continue
            val gid = fields[2].trim().toIntOrNull() ?: continue
            if (!out.containsKey(gid)) out[gid] = fields[0]
        }
        return out
    }

    /** Every name already present, including rows with an unparseable gid. */
    fun namesIn(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (line in text.split('\n')) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("+")) continue
            val name = trimmed.substringBefore(':')
            if (name.isNotEmpty()) out += name
        }
        return out
    }

    /**
     * Append one row per requested id that has no row yet.
     *
     * Original text is never rewritten: rows go after exactly one `\n`
     * (inserted only when the file did not end with one). A name produced by
     * [groupName] can already belong to another gid (a hand-written group);
     * then `kairos_g<gid>_2`, `_3`, … keeps the name legal and unique.
     */
    fun merge(text: String, ids: Iterable<Int>): Merge {
        val existing = parseGroupFile(text)
        val taken = namesIn(text).toMutableSet()
        val requested = normalizeIds(ids)
        val added = LinkedHashMap<Int, String>()
        val already = mutableListOf<Int>()
        for (id in requested) {
            if (existing.containsKey(id)) {
                already += id
                continue
            }
            var name = groupName(id)
            var suffix = 2
            while (taken.contains(name)) {
                name = "${groupName(id)}_$suffix"
                suffix++
            }
            taken += name
            added[id] = name
        }
        if (added.isEmpty()) {
            return Merge(text, emptyMap(), already)
        }
        val sb = StringBuilder(text)
        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        for ((id, name) in added) {
            sb.append(name).append(":x:").append(id).append(":\n")
        }
        return Merge(sb.toString(), added, already)
    }

    // ── Filesystem side ────────────────────────────────────────────────────

    /**
     * Ensure `<rootfs>/etc/group` names every id in [ids]. Never throws: a
     * read-only or malformed rootfs is reported through [Outcome.error] so the
     * caller can log it and keep the shell starting.
     */
    fun ensure(rootfs: File, ids: Iterable<Int>): Outcome {
        val requested = normalizeIds(ids)
        val file = File(rootfs, GROUP_FILE)
        if (requested.isEmpty()) {
            return Outcome(file.absolutePath, requested, emptyMap(), emptyList(), false)
        }
        return try {
            val target = resolveLink(file)
            // A symlinked etc/group is written through to its target, but the
            // target must still be inside this rootfs: a broken image must not
            // make us append rows to the host's /etc/group.
            val rootPath = rootfs.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(rootPath)) {
                return Outcome(
                    file.absolutePath,
                    requested,
                    emptyMap(),
                    emptyList(),
                    false,
                    "refusing to write outside the rootfs: $targetPath",
                )
            }
            if (target.exists() && !target.isFile) {
                return Outcome(
                    file.absolutePath,
                    requested,
                    emptyMap(),
                    emptyList(),
                    false,
                    "not a regular file: ${target.absolutePath}",
                )
            }
            val created = !target.isFile
            val current = if (created) "" else target.readText()
            val merge = merge(current, requested)
            if (merge.added.isEmpty()) {
                return Outcome(
                    file.absolutePath,
                    requested,
                    emptyMap(),
                    merge.alreadyNamed,
                    false,
                )
            }
            val text = if (created) ROOT_ROW + "\n" + merge.text else merge.text
            target.parentFile?.mkdirs()
            atomicWrite(target, text, created)
            Outcome(
                file.absolutePath,
                requested,
                merge.added,
                merge.alreadyNamed,
                created,
            )
        } catch (t: Throwable) {
            Outcome(
                file.absolutePath,
                requested,
                emptyMap(),
                emptyList(),
                false,
                "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /** Follow a symlink chain (bounded) to the real file, or return [file]. */
    fun resolveLink(file: File): File {
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
     * `write(tmp) + fsync + mode + rename` in the target's own directory, so a
     * crash cannot leave a truncated group database behind.
     *
     * The mode is not invented: an existing `/etc/group` keeps exactly the
     * permissions it had (a user-tightened 0600 stays 0600), and only a file
     * this run creates gets the conventional Ubuntu 0644. If the mode cannot be
     * read or applied, [applyMode] throws before `rename(2)`; [ensure] reports
     * that through [Outcome.error] and the original file is left untouched.
     */
    private fun atomicWrite(target: File, text: String, created: Boolean) {
        val dir = target.parentFile ?: throw IllegalStateException("no parent for $target")
        val tmp = File(dir, TMP_PREFIX + target.name + "." + TMP_COUNTER.incrementAndGet())
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            applyMode(target, tmp, created)
            if (!tmp.renameTo(target)) throw IllegalStateException("rename failed: $tmp -> $target")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Copy the original mode, or use 0644 for a newly created group file.
     * Unsupported/failed POSIX attributes propagate to [ensure], which returns
     * an error instead of replacing the file with a wider or unknown mode.
     */
    private fun applyMode(source: File, tmp: File, created: Boolean) {
        val perms = if (!created) {
            java.nio.file.Files.getPosixFilePermissions(source.toPath())
        } else {
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        }
        java.nio.file.Files.setPosixFilePermissions(tmp.toPath(), perms)
    }
}
