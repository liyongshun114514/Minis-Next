package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FEAT-261025-CHROOT] The chroot backend: command lines, mount policy,
 * lifecycle and the honest availability report.
 *
 * ## Shape of the design
 *
 * `chroot(2)` needs CAP_SYS_CHROOT, which an app never has. So the privileged
 * part is ONE long-lived helper (`libminis-chroot.so`, C source in
 * `src/main/cpp/minis_chroot.c`), started through the Minis root bridge with
 * the user's confirmation. After it prints a handshake it is already bound to
 * an abstract unix socket; every later command goes over that socket as an
 * ordinary unprivileged client (`--client`), which is this app's own uid.
 *
 * That split is what keeps the privileged argv CONSTANT: the user's command
 * never becomes part of an `su`/root command line. Compare the shape this
 * deliberately avoids — `su -c "<user string>"` — where one quoting bug turns
 * a guest command into a host root command. Here the bridge runs
 * [serverArgv], which is built only from paths the app owns plus a random
 * generation nonce, and the command travels in a length-prefixed frame the
 * helper re-validates.
 *
 * ## What the helper does per command
 *
 * For each request it forks, then in the child: `unshare(CLONE_NEWNS)`, make
 * `/` private, bind the minimal device nodes, mount a fresh procfs, bind the
 * validated `/var/minis/...` mounts (read-only ones remounted `MS_RDONLY`),
 * `chroot(rootfs)`, `chdir(cwd)`, drop to the app uid/gid, `setsid()`, and
 * `execve`. The host keeps streaming stdio through the client, so exit codes,
 * `Ctrl-C`, PTYs and `Stop` behave exactly as they do under PRoot.
 *
 * ## What it deliberately does NOT do
 *
 *  - no `setenforce`, no SELinux policy change, no `remount` of any real
 *    filesystem: every mount lives in the request's private namespace and
 *    disappears with the process;
 *  - no mount of `/`, `/system`, `/data` or the app's own data dir wholesale:
 *    the bind list is an allowlist ([validateMounts]);
 *  - no root process left behind after `shutdown()` (idle TTL + explicit stop);
 *  - no silent deletion of anything under the rootfs when switching back.
 */
object ChrootKernel {
    private const val TAG = "ChrootKernel"

    /** Packaged helper; `--client` and `--serve` live in the same binary. */
    const val LAUNCHER_NAME = "libminis-chroot.so"

    /** Guest-side native_offload client (static, installed into the rootfs). */
    const val OFFLOAD_CLIENT_NAME = "minis-offload-client"

    /** Directory inside the rootfs owned by this backend. */
    private const val STATE_DIR = ".minis-chroot"

    /** Client exit code when the privileged helper is not listening. */
    const val EXIT_NO_HELPER = 97

    /** Exit code the client uses when the helper rejected the request. */
    const val EXIT_REJECTED = 98

    /** How long the unprivileged stop client may take. */
    private const val STOP_TIMEOUT_MS = 5_000L

    /** How long the unprivileged liveness probe (`/bin/sh -c true`) may take. */
    private const val PROBE_TIMEOUT_MS = 5_000L

    /** How long [serverArgv]'s helper may idle before exiting on its own. */
    const val DEFAULT_IDLE_TTL_SECONDS = 900

    fun launcher(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, LAUNCHER_NAME)

    fun rootfs(context: Context): File = RootfsManager.getInstance(context).rootfsDir

    private fun stateDir(context: Context): File = File(rootfs(context), STATE_DIR)

    fun offloadClientPath(context: Context): File =
        File(rootfs(context), "usr/local/lib/minis/$OFFLOAD_CLIENT_NAME")

    /**
     * [FEAT-261025-CHROOT] Availability, from probes only — no root, no exec.
     *
     * File probes plus a `Class.forName` on the root bridge. Anything that
     * would need privilege to answer is reported as "not running" instead of
     * being guessed at; [serverRunning] therefore means "a grant exists for
     * this process AND the last handshake succeeded".
     */
    fun availability(context: Context): ChrootAvailability {
        val generation = SandboxBackendStore.generation(context)
        return ChrootAvailability(
            launcherInstalled = launcher(context).let { it.isFile && it.canExecute() },
            offloadClientInstalled = offloadClientPath(context).let { it.isFile && it.canExecute() },
            bridgePresent = ChrootPrivilege.bridgePresent(),
            rootManagerDetected = ChrootPrivilege.rootManagerDetected(),
            serverRunning = SandboxBackendStore.chrootGranted(generation) &&
                !SandboxBackendStore.chrootGrantMayBeExpired(generation),
            lastError = SandboxBackendStore.lastError(context),
        )
    }

