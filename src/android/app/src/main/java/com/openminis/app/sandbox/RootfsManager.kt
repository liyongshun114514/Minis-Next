package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Observable state for rootfs installation. Mirrors the conceptual iOS
 * `downloadRootfs(progress:)` contract: discrete phases with a 0..1 fraction
 * during the long-running extract step. Neither platform actually downloads
 * the rootfs from the network today — it ships bundled as an asset — so
 * "progress" tracks asset-stream consumption (compressed bytes) instead of
 * HTTP transfer. The fraction semantics are identical from the UI's viewpoint.
 */
sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    /** progress in 0f..1f, based on compressed asset bytes consumed. */
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * Manages the **Ubuntu 24.04 LTS arm64** rootfs installation and PRoot binary
 * extraction on Android (iOS keeps its Alpine/iSH guest).
 *
 * Distribution-specific constants, apt sources and the guest defaults live in
 * [SandboxDistribution]. Two properties of this class are load-bearing:
 *
 * 1. The directory is still named `alpine-rootfs` (see
 *    [SandboxDistribution.ROOTFS_DIR_NAME]) because unmodified readers hardcode
 *    that path. The `.distro` marker, not the name, identifies the guest.
 * 2. An existing rootfs is **never deleted or overwritten in place**. A
 *    complete tree is extracted into [SandboxDistribution.STAGING_DIR_NAME]
 *    first; only then is the old tree renamed to an
 *    `alpine-rootfs.pre-ubuntu-<timestamp>` sibling (data preserved) and the
 *    staging directory renamed into place. A failed extract leaves the previous
 *    guest untouched.
 *
 * Corresponds to iOS RootfsManager.swift, which is unchanged.
 */
class RootfsManager private constructor(private val context: Context) {

    /** See [SandboxDistribution.ROOTFS_DIR_NAME] — legacy path, not the distro. */
    val rootfsDir: File = File(context.filesDir, SandboxDistribution.ROOTFS_DIR_NAME)
    val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    val isInstalled: Boolean
        get() = SandboxDistribution.isUbuntuRootfs(rootfsDir) && UbuntuToolsetStatus.isReady(rootfsDir)

    /**
     * Directory that received the previous (Alpine/broken) rootfs during the
     * most recent install, or null. Set once per install, kept for the UI and
     * for logs — the backup itself is never removed automatically.
     */
    @Volatile
    var lastLegacyBackup: File? = null
        private set

    /** Every preserved pre-Ubuntu rootfs, newest last. Never deleted automatically. */
    val legacyBackupDirs: List<File>
        get() = context.filesDir.listFiles()
            ?.filter {
                it.isDirectory && it.name.startsWith(SandboxDistribution.LEGACY_BACKUP_PREFIX)
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Observable install progress. UI layers (OnboardingScreen,
     * RootfsManagementScreen) bind this and render a progress bar during
     * `installIfNeeded()` / `reset()`. Emits `Installed` on success and
     * `Failed` on error so callers can surface retry affordances.
     */
    private val _installState = MutableStateFlow<RootfsInstallState>(
        if (isInstalled) RootfsInstallState.Installed else RootfsInstallState.Idle
    )
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    /**
     * Install the Ubuntu 24.04 rootfs from assets if not already present.
     *
     * Flow: extract the whole archive into a staging directory (hardened
     * POSIX/GNU/PAX tar parser — see [extractTar]), write the distro/arch
     * markers and the apt/DNS/hosts/profile defaults, then swap it in. The
     * previous tree is renamed to an `alpine-rootfs.pre-ubuntu-*` sibling, so
     * migrating from Alpine preserves every byte of user data; nothing is
     * deleted. Progress is published to [installState] (Preparing →
     * Extracting(f) → Finalizing → Installed / Failed).
     */
    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            Log.d(TAG, "Rootfs already installed at $rootfsDir")
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }

