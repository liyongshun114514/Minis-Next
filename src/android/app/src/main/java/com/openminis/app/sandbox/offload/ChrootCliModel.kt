package com.openminis.app.sandbox.offload

/**
 * [FEAT-261027-CHROOT-CLI] Pure model for `android-chroot-cli`.
 *
 * Nothing in this file touches Android, Root, `su` or a process. The handler
 * builds these values from probes and the [ChrootCliCoordinator] decides
 * whether anything may run; keeping the decision data here lets the unit tests
 * exercise the approval order on a plain JVM.
 */

/** Default budget for one `exec`; the user can ask for up to [MAX]. */
internal const val CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS = 60

/**
 * Upper bound for one `exec`.
 *
 * An offload handler keeps its worker thread until the command ends, and the
 * permission dialog itself is bounded at 120 s. Ten minutes is long enough for
 * a build step, short enough that a forgotten command cannot hold a thread for
 * the life of the process (see NativeOffloadServer.MAX_CONCURRENT_CALLS).
 */
internal const val CHROOT_CLI_MAX_TIMEOUT_SECONDS = 600

/** One approved-or-not request; [command] is the exact shell string shown to the user. */
internal data class ChrootCliExecRequest(
    val command: String,
    val timeoutSeconds: Int = CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS,
    /** Chat session id forwarded by the guest shell; only used as spawn env. */
    val sessionId: String? = null,
)

/** What one client run produced. `exitCode` is the client's real exit status. */
internal data class ChrootCliRun(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    /** Extra, user-visible facts (spawn notes, temporary-helper retention). */
    val notes: List<String> = emptyList(),
)

internal enum class ChrootCliRefusal {
    /** Root is 不允许: nothing may start, and the switch is never flipped here. */
    ROOT_DISABLED,

    /** Root was revoked between the confirmation and the helper start. */
    ROOT_REVOKED,

    /** The user denied, cancelled, or the confirmation timed out. */
    DENIED,

    /** Root/helper startup failed; no command was run. */
    HELPER_FAILED,
}

internal sealed class ChrootCliOutcome {
    data class Completed(val run: ChrootCliRun) : ChrootCliOutcome()
    data class Refused(val reason: ChrootCliRefusal, val message: String) : ChrootCliOutcome()
}

/**
 * Parsed `exec` arguments. Help is separate from failure so `exec --help`
 * exits 0 while a bad option exits 2, matching the other android-* CLIs.
 */
internal sealed class ChrootCliParse {
    data class Exec(val request: ChrootCliExecRequest) : ChrootCliParse()
    data class Failure(val message: String) : ChrootCliParse()
    object Help : ChrootCliParse()
}

internal object ChrootCliArgs {
    /**
     * Parse the arguments AFTER the `exec` word.
     *
     * `--command` takes the next argv element verbatim, even when it starts
     * with `-`: the guest shell already split the command, and re-quoting it
     * would change what the user approved. `--timeout` is seconds.
     */
    fun parseExec(args: List<String>, sessionId: String?): ChrootCliParse {
        var command: String? = null
        var timeout = CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--help" || arg == "-h" -> return ChrootCliParse.Help
                arg == "--command" -> {
                    val value = args.getOrNull(i + 1)
                        ?: return ChrootCliParse.Failure("--command 缺少 <shell string>")
                    command = value
                    i += 2
                }
                arg.startsWith("--command=") -> {
                    command = arg.removePrefix("--command=")
                    i += 1
                }
                arg == "--timeout" -> {
                    val value = args.getOrNull(i + 1)
                        ?: return ChrootCliParse.Failure("--timeout 缺少秒数")
                    timeout = parseTimeout(value) ?: return ChrootCliParse.Failure(timeoutHint(value))
                    i += 2
                }
                arg.startsWith("--timeout=") -> {
                    val value = arg.removePrefix("--timeout=")
                    timeout = parseTimeout(value) ?: return ChrootCliParse.Failure(timeoutHint(value))
                    i += 1
                }
                arg.startsWith("-") -> return ChrootCliParse.Failure("未知选项 '$arg'")
                else -> return ChrootCliParse.Failure("多余参数 '$arg'；命令请放在 --command 之后")
            }
        }
        val cmd = command ?: return ChrootCliParse.Failure("缺少 --command <shell string>")
        if (cmd.isBlank()) return ChrootCliParse.Failure("--command 不能为空")
        return ChrootCliParse.Exec(
            ChrootCliExecRequest(command = cmd, timeoutSeconds = timeout, sessionId = sessionId),
        )
    }

    private fun parseTimeout(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it in 1..CHROOT_CLI_MAX_TIMEOUT_SECONDS }

    private fun timeoutHint(raw: String): String =
        "--timeout '$raw' 无效：必须是 1..$CHROOT_CLI_MAX_TIMEOUT_SECONDS 的整数秒" +
            "（默认 $CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS）"
}

