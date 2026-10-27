package com.openminis.app.offload

import java.security.MessageDigest

/**
 * [FEAT-261027-CHROOT-ROOT] The one decision table for "may THIS chroot spawn
 * run as uid 0?".
 *
 * It is the same unified tri-state the permission page shows
 * (`不允许 / 每次询问 / 完全允许`) and it is consulted at the spawn site, i.e.
 * where the child is actually built — not only by the settings UI. Pure Kotlin
 * so the table is unit-tested without a device, root or su.
 *
 * The chroot backend is a REAL uid-0 backend: when it is selected every spawn
 * asks for root, because that is what the backend is for (`apt`/`dpkg` must be
 * able to chown and write `/var/lib/dpkg`). There is therefore no third
 * "app uid" outcome that could quietly weaken a command the user asked to run
 * in the Ubuntu container. PRoot is unaffected: it never calls this table and
 * keeps its fake root inside its own tracer.
 *
 * Definitions that keep the states honest:
 *
 *  - [RootAccess.BYPASS] 完全允许 — chroot commands run as uid 0 directly.
 *    Persisted ONLY by an explicit user selection; no probe ever promotes
 *    询问 to this level.
 *  - [RootAccess.ASK_ONCE] 每次询问 — every command / interactive session needs
 *    a fresh single-use approval bound to that exact command. Without it the
 *    command is NOT executed (no downgrade to the app uid): refusal, cancel
 *    and timeout all mean "nothing runs".
 *  - [RootAccess.NOT_ALLOWED] 不允许 — chroot cannot serve uid 0, so the
 *    command is refused with a settings hint instead of being run with fewer
 *    privileges than the user asked for.
 */
enum class RootExecOutcome {
    /** Run as uid 0 inside the private chroot namespace. */
    ROOT,

    /** 每次询问 without a valid approval yet: the caller must ask once. */
    NEED_APPROVAL,

    /** Nothing may run; the caller builds a refusal spawn, never a downgrade. */
    REFUSED,
}

/**
 * One chroot spawn request. [fingerprint] is the identity an approval is bound
 * to: a grant minted for `apt install foo` can never authorise a different
 * command, even a prefix or a whitespace variant.
 */
data class RootExecRequest(
    /** Shell string. Empty for an interactive session (identity = session). */
    val command: String,
    val interactive: Boolean,
    val sessionId: String? = null,
    /** True for an explicit caller requirement (android-chroot-cli). */
    val required: Boolean = false,
) {
    val fingerprint: String get() = RootExecPolicy.fingerprint(this)
}

/**
 * A single-use approval for exactly one spawn.
 *
 * [epoch] pins the Root state epoch: a revoke/downgrade bumps it, which kills
 * every outstanding grant. [mintedAtMs] is elapsed-realtime, so a grant cannot
 * survive a reboot or a clock change.
 */
data class RootExecGrant(
    val fingerprint: String,
    val epoch: Long,
    val mintedAtMs: Long,
)

data class RootExecDecision(
    val outcome: RootExecOutcome,
    /** Stable, user-facing explanation; never empty. */
    val reason: String,
)

object RootExecPolicy {
    /**
     * How long an approval stays usable. The confirmation dialog itself can be
     * open for 120 s, and the spawn that consumes the grant happens right
     * after the answer, so anything older than this is a leak, not a queue.
     */
    const val GRANT_TTL_MS = 120_000L

    /**
     * Bind the approval to the exact command, the interactive flag, the chat
     * session and the "required" flag. SHA-256 over length-prefixed fields so
     * concatenation ambiguity cannot make two different requests collide.
     */
    fun fingerprint(request: RootExecRequest): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(value: String?) {
            val bytes = (value ?: "\u0000null").toByteArray(Charsets.UTF_8)
            val len = ByteArray(4)
            var n = bytes.size
            for (i in 3 downTo 0) { len[i] = (n and 0xff).toByte(); n = n ushr 8 }
            md.update(len)
            md.update(bytes)
        }
        put("v1")
        put(request.command)
        put(if (request.interactive) "1" else "0")
        put(request.sessionId)
        put(if (request.required) "1" else "0")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A grant is usable only for the same request, epoch and within the TTL. */
    fun grantStillValid(
        grant: RootExecGrant?,
        request: RootExecRequest,
        currentEpoch: Long,
        nowMs: Long,
    ): Boolean {
        if (grant == null) return false
        if (grant.fingerprint != request.fingerprint) return false
        if (grant.epoch != currentEpoch) return false
        val age = nowMs - grant.mintedAtMs
        return age in 0..GRANT_TTL_MS
    }

    /**
     * @param grant an approval already minted for [request] (consumed exactly
     *   once by the caller), or null.
     */
    fun decide(
        level: RootAccess,
        request: RootExecRequest,
        grant: RootExecGrant?,
        currentEpoch: Long,
        nowMs: Long,
    ): RootExecDecision = when (level) {
        RootAccess.NOT_ALLOWED -> RootExecDecision(
            RootExecOutcome.REFUSED,
            "Root 权限为“不允许”：chroot 命令需要 uid0，本次未执行（不会静默降级为 app uid）。" +
                "需要 apt/dpkg 时请在 设置 → 权限 → 原生 Root 改为“每次询问”或“完全允许”。",
        )
        RootAccess.BYPASS -> RootExecDecision(
            RootExecOutcome.ROOT,
            "Root 权限为“完全允许”（用户显式选择）：本次命令在 chroot 内以 uid0 执行。",
        )
        RootAccess.ASK_ONCE -> if (grantStillValid(grant, request, currentEpoch, nowMs)) {
            RootExecDecision(
                RootExecOutcome.ROOT,
                "Root 权限为“每次询问”：本次" +
                    (if (request.interactive) "交互会话" else "命令") +
                    "已获单次授权，在 chroot 内以 uid0 执行。",
            )
        } else {
            RootExecDecision(
                RootExecOutcome.NEED_APPROVAL,
                "Root 权限为“每次询问”：本次" +
                    (if (request.interactive) "交互会话" else "命令") +
                    "需要一次明确确认；拒绝、取消或确认超时都不会执行，" +
                    "单次授权不会把级别改成“完全允许”。",
            )
        }
    }

    /**
     * Capability bit for the helper's `--root-exec`. It is NOT permission to
     * execute: the frame still needs a per-command authorisation from Kotlin.
     * At 不允许 the helper must not even own the capability.
     */
    fun helperMayRunRoot(level: RootAccess): Boolean = level != RootAccess.NOT_ALLOWED

    /** True only for an explicit 完全允许. */
    fun isPersistentlyAllowed(level: RootAccess): Boolean = level == RootAccess.BYPASS
}
