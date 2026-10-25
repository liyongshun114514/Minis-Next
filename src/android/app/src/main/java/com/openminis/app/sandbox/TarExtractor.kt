package com.openminis.app.sandbox

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Minimal, defence-oriented tar reader used to unpack the Ubuntu base rootfs on
 * device (no external library, no shell, no host tools).
 *
 * Pure Kotlin on purpose: it has no Android dependency, so the JVM unit tests
 * can exercise the *production* extractor against synthetic archives and the
 * real ubuntu-base tarball instead of a copy of the algorithm.
 *
 * Safety properties (see docs/android-ubuntu.md):
 *  - entry names are reduced by [SandboxDistribution.sanitizeArchivePath];
 *    absolute names and any `..` segment throw instead of being extracted
 *  - parent directories are created only when they are real directories; a
 *    symlinked parent aborts the entry, so a crafted member cannot redirect a
 *    later write outside the rootfs (tar-slip)
 *  - an existing symlink at a regular-file path is deleted, never followed
 *  - tar header checksums are verified, catching desync/truncation
 *  - GNU long name/link ('L'/'K') and PAX ('x'/'g') headers are supported
 *  - hard links are materialised as a copy (or a symlink clone when the target
 *    is a symlink); `link(2)` is deliberately avoided — PRoot's link2symlink
 *    emulation and some emulated/FUSE storage mangle or reject it
 *  - device/FIFO/socket members are skipped (they need real root; PRoot binds
 *    /dev at runtime)
 *
 * Symlink *targets* are intentionally not sanitised: the Ubuntu archive has
 * 20 absolute symlinks and relative ones containing `..`, all valid inside the
 * guest. The extractor never follows them.
 */
object TarExtractor {

    private const val BLOCK = 512
    private const val MAX_METADATA_BYTES = 1L * 1024 * 1024

    /**
     * Extract [input] into [targetDir].
     *
     * @param log optional diagnostics sink (the app passes `Log.w`); must not
     *            throw. Defaults to a no-op so JVM tests stay silent.
     */
    fun extract(input: InputStream, targetDir: File, log: (String) -> Unit = {}) {
        val root = targetDir.canonicalFile
        if (!root.isDirectory && !root.mkdirs()) throw IOException("tar: cannot create $root")

        val header = ByteArray(BLOCK)
        val pendingHardLinks = ArrayList<Pair<String, String>>()
        val directoryModes = ArrayList<Pair<String, Int>>()
        var paxPath: String? = null
        var paxLink: String? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < BLOCK) break

            // Two consecutive zero blocks mark the end of the archive.
            if (header.all { it == 0.toByte() }) break
            if (!isValidChecksum(header)) {
                throw IOException("tar: header checksum mismatch — corrupt or truncated archive")
            }

            val typeFlag = header[156].toInt().toChar()
            val size = extractString(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            if (size < 0) throw IOException("tar: negative entry size")
            val mode = extractString(header, 100, 8).trim().toIntOrNull(8) ?: 0

            // Metadata entries describe the NEXT member and carry their own data.
            when (typeFlag) {
                'x', 'g' -> {
                    val data = readEntryData(input, size)
                    skipPadding(input, size)
                    if (typeFlag == 'x') {
                        val records = parsePaxRecords(data)
                        records["path"]?.let { paxPath = it }
                        records["linkpath"]?.let { paxLink = it }
                    }
                    continue
                }
                'L' -> {
                    gnuLongName = readEntryData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                    skipPadding(input, size)
                    continue
                }
                'K' -> {
                    gnuLongLink = readEntryData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                    skipPadding(input, size)
                    continue
                }
            }

            val ustarPrefix = if (extractString(header, 257, 6).startsWith("ustar")) {
                extractString(header, 345, 155)
            } else {
                ""
            }
            val rawName = paxPath ?: gnuLongName ?: joinName(extractString(header, 0, 100), ustarPrefix)
            val rawLink = paxLink ?: gnuLongLink ?: extractString(header, 157, 100)
            paxPath = null
            paxLink = null
            gnuLongName = null
            gnuLongLink = null

            val rel = SandboxDistribution.sanitizeArchivePath(rawName)
                ?: throw IOException("tar: refusing unsafe entry path '${rawName.take(200)}'")

            when (typeFlag) {
                '5', 'D' -> {
                    ensureDirectory(root, rel)
                    // Applied after the whole extract: a read-only directory
                    // mode would block writing its children.
                    if (mode and 0x1FF != 0) directoryModes += rel to mode
                }
                '2' -> createSymlink(root, rel, rawLink)
                '0', '\u0000', '7' -> {
                    writeFileEntry(input, root, rel, size, mode)
                    skipPadding(input, size)
                    continue
                }
                '1' -> {
                    val linkRel = SandboxDistribution.sanitizeArchivePath(rawLink)
                        ?: throw IOException("tar: refusing unsafe hard link target '${rawLink.take(200)}'")
                    pendingHardLinks += rel to linkRel
                }
                else -> log("tar: skipping unsupported entry type '$typeFlag' ($rel)")
            }

            if (size > 0) {
                skipFully(input, size)
                skipPadding(input, size)
            }
        }

