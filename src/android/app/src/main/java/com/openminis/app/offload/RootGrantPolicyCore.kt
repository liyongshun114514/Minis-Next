package com.openminis.app.offload

/**
 * The three-level Root gate as a pure decision table.
 *
 * Root is one of the integration CLIs, so it carries the SAME tri-state as
 * `android-shizuku-cli` (`Settings → Permissions → 原生 Root`):
 *
 *   BYPASS      完全允许 → run directly, no per-call dialog (su manager still asks)
 *   ASK_ONCE    每次询问 → one confirmable request per invocation; refusal,
 *                         cancel or timeout runs nothing and never changes the level
 *   NOT_ALLOWED 不允许   → refuse every call, with a settings hint for the
 *                         system paths (Chroot switch, settings probe)
 *
 * Everything Android-specific (prefs, su discovery, the dialog) lives in
 * [OffloadPermissionManager] / [RootManager]; this file is pure Kotlin so the
 * table can be unit-tested on the JVM.
 */
enum class RootAccess { BYPASS, ASK_ONCE, NOT_ALLOWED }

/** Who asked for Root: the agent, an explicit in-app user action, or an automatic path. */
enum class RootCaller {
    /** `android-root-cli` / `android-chroot-cli` — may raise one request dialog. */
    AGENT,

    /**
     * An EXPLICIT in-app user action (Settings → 原生 Root tri-state, the
     * chroot switch the user just pressed). Under 询问 it raises ONE request
     * for that action; it never changes the stored level.
     */
    USER,

    /**
     * Automatic work: helper auto-restart, liveness probe, settings status.
     * Never prompts and never runs su under 询问.
     */
    SYSTEM,
}

/** What the gate decided for one invocation. */
enum class RootDecision {
    /** Run now: an explicit 允许 grant, or a one-off approval for this call. */
    EXECUTE,

    /** 询问 + AGENT: show ONE confirmable request; nothing runs unless accepted. */
    REQUEST_ONCE,

    /** 不允许 (any caller) / 询问 + SYSTEM / stale approval: refuse. */
    DENY,

    /** Refused AND the user should be sent to Settings → 权限 → 原生 Root. */
    SETTINGS_HINT,
}

object RootGrantPolicy {
    /** One-line label per level, matching the tri-state row (完全允许 / 每次询问 / 不允许). */
    fun label(level: RootAccess): String = when (level) {
        RootAccess.BYPASS -> "完全允许"
        RootAccess.ASK_ONCE -> "每次询问"
        RootAccess.NOT_ALLOWED -> "不允许"
    }

    /**
     * The whole gate, as a value.
     *
     * @param level the stored tri-state level (defaults are resolved by the
     *   caller; see `OffloadPermissionManager.rootLevel()`).
     * @param ticketEpoch epoch an approval was issued under, null when the
     *   invocation has none.
     * @param currentEpoch the live epoch; a ticket from an older epoch belongs
     *   to a state the user has since left (revocation / downgrade).
     */
    fun decide(
        level: RootAccess,
        caller: RootCaller,
        ticketEpoch: Long? = null,
        currentEpoch: Long = 0L,
    ): RootDecision = when (level) {
        // Revoking 允许 bumps the epoch, so a ticket minted before the
        // downgrade can never carry a rejected level into execution.
        RootAccess.BYPASS -> RootDecision.EXECUTE
        RootAccess.ASK_ONCE -> when {
            // 允许 only this invocation, answered after the current state began.
            ticketEpoch != null && ticketEpoch == currentEpoch -> RootDecision.EXECUTE
            // A stale approval is dead: it never becomes a dialog, never runs.
            ticketEpoch != null -> RootDecision.DENY
            // The agent CLI and an explicit user action may ask once.
            caller == RootCaller.AGENT || caller == RootCaller.USER -> RootDecision.REQUEST_ONCE
            // Automatic paths (autostart/probes) NEVER run su unpermitted.
            else -> RootDecision.SETTINGS_HINT
        }
        RootAccess.NOT_ALLOWED -> when (caller) {
            RootCaller.AGENT, RootCaller.USER -> RootDecision.DENY
            RootCaller.SYSTEM -> RootDecision.SETTINGS_HINT
        }
    }

