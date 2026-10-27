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
 * Root is a tri-state permission stored by [OffloadPermissionManager], the
 * same model `android-shizuku-cli` uses (Settings → Permissions → 原生 Root):
 *
 *  - 允许 ([RootAccess.BYPASS]) — `android-root-cli` and the Chroot backend use
 *    it directly, no per-command app dialog. The device's su manager keeps its
 *    own prompt; this object never tries to bypass it.
 *  - 询问 ([RootAccess.ASK_ONCE]) — one confirmable request per invocation. An
 *    [RootCaller.AGENT] call (agent CLI) or an explicit [RootCaller.USER]
 *    action (the chroot switch the user pressed) raises the dialog, and the
 *    answer covers THAT call only; an automatic [RootCaller.SYSTEM] path
 *    (autostart, probe) never prompts and never runs su. Refusal, cancel or
 *    timeout runs nothing and never changes the level.
 *  - 不允许 ([RootAccess.NOT_ALLOWED]) — everything is refused, with a settings
 *    hint for the system paths; no Root dialog is ever shown.
 *
 * Default: 每次询问, always. A successful su probe only records that Root is
 * usable (status text); it never promotes the level. 完全允许 is persisted only
 * by an explicit user selection.
 */
object RootManager {
    enum class State { DISABLED, NOT_FOUND, NEED_PERMISSION, READY, DENIED }
    data class Snapshot(val state: State, val suPath: String? = null, val uid: Int = -1,
                        val message: String = "Not tested; no su command executed")
    private val _snapshot = MutableStateFlow(Snapshot(State.DISABLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    @Volatile private var appContext: Context? = null
    private const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024

    /** When the auto-detection probe last ran (native_root prefs). */
    private const val KEY_AUTO_PROBE_AT = "auto_default_probe_at"

    /** A device without Root is re-probed at most this often, not per launch. */
    private const val AUTO_PROBE_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /**
     * An approval for one agent invocation (or one Chroot switch). [epoch]
     * pins it to the state the user answered under: revoking 允许, downgrading
     * to 询问/不允许, or a Reset All bumps the epoch and every outstanding
     * ticket stops working.
     */
    class AccessTicket internal constructor(internal val epoch: Long)

    /** One dialog at a time; a queued call re-reads the level after waiting. */
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

    /** The effective tri-state level (允许 / 询问 / 不允许). Never probes su. */
    fun accessLevel(): RootAccess = OffloadPermissionManager.rootLevel()

    /** True only for 允许; the state that executes without a per-call dialog. */
    fun isEnabled(): Boolean = isEnabled(accessLevel())

    private fun isEnabled(level: RootAccess) = level == RootAccess.BYPASS

    /** Change the level; an explicit user choice is remembered as such. */
    fun setEnabled(enabled: Boolean) = setAccessLevel(
        if (enabled) RootAccess.BYPASS else RootAccess.NOT_ALLOWED,
    )

    fun setAccessLevel(level: RootAccess, byUser: Boolean = true) {
        OffloadPermissionManager.setRootLevel(level, byUser = byUser)
        if (level != RootAccess.BYPASS) {
            // The in-process chroot grant was authorized by 完全允许; drop it so
            // a later switch has to go through the gate again.
            runCatching { com.openminis.app.sandbox.SandboxBackendStore.revokeChrootGrant() }
            // A downgrade must not leave a long-lived uid-0 shell or a running
            // chroot command behind. Stopping is best effort: a process that
            // cannot be reached still cannot be used for NEW dispatches, which
            // re-check the level in ChrootKernel.spawn.
            runCatching { com.openminis.app.sandbox.ExecutionCoordinator.stopCurrentCommand() }
            runCatching { RootExecPermission.clear() }
            // The helper may outlive this process; its root token must not.
            // A later authorised spawn replaces the helper.
            runCatching { com.openminis.app.sandbox.ChrootKernel.forgetRootTokens() }
            // 不允许 also means "stop bringing the helper back by itself".
            appContext?.let {
                runCatching { com.openminis.app.sandbox.SandboxBackendStore.setChrootAutostart(it, false) }
            }
        }
        refresh()
    }

    /** Revoke from anywhere (e.g. reset-all); identical to 不允许. */
    fun revoke() = setEnabled(false)

    private fun config(timeoutMs: Long = 120_000): RootExecutorConfig = RootExecutorConfig().apply {
        // Only reached after the gate below approved this exact call.
        enabled = true
        suPath = appContext?.getSharedPreferences("native_root", Context.MODE_PRIVATE)
            ?.getString("su_path", "").orEmpty()
        timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(10, 900).toInt()
    }

    fun refresh() {
        val level = accessLevel()
        if (level != RootAccess.BYPASS) {
            _snapshot.value = Snapshot(State.DISABLED, message = when (level) {
                RootAccess.ASK_ONCE ->
                    "为“每次询问”；android-root-cli 每次调用、chroot 每条命令/每个终端都各询问一次。"
                else ->
                    "为“不允许”；Root 操作会被拒绝，可在 设置 → 权限 → 原生 Root 中改为“完全允许”。"
            })
            return
        }
        val path = RootExecutor(config()).resolveSu()
        val previous = _snapshot.value
        _snapshot.value = when {
            path == null -> Snapshot(State.NOT_FOUND, message = "No executable su found; Root is not available")
            // Root was verified on this install already: report 已授权 right
            // away instead of "等待 Root 授权", which made the state look lost
            // after every restart until the user pressed 验证 Root（id） again.
            OffloadPermissionManager.rootDetectedOnce() -> Snapshot(
                State.READY,
                path,
                -1,
                "Root 已验证可用；完全允许，命令直接执行",
            )
            previous.suPath == path && previous.state in setOf(State.READY, State.DENIED) -> previous
            else -> Snapshot(State.NEED_PERMISSION, path)
        }
    }

    /**
     * Ask the user once. Under 询问 this shows ONE confirmable request and
     * answers for THIS invocation only. Returns null when nothing may run:
     * 不允许, a refusal, a cancel or a timeout. Never flips the stored level.
     *
     * [RootAccess.BYPASS] returns a ticket immediately and never prompts.
     */
    fun requestAccessFromAgent(
        argv: Array<String>,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
    ): AccessTicket? = requestAccess(
        argv = argv,
        cwd = cwd,
        timeoutMs = timeoutMs,
        caller = RootCaller.AGENT,
    )

    /**
     * The one shared gate. Used by the agent CLI and by the Chroot switch:
     * 询问 asks exactly one confirmable request, 不允许 refuses, 允许 runs.
     */
    fun requestAccess(
        argv: Array<String>,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
        caller: RootCaller = RootCaller.AGENT,
    ): AccessTicket? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val level = accessLevel()
        val epoch = OffloadPermissionManager.rootAuthorizationEpoch()
        when (RootGrantPolicy.decide(level, caller, ticketEpoch = null, currentEpoch = epoch)) {
            RootDecision.EXECUTE -> return AccessTicket(epoch)
            RootDecision.DENY, RootDecision.SETTINGS_HINT -> return null
            RootDecision.REQUEST_ONCE -> Unit // fall through to the dialog
        }
        return runBlocking {
            agentAccessLock.withLock {
                // The level (or the epoch) may have moved while this call
                // waited: never ask for a state the user already left.
                val now = OffloadPermissionManager.rootAuthorizationEpoch()
                when (RootGrantPolicy.decide(accessLevel(), caller, ticketEpoch = null, currentEpoch = now)) {
                    RootDecision.EXECUTE -> return@withLock AccessTicket(now)
                    RootDecision.DENY, RootDecision.SETTINGS_HINT -> return@withLock null
                    RootDecision.REQUEST_ONCE -> Unit
                }
                val command = ShellCommand.joinQuoted(argv.toList())
                val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 900).toInt()
                val body = when (caller) {
                    RootCaller.AGENT -> rootAgentAccessRequest(cwd.orEmpty(), timeoutSec)
                    // An explicit in-app action (chroot switch). One launch
                    // approval for that action; the level is not changed.
                    else -> rootChrootSwitchRequest(timeoutSec)
                }
                val allowed = OffloadPermissionManager.confirmPrivilegedOperation(
                    "root_cli",
                    command,
                    body,
                )
                val current = OffloadPermissionManager.rootAuthorizationEpoch()
                if (!allowed) return@withLock null
                // Refuse an approval the user invalidated while the dialog was
                // open (they revoked, downgraded or reset permissions).
                if (current != now && accessLevel() != RootAccess.BYPASS) return@withLock null
                // [FEAT-261027-ROOT explicit] A single approval authorises this
                // call only. It does NOT persist 完全允许: that level is
                // written exclusively by an explicit user selection.
                if (caller == RootCaller.USER) refresh()
                AccessTicket(current)
            }
        }
    }

