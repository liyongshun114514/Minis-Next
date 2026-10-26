package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [FEAT-261025-CHROOT] Which execution backend runs the guest.
 *
 * Both backends share ONE rootfs. The rootfs is the guest's `/` either way;
 * PRoot fakes that mapping with ptrace, chroot gets it from the kernel. So
 * switching backends never migrates, copies or deletes user data — that is a
 * stated requirement, and [SandboxSwitchPolicy] is written so that the only
 * backend-specific state is a marker file and the generated socket name.
 */
enum class SandboxBackend {
    /** Default. Unprivileged, ptrace-based, carries native offload. */
    PROOT,

    /**
     * Real `chroot(2)` in a private mount namespace. Needs a runtime root
     * grant for the helper's handshake; every later command rides the
     * already-privileged helper over a local socket and carries no user data
     * in the privileged argv.
     */
    CHROOT,
    ;

    companion object {
        /** [FEAT-261025-CHROOT] PRoot stays the default: chroot needs root. */
        val DEFAULT: SandboxBackend = PROOT

        fun fromStored(raw: String?): SandboxBackend =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** [FEAT-261025-CHROOT] How `/usr/local/bin/minis-*` tools are served. */
enum class SandboxOffloadRoute {
    /** proot's native_offload extension intercepts the execve (PRoot backend). */
    PROOT_NATIVE,

    /**
     * The guest-side client (docs/android-chroot.md §5) speaks the same wire
     * protocol to [NativeOffloadServer] over the abstract socket. Every Minis
     * tool keeps working, as the same app uid.
     */
    GUEST_CLIENT,

    /**
     * The guest client is not installed. Stubs are left in place that say so
     * and exit 69 — the backend NEVER claims Android/Minix tools work when
     * they do not.
     */
    BLOCKED,
}

/**
 * [FEAT-261025-CHROOT] A task that a backend switch would strand.
 *
 * Collected by [SandboxActivityRegistry] from the four spawn sites, so a
 * switch can REFUSE instead of silently cutting a running command. [stop] is
 * only ever invoked after the user explicitly confirms "stop and switch".
 */
data class ActiveSandboxTask(
    val id: String,
    val label: String,
    val stop: () -> Unit,
)

/**
 * [FEAT-261025-CHROOT] Live-command registry.
 *
 * Deliberately small: the shell classes report begin/end, and the switch
 * controller reads the snapshot. It is NOT a scheduler and holds no locks
 * across execution.
 */
object SandboxActivityRegistry {
    private val tasks = ConcurrentHashMap<String, ActiveSandboxTask>()

    fun begin(task: ActiveSandboxTask): String {
        tasks[task.id] = task
        return task.id
    }

    fun end(id: String) {
        tasks.remove(id)
    }

    fun snapshot(): List<ActiveSandboxTask> = tasks.values.sortedBy { it.id }

    /** Test seam: never call from production code. */
    internal fun clearForTest() = tasks.clear()
}

/**
 * [FEAT-261025-CHROOT] Why chroot may or may not be offered right now.
 *
 * Every field is something the UI can show verbatim. [launcherInstalled] and
 * [offloadClientInstalled] are file probes — no root, no exec. [bridgePresent]
 * is a Class.forName on the root bridge the main agent integrates; absent
 * means switching is refused with an explicit reason rather than failing at
 * the first command.
 */
data class ChrootAvailability(
    val launcherInstalled: Boolean,
    val offloadClientInstalled: Boolean,
    val bridgePresent: Boolean,
    val rootManagerDetected: Boolean,
    val serverRunning: Boolean,
    val lastError: String? = null,
) {
    /** Everything needed to build a chroot command line exists. */
    val canRun: Boolean get() = launcherInstalled && bridgePresent

    /** One line the settings UI can print without inventing optimism. */
    fun summary(): String = when {
        !launcherInstalled ->
            "chroot helper not installed in this build (libminis-chroot.so)"
        !bridgePresent ->
            "root bridge not available — chroot needs the Minis root bridge"
        !serverRunning ->
            "chroot helper is not confirmed running (no grant, or its idle TTL expired) — " +
                "switch to chroot again to start it"
        !offloadClientInstalled ->
            "chroot ready; Minis tools BLOCKED (guest offload client not installed)"
        else -> "chroot ready; Minis tools via guest offload client"
    }
}

/**
 * [FEAT-261025-CHROOT] The switch decision, as a value.
 *
 * Pure so it can be unit-tested without a device: the policy is the part
 * that must never silently interrupt work, and it is also the part that is
 * easiest to get wrong.
 */
sealed class BackendSwitchDecision {
    data class NoChange(val backend: SandboxBackend) : BackendSwitchDecision()

    /**
     * Tasks are running. The caller must either stop them (with the user's
     * confirmation) and retry with [stopActive] = true, or leave the backend
     * alone. Nothing is killed here.
     */
    data class RefusedActiveTasks(
        val backend: SandboxBackend,
        val tasks: List<ActiveSandboxTask>,
    ) : BackendSwitchDecision()

    data class RefusedNotAuthorized(val backend: SandboxBackend, val reason: String) : BackendSwitchDecision()

    data class RefusedUnavailable(val backend: SandboxBackend, val reason: String) : BackendSwitchDecision()

    /** Proceed. [stopTasks] is non-empty only when the user confirmed stopping. */
    data class Allowed(
        val backend: SandboxBackend,
        val from: SandboxBackend,
        val stopTasks: List<ActiveSandboxTask>,
        val requiresRootGrant: Boolean,
    ) : BackendSwitchDecision()
}

/**
 * [FEAT-261025-CHROOT] The rules, without any Android dependency.
 *
 * @param authorizedThisProcess a chroot grant exists for THIS app process
 *   (see [SandboxBackendStore.grantChrootForProcess]). Selecting chroot in
 *   Settings persists the choice; the grant is deliberately not persisted, so
 *   a reboot or an app restart cannot inherit root.
 */
object SandboxSwitchPolicy {
    fun decide(
        current: SandboxBackend,
        target: SandboxBackend,
        activeTasks: List<ActiveSandboxTask>,
        availability: ChrootAvailability,
        authorizedThisProcess: Boolean,
        stopActive: Boolean,
        rootAccessGranted: Boolean = true,
    ): BackendSwitchDecision {
        if (current == target && (target != SandboxBackend.CHROOT || availability.serverRunning)) {
            return BackendSwitchDecision.NoChange(current)
        }

        if (target == SandboxBackend.CHROOT) {
            if (!availability.launcherInstalled) {
                return BackendSwitchDecision.RefusedUnavailable(
                    target,
                    "chroot helper (libminis-chroot.so) is not packaged in this build",
                )
            }
            if (!availability.bridgePresent) {
                return BackendSwitchDecision.RefusedUnavailable(
                    target,
                    "the Minis root bridge is unavailable — chroot needs a runtime root grant",
                )
            }
            if (!rootAccessGranted) {
                // Never enable Root implicitly from a backend switch, and never
                // pop a Root dialog here: point at the permission that does.
                return BackendSwitchDecision.RefusedNotAuthorized(
                    target,
                    com.openminis.app.offload.ROOT_DISABLED_FOR_SYSTEM,
                )
            }
        }

        if (activeTasks.isNotEmpty()) {
            if (!stopActive) {
                return BackendSwitchDecision.RefusedActiveTasks(target, activeTasks)
            }
            // stopActive == true is only reachable from the dialog that names
            // the tasks. The caller stops them; this decision says which.
        }

        return BackendSwitchDecision.Allowed(
            backend = target,
            from = current,
            stopTasks = if (stopActive) activeTasks else emptyList(),
            // Switching TO chroot (or back after the grant lapsed) needs the
            // root bridge's per-command confirmation. Switching to PRoot never
            // does — that is the safe direction and must stay one tap.
            requiresRootGrant = target == SandboxBackend.CHROOT && !authorizedThisProcess,
        )
    }
}

/**
 * [FEAT-261025-CHROOT] Persisted backend selection + the non-persisted grant.
 *
 * What is persisted: which backend the user chose, when they accepted the
 * risk, the last chroot failure (so the UI can explain a fallback), and the
 * generation nonce that names the helper's socket. What is NOT persisted: the
 * root grant itself. See [grantChrootForProcess].
 */
object SandboxBackendStore {
    private const val TAG = "SandboxBackendStore"
    private const val PREFS = "sandbox_backend"
    private const val KEY_SELECTED = "selected"
    private const val KEY_AUTHORIZED_AT = "chroot_authorized_at"
    private const val KEY_GENERATION = "chroot_generation"
    private const val KEY_LAST_ERROR = "chroot_last_error"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selected(context: Context): SandboxBackend =
        SandboxBackend.fromStored(prefs(context).getString(KEY_SELECTED, null))

    fun authorizedAt(context: Context): Long = prefs(context).getLong(KEY_AUTHORIZED_AT, 0L)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    /**
     * Persist the user's choice. [authorizedAt] is written only when the user
     * explicitly accepted the risk dialog; it is never cleared by a fallback,
     * so the UI can still say "you enabled this on <date>".
     */
    fun setSelected(context: Context, backend: SandboxBackend, authorizedAt: Long? = null) {
        prefs(context).edit().putString(KEY_SELECTED, backend.name).apply {
            if (authorizedAt != null) putLong(KEY_AUTHORIZED_AT, authorizedAt)
        }.apply()
        Log.i(TAG, "selected backend=$backend authorizedAt=${authorizedAt ?: authorizedAt(context)}")
    }

    fun setLastError(context: Context, message: String?) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
    }

    /**
     * [FEAT-261025-CHROOT] Persist ONLY the risk acknowledgement.
     *
     * [setSelected] stays the single writer of the selected backend, so the
     * acknowledgement cannot make the store claim a backend that was never
     * started. See [SandboxSwitchController.authorizeChroot].
     */
    fun setAuthorizedAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_AUTHORIZED_AT, at).apply()
    }

    /**
     * The helper's socket name / challenge nonce. Regenerated on every helper
     * start so a stale process can never be mistaken for the live one, and so
     * nobody can connect to a name learned from an earlier run. Persisted only
     * for diagnostics.
     */
    fun generation(context: Context): String {
        val existing = prefs(context).getString(KEY_GENERATION, null)
        if (!existing.isNullOrEmpty()) return existing
        val fresh = ChrootKernel.newGeneration()
        prefs(context).edit().putString(KEY_GENERATION, fresh).apply()
        return fresh
    }

    fun rotateGeneration(context: Context): String {
        val fresh = ChrootKernel.newGeneration()
        prefs(context).edit().putString(KEY_GENERATION, fresh).apply()
        return fresh
    }

    /**
     * [FEAT-261025-CHROOT] In-memory root grant, per app process.
     *
     * Not a `su` flag and not a persisted boolean: it records that the root
     * bridge answered the helper handshake for [generation] in THIS process.
     * A restart, a kill, or a switch back to PRoot drops it (see
     * [revokeChrootGrant]), so "chroot enabled" can never mean "root forever".
     */
    @Volatile
    private var grantedGeneration: String? = null

    /**
     * [FEAT-261025-CHROOT] When the grant was recorded, on the monotonic
     * clock. The grant is a fact about THIS process, but the helper it names
     * enforces its own idle TTL ([ChrootKernel.DEFAULT_IDLE_TTL_SECONDS]) and
     * may already have exited. Recording the time lets readers tell "definitely
     * active" from "possibly gone" instead of reporting a stale boolean.
     */
    @Volatile
    private var grantedAtElapsedMs: Long = 0L

    fun grantChrootForProcess(generation: String) {
        grantedGeneration = generation
        grantedAtElapsedMs = monotonicMs()
        Log.i(TAG, "chroot runtime grant present for generation=$generation")
    }

    /** Monotonic milliseconds; JVM-pure so the store stays unit-testable. */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    fun chrootGranted(generation: String): Boolean = grantedGeneration == generation

    /**
     * [FEAT-261025-CHROOT] True when the helper may have exited on its idle
     * TTL even though [chrootGranted] still reports a grant.
     *
     * This is deliberately a *lower bound*, not a liveness test: the helper
     * refreshes its TTL on every accepted connection, and a long-running
     * command keeps its handler alive past this point. So callers must use it
     * only to decide that a handshake is needed — [ChrootKernel.ensureServer]
     * probes an unprivileged `/bin/sh -c true` before it will start a second
     * helper — never to claim the helper is dead.
     *
     * A small margin covers the gap between the handshake and our timestamp;
     * it is capped at half the TTL so a short TTL stays meaningful.
     */
    fun chrootGrantMayBeExpired(
        generation: String,
        idleTtlSeconds: Int = ChrootKernel.DEFAULT_IDLE_TTL_SECONDS,
    ): Boolean {
        if (grantedGeneration != generation) return false
        val recordedAt = grantedAtElapsedMs
        if (recordedAt <= 0L) return false
        val ttlMs = idleTtlSeconds.coerceAtLeast(1) * 1000L
        val marginMs = 60_000L.coerceAtMost(ttlMs / 2)
        return monotonicMs() - recordedAt >= ttlMs - marginMs
    }

    fun revokeChrootGrant() {
        if (grantedGeneration != null) Log.i(TAG, "chroot runtime grant revoked")
        grantedGeneration = null
        grantedAtElapsedMs = 0L
    }
}

