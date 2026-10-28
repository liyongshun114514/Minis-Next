package com.openminis.app.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/**
 * Ordinary isolated filesystem compatibility fixtures; no guest program runs.
 *
 * A migrated old install keeps `alpine-rootfs` as one relative symlink whose
 * target string is exactly `Ubuntu-rootfs`, so PRoot `--link2symlink` `.l2s`
 * records with old host absolute paths keep resolving. A fresh install and a
 * pre-existing new tree have no old name at all.
 */
class RootfsDirectoryMigrationTest {
    private val aliasTarget = SandboxDistribution.ROOTFS_DIR_NAME
    private val pendingMarker = ".ubuntu-rootfs-migration.pending"
    private val pendingValue = SandboxDistribution.ROOTFS_DIR_NAME + "\n"

    private fun fixture(block: (File, File, File) -> Unit) {
        val parent = Files.createTempDirectory("ubuntu-rootfs-rename-").toFile()
        try {
            block(parent, File(parent, SandboxDistribution.LEGACY_ROOTFS_DIR_NAME),
                File(parent, SandboxDistribution.ROOTFS_DIR_NAME))
        } finally { GuestTreeFiles.deleteTree(parent) }
    }
    private fun write(root: File, path: String, text: String = "keep me"): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun existsNoFollow(path: File): Boolean = Files.exists(path.toPath(), NOFOLLOW_LINKS)

    /** Only this exact string is a compatibility alias; never a second tree. */
    private fun assertCompatibilityAlias(parent: File, old: File) {
        assertTrue("old name must remain a symlink alias", Files.isSymbolicLink(old.toPath()))
        assertEquals(aliasTarget, Files.readSymbolicLink(old.toPath()).toString())
        val target = File(parent, aliasTarget)
        assertTrue("alias must resolve to the current tree", Files.isSameFile(old.toPath(), target.toPath()))
        assertTrue("target must stay a real directory", Files.isDirectory(target.toPath(), NOFOLLOW_LINKS))
        assertFalse("an alias is not a second tree", Files.isDirectory(old.toPath(), NOFOLLOW_LINKS))
    }
    private fun assertNoOldName(old: File) = assertFalse(existsNoFollow(old))
    private fun assertNoPending(parent: File) = assertFalse(File(parent, pendingMarker).exists())