        val staging = File(context.filesDir, SandboxDistribution.STAGING_DIR_NAME)
        try {
            _installState.value = RootfsInstallState.Preparing
            Log.i(TAG, "Installing ${SandboxDistribution.DISPLAY_NAME} rootfs...")

            // Staging only. The live rootfsDir is not touched until the extract
            // has fully succeeded, so a crash leaves the old guest usable.
            GuestTreeFiles.deleteTree(staging)
            if (!staging.mkdirs() && !staging.isDirectory) {
                error("Cannot create staging directory at $staging")
            }

            // Extract rootfs from assets.
            // AAPT may decompress .tar.gz → .tar automatically, so try both names.
            val assetName = try {
                context.assets.open(ROOTFS_ASSET).close()
                ROOTFS_ASSET
            } catch (_: java.io.FileNotFoundException) {
                try {
                    context.assets.open(ROOTFS_ASSET_TAR).close()
                    ROOTFS_ASSET_TAR
                } catch (_: java.io.FileNotFoundException) {
                    throw IllegalStateException(
                        "Rootfs asset ${SandboxDistribution.ROOTFS_ASSET} is missing from the APK. " +
                            "Run scripts/prepare_android_ubuntu.sh before building."
                    )
                }
            }

            // Asset size for progress calculation — compressed length (for .gz)
            // or uncompressed length (for .tar). openFd() fails for 0-length
            // assets on some devices; fall back to 0 which disables progress.
            val assetTotal: Long = try {
                context.assets.openFd(assetName).use { it.length }
            } catch (_: Exception) { 0L }

            // Emit an initial 0% so the UI flips from Preparing → progress bar.
            _installState.value = RootfsInstallState.Extracting(0f)

            context.assets.open(assetName).use { rawAsset ->
                // Wrap the ASSET stream (not the gzip stream) so progress tracks
                // compressed bytes consumed — monotonic and matches the size we
                // have a total for. Throttle updates to avoid flooding the StateFlow.
                val progressStream = ProgressInputStream(rawAsset, assetTotal) { fraction ->
                    _installState.value = RootfsInstallState.Extracting(fraction)
                }
                if (assetName.endsWith(".gz")) {
                    GZIPInputStream(progressStream).use { gzipStream ->
                        extractTar(gzipStream, staging)
                    }
                } else {
                    extractTar(progressStream, staging)
                }
            }

            _installState.value = RootfsInstallState.Finalizing

            // Sanity check before the swap: a truncated/foreign archive must not
            // be promoted to a "successful" install.
            val shell = File(staging, "bin/sh")
            val loader = File(staging, "lib/ld-linux-aarch64.so.1")
            if (!shell.exists() || !loader.exists()) {
                throw IOException(
                    "Extracted rootfs is incomplete: missing /bin/sh or the glibc loader " +
                        "(${loader.name}). Refusing to install."
                )
            }

            UbuntuToolsetStatus.problem(staging)?.let { problem ->
                throw IOException("Bundled Ubuntu toolset is incomplete: $problem")
            }

            // Markers are written only after the complete preinstalled image
            // has been extracted and validated, never after a network install.
            File(staging, SandboxDistribution.ARCH_MARKER_FILE).writeText(ARCH)
            File(staging, SandboxDistribution.MARKER_FILE).writeText(SandboxDistribution.MARKER_VALUE)

            // Pre-create /var/minis directories. Mirrors iOS
            // RootfsManager.swift:76-80 (attachments/offloads/workspace/skills/
            // shared) plus Android-specific `memory` kept from prior parity work.
            // T219-6: also pre-create `mounts/` so PRoot's `-b host:/var/minis/mounts/<name>`
            // has the parent directory to bind into; without this, PRoot silently
            // skips bind mounts whose target path doesn't exist.
            val minisSubdirs = listOf(
                "attachments", "offloads", "workspace", "skills", "memory", "shared", "mounts",
                // [FEAT-261025-CHROOT] mcp-servers is a bind target like the rest;
                // the chroot helper can create it on demand, but pre-creating it
                // keeps the first chroot mount from reporting a missing target.
                "mcp-servers",
            )
            for (subdir in minisSubdirs) {
                File(staging, "var/minis/$subdir").mkdirs()
            }

            // Pre-create /opt/bin — appears in PATH so users can drop third-party
            // binaries here without first `mkdir -p`. Matches iOS PATH layout.
            File(staging, "opt/bin").mkdirs()

            // Ubuntu guest defaults: apt (deb822) sources, PRoot apt switches,
            // /etc/hosts (empty in ubuntu-base), hostname, profile.d, bootstrap.
            configureUbuntuRootfs(staging)

            // Swap in. Legacy data is preserved, never deleted.
            val preserved = preserveExistingRootfs()
            if (!staging.renameTo(rootfsDir)) {
                // Put the old guest back so the device is not left with nothing.
                if (preserved != null) preserved.renameTo(rootfsDir)
                throw IOException("Could not move the new rootfs into place at $rootfsDir")
            }
            lastLegacyBackup = preserved
            if (preserved != null) {
                Log.w(
                    TAG,
                    "Previous rootfs preserved at ${preserved.absolutePath} " +
                        "(not deleted; remove it manually after verifying Ubuntu works)"
                )
            }

            // resolv.conf now that the tree is live (needs the active network).
            refreshDns()

            // Flag a fresh install so MirrorSpeedTestViewModel.autoDetectOnceIfNeeded()
            // picks the fastest mirror for each category on first boot.
            context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("rootfs.freshInstall", true)
                .apply()

            Log.i(TAG, "Rootfs installation complete (${SandboxDistribution.MARKER_VALUE})")
            _installState.value = RootfsInstallState.Installed
        } catch (t: Throwable) {
            // Only the staging tree is discarded. rootfsDir (old Alpine or a
            // previous Ubuntu install) is intentionally left untouched.
            runCatching { GuestTreeFiles.deleteTree(staging) }
            Log.e(TAG, "Rootfs installation failed", t)
            _installState.value = RootfsInstallState.Failed(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    /** Directory containing extracted native libraries (read-only, executable). */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    // ── Ubuntu guest configuration ───────────────────────────────────────────

    /**
     * Write the Ubuntu-specific defaults into [target] (staging or live).
     *
     * `writeIfMissing` for everything the ubuntu-base tarball already ships in
     * a usable form: a user who edited `/etc/hosts` or pinned their own apt
     * suite keeps that edit across re-installs and reboots. The bootstrap
     * script is always (re)written — it is app-managed code, not user config.
     */
    private fun configureUbuntuRootfs(target: File) {
        // deb822 sources (Ubuntu 24.04 moved off /etc/apt/sources.list).
        writeIfMissing(
            File(target, SandboxDistribution.APT_SOURCES_PATH),
            SandboxDistribution.aptSources(SandboxDistribution.OFFICIAL_UBUNTU_PORTS),
        )
        // PRoot switches — written unconditionally: a wrong value breaks apt
        // entirely and there is no user-facing way to set it in the app.
        writeText(File(target, SandboxDistribution.APT_PROOT_CONF_PATH), SandboxDistribution.aptProotConf())

        writeIfMissing(File(target, "etc/hosts"), SandboxDistribution.hostsFile())
        writeIfMissing(File(target, "etc/hostname"), SandboxDistribution.hostnameFile())
        writeIfMissing(File(target, SandboxDistribution.PROFILE_PATH), SandboxDistribution.profileScript())
        writeIfMissing(
            File(target, SandboxDistribution.BOOTSTRAP_PACKAGES_PATH),
            SandboxDistribution.bootstrapPackageList(),
        )

        val bootstrap = File(target, SandboxDistribution.BOOTSTRAP_SCRIPT_PATH)
        writeText(bootstrap, SandboxDistribution.bootstrapScript())
        bootstrap.setExecutable(true, false)
        // Interactive shells run it once and print progress/failure; agent
        // shells are not guaranteed to be interactive (see bootstrapCommand()).
        writeText(
            File(target, SandboxDistribution.BOOTSTRAP_PROFILE_PATH),
            SandboxDistribution.bootstrapProfileScript(),
        )

        // apt needs both partial dirs; dpkg creates them lazily but a read-only
        // parent would surface as a confusing "Could not open lock file".
        File(target, "var/lib/apt/lists/partial").mkdirs()
        File(target, "var/cache/apt/archives/partial").mkdirs()

        // /etc/resolv.conf must be a REGULAR file in this guest: resolvconf and
        // systemd-resolved are not running under PRoot, and some Ubuntu base
        // builds ship it as a symlink into /run (writing through it fails with
        // ENOENT on a fresh extract). refreshDns() replaces it with the real
        // system DNS right after the swap; this is only the offline fallback.
        writeResolvConfTo(
            target,
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )
    }

    /**
     * Idempotent top-up of app-managed guest files, run on every boot after
     * the default_mount overlay. Only fills files that are missing, so it can
     * never revert a user's mirror selection or apt sources. No-op when the
     * installed tree is not Ubuntu (e.g. an untouched legacy Alpine rootfs).
     */
    fun ensureUbuntuGuestConfig() {
        if (!isInstalled) return
        try {
            configureUbuntuRootfs(rootfsDir)
        } catch (t: Throwable) {
            Log.w(TAG, "[UbuntuConfig] failed: ${t.message}", t)
        }
    }

    /** All default packages ship preinstalled; a marker alone never proves readiness. */
    val isBootstrapComplete: Boolean
        get() = UbuntuToolsetStatus.isReady(rootfsDir)

    /** Historical failures do not override a complete, verified preinstalled image. */
    val isBootstrapFailed: Boolean
        get() = !isBootstrapComplete && File(rootfsDir, SandboxDistribution.BOOTSTRAP_FAILED_MARKER).isFile

    /**
     * Command a shell executor should run once when [isBootstrapComplete] is
     * false. Kept here (rather than executed during install) because apt needs
     * the network and the guest, and a failure must surface in the shell, not
     * abort the rootfs install. See docs/android-ubuntu.md §7/§9.
     */
    fun bootstrapCommand(): String = "minis-bootstrap"

    /**
     * Move the current [rootfsDir] aside without deleting anything.
     *
     * Returns the backup directory, or null when there was nothing to preserve
     * (absent or an empty directory). Throws when the rename fails: the caller
     * must abort the install rather than fall back to `deleteRecursively` —
     * silently destroying the user's previous guest is exactly what this
     * migration must not do.
     */
    internal fun preserveExistingRootfs(): File? {
        if (!rootfsDir.exists()) return null
        if (rootfsDir.isDirectory && rootfsDir.list()?.isEmpty() == true) {
            // Nothing but an empty dir (a failed mkdir from an old build).
            if (rootfsDir.delete()) return null
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        var backup = File(context.filesDir, SandboxDistribution.LEGACY_BACKUP_PREFIX + stamp)
        var suffix = 1
        while (backup.exists()) {
            backup = File(context.filesDir, SandboxDistribution.LEGACY_BACKUP_PREFIX + "$stamp-$suffix")
            suffix++
        }
        val legacy = SandboxDistribution.isLegacyNonUbuntuRootfs(rootfsDir)
        if (!rootfsDir.renameTo(backup)) {
            throw IOException(
                "Could not preserve the existing rootfs at ${rootfsDir.absolutePath}. " +
                    "Nothing was deleted; free up space and retry."
            )
        }
        Log.w(
            TAG,
            "[Migration] ${if (legacy) "legacy Alpine" else "previous"} rootfs moved to " +
                "${backup.name} (preserved, not deleted)"
        )
        context.getSharedPreferences(PREFS_MIRROR_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LEGACY_BACKUP, backup.absolutePath)
            .apply()
        return backup
    }

    private fun writeIfMissing(file: File, content: String) {
        if (file.isFile && file.length() > 0L) return
        writeText(file, content)
    }

    private fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        // Never write *through* a symlink: a rootfs that ships
        // etc/apt/sources.list.d → /somewhere would otherwise leak the write
        // outside the tree. Replace the link with a regular file instead.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
        file.writeText(content)
    }


    /**
     * Verify PRoot binary is available in the native library directory.
     */
    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) {
        if (!prootBinary.exists() || !prootBinary.canExecute()) {
            throw IllegalStateException(
                "PRoot binary not found at $prootBinary. " +
                "It should be auto-extracted from jniLibs."
            )
        }

        // No libtalloc staging: deps/build_proot.sh links talloc statically
        // (the binary carries no DT_NEEDED for libtalloc.so), so there is no
        // shared object to version-rename. Older builds shipped libtalloc.so
        // in jniLibs and copied it here as libtalloc.so.2.

        Log.d(TAG, "PRoot binary available at $prootBinary")
    }

    /**
     * Reset rootfs. Optionally keeps user data (/root).
     * Returns the backup directory if keepUserData is true, null otherwise.
     */
    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        var backupDir: File? = null

        if (keepUserData) {
            val rootHome = File(rootfsDir, "root")
            if (rootHome.exists()) {
                backupDir = File(context.cacheDir, "rootfs-backup-root")
                GuestTreeFiles.deleteTree(backupDir)
                rootHome.copyRecursively(backupDir, overwrite = true)
            }
        }

        GuestTreeFiles.deleteTree(rootfsDir)
        installIfNeeded()

        // Restore user data
        if (backupDir != null && backupDir.exists()) {
            val rootHome = File(rootfsDir, "root")
            backupDir.copyRecursively(rootHome, overwrite = true)
            GuestTreeFiles.deleteTree(backupDir)
        }

        backupDir
    }

