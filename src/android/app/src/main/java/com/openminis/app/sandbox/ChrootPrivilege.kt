package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.offload.RootAccess
import com.openminis.app.offload.RootCaller
import com.openminis.app.offload.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FEAT-261025-CHROOT] The ONE place the chroot backend asks for privilege.
 *
 * It delegates to the root bridge the native-port agent landed:
 * [com.openminis.app.offload.RootManager.runProcess]. The authorization itself
 * is the unified Root tri-state (设置 → 权限 → 原生 Root): 完全允许 launches
 * directly, 每次询问 needs ONE approval for an explicit user action (a switch,
 * or a command/session the user just confirmed), 不允许 refuses with a settings
 * hint. An automatic restart never runs su under 每次询问, and a per-command
 * approval is passed in as a [RootManager.AccessTicket] so the launch does not
 * prompt a second time. RootManager still refuses custom environments and
 * main-thread callers, and the device su manager keeps its own prompt.
 *
 * ## Why a helper process instead of one root call per command
 *
 * `runProcess` is per-command because a root command is unrestricted. The
 * chroot helper is deliberately narrower: it binds a random-named abstract
 * socket, runs each guest command as the APP's uid inside a private mount
 * namespace, and refuses every path outside the allowlist it was started with.
 * Authorising it once per app run (see [SandboxBackendStore.grantChrootForProcess],
 * never persisted) keeps the root prompt rare enough to be read. A prompt per
 * `ls` would train users to approve blind.
 *
 * ## What never happens here
 *
 * No `su -c "<string>"`: [argv] is built by [ChrootKernel.serverArgv] from
 * app-owned paths plus a random nonce, and the user's command travels later in
 * a length-prefixed frame over the socket. No environment is forwarded to the
 * root process (RootManager rejects one anyway).
 */
internal object ChrootPrivilege {
    private const val TAG = "ChrootPrivilege"

    /** Time to bind the socket and print the handshake; the helper then detaches. */
    private const val LAUNCH_TIMEOUT_MS = 20_000L

    data class Outcome(val exitCode: Int, val stdout: String, val stderr: String) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr"
    }

    /** The bridge class is compiled in — see the import above. */
    fun bridgePresent(): Boolean = true

    /**
     * "Root is usable" for the settings summary. Read-only: [RootManager.refresh]
     * never executes su, it only resolves a path.
     */
    fun rootManagerDetected(): Boolean {
        RootManager.refresh()
        return RootManager.accessLevel() == RootAccess.BYPASS &&
            RootManager.snapshot.value.state != RootManager.State.NOT_FOUND
    }

    /**
     * Whether the chroot backend may be used at all under the current level:
     * 完全允许 runs directly; 每次询问 asks ONCE for the action that needs the
     * helper; 不允许 refuses with the settings hint (chroot never downgrades to
     * an unprivileged spawn either — see RootExecPolicy).
     */
    fun switchAllowed(): Boolean = when (RootManager.accessLevel()) {
        RootAccess.BYPASS, RootAccess.ASK_ONCE -> true
        RootAccess.NOT_ALLOWED -> false
    }

    /** True only when a helper launch may execute without any approval. */
    fun silentLaunchAllowed(): Boolean = RootManager.accessLevel() == RootAccess.BYPASS

    /** Human-readable bridge state, for the settings row. */
    fun bridgeState(): String = RootManager.snapshot.value.let { "${it.state}: ${it.message}" }

    /**
     * Run the helper launch through the root bridge.
     *
     * Always off the main thread ([RootManager] refuses it) and never throws:
     * a denied prompt, missing su or a timeout all come back as a non-zero
     * [Outcome] whose stderr is shown verbatim by the settings screen.
     */
    suspend fun run(
        context: Context,
        argv: List<String>,
        reason: String,
        timeoutMs: Long = LAUNCH_TIMEOUT_MS,
        /**
         * True only for an explicit in-app action (the settings switch, the
         * CLI's own confirmed command). Under 每次询问 it may raise ONE
         * launch approval. Automatic restart passes false and therefore never
         * runs su without permission.
         */
        userInitiated: Boolean = false,
        /**
         * A ticket for an approval the caller already obtained for this exact
         * action (the chroot CLI's single-use confirmation), so the launch
         * does not prompt a second time under 每次询问.
         */
        ticket: RootManager.AccessTicket? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        // Defence in depth: under 每次询问 an automatic caller with no ticket
        // must not even reach RootManager (which would report a settings hint
        // after a pointless call). A user-initiated launch without a ticket is
        // still allowed to ask once.
        if (RootManager.accessLevel() == RootAccess.ASK_ONCE && !userInitiated && ticket == null) {
            return@withContext Outcome(126, "", com.openminis.app.offload.ROOT_DISABLED_FOR_SYSTEM)
        }
        if (!switchAllowed()) {
            // 不允许: switching backends must never enable Root or pop a Root
            // dialog; the Chroot row reports the settings hint instead.
            return@withContext Outcome(126, "", com.openminis.app.offload.ROOT_DISABLED_FOR_SYSTEM)
        }
        RootManager.refresh()
        Log.i(
            TAG,
            "requesting root grant: reason=$reason state=${RootManager.snapshot.value.state} " +
                "argv0=${argv.firstOrNull()} argc=${argv.size}",
        )
        val result = runCatching {
            RootManager.runProcess(
                argv = argv.toTypedArray(),
                env = null,
                cwd = null,
                timeoutMs = timeoutMs,
                // Tri-state: 完全允许 executes directly. 每次询问 raises ONE
                // approval only for an explicit user action and never changes
                // the level; an automatic restart is refused without su.
                caller = if (userInitiated) RootCaller.USER else RootCaller.SYSTEM,
                ticket = ticket,
            )
        }.getOrElse { t ->
            return@withContext Outcome(-1, "", "root bridge threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        // Never log the raw stdout: it carries the helper's per-helper root
        // token. Only the exit code and whether a handshake line exists.
        Log.i(
            TAG,
            "root grant result exit=${result.exitCode} " +
                "handshake=${result.stdout.contains("MINIS-CHROOT-READY")}",
        )
        Outcome(result.exitCode, result.stdout, result.stderr)
    }
}
