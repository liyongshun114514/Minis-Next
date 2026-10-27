package com.openminis.app.offload

import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * [FEAT-261027-CHROOT-ROOT] The ONE gate that turns the unified Root tri-state
 * into a uid-0 chroot spawn.
 *
 * Design rules (all of them audited/testable):
 *
 *  1. An approval is a [RootExecGrant], never a boolean: it is bound to the
 *     exact [RootExecRequest.fingerprint] (command + interactive + session +
 *     required), to the Root authorisation epoch, and it expires after
 *     [RootExecPolicy.GRANT_TTL_MS]. A cached "approved=true" can therefore
 *     neither authorise a different command nor survive a revocation.
 *  2. A stashed grant is consumed EXACTLY once ([consume] removes it even when
 *     it turns out to be stale), so a second spawn of the same command needs
 *     its own confirmation.
 *  3. Refusal, cancel and the 120 s dialog timeout all return null. The caller
 *     then builds a refusal spawn: nothing runs, and nothing is downgraded to
 *     the app uid.
 *  4. A dialog is only ever raised off the main thread; a main-thread caller
 *     gets null instead of blocking the UI.
 */
object RootExecPermission {
    /**
     * Grants minted by a caller that already showed the user a confirmation
     * (`android-chroot-cli`'s own dialog) and that the spawn will consume.
     * Keyed by fingerprint; at most one grant per fingerprint, single use.
     */
    private val stashed = ConcurrentHashMap<String, RootExecGrant>()

    /**
     * True when a dialog may be shown from the calling thread.
     *
     * Most spawns run on a Dispatchers.IO worker, which has NO Looper at all —
     * that is a perfectly good place to suspend on the dialog. Only the
     * Android main thread (which would freeze the UI for up to 120 s) is
     * refused.
     */
    fun canPromptHere(): Boolean = runCatching {
        // A real Android main looper must exist. JVM Android stubs may return
        // null for BOTH calls; that is not an IO thread with a working UI.
        val main = Looper.getMainLooper() ?: return@runCatching false
        val looper = Looper.myLooper()
        looper == null || looper != main
    }.getOrDefault(false)

    fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Record the approval the caller just obtained for [request] and that the
     * immediately following spawn must consume. Old grants are replaced, and
     * a grant for a DIFFERENT command is never touched.
     */
    fun stash(
        request: RootExecRequest,
        epoch: Long = OffloadPermissionManager.rootAuthorizationEpoch(),
        nowMs: Long = nowMs(),
    ): RootExecGrant {
        prune(nowMs, epoch)
        val grant = RootExecGrant(request.fingerprint, epoch, nowMs)
        stashed[request.fingerprint] = grant
        return grant
    }

    /**
     * Take (and destroy) the grant for [request]. Returns null when there is
     * none, when it belongs to another command, when the Root epoch changed
     * (revoked/downgraded) or when it is older than the TTL.
     */
    fun consume(
        request: RootExecRequest,
        level: RootAccess = OffloadPermissionManager.rootLevel(),
        epoch: Long = OffloadPermissionManager.rootAuthorizationEpoch(),
        nowMs: Long = nowMs(),
    ): RootExecGrant? {
        val grant = stashed.remove(request.fingerprint) ?: return null
        if (level == RootAccess.NOT_ALLOWED) return null
        return grant.takeIf { RootExecPolicy.grantStillValid(it, request, epoch, nowMs) }
    }

    /** Drop only this request's pending grant (a refusal/abort, not a revoke). */
    fun discard(request: RootExecRequest) {
        stashed.remove(request.fingerprint)
    }

    /** Drop expired or foreign-epoch grants. Cheap; called on every stash/ask. */
    fun prune(nowMs: Long = nowMs(), epoch: Long = OffloadPermissionManager.rootAuthorizationEpoch()) {
        stashed.entries.removeAll { (_, g) -> g.epoch != epoch || nowMs - g.mintedAtMs !in 0..RootExecPolicy.GRANT_TTL_MS }
    }

    /**
     * Ask the user ONCE for this exact chroot command/session.
     *
     * @return a grant usable for this one spawn, or null when the level is not
     *   每次询问, the thread cannot host a dialog, or the user refused /
     *   cancelled / the dialog timed out. Never changes the stored level.
     */
    fun requestApproval(
        request: RootExecRequest,
        level: RootAccess = OffloadPermissionManager.rootLevel(),
        title: String = if (request.interactive) "允许以 Root 打开 chroot 终端？" else "允许以 Root 执行 chroot 命令？",
        description: String = rootExecRequestDescription(request),
    ): RootExecGrant? {
        if (level != RootAccess.ASK_ONCE) return null
        if (!canPromptHere()) return null
        prune()
        val epochBefore = OffloadPermissionManager.rootAuthorizationEpoch()
        val allowed = runBlocking {
            OffloadPermissionManager.confirmPrivilegedOperation(
                "root_cli",
                title,
                description,
            )
        }
        if (!allowed) return null
        // The user may have revoked or upgraded while the dialog was open.
        // 不允许 runs nothing; 完全允许 is re-decided by the caller without the
        // grant (the level alone authorises it).
        if (OffloadPermissionManager.rootLevel() != RootAccess.ASK_ONCE) return null
        if (OffloadPermissionManager.rootAuthorizationEpoch() != epochBefore) return null
        // Remember the confirmed action for the helper launch (same approval,
        // no second dialog) and mint the single-use command grant. Both are
        // pinned to the epoch the user actually answered under.
        RootManager.recordConfirmedAction(request.fingerprint, epoch = epochBefore)
        return RootExecGrant(request.fingerprint, epochBefore, nowMs())
    }

    /** User-visible body of the per-command confirmation. */
    fun rootExecRequestDescription(request: RootExecRequest): String {
        val what = if (request.interactive) {
            "这次交互会话（打开后该会话内的命令都以 uid0 运行）"
        } else {
            "这条命令"
        }
        // The command is shown VERBATIM and in full: truncating it would let a
        // harmless-looking prefix stand in for a different command at the end.
        // Newlines are preserved exactly as they will reach /bin/sh.
        val cmd = if (request.interactive) {
            "（交互 shell；会话 id=${request.sessionId ?: "无"}）"
        } else {
            request.command
        }
        return "Root 权限当前为“每次询问”。本次请求：$what\n" +
            "命令（逐字、完整）：\n$cmd\n\n" +
            "点“允许”只授权这一次：不会把权限改成“完全允许”，下一条命令/下一个终端会再次询问。" +
            "拒绝、取消或超时都不会执行任何进程。\n" +
            "这是设备上的真实 uid0（helper 通过 su 获得 Root 后 fork）：chroot 根目录和私有 mount " +
            "namespace 只是收窄可见范围，不是安全边界；uid0 命令仍可能修改容器内文件，" +
            "并通过共享挂载修改宿主文件，部分操作无法撤销。设备 su 管理器仍会单独授权，" +
            "本应用无法绕过。"
    }

    /** Test/administration hook: forget every stashed grant. */
    fun clear() = stashed.clear()
}
