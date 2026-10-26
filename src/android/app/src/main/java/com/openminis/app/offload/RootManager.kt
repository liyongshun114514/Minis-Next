package com.openminis.app.offload

import android.content.Context
import android.os.Looper
import app.openminis.rootbridge.RootExecutor
import app.openminis.rootbridge.RootExecutorConfig
import app.openminis.rootbridge.ShellCommand
import app.openminis.rootbridge.WireProtocol
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Native su backend, parallel to ShizukuManager.
 *
 * The permission is a persistent grant stored by [OffloadPermissionManager]:
 * once the user enables it in Settings → Permissions → Native Root,
 * `android-root-cli` and the Chroot backend use it directly — no per-command
 * app dialog. The device's su manager keeps its own prompt; this object never
 * tries to bypass it and never runs su during init/refresh.
 *
 * Two callers are distinguished:
 *  - [RootGrantPolicy.Caller.AGENT] — the agent's `android-root-cli`. When the
 *    grant is off, the first call sends ONE confirmable request (the exact
 *    argv is shown) and only that invocation runs if the user accepts; the
 *    settings switch stays off, so Root is never enabled implicitly.
 *  - [RootGrantPolicy.Caller.SYSTEM] — Chroot helper launch / settings probe.
 *    These never prompt: without the grant they fail with a settings hint.
 */