    /**
     * Calculate the total size of the rootfs directory in bytes.
     */
    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) return@withContext 0L
        calculateDirSize(rootfsDir)
    }

    /**
     * Restore user data from a backup directory into /root.
     */
    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        if (!backupDir.exists()) return@withContext
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()
        backupDir.copyRecursively(rootHome, overwrite = true)
        GuestTreeFiles.deleteTree(backupDir)
        Log.i(TAG, "User data restored from $backupDir")
    }

    /**
     * [T-android-storage-symlink-inflation] Third copy of the sizing walk —
     * routed through the shared one instead of keeping a following variant
     * alive here.
     *
     * This was hand-rolled recursion over `listFiles()`, and both `isDirectory`
     * and `length()` RESOLVE symlinks: a link to a directory was descended into
     * and its whole subtree counted again (once per link pointing at it), and a
     * link to a file was counted as a second copy of the target. That is the
     * same defect 754e65eac fixed in `SessionStorage.directorySize` and
     * `DebugRPCHandler.dirSize`, and missing it here is why the "Rootfs 管理"
     * screen still read 40.25 GB after the Storage screen had been corrected —
     * two screens sizing the same directory and disagreeing by an order of
     * magnitude.
     *
     * It was also the only one of the three with no cycle protection: a
     * `a/loop -> a` symlink recursed until StackOverflowError rather than
     * merely over-counting.
     */
    private fun calculateDirSize(dir: File): Long =
        com.openminis.app.data.session.SessionStorage.directorySize(dir)

    /**
     * Ensure session-specific directories exist on the host filesystem.
     */
    fun ensureSessionDirs(sessionId: String) {
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        val subdirs = listOf("attachments", "offloads", "workspace", "browser")
        for (subdir in subdirs) {
            File(sessionBase, subdir).mkdirs()
        }
    }

    /**
     * Read system DNS servers and search domains from ConnectivityManager,
     * then write resolv.conf into the guest rootfs.
     * Mirrors iOS ISHKernel.configureDns / refreshDns.
     * Falls back to 8.8.8.8 / 8.8.4.4 if no system DNS available.
     *
     * Ubuntu note: glibc (this guest) reads resolv.conf exactly like musl did,
     * but it is NOT a symlink here — [writeResolvConfTo] replaces any
     * systemd-resolved/resolvconf link with a regular file, because neither
     * daemon runs inside PRoot and writing through the link would fail.
     */
    fun refreshDns() {
        if (!rootfsDir.exists()) return

        // [T-container-network-dns] (OpenMinis#396) Custom mode: the user's
        // servers, verbatim, on every call — install, boot and each network
        // change all land here, so a network switch can no longer wash them
        // out. Custom with no usable server resolves to System (see
        // ContainerDns.plan), so this can never write a resolv.conf with no
        // nameserver. Auto (the default) is the code below, unchanged.
        val plan = ContainerDns.plan(context)
        if (plan is ContainerDns.Plan.Custom) {
            Log.i(TAG, "[DNS] custom mode: ${plan.servers.joinToString(", ")}")
            writeResolvConf(ContainerDns.customResolvConf(plan.servers))
            return
        }

        val resolvConf = StringBuilder()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null

            if (linkProps != null) {
                // Search domains
                val domains = linkProps.domains
                if (!domains.isNullOrBlank()) {
                    resolvConf.append("search $domains\n")
                    Log.i(TAG, "[DNS] search domains: $domains")
                }

                // Nameservers
                val dnsServers = linkProps.dnsServers
                if (dnsServers.isNotEmpty()) {
                    for (server in dnsServers) {
                        val addr = server.hostAddress ?: continue
                        resolvConf.append("nameserver $addr\n")
                        Log.i(TAG, "[DNS] system server: $addr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DNS] Failed to read system DNS: ${e.message}")
        }

        // Fallback to public DNS if no system servers were found
        if (!resolvConf.contains("nameserver")) {
            Log.i(TAG, "[DNS] no system DNS — using fallback: 8.8.8.8, 8.8.4.4")
            resolvConf.append("nameserver 8.8.8.8\n")
            resolvConf.append("nameserver 8.8.4.4\n")
        }

        writeResolvConf(resolvConf.toString())
    }

    private fun writeResolvConf(content: String) = writeResolvConfTo(rootfsDir, content)

    /**
     * Symlink-safe resolv.conf writer. Ubuntu base images differ on this one
     * file: 24.04.5 ships an empty regular file, older/media builds symlink it
     * to `../run/systemd/resolve/stub-resolv.conf` — and `/run` is recreated
     * empty on every PRoot boot, so writing through that link either fails or
     * lands somewhere the resolver never reads. Delete the link, write a file.
     */
    private fun writeResolvConfTo(target: File, content: String) {
        try {
            val file = File(target, "etc/resolv.conf")
            file.parentFile?.mkdirs()
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                Log.i(TAG, "[DNS] replacing resolv.conf symlink with a regular file")
                file.delete()
            }
            if (file.exists() && !file.isFile) file.delete()
            file.writeText(content)
            Log.i(TAG, "[DNS] resolv.conf updated (${target.name}):\n$content")
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to write resolv.conf: ${e.message}")
        }
    }

    /**
     * Copy every file under `assets/default_mount/` into the rootfs, preserving
     * the directory layout. Mirrors iOS RootfsManager.applyDefaultMountOverlay
     * (src/ios/iSH/RootfsManager.swift:153-225) — runs on every boot so shipping
     * updated profile scripts / URL wrappers with an app release just works.
     *
     * Files under `bin` / `sbin` paths get the execute bit set. Existing files
     * are overwritten so users see the latest shipped version even if they
     * previously edited the file — matching iOS behavior.
     *
     * No-op when the asset dir is missing or the rootfs hasn't been extracted.
     */
    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) {
            Log.d(TAG, "[DefaultMount] rootfs missing, skipping overlay")
            return@withContext
        }

        // Ubuntu defaults (apt sources, /etc/hosts, profile.d, bootstrap script)
        // first, so they exist even if the overlay asset is absent. Only missing
        // files are created — user edits and mirror selections survive.
        ensureUbuntuGuestConfig()

        // [T-git-createobject-rename] #409: before any shell can run git,
        // repair the invalid core.createObject an older minis.sh wrote.
        migrateSystemGitConfig()

        // AssetManager.list() returns an empty array for both "leaf file" and
        // "missing path", so probe for children before recursing — otherwise
        // a missing overlay dir would be mistaken for a single leaf file.
        val rootEntries = try {
            context.assets.list(DEFAULT_MOUNT_ASSET)
        } catch (t: Throwable) {
            null
        }
        if (rootEntries.isNullOrEmpty()) {
            Log.d(TAG, "[DefaultMount] no default_mount assets found, skipping overlay")
            return@withContext
        }

        val startNs = System.nanoTime()
        var fileCount = 0
        try {
            fileCount = copyAssetDir(DEFAULT_MOUNT_ASSET, rootfsDir)
        } catch (t: Throwable) {
            Log.w(TAG, "[DefaultMount] overlay failed: ${t.message}", t)
            return@withContext
        }

        // Mirror iOS removeExternallyManagedMarker() — drop PEP 668 marker so
        // `pip install` Just Works inside this embedded Alpine rootfs even
        // when the shipped pip.conf isn't being read (e.g. pip invoked with
        // --isolated or via a venv). Safe: this is a single-tenant sandbox.
        val markerRemoved = removeExternallyManagedMarker()

        // [T-mcp-cli-readonly-android] Make the shipped minis-mcp-cli Python lib
        // read-only inside the guest so a user can't `vi`-tamper the bundled
        // scripts (mirrors iOS #707). Scoped to /usr/local/lib/minis-mcp-cli/
        // ONLY — the wrapper at /usr/local/bin/minis-mcp-cli stays executable +
        // writable (app-managed). Re-applied on every boot AFTER the copy; the
        // copyAssetDir leaf-copy above re-opens read-only files writable first,
        // so the next app-upgrade overlay still overwrites cleanly.
        val lockedCount = lockMcpCliLibReadOnly()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        Log.i(TAG, "[DefaultMount] Done. $fileCount file(s) overlaid, $markerRemoved EXTERNALLY-MANAGED marker(s) removed, $lockedCount minis-mcp-cli lib path(s) locked read-only in %.1fms".format(elapsedMs))
    }

    /**
     * [T-mcp-cli-readonly-android] Set the `/usr/local/lib/minis-mcp-cli/`
     * subtree read-only for the guest: directories 0555 (read+execute, no
     * write), files 0444 (read-only). Java's File API has no octal chmod, so
     * we use setWritable(false, false) + setReadable(true, false)
     * (+ setExecutable(true, false) on dirs) — the `false` ownerOnly arg makes
     * the permission apply to all users, matching the setReadable style used
     * for libtalloc above. Returns the number of paths adjusted; 0 if the lib
     * dir is absent. Idempotent — the pre-copy unlock in copyAssetDir keeps the
     * upgrade path working across boots.
     */
    private fun lockMcpCliLibReadOnly(): Int {
        val libDir = File(rootfsDir, "usr/local/lib/minis-mcp-cli")
        if (!libDir.isDirectory) return 0
        var count = 0
        // walkBottomUp so child files are locked before their parent dir loses
        // write — dir mode doesn't gate chmod of already-visited children, but
        // bottom-up keeps the intent clear and avoids any traversal surprise.
        for (f in libDir.walkBottomUp()) {
            f.setWritable(false, false)
            f.setReadable(true, false)
            if (f.isDirectory) f.setExecutable(true, false)
            count++
        }
        return count
    }

    /**
     * Remove the PEP 668 EXTERNALLY-MANAGED marker file from every
     * `usr/lib/python3*` directory in the rootfs. Mirrors iOS
     * RootfsManager.removeExternallyManagedMarker (src/ios/iSH/RootfsManager.swift:228-239).
     * Returns the number of markers removed.
     */
    /**
     * [T-git-createobject-rename] openminis/openminis#409: older builds ran
     * `git config --system core.createObject copy` from minis.sh. `copy` is
     * not a valid value, so every git command in the sandbox died with
     * "invalid mode for object creation". Removes that line from the guest's
     * /etc/gitconfig on the host side (no shell needed). Everything else in
     * the file is kept byte for byte; running it again changes nothing.
     * Returns the number of lines removed.
     */
    private fun migrateSystemGitConfig(): Int = migrateGitConfigFile(File(rootfsDir, "etc/gitconfig"))

    private fun removeExternallyManagedMarker(): Int {
        val usrLib = File(rootfsDir, "usr/lib")
        val children = usrLib.listFiles() ?: return 0
        var removed = 0
        for (entry in children) {
            if (!entry.name.startsWith("python3")) continue
            val marker = File(entry, "EXTERNALLY-MANAGED")
            if (marker.exists() && marker.delete()) {
                Log.i(TAG, "[DefaultMount] Removed EXTERNALLY-MANAGED from ${entry.name}")
                removed++
            }
        }
        return removed
    }

    /**
     * Recursively copy an assets path into [targetBase]. Returns the number of
     * regular files written. Files under /bin/, /sbin/, /usr/bin/, /usr/sbin/,
     * /usr/local/bin/, /usr/local/sbin/ get the execute bit set.
     */
    private fun copyAssetDir(assetPath: String, targetBase: File, prefix: String = ""): Int {
        val entries = context.assets.list(assetPath) ?: return 0
        if (entries.isEmpty()) {
            // Leaf: asset is a file. Copy it.
            val dest = File(targetBase, prefix)
            dest.parentFile?.mkdirs()
            // [T-mcp-cli-readonly-android] A prior boot may have set this file
            // (and its dir) read-only — the minis-mcp-cli lib subtree, locked
            // below. Re-open both writable before overwriting, otherwise an app
            // upgrade can't replace the shipped file: truncating an existing
            // file needs write on the FILE, and creating a new one needs write
            // on the PARENT DIR. No-op when already writable / not yet present.
            dest.parentFile?.setWritable(true, true)
            if (dest.exists()) dest.setWritable(true, true)
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (isUnderBinDir(prefix)) {
                dest.setExecutable(true, false)
            }
            return 1
        }
        var count = 0
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
            count += copyAssetDir(childAsset, targetBase, childPrefix)
        }
        return count
    }

    private fun isUnderBinDir(relativePath: String): Boolean {
        val normalized = "/$relativePath"
        return BIN_DIR_PREFIXES.any { normalized.startsWith(it) }
    }

    // --- POSIX/GNU/PAX tar extraction ---

    /**
     * Extract a tar stream into [targetDir]. Delegates to [TarExtractor], which
     * is pure Kotlin (no Android imports) so the JVM tests can run the real
     * hardening logic against synthetic archives and the shipped Ubuntu base
     * image. See [TarExtractor] for the safety properties: path sanitising,
     * no writes through symlinked parents, header checksums, GNU/PAX long
     * names, hard-link handling.
     */
    internal fun extractTar(input: InputStream, targetDir: File) =
        TarExtractor.extract(input, targetDir) { message -> Log.w(TAG, message) }

    internal fun extractString(header: ByteArray, offset: Int, length: Int): String =
        TarExtractor.extractString(header, offset, length)

    internal fun readFully(input: InputStream, buf: ByteArray): Int =
        TarExtractor.readFully(input, buf)

    internal fun skipFully(input: InputStream, count: Long) =
        TarExtractor.skipFully(input, count)

    /**
     * InputStream wrapper that reports cumulative bytes read as a 0..1 fraction.
     * Throttles updates so each +1% bump emits at most once. Designed to wrap
     * the asset stream (not the gzip stream) so progress is monotonic against
     * a size we can cheaply know up front.
     */
    private class ProgressInputStream(
        inner: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterInputStream(inner) {
        private var read: Long = 0
        private var lastReportedPercent: Int = -1

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { read += 1; publish() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { read += n; publish() }
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) { read += skipped; publish() }
            return skipped
        }

        private fun publish() {
            if (total <= 0) return
            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastReportedPercent) {
                lastReportedPercent = pct
                onProgress(pct / 100f)
            }
        }
    }

    companion object {
        private const val TAG = "RootfsManager"

        /** [T-git-createobject-rename] Apply [migrateGitConfigText] to [file] in place; 0 when it is absent. */
        internal fun migrateGitConfigFile(file: File): Int {
            if (!file.isFile) return 0
            return try {
                val (migrated, removed) = migrateGitConfigText(file.readText())
                if (removed > 0) {
                    val tmp = File(file.parentFile, "${file.name}.minis-tmp")
                    tmp.writeText(migrated)
                    if (!tmp.renameTo(file)) {
                        file.writeText(migrated)
                        tmp.delete()
                    }
                    Log.i(TAG, "[GitConfigMigration] removed $removed invalid core.createObject line(s) from /etc/gitconfig")
                }
                removed
            } catch (t: Throwable) {
                Log.w(TAG, "[GitConfigMigration] failed: ${t.message}")
                0
            }
        }

        /**
         * [T-git-createobject-rename] The /etc/gitconfig text with every
         * invalid `createObject` entry of a `[core]` section removed, and how
         * many lines went.
         *
         * - The section name and the key are matched case-insensitively, as
         *   Git does.
         * - The value is valid only if it is exactly `link` or `rename`: Git
         *   compares the value case-sensitively (anything else, a missing
         *   value included, is fatal).
         * - Quotes and trailing `#` / `;` comments are ignored when reading
         *   the value.
         * - Other sections, other keys, comments, blank lines and the line
         *   endings are kept as they are. An emptied `[core]` header is kept
         *   too (harmless).
         */
        internal fun migrateGitConfigText(text: String): Pair<String, Int> {
            val lines = text.split("\n")
            val out = ArrayList<String>(lines.size)
            var inCore = false
            var removed = 0
            val header = Regex("""^\s*\[\s*([A-Za-z0-9.-]+)(\s+"[^"]*")?\s*]\s*([#;].*)?$""")
            val entry = Regex("""^\s*createobject\s*(=(.*))?$""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val bare = line.removeSuffix("\r")
                val h = header.find(bare)
                if (h != null) {
                    inCore = h.groupValues[1].equals("core", ignoreCase = true) && h.groupValues[2].isEmpty()
                    out += line
                    continue
                }
                if (inCore) {
                    val e = entry.find(bare)
                    if (e != null && gitConfigValue(e.groupValues[2], e.groupValues[1].isNotEmpty()) !in VALID_CREATE_OBJECT) {
                        removed++
                        continue
                    }
                }
                out += line
            }
            return (if (removed == 0) text else out.joinToString("\n")) to removed
        }

        private val VALID_CREATE_OBJECT = setOf("link", "rename")

        /** A git-config value without quotes or a trailing comment; null when the key has no `=`. */
        private fun gitConfigValue(raw: String, hasEquals: Boolean): String? {
            if (!hasEquals) return null
            val sb = StringBuilder()
            var quoted = false
            for (c in raw) {
                when {
                    c == '"' -> quoted = !quoted
                    !quoted && (c == '#' || c == ';') -> break
                    else -> sb.append(c)
                }
            }
            return sb.toString().trim()
        }
        private const val ARCH = SandboxDistribution.ARCH
        private const val ROOTFS_ASSET = SandboxDistribution.ROOTFS_ASSET
        private const val ROOTFS_ASSET_TAR = SandboxDistribution.ROOTFS_ASSET_TAR
        private const val PROOT_ASSET = "proot-aarch64"
        private const val DEFAULT_MOUNT_ASSET = "default_mount"

        /** Same prefs file MirrorSettingsScreen uses for "auto-detect once". */
        private const val PREFS_MIRROR_SETTINGS = "mirror_settings"

        /** Last preserved pre-Ubuntu rootfs, for a future UI surface. */
        const val KEY_LEGACY_BACKUP = "rootfs.legacyBackupPath"


        /**
         * Rootfs paths whose contents must be executable. Matches iOS
         * RootfsManager.swift:199 byte-for-byte so the same overlay tree
         * produces identical file modes on both platforms.
         */
        private val BIN_DIR_PREFIXES = listOf(
            "/bin/",
            "/sbin/",
            "/usr/bin/",
            "/usr/sbin/",
            "/usr/local/bin/",
            "/usr/local/sbin/",
        )

        @Volatile
        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager {
            return instance ?: synchronized(this) {
                instance ?: RootfsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