        // Directory modes last, deepest first.
        for ((rel, mode) in directoryModes.sortedByDescending { it.first.length }) {
            try {
                applyMode(File(root, rel), mode)
            } catch (t: Throwable) {
                log("tar: cannot set mode on directory '$rel': ${t.message}")
            }
        }

        for ((destRel, targetRel) in pendingHardLinks) {
            val dest = File(root, destRel)
            val target = File(root, targetRel)
            try {
                ensureSafeParents(root, destRel)
                if (!linkOrCopy(target, dest)) {
                    log("tar: hard link target missing for $destRel -> $targetRel")
                }
            } catch (t: Throwable) {
                // A hard link that cannot be materialised must not abort an
                // otherwise complete extract; the file list is still valid.
                log("tar: hard link $destRel -> $targetRel failed: ${t.message}")
            }
        }
    }

    // ── member handling ──────────────────────────────────────────────────────

    private fun joinName(name: String, prefix: String): String =
        if (prefix.isEmpty()) name else "$prefix/$name"

    /**
     * Create the parent chain of [rel] without ever traversing a symlink.
     * Throws when a component is a symlink or a non-directory: that is the
     * tar-slip case where a later entry would land outside the rootfs.
     */
    private fun ensureSafeParents(root: File, rel: String) {
        val segments = rel.split('/')
        var current = root
        for (i in 0 until segments.size - 1) {
            current = File(current, segments[i])
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) {
                throw IOException("tar: refusing to traverse symlinked directory '$rel'")
            }
            if (current.exists() && !current.isDirectory) {
                throw IOException("tar: '$rel' has a non-directory parent")
            }
            if (!current.isDirectory && !current.mkdirs() && !current.isDirectory) {
                throw IOException("tar: cannot create parent directory for '$rel'")
            }
        }
    }

    private fun ensureDirectory(root: File, rel: String) {
        ensureSafeParents(root, rel)
        val dir = File(root, rel)
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            throw IOException("tar: refusing to replace symlink '$rel' with a directory")
        }
        if (dir.exists() && !dir.isDirectory) {
            throw IOException("tar: directory entry '$rel' collides with a file")
        }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("tar: cannot create directory '$rel'")
        }
    }

    private fun createSymlink(root: File, rel: String, target: String) {
        if (target.isEmpty()) throw IOException("tar: symlink '$rel' has an empty target")
        ensureSafeParents(root, rel)
        val out = File(root, rel)
        val path = out.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            out.delete()
        } else if (out.isDirectory) {
            throw IOException("tar: symlink '$rel' collides with a directory")
        } else if (out.exists()) {
            out.delete()
        }
        java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
    }

    private fun writeFileEntry(input: InputStream, root: File, rel: String, size: Long, mode: Int) {
        ensureSafeParents(root, rel)
        val out = File(root, rel)
        val path = out.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            // Never follow a link shipped by an earlier entry.
            out.delete()
        } else if (out.isDirectory) {
            throw IOException("tar: file entry '$rel' collides with a directory")
        } else if (out.exists()) {
            out.delete()
        }
        out.outputStream().use { output ->
            var remaining = size
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw IOException("tar: truncated data for '$rel'")
                output.write(buf, 0, n)
                remaining -= n
            }
        }
        applyMode(out, mode)
    }

    /**
     * Apply the tar mode faithfully (0755/0644/…). Falls back to the three
     * Java booleans when POSIX permissions are unavailable (some emulated or
     * FUSE-backed storage), which is what the previous implementation did for
     * the execute bit only.
     */
    private fun applyMode(file: File, mode: Int) {
        val bits = mode and 0x1FF // permission bits only; setuid/setgid/sticky ignored
        if (bits == 0) return
        try {
            val spec = buildString(9) {
                for (shift in intArrayOf(6, 3, 0)) {
                    val v = (bits shr shift) and 7
                    append(if (v and 4 != 0) 'r' else '-')
                    append(if (v and 2 != 0) 'w' else '-')
                    append(if (v and 1 != 0) 'x' else '-')
                }
            }
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString(spec),
            )
        } catch (_: Throwable) {
            file.setReadable(bits and 0b100_100_100 != 0, false)
            file.setWritable(bits and 0b010_010_010 != 0, false)
            file.setExecutable(bits and 0b001_001_001 != 0, false)
        }
    }

    /**
     * Materialise a tar hard-link member.
     *
     * `link(2)` is deliberately not used:
     *  - PRoot's `link2symlink` extension rewrites hard links into `.l2s.*`
     *    sentinel files, which would litter the extracted rootfs (the two
     *    hard links in ubuntu-base are `usr/bin/perl5.38.2 -> usr/bin/perl` and
     *    `usr/bin/uncompress -> usr/bin/gunzip`);
     *  - emulated/FUSE-backed app storage can reject hard links outright.
     *
     * A copy (with the target's permission bits) or, when the target is a
     * symlink, a symlink clone is indistinguishable to the guest for these two
     * files. Costs ~4 MB once, and makes extraction deterministic on every
     * filesystem.
     */
    private fun linkOrCopy(target: File, dest: File): Boolean {
        val targetPath = target.toPath()
        val isLink = java.nio.file.Files.isSymbolicLink(targetPath)
        if (!isLink && !target.exists()) return false
        if (java.nio.file.Files.isSymbolicLink(dest.toPath()) || dest.exists()) dest.delete()
        if (isLink) {
            return try {
                java.nio.file.Files.createSymbolicLink(
                    dest.toPath(),
                    java.nio.file.Files.readSymbolicLink(targetPath),
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
        return try {
            target.copyTo(dest, overwrite = true)
            try {
                java.nio.file.Files.setPosixFilePermissions(
                    dest.toPath(),
                    java.nio.file.Files.getPosixFilePermissions(targetPath),
                )
            } catch (_: Throwable) {
                dest.setExecutable(target.canExecute(), false)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ── primitives ──────────────────────────────────────────────────────────

    fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charsets.UTF_8)
    }

    fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readEntryData(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_METADATA_BYTES) {
            throw IOException("tar: metadata entry too large ($size)")
        }
        val data = ByteArray(size.toInt())
        if (readFully(input, data) != data.size) throw IOException("tar: truncated archive")
        return data
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val remainder = (size % BLOCK.toLong()).toInt()
        if (remainder != 0) skipFully(input, (BLOCK - remainder).toLong())
    }

    /**
     * GNU tar writes 8 octal digits; other tools write 6 digits + NUL + space.
     * Both the unsigned and the historical signed sum are accepted.
     */
    internal fun isValidChecksum(header: ByteArray): Boolean {
        val stored = extractString(header, 148, 8).trim().trimEnd('\u0000').toLongOrNull(8) ?: return false
        var unsigned = 0L
        var signed = 0L
        for (i in header.indices) {
            if (i in 148..155) {
                unsigned += 32
                signed += 32
            } else {
                val b = header[i].toInt()
                unsigned += (b and 0xFF)
                signed += b
            }
        }
        return stored == unsigned || stored == signed
    }

    /**
     * PAX records: `"<len> <key>=<value>\n"` where `<len>` is the byte length of
     * the entire record. Unknown keys are ignored.
     */
    internal fun parsePaxRecords(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var space = i
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val len = String(data, i, space - i, Charsets.US_ASCII).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > data.size || i + len <= space) break
            val bodyLen = (i + len - 1) - (space + 1)
            if (bodyLen > 0) {
                val text = String(data, space + 1, bodyLen, Charsets.UTF_8)
                val eq = text.indexOf('=')
                if (eq > 0) out[text.substring(0, eq)] = text.substring(eq + 1)
            }
            i += len
        }
        return out
    }
}
