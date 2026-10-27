package com.openminis.app.offload

/**
 * Shared copy for every Root entry point.
 *
 * Root uses the same tri-state as `android-shizuku-cli`
 * (设置 → 权限 → 原生 Root):
 *  完全允许 → execute directly, no per-command app dialog;
 *  每次询问 → ONE confirmable request per command / per interactive session;
 *             refusal, cancel or timeout runs nothing;
 *  不允许   → refuse, with a settings hint; no Root dialog is ever raised.
 *
 * A single approval NEVER changes the stored level, and a successful su probe
 * never promotes 每次询问 to 完全允许. The device's own su manager keeps its
 * own prompt either way; we cannot and do not bypass it.
 */
internal const val ROOT_TRI_STATE_FOOTER =
    "三态控制：完全允许 → 直接使用，不逐次确认；每次询问 → 每条命令/每个终端各确认一次，" +
        "点“允许”只对该次有效，不改变级别；不允许 → 拒绝执行。设备 su 管理器仍会单独授权，本应用无法绕过。" +
        "检测到 Root 可用只用于显示状态，不会自动把“每次询问”升级为“完全允许”。" +
        "Root 操作可能修改系统或删除数据，部分操作无法撤销。"

/** Copy for the walkthrough page's enable dialog (legacy entry point). */
internal const val ROOT_ENABLE_MESSAGE =
    "将 Root 权限设为“完全允许”后，android-root-cli 与 chroot 后端直接使用该授权，不再逐条询问；" +
        "设备自己的 su 管理器仍会单独向你授权，本应用无法绕过。\n" +
        "Root 操作可能修改系统或删除数据，部分操作无法撤销。可随时改回“每次询问”或“不允许”，" +
        "撤销后新的 Root 操作会被拒绝。"

/** Metadata note rendered under the tri-state row (per-level meaning, short). */
internal fun rootLevelNote(level: RootAccess): String = when (level) {
    RootAccess.BYPASS ->
        "完全允许：android-root-cli 与 chroot 命令/终端直接以 uid0 执行，不逐次确认；su 管理器仍单独授权。"
    RootAccess.ASK_ONCE ->
        "每次询问：每条 chroot 命令、每个终端会话各确认一次；android-root-cli 每次调用询问一次；拒绝/取消/超时不执行。"
    RootAccess.NOT_ALLOWED ->
        "不允许：一律拒绝，chroot 也不会以 uid0 执行；切 Chroot 只给设置提示，不弹 Root 授权。"
}

/** Returned when an automatic (non-user) path needs Root that is not granted. */
internal const val ROOT_DISABLED_FOR_SYSTEM =
    "原生 Root 当前为“每次询问”或“不允许”：自动启动/探测不会静默执行 su。" +
        "请在 设置 → 权限 → 原生 Root 中选择级别，再由你本人的操作（切换 chroot、运行命令）触发一次确认。"

/** Returned when the user refuses (or ignores) the agent's authorization request. */
internal const val ROOT_AGENT_REQUEST_DENIED =
    "未授权本次原生 Root 请求，命令未执行（拒绝、取消与超时同样不执行）。" +
        "可在 设置 → 权限 → 原生 Root 中改为“完全允许”后重试。"

/** Settings-path label shown when the grant is revoked. */
internal const val ROOT_REVOKED_MESSAGE =
    "Root 权限已改为不允许，新的 Root 操作会被拒绝；已开始的命令按其自身超时结束。"

/** Exact argv is rendered separately; this never rates a command as safe. */
internal fun rootConfirmRisk(cwd: String, timeoutSec: Int): String =
    "关键风险：以 Root 身份执行，可能修改系统或删除数据，部分操作无法撤销。\n" +
        "目录：${cwd.ifBlank { "Android 默认目录" }} · 超时：${timeoutSec}s"

/**
 * Body of the one-off request shown when an agent calls `android-root-cli`
 * while the level is 每次询问. Accepting authorises THIS invocation only; the
 * stored level stays 每次询问, so the next call asks again.
 */
internal fun rootAgentAccessRequest(cwd: String, timeoutSec: Int): String =
    "Root 权限当前为“每次询问”。点“允许”只授权本次调用，不会改变权限级别；下一次调用会再次询问。" +
        "拒绝、取消或超时只跳过本次，命令不执行。\n" +
        "如需不再逐次确认，请到 设置 → 权限 → 原生 Root 选择“完全允许”。\n" +
        rootConfirmRisk(cwd, timeoutSec)

/** Confirmation shown once when the user switches the backend to Chroot under 每次询问. */
internal fun rootChrootSwitchRequest(timeoutSec: Int): String =
    "Root 权限为“每次询问”。点“允许”只为本次切换启动助手，权限级别保持不变；" +
        "之后的 chroot 命令/终端仍会各自询问一次。拒绝、取消或超时则不切换。\n" +
        "如需直接切换（不再询问），请到 设置 → 权限 → 原生 Root 中选择“完全允许”。\n" +
        rootConfirmRisk("", timeoutSec)