/**
 * [FEAT-261025-CHROOT] Everything a spawn site needs, backend-agnostic.
 *
 * `argv` is the full command line including argv[0]; `env` is merged over the
 * ambient environment. [offload] tells the caller (and the UI) how Minis tools
 * are being served, and is never optimistic: see [SandboxOffloadRoute].
 */
data class SandboxSpawn(
    val backend: SandboxBackend,
    val argv: List<String>,
    val env: Map<String, String>,
    val offload: SandboxOffloadRoute,
    val notes: List<String> = emptyList(),
    /**
     * [FEAT-261025-UBUNTU] User-visible one-liner for a first-run condition that
     * would otherwise look like "the sandbox has no python/git". Null when
     * nothing is wrong; callers prepend it to the command output.
     */
    val warning: String? = null,
)

/** [FEAT-261025-CHROOT] What a spawn site asks for. */
data class SandboxCommandSpec(
    /** Shell string. Ignored when [interactive] is true. */
    val command: String,
    /**
     * Non-null on the fresh-process path: where the in-guest wrapper reports
     * the exit status (see `ForegroundCommandGroup`). The PRoot backend appends
     * the wrapper; the chroot backend does not need it — the helper reports the
     * real child status itself and Stop/timeout kill the process group.
     */
    val statusGuestPath: String? = null,
    /** Long-lived `<shell>` with no `-c` (warm shell / terminal). */
    val interactive: Boolean = false,
    /** Login+interactive flags for the terminal (`/bin/sh -l -i`). */
    val loginShell: Boolean = false,
    val sessionId: String? = null,
    /**
     * Exact mount table for this shell. `null` means the process-global
     * [PRootKernel.bindMounts] (what `shell_execute` has always used); a
     * non-null map is the session's merged table (see `SessionMounts`).
     */
    val sessionMounts: Map<String, String>? = null,
)