    // ── Pure builders (unit-tested without a device) ───────────────────────

    private val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** [FEAT-261025-CHROOT] A fresh 128-bit socket nonce, lowercase alnum. */
    fun newGeneration(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder("g")
        repeat(24) { sb.append(NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)]) }
        return sb.toString()
    }

    /** Abstract socket name; leading `@` is how the C helper spells `\0`. */
    fun socketName(generation: String): String = "minis-chroot-$generation"

    /**
     * The privileged argv. Built ONLY from app-owned absolute paths, the app
     * uid and a random nonce — never from a command, a user path or an
     * environment value. The helper re-checks every one of them.
     */
    fun serverArgv(
        launcher: String,
        rootfs: String,
        generation: String,
        allowedHostRoots: List<String>,
        uid: Int,
        gid: Int,
        idleTtlSeconds: Int = DEFAULT_IDLE_TTL_SECONDS,
    ): List<String> {
        val argv = mutableListOf(
            launcher,
            "--serve",
            "--root", rootfs,
            "--socket", socketName(generation),
            "--uid", uid.toString(),
            "--gid", gid.toString(),
            "--idle-ttl", idleTtlSeconds.coerceIn(30, 86_400).toString(),
            "--state", "$rootfs/$STATE_DIR",
        )
        for (root in allowedHostRoots) {
            if (File(root).isAbsolute && !root.contains(':')) {
                argv += "--allow-root"
                argv += root
            }
        }
        return argv
    }

    /**
     * The unprivileged client argv for one request.
     *
     * [guestArgv] is the command to run inside the chroot; it is passed after
     * `--` so the helper's option parser cannot mistake a command word for a
     * flag. Interactive sessions pass `interactive = true` and a single
     * `/bin/sh` plus [loginShell].
     */
    fun clientArgv(
        launcher: String,
        generation: String,
        rootfs: String,
        guestArgv: List<String>,
        interactive: Boolean = false,
        loginShell: Boolean = false,
        cwd: String = "/root",
        mounts: List<ChrootMount> = emptyList(),
    ): List<String> {
        val argv = mutableListOf(
            launcher,
            "--client",
            "--socket", socketName(generation),
            "--root", rootfs,
            "--cwd", cwd,
        )
        if (interactive) argv += "--tty"
        if (loginShell) argv += "--login"
        for (m in mounts) {
            argv += if (m.readOnly) "--ro-mount" else "--mount"
            argv += "${m.guest}=${m.host}"
        }
        argv += "--"
        argv += guestArgv
        return argv
    }

    /** [FEAT-261025-CHROOT] One validated bind, as sent to the helper. */
    data class ChrootMount(val guest: String, val host: String, val readOnly: Boolean)

    /**
     * [FEAT-261025-CHROOT] Build the mount list for one spawn.
     *
     * Read-only is inherited from the mounted-folder store (same policy the
     * PRoot backend applies via its write-guard wrappers); everything else is
     * read-write. The helper re-checks each pair against its `--allow-root`
     * list, so a bug here cannot widen the sandbox.
     */
    fun mountsFor(context: Context, table: Map<String, String>): Pair<List<ChrootMount>, List<String>> {
        val validation = validateMounts(table, allowedHostRoots(context))
        val mounts = validation.accepted.map { (guest, host) ->
            ChrootMount(guest, host, readOnly = PRootKernel.isLinuxPathUnderReadOnlyMount(guest))
        }
        return mounts to validation.rejected
    }

    /** The control argv that asks a running helper to exit (fixed, no user data). */
    fun stopArgv(launcher: String, generation: String, rootfs: String): List<String> =
        listOf(launcher, "--client", "--socket", socketName(generation), "--root", rootfs, "--control", "stop")

    /**
     * [FEAT-261025-CHROOT] Mount policy, pure and testable.
     *
     * Accepted guest targets are a fixed set of sandbox paths; accepted host
     * sources must live under one of [allowedHostRoots] after normalisation.
     * Rejecting here means the helper never even sees the pair — and the
     * helper repeats the check, because a policy that only exists in Kotlin is
     * one reflection call away from being bypassed.
     */
    data class MountValidation(
        val accepted: List<Pair<String, String>>,
        val rejected: List<String>,
    )

    /** Guest mount targets this backend is willing to create (prefix match). */
    val ALLOWED_GUEST_PREFIXES = listOf("/var/minis/", "/mnt/", "/media/", "/home/", "/root/")

    fun validateMounts(
        mounts: Map<String, String>,
        allowedHostRoots: List<String>,
    ): MountValidation {
        val accepted = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<String>()
        val roots = allowedHostRoots.mapNotNull { normaliseAbsolute(it) }
        for ((guest, host) in mounts) {
            val g = normaliseGuestPath(guest)
            val h = normaliseAbsolute(host)
            when {
                g == null -> rejected += "$guest (bad guest path)"
                h == null -> rejected += "$guest -> $host (bad host path)"
                g == "/" || g.removeSuffix("/").isEmpty() -> rejected += "$guest (refuses to shadow /)"
                ALLOWED_GUEST_PREFIXES.none { p -> g == p.removeSuffix("/") || g.startsWith(p) } ->
                    rejected += "$guest -> $host (guest target outside the sandbox allowlist)"
                roots.none { r -> h == r || h.startsWith("$r/") } ->
                    rejected += "$guest -> $host (host source outside the app's allowed roots)"
                else -> accepted += g to h
            }
        }
        return MountValidation(
            accepted = accepted.sortedBy { it.first },
            rejected = rejected,
        )
    }

    /** `/a/./b//c` -> `/a/b/c`; null on relative, `..`, or control characters. */
    fun normaliseAbsolute(path: String): String? {
        if (!path.startsWith("/")) return null
        if (path.any { it.code < 0x20 || it == '\n' || it == ':' }) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return "/" + parts.joinToString("/")
    }

    /** Same, but `/` is not a legal mount target inside the sandbox. */
    private fun normaliseGuestPath(path: String): String? = normaliseAbsolute(path)

    /** [FEAT-261025-CHROOT] True when [path] is [root] or below it. */
    fun isUnder(path: String, root: String): Boolean {
        val p = normaliseAbsolute(path) ?: return false
        val r = normaliseAbsolute(root) ?: return false
        return p == r || p.startsWith(if (r.endsWith("/")) r else "$r/")
    }

    /**
     * [FEAT-261025-CHROOT] Parse the helper's readiness line.
     *
     * The helper prints it from the process that BOUND the socket, before it
     * detaches, so a printed line means the socket existed at that moment. The
     * nonce must match: a stale helper from an earlier generation printing its
     * own line must not be accepted as this one.
     */
    fun parseHandshake(stdout: String, generation: String): Int? {
        // The C helper prints the SOCKET name (minis-chroot-<generation>), not
        // the bare nonce, so a line from a stale generation cannot match.
        val marker = "MINIS-CHROOT-READY ${socketName(generation)} "
        val line = stdout.lineSequence().firstOrNull { it.startsWith(marker) } ?: return null
        return line.removePrefix(marker).trim().toIntOrNull()?.takeIf { it > 1 }
    }

    /** [FEAT-261025-CHROOT] Host roots the guest may bind from. */
    fun allowedHostRoots(context: Context): List<String> {
        val roots = mutableListOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
        )
        // External storage only when the app can actually read it; the mount
        // store's own entries are resolved to these roots.
        runCatching {
            android.os.Environment.getExternalStorageDirectory()?.absolutePath
        }.getOrNull()?.let { roots += it }
        // User-mounted folders that resolve to removable volumes.
        PRootKernel.bindMounts.values.forEach { host ->
            var f: File? = File(host)
            // Walk up to a stable allow-root: /storage/<vol>/... -> /storage/<vol>.
            while (f != null && f.parentFile != null && f.absolutePath.count { it == '/' } > 3) {
                f = f.parentFile
            }
            f?.absolutePath?.let { roots += it }
        }
        return roots.distinct()
    }

    // ── Spawn ──────────────────────────────────────────────────────────────

    /**
     * Build the client command line and environment for one command.
     *
     * Called from [SandboxRouter] on every spawn. If no runtime grant exists
     * the argv is still returned — the client then fails with [EXIT_NO_HELPER]
     * and a message naming the reason. That is deliberate: returning a host
     * `/bin/sh` here would run the command OUTSIDE the sandbox, which is the
     * one failure mode worse than a loud error.
     */
    fun spawn(context: Context, spec: SandboxCommandSpec): SandboxSpawn {
        val generation = SandboxBackendStore.generation(context)
        val rootfs = rootfs(context).absolutePath
        val launcher = launcher(context).absolutePath
        val guestArgv = when {
            !spec.interactive -> listOf("/bin/sh", "-c", spec.command)
            spec.loginShell -> listOf("/bin/sh", "-l", "-i")
            else -> listOf("/bin/sh")
        }
        val (mounts, rejected) = mountsFor(context, spec.sessionMounts ?: PRootKernel.bindMounts)
        val argv = clientArgv(
            launcher = launcher,
            generation = generation,
            rootfs = rootfs,
            guestArgv = guestArgv,
            interactive = spec.interactive,
            loginShell = spec.loginShell && spec.interactive,
            mounts = mounts,
        )
        val offloadRoute = offloadRoute(context)
        val env = linkedMapOf<String, String>()
        // Carry the parts of the PRoot environment that describe the machine
        // the guest is on rather than proot's own workarounds. GIT_CONFIG_* and
        // UV_LINK_MODE stay behind on purpose: they exist because of
        // link2symlink, and hardlinks work under chroot.
        for (key in CHROOT_ENV_KEYS) {
            PRootKernel.customEnvironment[key]?.let { env[key] = it }
        }
        env.putAll(PRootKernel.systemProxyEnv(context))
        env["MINIS_CHROOT_SOCKET"] = socketName(generation)
        env["MINIS_CHROOT_GENERATION"] = generation
        env["MINIS_NOFF_SOCKET"] = NativeOffloadServer.socketName
        env["MINIS_SANDBOX_BACKEND"] = SandboxBackend.CHROOT.name
        spec.sessionId?.let { env["MINIS_CHAT_SESSION_ID"] = it }
        if (!spec.interactive) {
            env["TERM"] = "dumb"
            env["PS1"] = ""
        } else {
            env["TERM"] = "xterm-256color"
        }
        val notes = mutableListOf<String>()
        if (!SandboxBackendStore.chrootGranted(generation)) {
            notes += "no runtime root grant for generation=$generation; client will exit $EXIT_NO_HELPER"
        } else if (SandboxBackendStore.chrootGrantMayBeExpired(generation)) {
            // The command still gets the real client argv; if the helper's
            // idle TTL already fired it exits EXIT_NO_HELPER and the user is
            // told to re-enable chroot. Never fall back to running outside
            // the sandbox.
            notes += "runtime grant may be stale (helper idle TTL); a command may exit $EXIT_NO_HELPER " +
                "until chroot is started again"
        }
        notes += rejected.map { "mount rejected: $it" }
        if (offloadRoute == SandboxOffloadRoute.BLOCKED) {
            notes += "guest offload client missing; Android/Minix tools are blocked in this backend"
        }
        Log.i(TAG, "spawn generation=$generation interactive=${spec.interactive} " +
            "offload=$offloadRoute mounts=${mounts.size} rejectedMounts=${rejected.size} notes=$notes")
        return SandboxSpawn(
            backend = SandboxBackend.CHROOT,
            argv = argv,
            env = env,
            offload = offloadRoute,
            notes = notes,
        )
    }

    /** Only these PRootKernel.customEnvironment entries make sense under chroot. */
    private val CHROOT_ENV_KEYS = listOf(
        "PATH", "HOME", "BROWSER", "ENV", "CHARSET", "TZ",
        "NO_COLOR", "PYTHONDONTWRITEBYTECODE", "GOMAXPROCS",
        "http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "no_proxy", "NO_PROXY",
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * [FEAT-261025-CHROOT] Start (or adopt) the privileged helper.
     *
     * Requires the persisted risk acknowledgement AND the root bridge. The
     * bridge shows its own per-command confirmation; we pass [reason] so the
     * dialog says what is being authorised. On success the handshake's nonce
     * is checked before the in-memory grant is recorded.
     */
    suspend fun ensureServer(context: Context, reason: String = DEFAULT_GRANT_REASON): Result<Unit> {
        if (SandboxBackendStore.authorizedAt(context) <= 0L) {
            return Result.failure(
                IllegalStateException("chroot was never enabled by the user (no risk acknowledgement)"),
            )
        }
        val launcher = launcher(context)
        if (!launcher.isFile) {
            return Result.failure(IllegalStateException("helper not packaged: ${launcher.absolutePath}"))
        }
        val generation = SandboxBackendStore.generation(context)
        if (SandboxBackendStore.chrootGranted(generation)) {
            if (!SandboxBackendStore.chrootGrantMayBeExpired(generation)) {
                return Result.success(Unit)
            }
            // [FEAT-261025-CHROOT] The helper enforces its own idle TTL, so a
            // live grant can outlive the process it names. Do NOT trust the
            // boolean: ask the helper (as the ordinary app-uid client) before
            // either declaring success or starting a second one.
            when (val probe = probeServer(context, generation)) {
                EXIT_NO_HELPER -> {
                    Log.i(TAG, "helper for generation=$generation is gone (idle TTL/crash); relaunching")
                    SandboxBackendStore.revokeChrootGrant()
                }
                null -> {
                    val reason =
                        "chroot helper state unknown: liveness probe did not complete; " +
                            "refusing to start a second helper"
                    SandboxBackendStore.setLastError(context, reason)
                    return Result.failure(IllegalStateException(reason))
                }
                else -> {
                    // The lease was merely stale; a long-running command kept
                    // the helper alive. Re-arm the timestamp, not root.
                    SandboxBackendStore.grantChrootForProcess(generation)
                    SandboxBackendStore.setLastError(context, null)
                    Log.i(TAG, "helper still alive (probe exit=$probe); grant refreshed")
                    return Result.success(Unit)
                }
            }
        }

        // A helper started by a PREVIOUS app process can still be alive: the
        // in-memory grant died with that process, the detached root helper
        // did not. It still owns this generation's abstract socket, so a new
        // helper would fail to bind. Stopping it is unprivileged (app-uid
        // client) and harmless when nothing is listening (exit 97).
        val staleStop = stopServer(context, generation)
        if (staleStop == null || (staleStop != 0 && staleStop != EXIT_NO_HELPER)) {
            val reason = if (staleStop == null) {
                "could not confirm that a previous chroot helper is stopped (client did not finish)"
            } else {
                "previous chroot helper did not stop (client exit=$staleStop)"
            }
            SandboxBackendStore.setLastError(context, reason)
            return Result.failure(IllegalStateException(reason))
        }

        val argv = serverArgv(
            launcher = launcher.absolutePath,
            rootfs = rootfs(context).absolutePath,
            generation = generation,
            allowedHostRoots = allowedHostRoots(context),
            uid = android.os.Process.myUid(),
            gid = appGid(),
        )
        val outcome = ChrootPrivilege.run(context, argv, reason)
        // A printed handshake is not enough: `su` may print it from a wrapper
        // or the helper may die right after binding. Only exit 0 counts.
        val pid = if (outcome.exitCode == 0) parseHandshake(outcome.stdout, generation) else null
        if (pid == null) {
            val detail = outcome.stderr.ifBlank { outcome.stdout }.trim().take(400)
            SandboxBackendStore.setLastError(context, "helper handshake failed: $detail")
            return Result.failure(
                IllegalStateException("chroot helper did not become ready: $detail"),
            )
        }
        SandboxBackendStore.grantChrootForProcess(generation)
        SandboxBackendStore.setLastError(context, null)
        writeStateMarker(context, generation, pid)
        Log.i(TAG, "chroot helper ready generation=$generation pid=$pid")
        return Result.success(Unit)
    }

    /**
     * Android app processes have gid == uid (the package's primary gid), so
     * the helper is given one number and re-checks it before dropping.
     */
    private fun appGid(): Int = android.os.Process.myUid()

    private fun writeStateMarker(context: Context, generation: String, pid: Int) {
        runCatching {
            val dir = stateDir(context).also { it.mkdirs() }
            File(dir, "helper.state").writeText("generation=$generation\npid=$pid\n")
        }.onFailure { Log.w(TAG, "state marker failed: ${it.message}") }
    }

    /**
     * [FEAT-261025-CHROOT] Ask a running helper whether it is still there.
     *
     * The helper has no ping frame, so the cheapest honest question is a real
     * command. This runs as the ordinary app-uid client (same as every other
     * command; no root prompt) and returns the client's exit code:
     *
     *  - [EXIT_NO_HELPER]: nothing is listening on this generation's abstract
     *    socket — the idle TTL or a crash ended it;
     *  - any other code: the helper answered. The probe command itself may
     *    have failed (98 rejected, 126/127 exec), but that still proves the
     *    server is alive;
     *  - `null`: it could not be started or did not finish. The state is
     *    UNKNOWN, and callers must not start a second helper: two servers on
     *    one abstract socket would fight over the name.
     */
    private suspend fun probeServer(context: Context, generation: String): Int? {
        val launcherFile = launcher(context)
        if (!launcherFile.isFile) return EXIT_NO_HELPER
        return withContext(Dispatchers.IO) {
            val proc = runCatching {
                val pb = ProcessBuilder(
                    clientArgv(
                        launcher = launcherFile.absolutePath,
                        generation = generation,
                        rootfs = rootfs(context).absolutePath,
                        guestArgv = listOf("/bin/sh", "-c", "true"),
                        mounts = emptyList(),
                    ),
                )
                pb.redirectErrorStream(true)
                pb.start()
            }.getOrNull() ?: return@withContext null
            val finished = runCatching { proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return@withContext null
            }
            // The probe prints at most one diagnostic line; drain it without
            // letting a write block the child.
            runCatching { proc.inputStream.readBytes() }
            runCatching { proc.exitValue() }.getOrNull()
        }
    }

    /**
     * [FEAT-261025-CHROOT] Ask the helper to exit, as the ordinary app-uid
     * client.
     *
     * [security] The stop request is unprivileged on purpose: the helper
     * checks SO_PEERCRED against `--uid`, so routing this through
     * [RootManager] would run it as uid 0 (rejected) and would ask the user
     * for a root grant just to shut down.
     *
     * @return the client's exit code (0 = the server acknowledged and is
     *   exiting, [EXIT_NO_HELPER] = nothing was listening), or `null` when
     *   the client timed out / could not start.
     */
    private suspend fun stopServer(context: Context, generation: String): Int? {
        val launcherFile = launcher(context)
        if (!launcherFile.isFile) return EXIT_NO_HELPER
        return withContext(Dispatchers.IO) {
            val proc = runCatching {
                val pb = ProcessBuilder(
                    stopArgv(launcherFile.absolutePath, generation, rootfs(context).absolutePath),
                )
                pb.redirectErrorStream(true)
                pb.start()
            }.getOrNull() ?: return@withContext null
            val finished = runCatching { proc.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return@withContext null
            }
            runCatching { proc.inputStream.readBytes() }
            runCatching { proc.exitValue() }.getOrNull()
        }
    }

    /**
     * [FEAT-261025-CHROOT] Stop the helper and restore the PRoot stub set.
     *
     * Nothing under the rootfs is deleted except this backend's own state
     * directory and the offload wrappers, which are re-created by
     * [PRootKernel.refreshOffloadStubs]. User data, installed packages and the
     * rootfs itself are untouched — switching back to PRoot is not a reset.
     *
     * The stop is attempted whenever the launcher exists, NOT only when this
     * process holds a grant: a helper started by an earlier app process
     * survives that process (it is detached) for up to its idle TTL, and the
     * persisted generation is the only handle on it. If the helper does not
     * confirm the stop, the generation is deliberately NOT rotated — rotating
     * would make a still-running helper unreachable forever.
     */
    suspend fun shutdown(context: Context): Boolean {
        val generation = SandboxBackendStore.generation(context)
        val stopExit = stopServer(context, generation)
        val stopped = stopExit == 0
        val gone = stopped || stopExit == EXIT_NO_HELPER
        SandboxBackendStore.revokeChrootGrant()
        if (gone) {
            SandboxBackendStore.rotateGeneration(context)
            runCatching { stateDir(context).deleteRecursively() }
        } else {
            Log.w(TAG, "shutdown: helper did not confirm stop (client exit=$stopExit); generation kept")
            SandboxBackendStore.setLastError(
                context,
                "chroot helper did not confirm stop (client exit=${stopExit ?: "timeout"})",
            )
        }
        PRootKernel.refreshOffloadStubs()
        Log.i(TAG, "shutdown: helperStopped=$stopped gone=$gone, PRoot stubs restored, rootfs untouched")
        return stopped
    }

    /**
     * [FEAT-261025-CHROOT] Called from [PRootKernel.boot] when chroot is the
     * selected backend: install the guest-side offload client wrappers and
     * report the resulting route. It cannot start the helper (that needs the
     * user's per-command confirmation in the UI), so the first command may
     * exit [EXIT_NO_HELPER]; the settings screen offers "授权并启动".
     */
    /**
     * [FEAT-261025-CHROOT] Copy `assets/chroot/minis-offload-client` into the
     * rootfs, atomically and without touching [RootfsManager].
     *
     * Written to a temp file in the same directory, chmod 0755, then renamed:
     * a partially copied binary is never visible under the final name, which
     * is the difference between "offload blocked" and "execve ENOEXEC" on the
     * next command. Missing asset is not an error — [offloadRoute] then
     * reports BLOCKED and the UI says so.
     */
    fun installOffloadClient(context: Context): Boolean {
        val target = offloadClientPath(context)
        if (target.isFile && target.canExecute()) return true
        val assetName = "chroot/$OFFLOAD_CLIENT_NAME"
        val dir = target.parentFile ?: return false
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, ".$OFFLOAD_CLIENT_NAME.tmp")
            context.assets.open(assetName).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(target)) {
                // rename(2) over an existing file is fine on Linux; a failure
                // here means a different filesystem, so fall back to delete+move.
                target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
            }
            target.setExecutable(true, false)
            Log.i(TAG, "installed guest offload client -> ${target.absolutePath} (${target.length()} bytes)")
            true
        }.getOrElse {
            Log.w(TAG, "guest offload client not installed ($assetName): ${it.javaClass.simpleName}: ${it.message}")
            File(dir, ".$OFFLOAD_CLIENT_NAME.tmp").delete()
            false
        }
    }

    fun onRootfsReady(context: Context) {
        installOffloadClient(context)
        val route = installOffloadStubs(context)
        Log.i(TAG, "onRootfsReady backend=CHROOT offload=$route")
        if (route == SandboxOffloadRoute.BLOCKED) {
            Log.w(
                TAG,
                "guest offload client missing at ${offloadClientPath(context)} — " +
                    "Minis tools will report unavailable instead of silently failing",
            )
        }
    }

    /** Where the Minis tools land under this backend, from probes only. */
    fun offloadRoute(context: Context): SandboxOffloadRoute {
        val client = offloadClientPath(context)
        return if (client.isFile && client.canExecute()) {
            SandboxOffloadRoute.GUEST_CLIENT
        } else {
            SandboxOffloadRoute.BLOCKED
        }
    }

    /**
     * [FEAT-261025-CHROOT] Install `/usr/local/bin/<handler>` wrappers that
     * exec the guest offload client.
     *
     * The wrappers replace proot's execve-interception stubs. They are only
     * written when the client binary is present; otherwise [HandlerStub]'s
     * honest "unavailable" stub is left alone (and [offloadRoute] says
     * BLOCKED). Called by [PRootKernel.boot] when chroot is selected.
     */
    fun installOffloadStubs(context: Context): SandboxOffloadRoute {
        val client = offloadClientPath(context)
        if (!client.isFile) return SandboxOffloadRoute.BLOCKED
        runCatching { client.setExecutable(true, false) }
        val binDir = File(rootfs(context), "usr/local/bin").also { it.mkdirs() }
        val names = NativeOffloadServer.registeredHandlers
        for (name in names) {
            val wrapper = File(binDir, name)
            wrapper.writeText(
                "#!/bin/sh\n" +
                    "# Minis chroot offload wrapper (FEAT-261025-CHROOT).\n" +
                    "# Serves the same wire protocol as proot's native_offload extension.\n" +
                    "exec /usr/local/lib/minis/$OFFLOAD_CLIENT_NAME \"\$0\" \"\$@\"\n",
            )
            wrapper.setExecutable(true, false)
        }
        Log.i(TAG, "installOffloadStubs: ${names.size} handler(s) -> guest client")
        return SandboxOffloadRoute.GUEST_CLIENT
    }

    /** Reason string shown by the root bridge's confirmation dialog. */
    const val DEFAULT_GRANT_REASON =
        "Start the chroot sandbox helper (real chroot, app uid, private mount namespace). " +
            "Commands run inside the sandbox only; no SELinux, partition or system change."
}
