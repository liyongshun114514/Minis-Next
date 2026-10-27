package com.openminis.app.sandbox

import android.util.Log
import java.io.File

/**
 * [FEAT-261028-PROOT-GROUPS] Android entry point for [GuestGroupNames].
 *
 * The id list is read from the *running process* every time, not from a
 * constant table. Android gives each app a device/OEM/OS-dependent set of
 * supplementary groups, and PRoot passes that inherited list straight into the
 * guest. The ids 3003, 9997, 20368 and 50368 are only what the original report
 * showed — they are not a mapping to fixed group names and must not be
 * hard-coded.
 *
 * `/proc/self/status` is the source: the kernel prints the process's own
 * supplementary list on its `Groups:` line. `android.system.Os` has no public
 * `getgroups()` on the shipped SDK, so it is not called (and nothing is
 * reflected).
 *
 * Called from the PRoot boot path before any shell is spawned. The chroot
 * backend is deliberately untouched: its helper calls `setgroups(0)`, so it
 * has no host supplementary ids to name.
 */
object GuestSupplementaryGroups {
    private const val TAG = "GuestSupplementaryGroups"
    const val PROC_STATUS = "/proc/self/status"

    private val lock = Any()

    /** Last run, for diagnostics and tests; null before the first attempt. */
    @Volatile
    var lastOutcome: GuestGroupNames.Outcome? = null
        private set

    /** The last failure, or null. Mirrors [GuestGroupNames.Outcome.error]. */
    val lastError: String? get() = lastOutcome?.error

    /**
     * Supplementary group ids of this process, exactly as the kernel reports
     * them. Never throws: an unreadable `/proc` is an empty list plus a WARN.
     */
    fun processGroupIds(): List<Int> {
        val status = runCatching { File(PROC_STATUS).readText() }
            .onFailure {
                Log.w(TAG, "cannot read $PROC_STATUS: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrNull() ?: return emptyList()
        val ids = GuestGroupNames.parseProcStatus(status)
        if (ids.isEmpty()) {
            Log.w(TAG, "$PROC_STATUS has no parseable Groups: line; nothing to name")
        }
        return ids
    }

    /**
     * Idempotent [GuestGroupNames.ensure] for [rootfs]. Serialised because two
     * shells can be prepared concurrently; [GuestGroupNames.ensure] itself is
     * read-modify-write.
     */
    fun ensure(rootfs: File, ids: Iterable<Int> = processGroupIds()): GuestGroupNames.Outcome =
        synchronized(lock) {
            val outcome = GuestGroupNames.ensure(rootfs, ids)
            lastOutcome = outcome
            val error = outcome.error
            if (error != null) {
                // Visible on purpose: a silent failure brings the original
                // "cannot find name for group ID" warning back with no clue.
                Log.w(
                    TAG,
                    "could not name host group ids ${outcome.requested} in " +
                        "${outcome.groupFile}: $error (groups/id may still print numeric ids)",
                )
            } else if (outcome.added.isNotEmpty()) {
                Log.i(
                    TAG,
                    "named ${outcome.added.size} host group id(s) for the guest: " +
                        outcome.added.entries.joinToString { "${it.key}=${it.value}" },
                )
            } else {
                Log.d(TAG, "guest already names every host group id (${outcome.requested.size} checked)")
            }
            outcome
        }
}