/**
 * [FEAT-261025-CHROOT] Single routing point for every backend.
 *
 * The four spawn sites (`ShellExecutor`, `FreshProcessShell`,
 * `PersistentShell`, `TerminalSession`) all call this, so the two backends can
 * never drift apart the way `--fake-netlink` once did (see
 * [ProotFlagParityTest]). PRoot keeps calling [PRootKernel.buildProotCommand]
 * unchanged — the public API of that object is preserved for every existing
 * caller, and the parity test pins the argv.
 */
object SandboxRouter {
    private const val TAG = "SandboxRouter"

    fun build(context: Context, spec: SandboxCommandSpec): SandboxSpawn =
        when (SandboxBackendStore.selected(context)) {
            SandboxBackend.PROOT -> withBootstrapHint(context, spec, prootSpawn(context, spec))
            SandboxBackend.CHROOT -> ChrootKernel.spawn(context, spec)
        }

    /**
     * [FEAT-261025-UBUNTU] Ubuntu rootfs bootstrap is done by the FIRST
     * INTERACTIVE shell, so a non-interactive agent command can run before
     * python/git exist. Saying nothing would let the agent conclude the tool is
     * missing from the image.
     *
     * We deliberately do NOT run `minis-bootstrap` from here: it is an apt
     * install that can take minutes, would run under the tool call's timeout,
     * and retrying it on every command is exactly the loop the requirement
     * forbids. The caller gets one explicit sentence pointing at the terminal
     * command, and [RootfsManager.isBootstrapFailed] turns it into a retry hint
     * instead of an endless "initialising…".
     */
    private fun withBootstrapHint(
        context: Context,
        spec: SandboxCommandSpec,
        spawn: SandboxSpawn,
    ): SandboxSpawn {
        if (spec.interactive) return spawn
        val manager = runCatching { RootfsManager.getInstance(context) }.getOrNull() ?: return spawn
        if (manager.isBootstrapComplete) return spawn
        val text = "内置 Ubuntu 工具环境未通过完整性检查。此版本应离线解压预装环境，" +
            "无需进入终端安装软件；请在环境设置中安装完整环境包。"
        Log.w(TAG, "bootstrap hint (complete=${manager.isBootstrapComplete} failed=${manager.isBootstrapFailed})")
        return spawn.copy(warning = text)
    }

