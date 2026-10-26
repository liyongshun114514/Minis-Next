package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.offload.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FEAT-261025-CHROOT] The ONE place the chroot backend asks for privilege.
 *
 * It delegates to the root bridge the native-port agent landed:
 * [com.openminis.app.offload.RootManager.runProcess]. Chroot does NOT show a
 * per-command Root prompt: the persistent Root grant (Settings → Permissions →
 * Native Root) is the authorisation, and without it the switch refuses with a
 * settings hint instead of enabling anything. RootManager still refuses custom
 * environments and main-thread callers, and the device su manager keeps its
 * own prompt.
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
        return RootManager.isEnabled() &&
            RootManager.snapshot.value.state != RootManager.State.NOT_FOUND
    }

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
    ): Outcome = withContext(Dispatchers.IO) {
        if (!RootManager.isEnabled()) {
            // Switching backends must never enable Root or pop a Root dialog:
            // the Chroot row reports the settings hint instead.
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
                // Chroot is authorised by the persistent Root grant, not by a
                // per-command prompt: no dialog, and no grant → settings hint.
                caller = com.openminis.app.offload.RootGrantPolicy.Caller.SYSTEM,
            )
        }.getOrElse { t ->
            return@withContext Outcome(-1, "", "root bridge threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        Log.i(TAG, "root grant result exit=${result.exitCode} out=${result.stdout.take(120)}")
        Outcome(result.exitCode, result.stdout, result.stderr)
    }
}
