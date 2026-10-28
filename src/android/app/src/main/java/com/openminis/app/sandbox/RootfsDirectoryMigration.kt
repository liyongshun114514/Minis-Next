package com.openminis.app.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption

/** A one-time directory rename; never copies, merges or traverses a guest tree. */
object RootfsDirectoryMigration {
    private const val LOCK_FILE = ".ubuntu-rootfs-migration.lock"
    private const val PENDING_FILE = ".ubuntu-rootfs-migration.pending"

    /** Only this exact, relative compatibility alias is accepted. No guest traversal. */
    private fun isCompatibilityAlias(old: File): Boolean =
        Files.isSymbolicLink(old.toPath()) &&
            Files.readSymbolicLink(old.toPath()).toString() == SandboxDistribution.ROOTFS_DIR_NAME

    private fun makeAlias(old: File) {
        Files.createSymbolicLink(old.toPath(), Paths.get(SandboxDistribution.ROOTFS_DIR_NAME))
    }

    /**
     * Serializes app processes as well as threads. The lock inode stays in place:
     * deleting it could let another process lock a different inode concurrently.
     * Failure is reported to RootfsManager, which disables installation/writes.
     */
    @Synchronized
    fun migrate(filesDir: File): File {
        val target = File(filesDir, SandboxDistribution.ROOTFS_DIR_NAME)
        val old = File(filesDir, SandboxDistribution.LEGACY_ROOTFS_DIR_NAME)
        val lock = File(filesDir, LOCK_FILE).toPath()
        if (!filesDir.isDirectory) throw IOException("App files directory is unavailable: $filesDir")
        // NOFOLLOW_LINKS prevents opening a symlink as the migration lock.
        Files.newByteChannel(lock, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
            val fileChannel = channel as? java.nio.channels.FileChannel
                ?: throw IOException("Cannot lock Ubuntu rootfs directory migration")
            fileChannel.lock().use {
                val targetExists = validateDirectory(target)
                val pending = File(filesDir, PENDING_FILE)
                val pendingExists = Files.exists(pending.toPath(), NOFOLLOW_LINKS)
                if (pendingExists && (Files.isSymbolicLink(pending.toPath()) || !pending.isFile ||
                        pending.readText() != SandboxDistribution.ROOTFS_DIR_NAME + "\n")) {
                    throw IOException("Unrecognized Ubuntu migration journal at $pending; no directories were changed")
                }
                if (isCompatibilityAlias(old)) {
                    if (!targetExists) throw IOException("Ubuntu compatibility alias has no target at $target; restore the data before restarting")
                    if (pendingExists) Files.delete(pending.toPath())
                    return target
                }
                val oldExists = validateDirectory(old)
                when {
                    !oldExists -> {
                        // A killed process may have completed the rename but not
                        // created the compatibility alias. Only our journal opts in.
                        if (pendingExists) {
                            if (!targetExists) throw IOException("Interrupted Ubuntu directory migration: both data directories are missing")
                            makeAlias(old)
                            Files.delete(pending.toPath())
                        }
                    }
                    !targetExists -> renameWithCompatibility(old, target, pending)
                    isEmpty(old) -> {
                        Files.delete(old.toPath()) // only an empty directory, never user content
                        if (pendingExists) Files.delete(pending.toPath())
                    }
                    isEmpty(target) -> {
                        Files.delete(target.toPath())
                        renameWithCompatibility(old, target, pending)
                    }
                    else -> throw IOException(
                        "Both ${old.absolutePath} and ${target.absolutePath} contain data. " +
                            "Neither directory was changed. Back up both and keep only the intended live tree before restarting."
                    )
                }
                return target
            }
        }
    }

    private fun validateDirectory(dir: File): Boolean {
        val path = dir.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw IOException("Rootfs path is not a regular directory: ${dir.absolutePath}; nothing was changed")
        }
        return true
    }

    private fun isEmpty(dir: File): Boolean = Files.newDirectoryStream(dir.toPath()).use {
        !it.iterator().hasNext()
    }

    private fun renameWithCompatibility(old: File, target: File, pending: File) {
        // PRoot link2symlink sentinels and imported host-absolute references
        // may still contain the old path. Keep ONE relative alias, not a copy.
        // Do not rewrite user files/symlinks or enumerate the installed tree.
        Files.newByteChannel(pending.toPath(),
            setOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { journal ->
            val channel = journal as? java.nio.channels.FileChannel
                ?: throw IOException("Cannot persist Ubuntu migration journal")
            val bytes = java.nio.ByteBuffer.wrap((SandboxDistribution.ROOTFS_DIR_NAME + "\n").toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        move(old, target)
        try {
            makeAlias(old)
        } catch (failure: Exception) {
            // Roll back the rename if creating the alias failed. Keep the
            // journal if rollback also fails so the next launch can recover.
            try {
                Files.move(target.toPath(), old.toPath())
                Files.delete(pending.toPath())
            } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw IOException("Ubuntu rootfs compatibility alias could not be created; migration is blocked", failure)
        }
        Files.delete(pending.toPath())
    }

    private fun move(old: File, target: File) {
        // Default move explicitly does NOT use REPLACE_EXISTING. Same-parent
        // files are renamed; no copy fallback and no child contents are touched.
        try {
            Files.move(old.toPath(), target.toPath())
        } catch (e: IOException) {
            throw IOException("Cannot rename ${old.absolutePath} to ${target.absolutePath}; existing data was preserved", e)
        }
    }
}
