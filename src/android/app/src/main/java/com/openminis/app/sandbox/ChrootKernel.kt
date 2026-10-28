package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.RootAccess
import com.openminis.app.offload.RootExecGrant
import com.openminis.app.offload.RootExecOutcome
import com.openminis.app.offload.RootExecPermission
import com.openminis.app.offload.RootExecPolicy
import com.openminis.app.offload.RootExecRequest
import com.openminis.app.offload.RootManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * `chroot(rootfs)`, `chdir(cwd)`, `setgid/setuid(0)` (an authorised uid-0 frame), `setsid()`, and
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
    // Several confirmed CLI calls, startup and settings can arrive together.
    // Serialize handshake/stop so one launch cannot stop another fresh helper.
    private val lifecycleLock = Mutex()

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

    /** Bumped whenever the client/helper frame or handshake changes. */
    const val SOCKET_PROTOCOL_VERSION = 2

    /** Abstract socket name; leading `@` is how the C helper spells `\0`. */
    fun socketName(generation: String): String =
        "minis-chroot-v$SOCKET_PROTOCOL_VERSION-$generation"

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
        /**
         * [FEAT-261027-CHROOT-ROOT] Give the helper the CAPABILITY to serve a
         * uid-0 frame. It is still refused unless the request carries the
         * root flag, which only the Kotlin gate sets.
         */
        rootExecCapable: Boolean = false,
        /** App-managed shared roots only; never inferred from arbitrary allow-roots. */
        appFilesDir: String? = null,
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
        // The token is generated by the helper and returned on its handshake
        // line; it is never an argument (argv is world-readable to root).
        if (rootExecCapable) argv += "--root-exec"
        appFilesDir?.let { argv += listOf("--app-files", it) }
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
        // [FEAT-261025-CHROOT account] `/root` is 0700 root-owned in the image
        // and the app uid cannot enter it; the account's home is ours.
        cwd: String = GuestAccount.WORKDIR,
        mounts: List<ChrootMount> = emptyList(),
        /** [FEAT-261027-CHROOT-ROOT] Ask the helper to keep uid/gid 0. */
        rootExec: Boolean = false,
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
        // Not `--root`: that is the rootfs option of this client. See minis_chroot.c.
        if (rootExec) argv += "--root-user"
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
    /**
     * Parsed handshake. [token] is the per-helper secret the server generated
     * for uid-0 frames ("-" when the helper was started without --root-exec).
     */
    data class Handshake(val pid: Int, val token: String?)

    fun parseHandshake(stdout: String, generation: String): Handshake? {
        // The C helper prints the SOCKET name (minis-chroot-<generation>), not
        // the bare nonce, so a line from a stale generation cannot match.
        val marker = "MINIS-CHROOT-READY ${socketName(generation)} "
        val line = stdout.lineSequence().firstOrNull { it.startsWith(marker) } ?: return null
        val parts = line.removePrefix(marker).trim().split(' ', limit = 2)
        val pid = parts.firstOrNull()?.toIntOrNull()?.takeIf { it > 1 } ?: return null
        val token = parts.getOrNull(1)?.trim()?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
        return Handshake(pid, token)
    }

    /** Remove a 64-hex helper token from text that may be logged or shown. */
    fun redactHandshake(text: String): String =
        text.replace(Regex("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])"), "<token>")

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

    /**
     * [FEAT-261027-CHROOT-ROOT] Per-helper root token, memory only.
     *
     * A helper is bound to one generation; the token dies with this process.
     * A helper that outlived its app process cannot be reused because the
     * in-memory chroot grant died with that process too, so the next command
     * stops it and starts a fresh one with a fresh token.
     */
    private val helperRootTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Token for the live helper of [generation], or null when none was made. */
    private fun rootTokenFor(generation: String): String? = helperRootTokens[generation]

    /**
     * [FEAT-261027-CHROOT-ROOT] Drop every helper root token.
     *
     * Called on a downgrade/revoke. The helper may still be alive, but without
     * the token this process cannot ask it for a uid-0 frame; the next
     * authorised spawn stops it and starts a fresh helper with a new token.
     */
    fun forgetRootTokens() {
        helperRootTokens.clear()
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
        // [FEAT-261025-CHROOT account] The guest must be able to name the uid
        // it runs as (otherwise bash prints "I have no name!" and `groups`
        // reports a numeric id). Idempotent and cheap once provisioned; it
        // only APPENDS a row for this app's uid, it never rewrites one.
        val account = GuestAccount.ensureForApp(context)
        val rootfs = rootfs(context).absolutePath
        val launcher = launcher(context).absolutePath
        val guestArgv = when {
            // [T-android-fresh-exit-status-file] FreshProcessShell reads the
            // command's exit from a status file written by the in-guest
            // runner, and uses <status>.pid to kill exactly this command's
            // group. CHROOT must run the SAME wrapper as PRoot, otherwise a
            // completed command would block until EOF and Stop/timeout would
            // have no pid to target. The command and path stay separate argv
            // entries; neither is re-parsed by the runner's shell.
            !spec.interactive -> spec.statusGuestPath?.let { statusPath ->
                ForegroundCommandGroup.wrapForFreshProcess(spec.command, statusPath)
            } ?: listOf("/bin/sh", "-c", spec.command)
            spec.loginShell -> listOf("/bin/bash", "--login", "-i")
            else -> listOf("/bin/sh")
        }
        val (mounts, rejected) = mountsFor(context, spec.sessionMounts ?: PRootKernel.bindMounts)
        // [FEAT-261027-CHROOT-ROOT] THE authorisation point for this backend.
        // Every shell, warm shell, terminal and CLI spawn passes here, so the
        // unified tri-state is enforced where the process is built — not only
        // in the settings UI. The chroot backend always asks for real uid 0
        // (that is what apt/dpkg need); there is no silent downgrade to the
        // app uid. 每次询问 asks once per command/session, and refusal, cancel
        // or timeout throws before any helper/su/client exists.
        var level = RootManager.accessLevel()
        var epoch = OffloadPermissionManager.rootAuthorizationEpoch()
        var now = RootExecPermission.nowMs()
        val rootRequest = RootExecRequest(
            command = if (spec.interactive) "" else spec.command,
            interactive = spec.interactive,
            sessionId = spec.sessionId,
            required = spec.rootRequired,
        )
        // The registry copy is removed by consume(), so a grant stashed by the
        // CLI's own confirmation can authorise THIS spawn only. There is no
        // "approved" boolean and no grant field on the spec to replay.
        var grant = RootExecPermission.consume(rootRequest, level, epoch, now)
        var decision = RootExecPolicy.decide(level, rootRequest, grant, epoch, now)
        if (decision.outcome == RootExecOutcome.NEED_APPROVAL) {
            // A dialog needs a background thread (it can stay open 120 s).
            grant = RootExecPermission.requestApproval(rootRequest, level)
            // The user may have revoked, downgraded or upgraded while the
            // dialog was open. Decide on the LIVE level + epoch, never on the
            // values captured before the dialog: a grant minted for an epoch
            // the user has since left must not authorise anything.
            level = RootManager.accessLevel()
            epoch = OffloadPermissionManager.rootAuthorizationEpoch()
            now = RootExecPermission.nowMs()
            decision = RootExecPolicy.decide(level, rootRequest, grant, epoch, now)
        }
        if (decision.outcome != RootExecOutcome.ROOT) {
            // Refused BEFORE any helper/su/client is built: a caller sees a
            // recognisable failure, not a silent app-uid command and not a
            // refusal "command" executed inside the sandbox.
            Log.i(TAG, "root exec refused: level=$level interactive=${spec.interactive}")
            RootExecPermission.discard(rootRequest)
            throw RootExecRefusedException(decision.reason + "（本次未执行任何 chroot 进程）")
        }
        val rootExec = decision.outcome == RootExecOutcome.ROOT
        // Authorised now; only NOW may the helper be (re)started. A refused
        // command above returns before this, so no su is run for it.
        val helperLaunch = launchHelperForSpawn(context, rootRequest, level)
        helperLaunch.exceptionOrNull()?.let { failure ->
            // A helper that did not start cannot serve this command. Building
            // a client argv anyway would either run nothing (exit 97) or,
            // worse, look like a successful spawn whose command never ran.
            // Throw a recognisable failure instead.
            Log.i(TAG, "chroot helper launch failed before the command: ${failure.message}")
            RootExecPermission.discard(rootRequest)
            throw RootExecRefusedException(
                "chroot helper 未启动，本次未执行任何命令：" +
                    (failure.message ?: failure.javaClass.simpleName),
            )
        }
        // Starting the helper can take seconds (su prompt + handshake). The
        // level or the epoch can change inside that window, so re-read them
        // and re-decide BEFORE the client argv/env (and the token) are built.
        level = RootManager.accessLevel()
        epoch = OffloadPermissionManager.rootAuthorizationEpoch()
        now = RootExecPermission.nowMs()
        decision = RootExecPolicy.decide(level, rootRequest, grant, epoch, now)
        if (decision.outcome != RootExecOutcome.ROOT) {
            // Do not leave a root-capable helper (or its token) behind for a
            // grant the user just revoked.
            helperRootTokens.remove(generation)
            SandboxBackendStore.revokeChrootGrant()
            RootExecPermission.clear()
            runCatching { kotlinx.coroutines.runBlocking { stopServer(context, generation) } }
            Log.i(TAG, "root exec revoked during helper start: level=$level")
            throw RootExecRefusedException(
                "Root 授权在 chroot helper 启动期间被撤销或改变，本次未执行任何命令。",
            )
        }
        val argv = clientArgv(
            launcher = launcher,
            generation = generation,
            rootfs = rootfs,
            guestArgv = guestArgv,
            interactive = spec.interactive,
            loginShell = spec.loginShell && spec.interactive,
            // [FEAT-261025-CHROOT account] `/root` is mode 0700 owned by guest
            // root in every image, so the app uid cannot chdir into it and the
            // helper fell back to `/`. /home/minis belongs to us. A uid-0
            // command starts in the root home, like `su -`/`sudo -i` do.
            cwd = if (rootExec) "/root" else GuestAccount.WORKDIR,
            mounts = mounts,
            rootExec = rootExec,
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
        // The PRoot environment names "/root" for HOME; under chroot that
        // directory is unreachable for the app uid. These four are the ones
        // bash's login/PS1 code and `groups` consult.
        env.putAll(GuestAccount.environment())
        if (rootExec) {
            // Real uid 0 inside the guest: name it, so apt/dpkg and bash do not
            // read a minis-user profile while running as root.
            env["HOME"] = "/root"
            env["USER"] = "root"
            env["LOGNAME"] = "root"
        }
        env["MINIS_CHROOT_SOCKET"] = socketName(generation)
        env["MINIS_CHROOT_GENERATION"] = generation
        env["MINIS_NOFF_SOCKET"] = NativeOffloadServer.socketName
        env["MINIS_SANDBOX_BACKEND"] = SandboxBackend.CHROOT.name
        env["MINIS_CHROOT_UID"] = if (rootExec) "0" else account?.uid?.toString().orEmpty()
        // The app's own uid, for the offload client's connect(2) ONLY. A uid0
        // guest command uses it to pass NativeOffloadServer's unchanged
        // "peer must be the app uid" check, then restores uid 0; the command
        // keeps running as real root.
        env["MINIS_CHROOT_APP_UID"] = android.os.Process.myUid().toString()
        if (rootExec) {
            // Env, not argv: the token is only for this client process. The
            // helper refuses a uid-0 frame whose token does not match.
            rootTokenFor(generation)?.let { env["MINIS_CHROOT_ROOT_TOKEN"] = it }
        }
        spec.sessionId?.let { env["MINIS_CHAT_SESSION_ID"] = it }
        if (!spec.interactive) {
            env["TERM"] = "dumb"
            env["PS1"] = ""
        } else {
            env["TERM"] = "xterm-256color"
        }
        val notes = mutableListOf<String>()
        if (!SandboxBackendStore.chrootGranted(generation)) {
            notes += "chroot helper 未运行（generation=$generation）：已尝试自动重启，" +
                "若命令仍失败请到 设置 → Shell 容器 执行后端 里点一次启用 chroot。" +
                "最近错误：" + (SandboxBackendStore.lastError(context) ?: "无")
        } else if (SandboxBackendStore.chrootGrantMayBeExpired(generation)) {
            // The command still gets the real client argv; if the helper's
            // idle TTL already fired it exits EXIT_NO_HELPER and the user is
            // told to re-enable chroot. Never fall back to running outside
            // the sandbox.
            notes += "runtime grant may be stale (helper idle TTL); a command may exit $EXIT_NO_HELPER " +
                "until chroot is started again"
        }
        notes += decision.reason
        notes += rejected.map { "mount rejected: $it" }
        account?.warning?.let { notes += "guest account incomplete: $it" }
        if (account?.userName == null && account != null) {
            notes += "guest has no name for uid=${account.uid}; the shell will show a numeric identity"
        }
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
            rootExec = rootExec,
        )
    }

    /**
     * [FEAT-261027-CHROOT-ROOT] Thrown by [spawn] when the unified Root
     * tri-state does not allow this uid-0 command.
     *
     * It is thrown BEFORE a helper is launched and before a client argv is
     * built, so a refusal runs nothing at all: no su, no chroot command, not
     * even a message-printing shell inside the sandbox. Callers surface
     * [message] (terminal start catches it; a tool call reports it as the
     * command's failure).
     */
    class RootExecRefusedException(message: String) : IllegalStateException(message)

    /**
     * Launch (or adopt) the helper for a spawn that the Root gate JUST
     * authorised.
     *
     * Under 每次询问 the su launch reuses the same confirmation through
     * [RootManager.ticketForConfirmedAction]; without that ticket the helper
     * is never started from here, so a command can never turn into a second
     * Root prompt (or into an unapproved su).
     */
    private fun launchHelperForSpawn(
        context: Context,
        request: RootExecRequest,
        level: RootAccess,
    ): Result<Unit> {
        val generation = SandboxBackendStore.generation(context)
        val alive = SandboxBackendStore.chrootGranted(generation) &&
            !SandboxBackendStore.chrootGrantMayBeExpired(generation)
        if (alive && rootTokenFor(generation) != null) {
            return Result.success(Unit)
        }
        // A helper started while Root was 不允许 has no --root-exec capability
        // and no token. After an explicit upgrade it must be replaced, or every
        // uid-0 frame would be rejected by the helper.
        if (alive) {
            val stopped = kotlinx.coroutines.runBlocking { stopServer(context, generation) }
            if (stopped == null || (stopped != 0 && stopped != EXIT_NO_HELPER)) {
                return Result.failure(
                    IllegalStateException("previous non-root chroot helper did not stop (client exit=$stopped)"),
                )
            }
            SandboxBackendStore.revokeChrootGrant()
        }
        if (!RootExecPermission.canPromptHere()) {
            return Result.failure(IllegalStateException("cannot start the chroot helper from this thread"))
        }
        val ticket: RootManager.AccessTicket? = if (level == RootAccess.ASK_ONCE) {
            RootManager.ticketForConfirmedAction(request.fingerprint)
                ?: return Result.failure(
                    IllegalStateException(
                        "no confirmed Root action for this command; refusing to start the helper",
                    ),
                )
        } else {
            null
        }
        // ensureServer already returns Result<Unit>: runBlocking it and map a
        // thrown exception (never expected) without nesting the Result.
        return try {
            kotlinx.coroutines.runBlocking {
                ensureServer(
                    context,
                    "root-authorised chroot command",
                    // A one-shot command never opts the user into restart-time
                    // root work; only an explicit switch persists autostart.
                    persistAutostart = false,
                    userInitiated = true,
                    launchTicket = ticket,
                )
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
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
     * Follows the Root tri-state (Settings → Permissions → 原生 Root):
     * 允许 starts the helper directly; 询问 asks ONE confirmable request for
     * this switch (RootPrivilege → RootManager SYSTEM caller); 不允许 fails
     * with a settings hint. A switch never enables Root by itself. On success
     * the handshake's nonce is checked before the in-memory grant is recorded.
     */
    suspend fun ensureServer(
        context: Context,
        reason: String = DEFAULT_GRANT_REASON,
        // Explicit one-shot CLI calls do not opt the user into restart-time
        // background work. Never snapshot/restore this preference: a settings
        // change made concurrently must not be overwritten by a finishing CLI.
        persistAutostart: Boolean = true,
        /**
         * True only when the USER just asked for this launch (settings switch,
         * an already-confirmed CLI command). Under 每次询问 that may raise ONE
         * launch approval; false (auto-restart/restore) never runs su.
         */
        userInitiated: Boolean = false,
        /** Approval the caller already obtained for this exact action. */
        launchTicket: RootManager.AccessTicket? = null,
    ): Result<Unit> = lifecycleLock.withLock {
        ensureServerLocked(context, reason, persistAutostart, userInitiated, launchTicket)
    }

    private suspend fun ensureServerLocked(
        context: Context,
        reason: String,
        persistAutostart: Boolean,
        userInitiated: Boolean = false,
        launchTicket: RootManager.AccessTicket? = null,
    ): Result<Unit> {
        if (!com.openminis.app.sandbox.ChrootPrivilege.switchAllowed()) {
            // 不允许: no implicit enable and no Root dialog from a switch.
            return Result.failure(
                IllegalStateException(com.openminis.app.offload.ROOT_DISABLED_FOR_SYSTEM),
            )
        }
        val launcher = launcher(context)
        if (!launcher.isFile) {
            return Result.failure(IllegalStateException("helper not packaged: ${launcher.absolutePath}"))
        }
        // [FEAT-261025-CHROOT account] Before the first command, not only
        // before the terminal: the agent's warm shell runs `id`/`groups` too.
        GuestAccount.ensureForApp(context)
        // [T-chroot-offload-client-refresh] Idempotent (content-compared), so a
        // helper that outlived an app update still serves the client this build
        // shipped. Without this the first client ever written into a rootfs was
        // kept forever, and after real-uid0 chroot commands arrived it kept
        // connecting as uid 0 - refused by NativeOffloadServer, reported as
        // "malformed reply".
        installOffloadClient(context)
        val generation = SandboxBackendStore.generation(context)
        if (SandboxBackendStore.chrootGranted(generation)) {
            // [FEAT-261025-CHROOT] The helper enforces its own idle TTL and
            // can also crash, so a live grant can outlive the process it
            // names. Do NOT trust the boolean: ask the helper (as the
            // ordinary app-uid client) before either declaring success or
            // starting a second one. The probe is cheap and only runs on an
            // actual switch, never per command.
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
                    // Alive: a long-running command may have kept it past our
                    // lease, or the grant simply predates this check. Re-arm
                    // the timestamp; never ask for root again here.
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
            appFilesDir = context.filesDir.absolutePath,
            // The capability is granted only while the level permits root at
            // all; at 不允许 the helper can never serve a uid-0 frame.
            rootExecCapable = RootExecPolicy.helperMayRunRoot(
                RootManager.accessLevel(),
            ),
        )
        val outcome = ChrootPrivilege.run(
            context,
            argv,
            reason,
            userInitiated = userInitiated,
            ticket = launchTicket,
        )
        // A printed handshake is not enough: `su` may print it from a wrapper
        // or the helper may die right after binding. Only exit 0 counts.
        val handshake = if (outcome.exitCode == 0) parseHandshake(outcome.stdout, generation) else null
        if (handshake == null) {
            val detail = redactHandshake(outcome.stderr.ifBlank { outcome.stdout }).trim().take(400)
            SandboxBackendStore.setLastError(context, "helper handshake failed: $detail")
            return Result.failure(
                IllegalStateException("chroot helper did not become ready: $detail"),
            )
        }
        val pid = handshake.pid
        if (RootExecPolicy.helperMayRunRoot(RootManager.accessLevel())) {
            val token = handshake.token
            if (token == null) {
                // A helper without the token cannot serve a uid-0 frame; fail
                // loudly instead of accepting commands that would be refused.
                SandboxBackendStore.setLastError(
                    context,
                    "helper did not return a root token; uid0 commands are unavailable",
                )
                return Result.failure(
                    IllegalStateException("chroot helper did not return a root token"),
                )
            }
            helperRootTokens[generation] = token
        } else {
            helperRootTokens.remove(generation)
        }
        SandboxBackendStore.grantChrootForProcess(generation)
        // [FEAT-261027-CHROOT-autostart] Remember that chroot is wanted, so a
        // restart relaunches the helper instead of failing every command.
        if (persistAutostart) SandboxBackendStore.setChrootAutostart(context, true)
        SandboxBackendStore.setLastError(context, null)
        writeStateMarker(context, generation, pid)
        Log.i(TAG, "chroot helper ready generation=$generation pid=$pid")
        return Result.success(Unit)
    }

    /** Actual process primary GID; do not assume a numeric UID/GID equality. */
    private fun appGid(): Int = android.system.Os.getgid()

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
     * [RootManager] would run it as uid 0 (rejected) and would need a Root
     * grant just to shut down.
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
    suspend fun shutdown(context: Context): Boolean = lifecycleLock.withLock {
        shutdownLocked(context)
    }

    private suspend fun shutdownLocked(context: Context): Boolean {
        val generation = SandboxBackendStore.generation(context)
        val stopExit = stopServer(context, generation)
        val stopped = stopExit == 0
        val gone = stopped || stopExit == EXIT_NO_HELPER
        helperRootTokens.remove(generation)
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
     * next command. A missing asset is not an error: an already installed,
     * executable client is kept and [offloadRoute] reports GUEST_CLIENT; only
     * "no asset and no usable file" reports BLOCKED.
     */
    @Synchronized
    fun installOffloadClient(context: Context): Boolean {
        val target = offloadClientPath(context)
        val assetName = "chroot/$OFFLOAD_CLIENT_NAME"
        val dir = target.parentFile ?: return false
        // No asset in this APK: keep a valid client the rootfs already has
        // instead of reporting BLOCKED for a working binary. Nothing is deleted.
        val assetInput = runCatching { context.assets.open(assetName) }.getOrNull()
            ?: run {
                Log.w(TAG, "guest offload client asset missing ($assetName); keeping the installed copy")
                return target.isFile && target.canExecute()
            }
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, ".$OFFLOAD_CLIENT_NAME.tmp")
            val stamp = File(dir, OffloadClientInstall.STAMP_NAME)
            // Stream the asset to a temp file, hashing it on the way: the copy
            // and the decision use the same bytes, and nothing is held in memory.
            val assetCrc = assetInput.use { input ->
                tmp.outputStream().use { output ->
                    val crc = CRC32()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                    crc.value
                }
            }
            val targetCrc = if (target.isFile) crcOf(target) else null
            val stampCrc = runCatching { stamp.readText().trim().toLong() }.getOrNull()
            if (!OffloadClientInstall.needsRefresh(assetCrc, stampCrc, target.isFile, targetCrc)) {
                tmp.delete()
                target.setExecutable(true, false)
                return@runCatching true
            }
            tmp.setExecutable(true, false)
            // Both files share a directory. If atomic replacement fails, keep
            // the previous client intact and report failure instead of deleting it.
            check(tmp.renameTo(target)) { "atomic client replacement failed" }
            target.setExecutable(true, false)
            // Stamp only after the rename: a crash leaves either the old client
            // with an old stamp, or the new client with no stamp (both refresh
            // again next boot, never the reverse).
            runCatching { stamp.writeText(assetCrc.toString()) }
            Log.i(
                TAG,
                "installed guest offload client -> ${target.absolutePath} " +
                    "(${target.length()} bytes, crc=$assetCrc, replaced=${targetCrc != null})",
            )
            true
        }.getOrElse {
            Log.w(TAG, "guest offload client not installed ($assetName): ${it.javaClass.simpleName}: ${it.message}")
            File(dir, ".$OFFLOAD_CLIENT_NAME.tmp").delete()
            false
        }
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
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
        "Start the chroot sandbox helper (real chroot, real uid0 command child, private mount namespace). " +
            "The helper itself makes no SELinux, partition or system change; chroot is not a security boundary."


}

/**
 * [T-chroot-offload-client-refresh] When the guest client in the rootfs must be
 * replaced by the one in the APK.
 *
 * Install-once was the bug: the check used to be "the file exists and is
 * executable", so the first client ever written into a rootfs stayed there
 * forever. The CHROOT backend then started running commands as REAL uid 0
 * (FEAT-261027-CHROOT-ROOT) while the stale client still connected to the
 * offload socket with uid 0. NativeOffloadServer refuses any peer that is not
 * this app's uid, so every Android/minis tool inside a chroot shell failed -
 * `status` and `exec` alike - with "malformed reply ... exit 69", and no app
 * update could repair it.
 *
 * Pure (no Android API) so the rule is unit-tested on a plain JVM.
 */
internal object OffloadClientInstall {
    const val STAMP_NAME = ".minis-offload-client.stamp"

    /**
     * True when the installed bytes are not the APK's bytes.
     *
     * A missing target, a missing/corrupt stamp, a stamp from another build, or
     * a target whose content no longer matches the asset all mean "refresh".
     * Comparing the target's own CRC as well as the stamp catches a truncated
     * or half-overwritten client that a stamp alone would call up to date.
     */
    fun needsRefresh(assetCrc: Long, stampCrc: Long?, targetExists: Boolean, targetCrc: Long?): Boolean {
        if (!targetExists || targetCrc == null) return true
        if (stampCrc == null || stampCrc != assetCrc) return true
        return targetCrc != assetCrc
    }
}