    /**
     * Every invocation goes through the same gate.
     *
     * @param ticket approval obtained by [requestAccess] for a multi-command
     *   CLI invocation; null means "answer for this command".
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>? = null,
        cwd: String? = null,
        timeoutMs: Long = 5_000,
        caller: RootCaller = RootCaller.AGENT,
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
        val level = accessLevel()
        val approval: AccessTicket? = when (
            RootGrantPolicy.decide(level, caller, ticketEpoch = ticket?.epoch, currentEpoch = epoch)
        ) {
            RootDecision.EXECUTE ->
                ticket?.takeIf { RootGrantPolicy.ticketStillValid(it.epoch, epoch) }
                    ?: AccessTicket(epoch)
            RootDecision.REQUEST_ONCE -> requestAccess(argv, cwd, timeoutMs, caller)
            RootDecision.DENY, RootDecision.SETTINGS_HINT -> null
        }
        if (approval == null) {
            val settingsHint = RootGrantPolicy.decide(
                level, caller, ticketEpoch = ticket?.epoch, currentEpoch = epoch,
            ) == RootDecision.SETTINGS_HINT
            return failure(
                if (settingsHint || caller == RootCaller.SYSTEM) ROOT_DISABLED_FOR_SYSTEM
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
            // A real Root capability was just proven. Detection only records
            // the fact for the status line; it never promotes the level.
            val detected = uid == 0
            OffloadPermissionManager.applyDetectedRootDefault(detected)
            if (detected) {
                val label = when (accessLevel()) {
                    RootAccess.BYPASS -> "完全允许，命令直接执行"
                    RootAccess.ASK_ONCE -> "每次询问：每条命令/每个终端仍会各确认一次"
                    RootAccess.NOT_ALLOWED -> "当前为不允许，实际执行会被拒绝"
                }
                _snapshot.value = Snapshot(State.READY, executor.resolveSu(), uid, "Root 已验证；$label")
            } else {
                _snapshot.value = Snapshot(State.DENIED, executor.resolveSu(), uid, result.combined)
            }
        }
        return result
    }

    /**
     * [T-android-root-default] "Detected Root → 允许, recorded once."
     *
     * The user asked not to re-authorise Root on every launch. So the very
     * first time this process can see a usable `su`, it verifies Root exactly
     * once (`su -c id`), and on uid 0 persists 允许 through
     * [OffloadPermissionManager.applyDetectedRootDefault]. From then on the
     * stored level is the user's state, and no probe ever overwrites it.
     *
     * Deliberately NOT routed through the level gate: 询问 would otherwise
     * turn the detection into the per-launch dialog this removes. The device's
     * su manager keeps its own prompt — a device that has not granted Root
     * records `detected = false`, writes no level, and is retried after
     * [AUTO_PROBE_COOLDOWN_MS] instead of on every app start.
     *
     * @return true when this call wrote the 允许 default.
     */
    fun ensureDetectedDefault(): Boolean {
        val ctx = appContext ?: return false
        // [FEAT-261027-ROOT explicit] Detection no longer runs su on its own:
        // under 每次询问 that would either prompt from a background thread or
        // execute silently, and both are wrong. The settings page's 验证 Root
        //（id） is the one place that may execute `id`, and only as an explicit
        // user action. This keeps the refresh for the status line.
        refresh()
        return false
    }

