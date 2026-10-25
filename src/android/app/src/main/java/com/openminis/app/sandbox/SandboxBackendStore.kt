package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

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
    ): BackendSwitchDecision {
        if (current == target) return BackendSwitchDecision.NoChange(current)

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

    fun grantChrootForProcess(generation: String) {
        grantedGeneration = generation
        Log.i(TAG, "chroot runtime grant present for generation=$generation")
    }

    fun chrootGranted(generation: String): Boolean = grantedGeneration == generation

    fun revokeChrootGrant() {
        if (grantedGeneration != null) Log.i(TAG, "chroot runtime grant revoked")
        grantedGeneration = null
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
)

/** [FEAT-261025-CHROOT] What a spawn site asks for. */
data class SandboxCommandSpec(
    /** Shell string. Ignored when [interactive] is true. */
    val command: String,
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
            SandboxBackend.PROOT -> prootSpawn(context, spec)
            SandboxBackend.CHROOT -> ChrootKernel.spawn(context, spec)
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
            !spec.interactive -> PRootKernel.buildProotCommand(spec.command, mounts)
            spec.loginShell -> PRootKernel.buildProotInteractiveCommand(listOf("/bin/sh", "-l", "-i"), mounts)
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
 * 3. Start the chroot helper (root bridge, per-command confirmation) or stop it
 *    and restore the PRoot stubs.
 * 4. Persist the choice LAST, so a failed handshake leaves the previous
 *    backend selected instead of a selection that cannot run.
 *
 * No wait loops: [SandboxActivityRegistry] is updated by the shells themselves,
 * and a command that is still winding down after `stop()` is still visible to
 * the next switch attempt, which then refuses again. That is the safe failure.
 */
object SandboxSwitchController {
    private const val TAG = "SandboxSwitchController"

    /** Record the user's explicit risk acknowledgement (shown by the UI). */
    fun authorizeChroot(context: Context, at: Long = System.currentTimeMillis()) {
        SandboxBackendStore.setSelected(context, SandboxBackend.CHROOT, authorizedAt = at)
        Log.i(TAG, "user acknowledged the chroot risk at $at")
    }

    suspend fun requestSwitch(
        context: Context,
        target: SandboxBackend,
        stopActive: Boolean = false,
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
                if (target == SandboxBackend.CHROOT) {
                    val started = ChrootKernel.ensureServer(context)
                    if (started.isFailure) {
                        val reason = started.exceptionOrNull()?.message ?: "chroot helper failed to start"
                        SandboxBackendStore.setLastError(context, reason)
                        return BackendSwitchDecision.RefusedUnavailable(target, reason)
                    }
                } else {
                    ChrootKernel.shutdown(context)
                }
                SandboxBackendStore.setSelected(context, target)
                Log.i(TAG, "backend switched $current -> $target")
                return decision
            }
        }
    }
}