    /**
     * The PRoot argv comes from [PRootKernel.buildProotCommand] verbatim for
     * the non-interactive case; the other two shapes assemble the same flags
     * they always did. Environment stays in the caller for PRoot (it is
     * already written there), so this returns only the proot-owned entries.
     */
    private fun prootSpawn(context: Context, spec: SandboxCommandSpec): SandboxSpawn {
        val mounts = spec.sessionMounts ?: PRootKernel.bindMounts
        val argv = when {
            !spec.interactive -> {
                val shellArgv = spec.statusGuestPath?.let { statusPath ->
                    ForegroundCommandGroup.wrapForFreshProcess(spec.command, statusPath)
                } ?: listOf("/bin/sh", "-c", spec.command)
                PRootKernel.buildProotArgv(shellArgv, mounts)
            }
            spec.loginShell -> PRootKernel.buildProotInteractiveCommand(listOf("/bin/bash", "--login", "-i"), mounts)
            else -> PRootKernel.buildProotInteractiveCommand(listOf("/bin/sh"), mounts)
        }
        val offload = if (
            NativeOffloadServer.registeredHandlers.isNotEmpty() && NativeOffloadServer.awaitListening()
        ) {
            SandboxOffloadRoute.PROOT_NATIVE
        } else {
            // The stubs report this truthfully; see HandlerStub. Not BLOCKED:
            // the route is the native one, it is just not bound right now.
            SandboxOffloadRoute.PROOT_NATIVE
        }
        Log.d(TAG, "route=PROOT argv0=${argv.firstOrNull()} argc=${argv.size}")
        return SandboxSpawn(
            backend = SandboxBackend.PROOT,
            argv = argv,
            env = emptyMap(),
            offload = offload,
        )
    }
}