    /** Old body kept out of the build path; see [ensureDetectedDefault]. */
    private fun ensureDetectedDefaultLegacy(): Boolean {
        val ctx = appContext ?: return false
        // 原生 Root 只验证一次：su was already proven usable on this install,
        // so there is nothing left to detect and nothing to ask again — but the
        // su channel is still warmed up once per launch, so the first real
        // command does not have to pay the su-manager grant. This is what used
        // to require pressing 验证 Root（id） after every restart.
        if (OffloadPermissionManager.rootDetectedOnce()) {
            warmUp()
            return false
        }
        // An explicit 不允许 is final; 询问 does not block detection (an old
        // build's stored default must not turn into a permanent prompt).
        if (OffloadPermissionManager.isRootLevelUserChosen() &&
            OffloadPermissionManager.rootLevel() == RootAccess.NOT_ALLOWED
        ) {
            return false
        }
        val prefs = ctx.getSharedPreferences("native_root", Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_AUTO_PROBE_AT, 0L)
        val now = System.currentTimeMillis()
        if (last > 0 && now - last in 0 until AUTO_PROBE_COOLDOWN_MS) return false
        prefs.edit().putLong(KEY_AUTO_PROBE_AT, now).apply()
        // resolveSu only walks candidate paths; it never executes su.
        if (RootExecutor(config()).resolveSu() == null) {
            OffloadPermissionManager.recordRootProbe(false)
            return false
        }
        val detected = probeId()
        val written = OffloadPermissionManager.applyDetectedRootDefault(detected)
        refresh()
        return written
    }

