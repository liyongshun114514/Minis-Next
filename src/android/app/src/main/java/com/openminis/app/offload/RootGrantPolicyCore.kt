package com.openminis.app.offload

/**
 * The three-level Root gate as a pure decision table.
 *
 * Root is one of the integration CLIs, so it carries the SAME tri-state as
 * `android-shizuku-cli` (`Settings → Permissions → 原生 Root`):
 *
 *   BYPASS      允许    → run directly, no per-call dialog (su manager still asks)
 *   ASK_ONCE    询问    → one confirmable request per invocation; refusal,
 *                         cancel or timeout runs nothing and never flips the level
 *   NOT_ALLOWED 不允许  → refuse every call, with a settings hint for the
 *                         system paths (Chroot switch, settings probe)
 *
 * Everything Android-specific (prefs, su discovery, the dialog) lives in
 * [OffloadPermissionManager] / [RootManager]; this file is pure Kotlin so the
 * table can be unit-tested on the JVM.
 */
enum class RootAccess { BYPASS, ASK_ONCE, NOT_ALLOWED }

/** Who asked for Root: the agent's `android-root-cli`, or a system path. */
enum class RootCaller {
    /** `android-root-cli` — the only caller allowed to raise a request dialog. */
    AGENT,

    /** Chroot helper launch / settings probe — never prompts. */
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
    /** One-line label per level, matching R.string.perm_level_* (允许 / 询问 / 不允许). */
    fun label(level: RootAccess): String = when (level) {
        RootAccess.BYPASS -> "允许"
        RootAccess.ASK_ONCE -> "询问"
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
            caller == RootCaller.AGENT -> RootDecision.REQUEST_ONCE
            // Chroot NEVER pops a Root prompt: it reports the settings hint.
            else -> RootDecision.SETTINGS_HINT
        }
        RootAccess.NOT_ALLOWED -> when (caller) {
            RootCaller.AGENT -> RootDecision.DENY
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
 * "Default 允许 once root is actually available" — and only ever once, and
 * only while the user has never chosen a level themselves.
 *
 * Two independent facts decide the stored default:
 *
 *  - [rootDetected]: the existing probe succeeded (an executable `su` was
 *    resolved, or `id` came back uid 0). A failed probe changes nothing, so a
 *    device without Root stays on 询问 instead of silently becoming 允许.
 *  - [userChosen]: the user touched the tri-state row / the one-off request
 *    auto-enabled. Once true, detection is never consulted again — a later
 *    reboot with su present must not override an explicit 不允许.
 *
 * [alreadyApplied] makes the write idempotent and one-shot: the promotion is
 * persisted by [OffloadPermissionManager.applyDetectedRootDefault] so that a
 * process restart, a fresh settings page or an app relaunch reads 允许 back
 * from prefs instead of probing again.
 */
object RootDefaultPolicy {
    /** Persisted marker: the user picked a level explicitly. Never cleared. */
    const val KEY_USER_CHOSEN = "root_level_user_chosen"

    /** Persisted marker: the detection-driven default was already written. */
    const val KEY_DEFAULT_APPLIED = "root_level_default_applied"

    fun defaultLevel(userChosen: Boolean, rootDetected: Boolean): RootAccess =
        if (!userChosen && rootDetected) RootAccess.BYPASS else RootAccess.ASK_ONCE

    /**
     * Should the detected-Root default be written right now?
     *
     * False when the user has ever chosen, or when it was already written
     * (so a later 允许 → 询问 downgrade is not undone by the next probe).
     */
    fun shouldPromote(
        userChosen: Boolean,
        alreadyApplied: Boolean,
        rootDetected: Boolean,
    ): Boolean = rootDetected && !userChosen && !alreadyApplied

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