object RootManager {
    enum class State { DISABLED, NOT_FOUND, NEED_PERMISSION, READY, DENIED }
    data class Snapshot(val state: State, val suPath: String? = null, val uid: Int = -1,
                        val message: String = "Not tested; no su command executed")
    private val _snapshot = MutableStateFlow(Snapshot(State.DISABLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    @Volatile private var appContext: Context? = null
    private const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024

    /**
     * An approval for one agent invocation. [epoch] pins it to the grant state
     * the user answered under: revoking Root (or resetting permissions) bumps
     * the epoch and every outstanding ticket stops working.
     */
    class AccessTicket internal constructor(internal val epoch: Long)

    /** One dialog at a time; a queued call re-checks the grant after waiting. */
    private val agentAccessLock = Mutex()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // refresh() reads the permission level through SharedPreferences;
        // initialising it here makes RootManager usable even if a future
        // startup reorder (or a standalone activity) calls us first.
        OffloadPermissionManager.init(app)
        refresh()
    }

    /** True only for the explicit persistent grant; default is off. */
    fun isEnabled(): Boolean = OffloadPermissionManager.isInitialized() &&
        OffloadPermissionManager.isRootGranted()

    fun setEnabled(enabled: Boolean) {
        OffloadPermissionManager.setRootGranted(enabled)
        if (!enabled) {
            // The in-process chroot grant was authorized by the Root grant;
            // drop it so a later switch has to go through the gate again.
            runCatching { com.openminis.app.sandbox.SandboxBackendStore.revokeChrootGrant() }
        }
        refresh()
    }

    /** Revoke from anywhere (e.g. reset-all); identical to setEnabled(false). */
    fun revoke() = setEnabled(false)

    private fun config(timeoutMs: Long = 120_000): RootExecutorConfig = RootExecutorConfig().apply {
        // Only reached after the gate below approved this exact call.
        enabled = true
        suPath = appContext?.getSharedPreferences("native_root", Context.MODE_PRIVATE)
            ?.getString("su_path", "").orEmpty()
        timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(10, 900).toInt()
    }

    fun refresh() {
        if (!isEnabled()) {
            _snapshot.value = Snapshot(State.DISABLED, message =
                "未开启；android-root-cli 请求会先发送一次授权请求，不会自动开启。")
            return
        }
        val path = RootExecutor(config()).resolveSu()
        val previous = _snapshot.value
        _snapshot.value = when {
            path == null -> Snapshot(State.NOT_FOUND, message = "No executable su found; Root is not available")
            previous.suPath == path && previous.state in setOf(State.READY, State.DENIED) -> previous
            else -> Snapshot(State.NEED_PERMISSION, path)
        }
    }

    /**
     * Ask the user once for this agent invocation. Returns null when the grant
     * is off and the user refused, dismissed or timed out — nothing runs then.
     * Never called for [RootGrantPolicy.Caller.SYSTEM].
     */
    fun requestAccessFromAgent(
        argv: Array<String>,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
    ): AccessTicket? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val grantedEpoch = OffloadPermissionManager.rootAuthorizationEpoch()
        if (isEnabled()) return AccessTicket(grantedEpoch)
        return runBlocking {
            agentAccessLock.withLock {
                // Another invocation may have been granted (or the user may
                // have enabled Root) while this one waited: never re-prompt.
                if (isEnabled()) return@withLock AccessTicket(
                    OffloadPermissionManager.rootAuthorizationEpoch(),
                )
                val epoch = OffloadPermissionManager.rootAuthorizationEpoch()
                val command = ShellCommand.joinQuoted(argv.toList())
                val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 900).toInt()
                val allowed = OffloadPermissionManager.confirmPrivilegedOperation(
                    "root_cli",
                    command,
                    rootAgentAccessRequest(cwd.orEmpty(), timeoutSec),
                )
                val current = OffloadPermissionManager.rootAuthorizationEpoch()
                if (!allowed) return@withLock null
                // Refuse an approval the user invalidated while the dialog
                // was open (they revoked or reset permissions).
                if (current != epoch && !isEnabled()) return@withLock null
                AccessTicket(current)
            }
        }
    }

    /**
     * Every invocation goes through the same gate.
     *
     * @param ticket approval obtained by [requestAccessFromAgent] for a
     *   multi-command CLI invocation; null means "answer for this command".
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>? = null,
        cwd: String? = null,
        timeoutMs: Long = 5_000,
        caller: RootGrantPolicy.Caller = RootGrantPolicy.Caller.AGENT,
        ticket: AccessTicket? = null,
    ): ShizukuManager.ProcessResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return failure("Root commands cannot run on the UI thread")
        if (argv.isEmpty() || argv.any { '\u0000' in it }) return failure("Invalid argv")
        // Host environment is intentionally not imported from the Linux guest.
        if (!env.isNullOrEmpty()) return failure("Custom environment is not accepted by the native Root backend")

        // The UI text, the executor override and the kill deadline must all
        // describe the same number; the old copy showed 1s for a 5s request
        // clamped to 10s by RootExecutorConfig.
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 900).toInt()

        val epoch = OffloadPermissionManager.rootAuthorizationEpoch()
        val decision = RootGrantPolicy.decide(
            granted = isEnabled(),
            caller = caller,
            ticketEpoch = ticket?.epoch,
            currentEpoch = epoch,
        )
        val approval: AccessTicket? = when (decision) {
            RootGrantPolicy.Decision.USE_GRANT ->
                ticket?.takeIf { it.epoch == epoch } ?: AccessTicket(epoch)
            RootGrantPolicy.Decision.REQUEST_ACCESS ->
                requestAccessFromAgent(argv, cwd, timeoutMs)
            RootGrantPolicy.Decision.REFUSE -> null
        }
        if (approval == null) {
            return failure(
                if (caller == RootGrantPolicy.Caller.SYSTEM) ROOT_DISABLED_FOR_SYSTEM
                else ROOT_AGENT_REQUEST_DENIED,
            )
        }

        val request = WireProtocol.Request(argv.toList(), cwd.orEmpty(), false)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit = 126
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        // Capture never answers "client gone": exceeding the cap truncates the
        // stream instead of making RootExecutor kill the running command.
        fun captureInto(out: ByteArrayOutputStream, data: ByteArray): Boolean {
            synchronized(out) {
                val remaining = MAX_CAPTURE_BYTES - out.size()
                if (remaining <= 0) {
                    truncated.set(true)
                    return true
                }
                val take = minOf(remaining, data.size)
                out.write(data, 0, take)
                if (take < data.size) truncated.set(true)
            }
            return true
        }
        val executor = RootExecutor(config(timeoutMs), timeoutSec, RootExecutor.Authorizer {
            // Re-checked immediately before su: a revocation between the gate
            // and this point must stop the command from starting.
            RootGrantPolicy.ticketStillValid(
                approval.epoch,
                OffloadPermissionManager.rootAuthorizationEpoch(),
            )
        })
        executor.execute(request, object : RootExecutor.Sink {
            override fun stdout(data: ByteArray): Boolean = captureInto(stdout, data)
            override fun stderr(data: ByteArray): Boolean = captureInto(stderr, data)
            override fun finish(code: Int) { exit = code }
        })
        val stderrText = buildString {
            append(stderr.toString("UTF-8"))
            if (truncated.get()) {
                if (isNotEmpty()) append('\n')
                append("[minis-root: output truncated at 2 MiB per stream; the command was not killed]\n")
            }
        }
        val result = ShizukuManager.ProcessResult(exit, stdout.toString("UTF-8"), stderrText)
        if (argv.contentEquals(arrayOf("/system/bin/id")) || argv.contentEquals(arrayOf("id"))) {
            val uid = if (exit == 0) ShellCommand.parseUid(result.stdout) else -1
            _snapshot.value = Snapshot(
                if (uid == 0) State.READY else State.DENIED,
                executor.resolveSu(),
                uid,
                if (uid == 0) "Root verified; subsequent commands use this grant directly"
                else result.combined,
            )
        }
        return result
    }

    private fun failure(message: String) = ShizukuManager.ProcessResult(126, "", message)
}