    /**
     * One `id` through the normal execution path, no in-app dialog.
     *
     * Runs on the detector thread (never the UI thread) and only after the
     * level is already 允许, so it cannot raise a permission prompt of its own;
     * the device su manager may still show its usual grant dialog.
     */
    private fun warmUp() {
        runCatching {
            runProcess(
                arrayOf("/system/bin/id"),
                timeoutMs = 30_000,
                caller = RootCaller.SYSTEM,
            )
            refresh()
        }
    }

    /**
     * Fire-and-forget wrapper kept for startup callers. It no longer runs su:
     * [ensureDetectedDefault] only refreshes the status snapshot.
     */
    fun startDetectedDefaultProbe() {
        Thread({ runCatching { ensureDetectedDefault() } }, "minis-root-status")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * A ticket for an action the caller has ALREADY shown the user and the
     * user approved (the chroot CLI's own confirmation / the per-command Root
     * dialog). It authorises only this epoch; it does not change the stored
     * level.
     *
     * [actionKey] is the [RootExecRequest.fingerprint] of the approved action.
     * A ticket is only minted while a confirmation for exactly that key is on
     * record, so no caller can conjure one out of the current level.
     */
    private data class ConfirmedAction(val key: String, val epoch: Long, val atMs: Long)

    private val lastConfirmedAction =
        java.util.concurrent.atomic.AtomicReference<ConfirmedAction?>(null)

    /** Remember an approval JUST obtained from the user for [actionKey]. */
    internal fun recordConfirmedAction(
        actionKey: String,
        epoch: Long = OffloadPermissionManager.rootAuthorizationEpoch(),
        atMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        lastConfirmedAction.set(ConfirmedAction(actionKey, epoch, atMs))
    }

    /**
     * Ticket for launching the helper as part of an action the user just
     * approved (one dialog covers both the su launch and the command).
     *
     * Returns null unless a fresh confirmation for [actionKey] exists under
     * the current epoch and the level is not 不允许.
     */
    fun ticketForConfirmedAction(
        actionKey: String,
        maxAgeMs: Long = RootExecPolicy.GRANT_TTL_MS,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): AccessTicket? {
        if (accessLevel() == RootAccess.NOT_ALLOWED) return null
        val recorded = lastConfirmedAction.get() ?: return null
        if (recorded.key != actionKey) return null
        if (nowMs - recorded.atMs !in 0..maxAgeMs) return null
        val epoch = OffloadPermissionManager.rootAuthorizationEpoch()
        if (recorded.epoch != epoch) return null
        // SINGLE USE: the confirmation covers one helper launch. A later caller
        // must not re-mint from the same answer (that would be a cache).
        if (!lastConfirmedAction.compareAndSet(recorded, null)) return null
        return AccessTicket(epoch)
    }

    /** One `su -c id`, captured like a normal command but with no gate. */
    private fun probeId(): Boolean {
        val out = ByteArrayOutputStream()
        var exit = 126
        val executor = RootExecutor(config(15_000), 15, RootExecutor.Authorizer { true })
        return try {
            executor.execute(
                WireProtocol.Request(listOf("/system/bin/id"), "", false),
                object : RootExecutor.Sink {
                    override fun stdout(data: ByteArray): Boolean {
                        synchronized(out) {
                            if (out.size() < MAX_CAPTURE_BYTES) {
                                out.write(data, 0, minOf(MAX_CAPTURE_BYTES - out.size(), data.size))
                            }
                        }
                        return true
                    }

                    override fun stderr(data: ByteArray): Boolean = true

                    override fun finish(code: Int) { exit = code }
                },
            )
            exit == 0 && ShellCommand.parseUid(out.toString("UTF-8")) == 0
        } catch (t: Throwable) {
            false
        }
    }

    private fun failure(message: String) = ShizukuManager.ProcessResult(126, "", message)
}