/**
 * [FEAT-261025-CHROOT] Applies a backend switch, in the one order that cannot
 * strand a running command.
 *
 * 1. Ask [SandboxSwitchPolicy] — a refusal returns the tasks, nothing is killed.
 * 2. Only when the decision says so: stop the tasks the user just confirmed.
 * 3. Start the chroot helper (root bridge, per-command confirmation, plus an
 *    app-uid handshake against a stale grant) or stop it and restore the PRoot
 *    stubs. Idle warm shells are not in the activity registry; they are torn
 *    down here because they were spawned with the old backend's argv and would
 *    otherwise serve the next command under it.
 * 4. Persist the choice LAST, so a failed handshake leaves the previous
 *    backend selected instead of a selection that cannot run.
 *
 * Steps 2-4 re-check the registry before persisting: a command that started
 * while the helper was being confirmed is not killed unconfirmed, and it keeps
 * the old backend selected. This shrinks but cannot eliminate the race with a
 * spawn that begins in the last few instructions before
 * [SandboxBackendStore.setSelected] — closing it fully would need every spawn
 * site to take the same lock.
 *
 * No wait loops: [SandboxActivityRegistry] is updated by the shells themselves,
 * and a command that is still winding down after `stop()` is still visible to
 * the next switch attempt, which then refuses again. That is the safe failure.
 */
object SandboxSwitchController {
    private const val TAG = "SandboxSwitchController"

    /**
     * [FEAT-261025-CHROOT] Serialises switch attempts.
     *
     * Two quick taps (or "switch to chroot" while a "back to PRoot" is still
     * waiting for the root dialog) used to run the whole decide/stop/start/
     * persist sequence twice against the same backend state. The second
     * attempt then read a generation the first had already rotated, or started
     * a helper while the other was stopping it. One lock, no waiting loop.
     */
    private val switchLock = Mutex()

    /**
     * [FEAT-261025-CHROOT] Legacy risk-acknowledgement hook.
     *
     * The separate chroot risk dialog is gone: the persistent Native Root grant
     * is the single authorisation, so switching neither asks for another
     * acknowledgement nor enables Root. Kept as a no-op so an older caller
     * cannot bring the second prompt back.
     */
    @Deprecated("The Native Root grant replaces the separate chroot acknowledgement")
    fun authorizeChroot(context: Context, at: Long = System.currentTimeMillis()) {
        Log.i(TAG, "authorizeChroot ignored; Native Root grant is the authorisation ($at)")
    }