/** Facts the confirmation text must state without over-claiming. */
internal data class ChrootCliIdentity(
    val uid: Int,
    val gid: Int,
    val rootfs: String,
    val socketName: String,
    val idleTtlSeconds: Int,
)

/**
 * The exact text of the single per-exec confirmation.
 *
 * It is deliberately concrete: the command verbatim, the timeout that will be
 * enforced, the uid/gid the helper drops to, where the rootfs lives, that the
 * mount namespace is private to this command, and that the privileged helper
 * is temporary and never receives the command as a `su` argv word.
 */
internal object ChrootCliPresentation {
    const val TOOL_NAME = "android-chroot-cli"

    fun title(request: ChrootCliExecRequest): String {
        val oneLine = request.command.lineSequence().firstOrNull()?.trim().orEmpty()
        val preview = if (oneLine.length > 64) oneLine.take(61) + "..." else oneLine
        return "chroot 执行（仅本次）：$preview"
    }

    fun describe(request: ChrootCliExecRequest, identity: ChrootCliIdentity): String =
        buildString {
            appendLine("即将在 chroot 沙箱内执行以下命令（原样交给 /bin/sh -c，不拼进 su 命令行）：")
            appendLine()
            appendLine("    ${request.command}")
            appendLine()
            appendLine("超时：${request.timeoutSeconds} 秒；超时只终止本次命令的 app-uid chroot 客户端进程，返回退出码 124。")
            appendLine(
                "运行身份：Android app uid=${identity.uid} gid=${identity.gid}" +
                    "（guest 账号 minis，HOME=/home/minis；命令子进程非 root；" +
                    "helper 执行 setgroups(0) + PR_SET_NO_NEW_PRIVS + setgid/setuid，CapEff=0）。",
            )
            appendLine("文件系统：rootfs=${identity.rootfs}；每条命令一个私有挂载命名空间" +
                "（unshare(CLONE_NEWNS)），随进程消失，不改 SELinux、分区或系统设置。")
            appendLine(
                "临时 helper：已有则复用，否则按现有 Root 授权启动 libminis-chroot.so" +
                    "（helper 本身为 uid0 特权进程；抽象 socket ${identity.socketName}，" +
                    "空闲 ${identity.idleTtlSeconds}s 自退）。只有 helper 启动这一动作可能经 RootManager " +
                    "调用 su；你的命令经本地 socket 以 app uid 交给 helper，永远不会出现在 su 的命令行中。",
            )
            appendLine("本次确认只对这一次执行有效。拒绝、取消或确认超时都不会启动 helper 或任何进程。")
        }.trimEnd()

    fun help(): String = """
        android-chroot-cli — 在现有 chroot 后端里执行一条命令（每次执行都要用户在应用内确认）

        用法：
          android-chroot-cli --help
          android-chroot-cli status
          android-chroot-cli exec --command '<shell string>' [--timeout 60]

        说明：
          --help, status   只读文件/内存状态（launcher、rootfs、grant、Root 级别、最近错误）。
                           不调用 su，不启动 helper，不执行任何进程。
          exec             每次都调用一次单次确认（ALLOW_ONCE）；Root 的 BYPASS/会话授权不会跳过它。
                           确认后才按现有 Root 授权确保 helper，再直接走 ChrootKernel 生成 CHROOT argv。
                           不改全局 selected backend，不影响当前 shell/终端。
          --command        原样传给 chroot 内的 /bin/sh -c；不要在这里再套 su。
          --timeout        1..$CHROOT_CLI_MAX_TIMEOUT_SECONDS 秒，默认 $CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS。
                           超时终止本次客户端进程并返回 124。
          stdout/stderr    并行读取，各自上限 256 KiB（超出截断并注明）。

        运行身份：
          app uid（非 root，CapEff=0，NoNewPrivs=1），私有 mount 命名空间；命令不会进入 su argv。

        退出码：
          命令真实退出码；126 = 拒绝/取消/未授权/helper 失败；124 = 超时；130 = 被停止；2 = 参数错误。
    """.trimIndent()
}