    @Test fun freshInstallReturnsNewPathWithoutCreatingEitherTreeOrAlias() = fixture { parent, old, target ->
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertNoOldName(old)
        assertFalse(target.exists())
        assertNoPending(parent)
    }
    @Test fun fullTreeIsRenamedWithDataTimestampAndExecutableMode() = fixture { parent, old, target ->
        val data = write(old, "var/minis/workspace/data.txt", "personal data\n")
        write(old, "root/.hidden", "hidden config\n")
        val tool = write(old, "usr/local/bin/custom", "#!/bin/sh\necho local\n")
        assertTrue(tool.setExecutable(true, false))
        val timestamp = data.lastModified()
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertCompatibilityAlias(parent, old)
        assertEquals("personal data\n", File(target, "var/minis/workspace/data.txt").readText())
        assertEquals(timestamp, File(target, "var/minis/workspace/data.txt").lastModified())
        assertEquals("hidden config\n", File(target, "root/.hidden").readText())
        assertTrue(File(target, "usr/local/bin/custom").canExecute())
        assertEquals("personal data\n", File(old, "var/minis/workspace/data.txt").readText())
        assertNoPending(parent)
    }
    @Test fun relativeLinksAndHardlinkIdentitySurviveRename() = fixture { parent, old, target ->
        val file = write(old, "usr/bin/custom", "data")
        Files.createSymbolicLink(File(old, "bin").toPath(), File("usr/bin").toPath())
        Files.createLink(File(old, "usr/bin/custom-alias").toPath(), file.toPath())
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertEquals("usr/bin", Files.readSymbolicLink(File(target, "bin").toPath()).toString())
        assertEquals("data", File(target, "bin/custom").readText())
        assertTrue(Files.isSameFile(File(target, "usr/bin/custom").toPath(), File(target, "usr/bin/custom-alias").toPath()))
        assertEquals("data", File(target, "usr/bin/custom-alias").readText())
        // The old path must keep reaching the very same inode through one alias.
        assertCompatibilityAlias(parent, old)
        assertEquals("data", File(old, "usr/bin/custom").readText())
        assertTrue(Files.isSameFile(File(target, "usr/bin/custom").toPath(), file.toPath()))
    }
    @Test fun repeatedCallsKeepCurrentTreeAndOneExactAlias() = fixture { parent, old, target ->
        write(old, "content")
        repeat(3) { assertEquals(target, RootfsDirectoryMigration.migrate(parent)) }
        assertCompatibilityAlias(parent, old)
        assertEquals("keep me", File(target, "content").readText())
        assertNoPending(parent)
    }
    @Test fun oldInstalledCompatibilityAliasIsAcceptedAndVerifiedWithoutRename() = fixture { parent, old, target ->
        write(target, "var/minis/workspace/data.txt", "kept\n")
        val before = target.lastModified()
        Files.createSymbolicLink(old.toPath(), Paths.get(aliasTarget))
        repeat(2) { assertEquals(target, RootfsDirectoryMigration.migrate(parent)) }
        assertCompatibilityAlias(parent, old)
        assertEquals("kept\n", File(target, "var/minis/workspace/data.txt").readText())
        assertEquals(before, target.lastModified())
        assertNoPending(parent)
    }
    @Test fun absoluteAliasIsRejectedEvenWhenItResolvesToTheCurrentTree() = fixture { parent, old, target ->
        write(target, "current.txt", "current")
        Files.createSymbolicLink(old.toPath(), target.toPath())
        val failure = runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull()
        assertTrue("only the exact relative alias is accepted", failure is IOException)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals(target.toPath(), Files.readSymbolicLink(old.toPath()))
        assertEquals("current", File(target, "current.txt").readText())
    }
    @Test fun nonExactRelativeAliasesAreRejectedWithoutTouchingEitherTree() {
        for (bad in listOf("../elsewhere", "ubuntu-rootfs", "./$aliasTarget")) {
            fixture { parent, old, target ->
                val elsewhere = File(parent, "elsewhere").apply { mkdirs() }
                write(elsewhere, "outside.txt", "outside")
                write(target, "current.txt", "current")
                Files.createSymbolicLink(old.toPath(), Paths.get(bad))
                val failure = runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull()
                assertTrue("alias '$bad' must be rejected", failure is IOException)
                assertTrue(Files.isSymbolicLink(old.toPath()))
                assertEquals(bad, Files.readSymbolicLink(old.toPath()).toString())
                assertEquals("current", File(target, "current.txt").readText())
                assertEquals("outside", File(elsewhere, "outside.txt").readText())
            }
        }
    }
    @Test fun danglingExactAliasWithoutCurrentTreeIsRejectedAndPreserved() = fixture { parent, old, _ ->
        Files.createSymbolicLink(old.toPath(), Paths.get(aliasTarget))
        val failure = runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals(aliasTarget, Files.readSymbolicLink(old.toPath()).toString())
        assertNoPending(parent)
    }
    @Test fun pendingMarkerRestoresAliasAfterRenameWithoutCopyingTheTree() = fixture { parent, old, target ->
        write(target, "var/minis/workspace/data.txt", "kept\n")
        File(parent, pendingMarker).writeText(pendingValue)
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertCompatibilityAlias(parent, old)
        assertEquals("kept\n", File(old, "var/minis/workspace/data.txt").readText())
        assertNoPending(parent)
    }
    @Test fun pendingMarkerIsClearedWhenTheCorrectAliasAlreadyExists() = fixture { parent, old, target ->
        write(target, "content", "current")
        Files.createSymbolicLink(old.toPath(), Paths.get(aliasTarget))
        File(parent, pendingMarker).writeText(pendingValue)
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertCompatibilityAlias(parent, old)
        assertEquals("current", File(target, "content").readText())
        assertNoPending(parent)
    }
    @Test fun existingTargetWithoutOldCreatesNoAliasAndNoPendingMarker() = fixture { parent, old, target ->
        write(target, "current.txt", "current")
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertNoOldName(old)
        assertEquals("current", File(target, "current.txt").readText())
        assertNoPending(parent)
    }
    @Test fun emptyNewDirectoryDoesNotHideOldUserData() = fixture { parent, old, target ->
        target.mkdirs()
        write(old, "old-content")
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertCompatibilityAlias(parent, old)
        assertEquals("keep me", File(target, "old-content").readText())
    }
    @Test fun emptyLegacyDirectoryCanBeRemovedWithoutTouchingCurrentDataOrCreatingAlias() = fixture { parent, old, target ->
        old.mkdirs()
        write(target, "current-content")
        assertEquals(target, RootfsDirectoryMigration.migrate(parent))
        assertNoOldName(old)
        assertEquals("keep me", File(target, "current-content").readText())
        assertNoPending(parent)
    }
    @Test fun twoEmptyDirectoriesKeepOnlyCanonicalPathAndNoAlias() = fixture { parent, old, target ->
        old.mkdirs(); target.mkdirs()
        RootfsDirectoryMigration.migrate(parent)
        assertNoOldName(old); assertTrue(target.isDirectory)
        assertNoPending(parent)
    }
    @Test fun twoPopulatedDirectoriesNeverMergeOverwriteOrAlias() = fixture { parent, old, target ->
        write(old, "same.txt", "old contents")
        write(target, "same.txt", "new contents")
        try { RootfsDirectoryMigration.migrate(parent); fail("Conflict must be reported") }
        catch (expected: IOException) { assertTrue(expected.message!!.contains("Both")) }
        assertFalse(Files.isSymbolicLink(old.toPath()))
        assertEquals("old contents", File(old, "same.txt").readText())
        assertEquals("new contents", File(target, "same.txt").readText())
    }
    @Test fun regularFileAtLegacyPathIsNotDeletedOrAliased() = fixture { parent, old, target ->
        old.writeText("not a directory")
        assertTrue(runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull() is IOException)
        assertFalse(Files.isSymbolicLink(old.toPath()))
        assertEquals("not a directory", old.readText())
        assertFalse(target.exists())
    }
    @Test fun regularFileAtTargetIsNotOverwritten() = fixture { parent, old, target ->
        write(old, "old-content")
        target.writeText("keep target file")
        assertTrue(runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull() is IOException)
        assertEquals("keep target file", target.readText())
        assertEquals("keep me", File(old, "old-content").readText())
        assertFalse(Files.isSymbolicLink(old.toPath()))
    }
    @Test fun topLevelLinkIsNotTraversedOrPromoted() = fixture { parent, old, target ->
        val elsewhere = File(parent, "elsewhere").apply { mkdirs() }
        write(elsewhere, "data.txt", "outside")
        Files.createSymbolicLink(old.toPath(), elsewhere.toPath())
        assertTrue(runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull() is IOException)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertFalse(target.exists())
        assertEquals("outside", File(elsewhere, "data.txt").readText())
    }
    @Test fun targetLinkIsNeverAcceptedEvenWhenLegacyIsMissing() = fixture { parent, _, target ->
        val elsewhere = File(parent, "elsewhere").apply { mkdirs() }
        Files.createSymbolicLink(target.toPath(), elsewhere.toPath())
        assertTrue(runCatching { RootfsDirectoryMigration.migrate(parent) }.exceptionOrNull() is IOException)
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertTrue(elsewhere.list()!!.isEmpty())
    }
    @Test fun concurrentThreadsUseOneRenameAndSameAlias() = fixture { parent, old, target ->
        write(old, "content")
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val jobs = (1..6).map { executor.submit<File> { RootfsDirectoryMigration.migrate(parent) } }
            jobs.forEach { assertEquals(target, it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertCompatibilityAlias(parent, old)
            assertEquals("keep me", File(target, "content").readText())
            assertNoPending(parent)
        } finally { executor.shutdownNow() }
    }
}