    suspend fun requestSwitch(
        context: Context,
        target: SandboxBackend,
        stopActive: Boolean = false,
    ): BackendSwitchDecision = switchLock.withLock {
        requestSwitchLocked(context, target, stopActive)
    }

    private suspend fun requestSwitchLocked(
        context: Context,
        target: SandboxBackend,
        stopActive: Boolean,
    ): BackendSwitchDecision {
        val current = SandboxBackendStore.selected(context)
        val generation = SandboxBackendStore.generation(context)
        val decision = SandboxSwitchPolicy.decide(
            current = current,
            target = target,
            activeTasks = SandboxActivityRegistry.snapshot(),
            availability = ChrootKernel.availability(context),
            authorizedThisProcess = SandboxBackendStore.chrootGranted(generation),
            stopActive = stopActive,
            // 不允许 refuses the switch; 允许/询问 may proceed (询问 asks once
            // inside ChrootKernel.ensureServer, never enabling Root here).
            rootAccessGranted = com.openminis.app.sandbox.ChrootPrivilege.switchAllowed(),
        )
        when (decision) {
            is BackendSwitchDecision.NoChange,
            is BackendSwitchDecision.RefusedActiveTasks,
            is BackendSwitchDecision.RefusedNotAuthorized,
            is BackendSwitchDecision.RefusedUnavailable,
            -> {
                Log.i(TAG, "switch $current -> $target not applied: ${decision.javaClass.simpleName}")
                return decision
            }
            is BackendSwitchDecision.Allowed -> {
                decision.stopTasks.forEach { task ->
                    Log.i(TAG, "stopping '${task.label}' before switching to $target")
                    runCatching { task.stop() }
                }

                // A task that started after the decision snapshot was NOT in
                // the dialog the user confirmed. Exclude the ones we just
                // stopped (their `finally` may not have unregistered yet) and
                // refuse rather than kill anything unconfirmed.
                val confirmedIds = decision.stopTasks.mapTo(HashSet()) { it.id }
                fun appearedSinceDecision(): List<ActiveSandboxTask> =
                    SandboxActivityRegistry.snapshot().filterNot { it.id in confirmedIds }

                appearedSinceDecision().takeIf { it.isNotEmpty() }?.let { appeared ->
                    Log.i(TAG, "not switching: ${appeared.size} task(s) started after the decision")
                    return BackendSwitchDecision.RefusedActiveTasks(target, appeared)
                }

                if (target == SandboxBackend.CHROOT) {
                    // The root bridge may show a confirmation dialog here, so
                    // this can take a while. Keep the old backend selected
                    // until the helper is ready: a refused/failed grant must
                    // leave the previous backend fully usable.
                    val started = runCatching { ChrootKernel.ensureServer(context) }
                        .getOrElse { Result.failure(it) }
                    if (started.isFailure) {
                        val reason = started.exceptionOrNull()?.message ?: "chroot helper failed to start"
                        SandboxBackendStore.setLastError(context, reason)
                        return BackendSwitchDecision.RefusedUnavailable(target, reason)
                    }
                } else {
                    ChrootKernel.shutdown(context)
                }

                // [FEAT-261025-CHROOT] Idle warm shells are invisible to the
                // activity registry: PersistentShell registers only while a
                // command is in flight, but keeps its `/bin/sh` (spawned with
                // the OLD backend's argv) alive between commands. Without this
                // teardown the next `shell_execute` would silently run under
                // the previous backend after the switch. Only idle shells can
                // be hit here — every in-flight command is registered and was
                // either absent from the snapshot or explicitly stopped above
                // — and the re-check keeps a task that appeared during a root
                // prompt from being killed unconfirmed.
                val late = appearedSinceDecision()
                if (late.isNotEmpty()) {
                    Log.i(TAG, "helper ready, but ${late.size} task(s) started meanwhile; backend not switched")
                    return BackendSwitchDecision.RefusedActiveTasks(target, late)
                }
                ExecutionCoordinator.stopCurrentCommand()

                // Last look before persisting: if anything registered while
                // the idle shells were being torn down, keep the old backend
                // rather than claim a switch under a running command.
                val raced = appearedSinceDecision()
                if (raced.isNotEmpty()) {
                    Log.i(TAG, "backend not switched: ${raced.size} task(s) appeared during teardown")
                    return BackendSwitchDecision.RefusedActiveTasks(target, raced)
                }

                SandboxBackendStore.setSelected(context, target)
                Log.i(TAG, "backend switched $current -> $target")
                return decision
            }
        }
    }
}
