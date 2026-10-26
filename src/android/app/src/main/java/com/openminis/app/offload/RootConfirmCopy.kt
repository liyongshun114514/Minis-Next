package com.openminis.app.offload

/**
 * Shared copy for every Root entry point.
 *
 * Root is authorised with the same tri-state as `android-shizuku-cli`:
 * 允许 executes directly (no per-command app dialog), 询问 raises one
 * confirmable request per invocation, 不允许 refuses. The device's own su
 * manager keeps its own prompt either way; we cannot and do not bypass it.
 *
 * On a device where Root is actually available the level starts at 允许 — the
 * choice is stored, so it survives an app restart and is never asked again.
 */
internal const val ROOT_TRI_STATE_FOOTER =
    "与 android-shizuku-cli 一样按三态控制：允许 → 直接使用，不逐次确认；" +
        "询问 → 首次调用询问一次，点“允许”后记住、不再逐次询问；不允许 → 拒绝执行。设备 su 管理器仍会单独授权，本应用无法绕过。" +
        "检测到设备 Root 可用时默认允许并长期记住：只验证一次，之后直接使用、不再重复确认，可随时更改（选“不允许”即彻底关闭）；" +
        "Root 操作可能修改系统或删除数据，部分操作无法撤销。"

/**
 * Legacy copy kept for the walkthrough page's enable dialog and older
 * callers. It still describes the 允许 semantics accurately: direct use, no
 * per-command confirmation, su manager unchanged.
 */
internal const val ROOT_ENABLE_MESSAGE =
    "开启后 android-root-cli 与 Chroot 后端直接使用这次授权，不再逐条询问；" +
        "设备自己的 su 管理器仍会单独向你授权，本应用无法绕过。\n" +
        "Root 操作可能修改系统或删除数据，部分操作无法撤销。可随时在设置中关闭，关闭后新的 Root 操作会被拒绝。"

/** Metadata note rendered under the tri-state row (per-level meaning, short). */
internal fun rootLevelNote(level: RootAccess): String = when (level) {
    RootAccess.BYPASS -> "允许：android-root-cli 与 Chroot 直接执行，不逐次确认；su 管理器仍单独授权。"
    RootAccess.ASK_ONCE -> "询问：每次调用询问一次；切 Chroot 询问一次；拒绝或超时不执行。"
    RootAccess.NOT_ALLOWED -> "不允许：一律拒绝；切 Chroot 只给设置提示，不弹 Root 授权。"
}

/** Returned when a system-driven (non-agent) path needs Root that is not granted. */
internal const val ROOT_DISABLED_FOR_SYSTEM =
    "原生 Root 当前不是“允许”（Chroot 不会弹出 Root 请求）。请先在 设置 → 权限 → 原生 Root 中设为“允许”，再重试。"

/** Returned when the user refuses (or ignores) the agent's authorization request. */
internal const val ROOT_AGENT_REQUEST_DENIED =
    "未授权本次原生 Root 请求，命令未执行。可在 设置 → 权限 → 原生 Root 中改为“允许”后重试。"

/** Settings-path label shown when the grant is revoked. */
internal const val ROOT_REVOKED_MESSAGE =
    "Root 权限已改为不允许，新的 Root 操作会被拒绝；已开始的命令按其自身超时结束。"

/** Exact argv is rendered separately; this never rates a command as safe. */
internal fun rootConfirmRisk(cwd: String, timeoutSec: Int): String =
    "关键风险：以 Root 身份执行，可能修改系统或删除数据，部分操作无法撤销。\n" +
        "目录：${cwd.ifBlank { "Android 默认目录" }} · 超时：${timeoutSec}s"

/**
 * Body of the one-off request shown when an agent calls `android-root-cli`
 * while the level is 询问. Accepting it authorises THIS invocation only; the
 * stored level stays 询问, so the next call asks again.
 */
internal fun rootAgentAccessRequest(cwd: String, timeoutSec: Int): String =
    "Root 权限当前为“询问”。点“允许”会记住这次授权：之后直接用，不再逐次询问；拒绝、取消或超时则只跳过本次，权限保持“询问”。\n" +
        "想彻底关闭请到 设置 → 权限 → 原生 Root 改为“不允许”。\n" +
        rootConfirmRisk(cwd, timeoutSec)

/** Confirmation shown once when the user switches the backend to Chroot under 询问. */
internal fun rootChrootSwitchRequest(timeoutSec: Int): String =
    "Root 权限为“询问”。点“允许”会记住这次授权：之后的切换不再询问；拒绝、取消或超时则不切换，权限保持“询问”。\n" +
        "如需直接切换（不再询问），请到 设置 → 权限 → 原生 Root 中改为“允许”。\n" +
        rootConfirmRisk("", timeoutSec)
