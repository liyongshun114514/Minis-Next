package com.openminis.app.offload

/**
 * Shared copy for every Root entry point.
 *
 * The permission is a persistent grant now: once the user enables it in
 * Settings → Permissions → Native Root, `android-root-cli` and the Chroot
 * backend use it directly, without a per-command app dialog. The copy must say
 * that plainly — and must not imply that a per-command confirmation is still
 * coming. The device's own su manager keeps its own prompt; we cannot and do
 * not bypass it.
 */
internal const val ROOT_ENABLE_MESSAGE =
    "开启后 android-root-cli 与 Chroot 后端直接使用这次授权，不再逐条询问；" +
        "设备自己的 su 管理器仍会单独向你授权，本应用无法绕过。\n" +
        "Root 操作可能修改系统或删除数据，部分操作无法撤销。可随时在设置中关闭，关闭后新的 Root 操作会被拒绝。"

/** Returned when a system-driven (non-agent) path needs Root that is not granted. */
internal const val ROOT_DISABLED_FOR_SYSTEM =
    "原生 Root 未开启。请先在 设置 → 权限 → 原生 Root 中开启，再重试。"

/** Returned when the user refuses (or ignores) the agent's authorization request. */
internal const val ROOT_AGENT_REQUEST_DENIED =
    "未授权原生 Root，命令未执行。可在 设置 → 权限 → 原生 Root 中开启后重试。"

/** Settings-path label shown when the grant is revoked. */
internal const val ROOT_REVOKED_MESSAGE =
    "Root 授权已关闭，新的 Root 操作会被拒绝；已开始的命令按其自身超时结束。"

/** Exact argv is rendered separately; this never rates a command as safe. */
internal fun rootConfirmRisk(cwd: String, timeoutSec: Int): String =
    "关键风险：以 Root 身份执行，可能修改系统或删除数据，部分操作无法撤销。\n" +
        "目录：${cwd.ifBlank { "Android 默认目录" }} · 超时：${timeoutSec}s"

/**
 * Body of the one-off request shown when an agent calls `android-root-cli`
 * while the persistent grant is off. Accepting it authorises THIS invocation
 * only; it does not flip the settings switch, so the next call asks again
 * unless the user enabled Root deliberately.
 */
internal fun rootAgentAccessRequest(cwd: String, timeoutSec: Int): String =
    "Root 当前未开启。允许只执行这次调用，不会自动开启 Root 权限；拒绝、取消或超时则不执行。\n" +
        "如需之后直接使用（不再逐次询问），请到 设置 → 权限 → 原生 Root 中开启。\n" +
        rootConfirmRisk(cwd, timeoutSec)

/**
 * Pure grant/revocation state machine, kept free of Android types so it can be
 * unit-tested on the JVM.
 *
 *  - an epoch-matched ticket means the user already answered for this
 *    invocation → [Decision.USE_GRANT];
 *  - the persistent grant alone → [Decision.USE_GRANT];
 *  - no grant, agent caller → [Decision.REQUEST_ACCESS] (one dialog, never an
 *    implicit enable);
 *  - no grant, system caller (Chroot, settings probe) → [Decision.REFUSE];
 *  - a ticket whose epoch no longer matches → [Decision.REFUSE]: revoking the
 *    permission invalidates approvals that were given before the revocation.
 */
object RootGrantPolicy {
    enum class Caller { AGENT, SYSTEM }
    enum class Decision { USE_GRANT, REQUEST_ACCESS, REFUSE }

    fun decide(
        granted: Boolean,
        caller: Caller,
        ticketEpoch: Long?,
        currentEpoch: Long,
    ): Decision = when {
        granted -> Decision.USE_GRANT
        ticketEpoch != null && ticketEpoch == currentEpoch -> Decision.USE_GRANT
        ticketEpoch != null -> Decision.REFUSE
        caller == Caller.SYSTEM -> Decision.REFUSE
        else -> Decision.REQUEST_ACCESS
    }

    /** A ticket is valid only while the grant epoch it was issued under stands. */
    fun ticketStillValid(ticketEpoch: Long, currentEpoch: Long): Boolean =
        ticketEpoch == currentEpoch
}
