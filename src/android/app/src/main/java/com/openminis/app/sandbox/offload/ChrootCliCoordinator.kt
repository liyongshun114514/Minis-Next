package com.openminis.app.sandbox.offload

/**
 * [FEAT-261027-CHROOT-CLI] The one decision path for `exec`.
 *
 * Order is the security property, so it lives in a pure class the tests can
 * drive with fakes:
 *
 *  1. Root 不允许  -> refuse BEFORE any confirmation, helper or process;
 *  2. ONE single-use user confirmation ([confirmPrivilegedOperation], via the
 *     handler's lambda); denial/cancel/timeout ends here;
 *  3. Root is re-checked because the level can be revoked while the dialog is
 *     open — a revoked level must not start the helper;
 *  4. only now ensure the helper exists;
 *  5. Root is checked once more after the helper start (the helper handshake
 *     can take a while too), so a revocation during it runs nothing;
 *  6. only then is the command spawned.
 *
 * There is no session cache and no BYPASS shortcut: [confirm] is called on
 * every invocation.
 */
internal class ChrootCliCoordinator(
    private val rootSwitchAllowed: () -> Boolean,
    private val confirm: suspend (ChrootCliExecRequest) -> Boolean,
    private val ensureServer: suspend () -> Result<Unit>,
    private val execute: (ChrootCliExecRequest) -> ChrootCliRun,
) {
    suspend fun exec(request: ChrootCliExecRequest): ChrootCliOutcome {
        if (!rootSwitchAllowed()) {
            return ChrootCliOutcome.Refused(ChrootCliRefusal.ROOT_DISABLED, ROOT_DISABLED_MESSAGE)
        }
        // Always ask; a previous ALLOW_SESSION / Root BYPASS never reaches here.
        if (!confirm(request)) {
            return ChrootCliOutcome.Refused(ChrootCliRefusal.DENIED, DENIED_MESSAGE)
        }
        // The dialog can stay open for up to two minutes; the user may have
        // moved Root back to 不允许 meanwhile. Re-check before any privilege.
        if (!rootSwitchAllowed()) {
            return ChrootCliOutcome.Refused(ChrootCliRefusal.ROOT_REVOKED, ROOT_REVOKED_MESSAGE)
        }
        val started = ensureServer()
        if (started.isFailure) {
            val detail = started.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            return ChrootCliOutcome.Refused(
                ChrootCliRefusal.HELPER_FAILED,
                HELPER_FAILED_MESSAGE + (detail?.let { "：$it" } ?: ""),
            )
        }
        // Starting the helper is itself a Root action and may take seconds; the
        // user can revoke the level in that window too. No user command may run
        // on a revoked grant.
        if (!rootSwitchAllowed()) {
            return ChrootCliOutcome.Refused(
                ChrootCliRefusal.ROOT_REVOKED,
                ROOT_REVOKED_AFTER_HELPER_MESSAGE,
            )
        }
        // A throw here is a start failure, not a command exit status; it is
        // reported as exit 125 with the failure in stderr.
        val run = runCatching { execute(request) }.getOrElse { t ->
            ChrootCliRun(
                exitCode = 125,
                stderr = "chroot 客户端启动失败：${t.javaClass.simpleName}: ${t.message ?: "unknown"}",
            )
        }
        return ChrootCliOutcome.Completed(run)
    }

    companion object {
        const val ROOT_DISABLED_MESSAGE =
            "Root 权限为“不允许”，chroot helper 不会启动，命令也未执行。" +
                "请到 设置 → 权限 → 原生 Root 选择“允许”，再到 设置 → Shell 容器 启用 chroot。" +
                "本工具不会改动 Root 开关。"

        const val ROOT_REVOKED_MESSAGE =
            "确认后 Root 权限已被撤销：未启动 chroot helper，也未执行命令。" +
                "请到 设置 → 权限 → 原生 Root 检查授权。"

        const val ROOT_REVOKED_AFTER_HELPER_MESSAGE =
            "helper 启动后 Root 权限已被撤销：未执行用户命令。" +
                "请到 设置 → 权限 → 原生 Root 检查授权。"

        const val DENIED_MESSAGE =
            "已拒绝、取消或确认超时：未启动 chroot helper，未执行任何进程。"

        const val HELPER_FAILED_MESSAGE =
            "chroot helper 未就绪，命令未执行。请检查 设置 → Shell 容器 的 chroot 状态/最近错误"
    }
}