    /** A ticket is valid only while the state epoch it was issued under stands. */
    fun ticketStillValid(ticketEpoch: Long, currentEpoch: Long): Boolean =
        ticketEpoch == currentEpoch

    /**
     * Map a stored [OffloadPermissionManager.PermissionLevel] onto the gate.
     * ASK_ONCE is a real, user-selectable level here (it used to be migrated
     * away); an unknown value falls back to the safe middle level.
     */
    fun levelOf(level: OffloadPermissionManager.PermissionLevel): RootAccess = when (level) {
        OffloadPermissionManager.PermissionLevel.BYPASS -> RootAccess.BYPASS
        OffloadPermissionManager.PermissionLevel.ASK_ONCE -> RootAccess.ASK_ONCE
        OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> RootAccess.NOT_ALLOWED
    }

    fun toLevel(access: RootAccess): OffloadPermissionManager.PermissionLevel = when (access) {
        RootAccess.BYPASS -> OffloadPermissionManager.PermissionLevel.BYPASS
        RootAccess.ASK_ONCE -> OffloadPermissionManager.PermissionLevel.ASK_ONCE
        RootAccess.NOT_ALLOWED -> OffloadPermissionManager.PermissionLevel.NOT_ALLOWED
    }
}

/**
 * Root availability is a FACT, not a permission decision.
 *
 * A successful probe (an executable `su` was resolved, or `id` came back uid 0)
 * is recorded for the status line only. It NEVER writes 完全允许: that level is
 * persisted exclusively by an explicit user selection. The undecided default is
 * always 每次询问, so a background probe cannot silently grant persistent uid-0
 * execution.
 *
 * [KEY_USER_CHOSEN] marks an explicit selection; [KEY_DETECTED_ONCE] marks a
 * probe result. Only the tri-state row / [OffloadPermissionManager.setRootLevel]
 * writes the former and the level.
 */
object RootDefaultPolicy {
    /** Persisted marker: the user picked a level explicitly. Never cleared. */
    const val KEY_USER_CHOSEN = "root_level_user_chosen"

    /** Persisted marker from older builds; no longer written by detection. */
    const val KEY_DEFAULT_APPLIED = "root_level_default_applied"

    /**
     * Persisted marker: Root was PROVEN usable at least once.
     *
     * Used only to describe the bridge status. It is cleared on an explicit
     * 每次询问 or 不允许 so no "verified" badge outlives that choice; it never
     * changes the level by itself.
     */
    const val KEY_DETECTED_ONCE = "root_detected_once"

    /**
     * The undecided default is ALWAYS 每次询问.
     *
     * [rootDetected] is accepted for source compatibility but deliberately
     * ignored: a successful probe only records that su is usable, it must
     * never turn 每次询问 (or a fresh install) into a persisted 完全允许. Only
     * the user's own selection writes that level.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultLevel(userChosen: Boolean, rootDetected: Boolean): RootAccess = RootAccess.ASK_ONCE

    /** Detection never promotes a level. Kept for older callers. */
    @Suppress("UNUSED_PARAMETER")
    fun shouldPromote(
        userChosen: Boolean,
        alreadyApplied: Boolean,
        rootDetected: Boolean,
    ): Boolean = false

    /**
     * Read the level a caller must honour for one invocation.
     *
     * Order matters: a level the user stored always wins; otherwise the
     * detection-derived default applies, but only while Root is really
     * available — a device where the probe failed is answered with 询问, never
     * with an implicit 允许.
     */
    fun effectiveLevel(
        stored: OffloadPermissionManager.PermissionLevel?,
        userChosen: Boolean,
        rootDetected: Boolean,
    ): RootAccess = stored?.let { RootGrantPolicy.levelOf(it) }
        ?: defaultLevel(userChosen = userChosen, rootDetected = rootDetected)
}
