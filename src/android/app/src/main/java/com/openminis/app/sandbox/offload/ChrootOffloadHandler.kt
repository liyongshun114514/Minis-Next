package com.openminis.app.sandbox.offload

import android.content.Context
import android.os.Process
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.RootAccess
import com.openminis.app.sandbox.ChrootKernel
import com.openminis.app.sandbox.ActiveSandboxTask
import com.openminis.app.sandbox.ChrootPrivilege
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SandboxActivityRegistry
import com.openminis.app.sandbox.SandboxBackend
import com.openminis.app.sandbox.SandboxBackendStore
import com.openminis.app.sandbox.SandboxCommandSpec
import com.openminis.app.sandbox.SandboxSpawn
import com.openminis.app.sandbox.SpawnSignalMask
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [FEAT-261027-CHROOT-CLI] `android-chroot-cli` — one-shot chroot execution
 * for the agent, gated by a single in-app confirmation per call.
 *
 * What this handler is allowed to do:
 *  - `--help` / `status`: file and in-memory probes only. No `su`, no helper
 *    start, no process of any kind.
 *  - `exec`: ONE [OffloadPermissionManager.confirmPrivilegedOperation] (never
 *    cached, never skipped for Root BYPASS or a session grant), a Root
 *    re-check after the answer, then [ChrootKernel.ensureServer] and a direct
 *    [ChrootKernel.spawn] call. It never calls `SandboxRouter`, never writes
 *    the selected backend, and never touches the existing shell/terminal.
 *
 * The command travels to the already-privileged helper over its abstract
 * socket; it is never appended to an `su` argv. The client runs as this app's
 * uid/gid, with the helper enforcing CapEff=0 / NoNewPrivs for the guest.
 *
 * If the selected backend is PRoot, this call still uses chroot for exactly
 * this command (the user asked for it by name). The helper that had to be
 * started is left alone on purpose: stopping it would race a concurrent
 * CHROOT session, so its idle TTL is the retention policy and the output says
 * so.
 */
class ChrootOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = request.argv.drop(1)
        val sub = args.firstOrNull()
            ?: return NativeOffloadResult(2, "android-chroot-cli: 缺少子命令\n\n" + HELP)
        return when (sub) {
            "--help", "-h", "help" -> NativeOffloadResult(0, HELP)
            "status" -> status(args.drop(1))
            "exec" -> exec(args.drop(1), request)
            else -> NativeOffloadResult(2, "android-chroot-cli: 未知子命令 '$sub'\n\n" + HELP)
        }
    }

    // ── status: probes only ────────────────────────────────────────────────

    private fun status(rest: List<String>): NativeOffloadResult {
        if (rest.any { it == "--help" || it == "-h" }) return NativeOffloadResult(0, HELP)
        val unknown = rest.filterNot { it == "--compact" }
        if (unknown.isNotEmpty()) {
            return NativeOffloadResult(
                2,
                "android-chroot-cli status: 不支持的参数 '${unknown.first()}'（status 不执行任何进程）\n",
            )
        }
        return runCatching {
            val json = statusJson(ChrootCliStatus.snapshot(probeSource()))
            val text = if ("--compact" in rest) json.toString() else json.toString(2)
            NativeOffloadResult(0, text + "\n")
        }.getOrElse { t ->
            val body = JSONObject()
                .put("ok", false)
                .put("error", "status_failed")
                .put("message", "${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
                .toString(2)
            NativeOffloadResult(1, body + "\n")
        }
    }

    /** JSON shape of the pure [ChrootCliStatusSnapshot]. */
    private fun statusJson(s: ChrootCliStatusSnapshot): JSONObject = JSONObject()
        .put("tool", NAME)
        .put("probes_only", true)
        .put(
            "identity",
            JSONObject()
                .put("uid", s.uid)
                .put("gid", s.gid)
                .put("guest_user", "minis")
                .put("scope", "command child (helper itself is uid0)")
                .put("non_root", true)
                .put("cap_eff", 0)
                .put("no_new_privs", true),
        )
        .put(
            "backend",
            JSONObject()
                .put("selected", s.selectedBackend)
                // This CLI never writes the selected backend.
                .put("changed_by_this_tool", false),
        )
        .put(
            "chroot",
            JSONObject()
                .put(
                    "launcher",
                    JSONObject()
                        .put("path", s.launcherPath)
                        .put("installed", s.launcherInstalled)
                        .put("executable", s.launcherExecutable),
                )
                .put(
                    "rootfs",
                    JSONObject()
                        .put("path", s.rootfsPath)
                        .put("present", s.rootfsPresent),
                )
                .put(
                    "offload_client",
                    JSONObject()
                        .put("path", s.offloadClientPath)
                        .put("installed", s.offloadClientInstalled),
                )
                .put("generation", s.generation)
                .put(
                    "helper",
                    JSONObject()
                        // Not "running": a grant only records a past handshake.
                        .put("grant_recorded", s.helper.grantRecorded)
                        .put("grant_may_be_expired", s.helper.grantMayBeExpired)
                        .put("helper_liveness", s.helper.helperLiveness)
                        .put("autostart", s.helper.autostart)
                        .put("state_marker", s.helper.stateMarker ?: JSONObject.NULL)
                        .put("last_error", s.helper.lastError ?: JSONObject.NULL),
                )
                .put("root_level", s.rootLevel)
                .put("root_switch_allowed", s.rootSwitchAllowed)
                .put("prerequisites_met", s.prerequisitesMet),
        )
        .put(
            "exec",
            JSONObject()
                .put("default_timeout_seconds", CHROOT_CLI_DEFAULT_TIMEOUT_SECONDS)
                .put("max_timeout_seconds", CHROOT_CLI_MAX_TIMEOUT_SECONDS)
                .put("requires_confirmation_each_call", true),
        )
        .put(
            "notes",
            JSONArray().apply { s.notes.forEach { put(it) } },
        )

    /**
     * Every value here comes from a stat, a preference or the helper's state
     * file. `ChrootKernel.availability()` is intentionally NOT used: it calls
     * RootManager.refresh(), which resolves `su` paths; status must stay a
     * zero-exec question.
     */
    private fun probeSource(): ChrootCliStatusSource {
        val rootfs = ChrootKernel.rootfs(context)
        val launcher = ChrootKernel.launcher(context)
        val client = ChrootKernel.offloadClientPath(context)
        val generation = SandboxBackendStore.generation(context)
        val level = OffloadPermissionManager.rootLevel()
        val marker = runCatching {
            // Read-only mirror of ChrootKernel's private STATE_DIR. The file is
            // written at handshake; it can be stale, hence the note in the JSON.
            File(rootfs, ".minis-chroot/helper.state")
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.take(300)
        }.getOrNull()
        val uid = Process.myUid()
        return object : ChrootCliStatusSource {
            override val launcherPath: String = launcher.absolutePath
            override val launcherInstalled: Boolean = launcher.isFile
            override val launcherExecutable: Boolean = launcher.isFile && launcher.canExecute()
            override val rootfsPath: String = rootfs.absolutePath
            override val rootfsPresent: Boolean = rootfs.isDirectory
            override val offloadClientPath: String = client.absolutePath
            override val offloadClientInstalled: Boolean = client.isFile && client.canExecute()
            override val selectedBackend: String = SandboxBackendStore.selected(context).name
            override val generation: String = generation
            override val runtimeGrantPresent: Boolean =
                SandboxBackendStore.chrootGranted(generation)
            override val grantMayBeExpired: Boolean =
                SandboxBackendStore.chrootGrantMayBeExpired(generation)
            override val autostart: Boolean = SandboxBackendStore.chrootAutostart(context)
            override val helperStateMarker: String? = marker
            override val lastError: String? = SandboxBackendStore.lastError(context)
            override val rootLevel: String = level.name
            override val rootSwitchAllowed: Boolean = level != RootAccess.NOT_ALLOWED
            override val uid: Int = uid
            // Android app processes use the same number for gid; ChrootKernel
            // passes Process.myUid() as gid for exactly this reason.
            override val gid: Int = uid
        }
    }

    // ── exec: one confirmation, then the existing chroot path ──────────────

    private fun exec(args: List<String>, request: NativeOffloadRequest): NativeOffloadResult =
        when (val parsed = ChrootCliArgs.parseExec(args, request.sessionId)) {
            ChrootCliParse.Help -> NativeOffloadResult(0, HELP)
            is ChrootCliParse.Failure ->
                NativeOffloadResult(2, "android-chroot-cli: ${parsed.message}\n\n" + HELP)
            is ChrootCliParse.Exec -> runExec(parsed.request)
        }

    private fun runExec(request: ChrootCliExecRequest): NativeOffloadResult {
        val generation = SandboxBackendStore.generation(context)
        val backendBefore = SandboxBackendStore.selected(context)
        val hadGrantBefore = SandboxBackendStore.chrootGranted(generation)
        val uid = Process.myUid()
        val identity = ChrootCliIdentity(
            uid = uid,
            gid = uid,
            rootfs = ChrootKernel.rootfs(context).absolutePath,
            socketName = ChrootKernel.socketName(generation),
            idleTtlSeconds = ChrootKernel.DEFAULT_IDLE_TTL_SECONDS,
        )

        val coordinator = ChrootCliCoordinator(
            rootSwitchAllowed = { ChrootPrivilege.switchAllowed() },
            confirm = { req ->
                // Always a fresh single-use dialog. confirmPrivilegedOperation
                // ignores BYPASS and session grants by construction.
                OffloadPermissionManager.confirmPrivilegedOperation(
                    ChrootCliPresentation.TOOL_NAME,
                    ChrootCliPresentation.title(req),
                    ChrootCliPresentation.describe(req, identity),
                )
            },
            ensureServer = {
                // [FEAT-261027-CHROOT-CLI] One-shot: never persist the
                // restart-time autostart decision from an explicit CLI call.
                // ChrootKernel skips the write when this is false, so a
                // concurrent settings change is never overwritten.
                ChrootKernel.ensureServer(
                    context,
                    "android-chroot-cli: one-shot chroot command (app uid, private mount namespace)",
                    persistAutostart = false,
                )
            },
            execute = { req ->
                executeInChroot(req, backendBefore, hadGrantBefore, generation, identity)
            },
        )

        return when (val outcome = runBlocking { coordinator.exec(request) }) {
            is ChrootCliOutcome.Refused -> refusal(outcome.reason, outcome.message)
            is ChrootCliOutcome.Completed -> NativeOffloadResult(
                outcome.run.exitCode,
                render(outcome.run, request),
            )
        }
    }

    /**
     * The command itself: build the CHROOT spec, ask ChrootKernel directly
     * (never SandboxRouter), then run the app-uid client with a hard timeout
     * and parallel pipes.
     */
    private fun executeInChroot(
        request: ChrootCliExecRequest,
        backendBefore: SandboxBackend,
        hadGrantBefore: Boolean,
        generationBefore: String,
        identity: ChrootCliIdentity,
    ): ChrootCliRun {
        // Visible to a backend switch: it either refuses or explicitly stops
        // this task, and the callback kills ONLY this client process.
        val handle = ChrootClientProcess.Handle()
        val preview = request.command.lineSequence().firstOrNull()?.trim().orEmpty().take(60)
        val activityId = SandboxActivityRegistry.begin(
            ActiveSandboxTask(
                id = "chroot-cli-${System.identityHashCode(handle)}",
                label = "android-chroot-cli: $preview",
                stop = { handle.stop() },
            ),
        )
        try {
            // [FEAT-261027-CHROOT-CLI] Guest-side offload client for THIS
            // backend: only this file inside the rootfs is installed.
            // installOffloadStubs() is deliberately NOT called — it rewrites
            // the globally shared /usr/local/bin wrappers.
            val offloadClient = runCatching { ChrootKernel.installOffloadClient(context) }
                .getOrDefault(false)
            val spawn = ChrootKernel.spawn(
                context,
                SandboxCommandSpec(
                    command = request.command,
                    interactive = false,
                    loginShell = false,
                    sessionId = request.sessionId,
                    // null = process-global bind mounts, the same table a
                    // normal shell_execute uses. Mount policy is unchanged.
                    sessionMounts = null,
                ),
            )
            if (spawn.backend != SandboxBackend.CHROOT) {
                return ChrootCliRun(
                    exitCode = 125,
                    stderr = "内部错误：ChrootKernel.spawn 返回了 ${spawn.backend}，拒绝在 chroot 外执行。",
                )
            }
            val run = ChrootClientProcess.run(spawn, request.timeoutSeconds, handle)
            val notes = run.notes.toMutableList()
            notes += spawn.notes
            if (spawn.warning != null) notes += spawn.warning
            if (run.exitCode == ChrootKernel.EXIT_NO_HELPER) {
                notes += "helper 未监听本次请求（exit ${ChrootKernel.EXIT_NO_HELPER}）；" +
                    "请重试一次，或到 设置 → Shell 容器 检查 chroot 状态。"
            } else if (run.exitCode == ChrootKernel.EXIT_REJECTED) {
                notes += "helper 拒绝了本次请求（exit ${ChrootKernel.EXIT_REJECTED}）；" +
                    "mount/uid 等校验未通过，命令未执行。"
            }
            if (!offloadClient) {
                notes += "guest offload client 未安装（assets 缺失或写入失败）：" +
                    "chroot 内 Android/Minix 工具为 BLOCKED；命令本身仍按 app uid 执行。"
            }
            if (backendBefore == SandboxBackend.PROOT) {
                notes += "本次未改变全局 selected backend（仍为 PRoot）；仅这一条命令走 chroot。"
            }
            val generationNow = SandboxBackendStore.generation(context)
            if (backendBefore == SandboxBackend.PROOT &&
                !hadGrantBefore &&
                generationNow == generationBefore &&
                SandboxBackendStore.chrootGranted(generationNow)
            ) {
                // Deliberate retention: shutdown() would rotate the generation
                // and revoke the grant, which would break a CHROOT session
                // another task may have started while this command ran.
                notes += "本次按需启动了临时 chroot helper（未切换后端）。特权 helper 保留至空闲 " +
                    "${identity.idleTtlSeconds}s 后自退（helper 本身为 uid0，仅命令子进程降为 app uid）；" +
                    "为不打断并发 CHROOT 会话，本命令不调用 shutdown。"
            }
            return run.copy(notes = notes.distinct())
        } finally {
            SandboxActivityRegistry.end(activityId)
        }
    }

    // ── output ─────────────────────────────────────────────────────────────

    private fun refusal(reason: ChrootCliRefusal, message: String): NativeOffloadResult {
        val code = when (reason) {
            ChrootCliRefusal.ROOT_DISABLED, ChrootCliRefusal.ROOT_REVOKED -> "root_not_allowed"
            ChrootCliRefusal.DENIED -> "permission_denied"
            ChrootCliRefusal.HELPER_FAILED -> "helper_not_ready"
        }
        val body = JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message)
            .toString(2)
        return NativeOffloadResult(126, body + "\n")
    }

    private fun render(run: ChrootCliRun, request: ChrootCliExecRequest): String {
        val sb = StringBuilder(run.stdout)
        fun newlineIfNeeded() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }
        if (run.stderr.isNotEmpty()) {
            newlineIfNeeded()
            sb.append(run.stderr)
        }
        if (run.cancelled) {
            newlineIfNeeded()
            sb.append("[android-chroot-cli] 已被停止（后端切换/显式停止），")
                .append("仅终止本次 chroot 客户端进程，退出码 130。")
        }
        if (run.timedOut) {
            newlineIfNeeded()
            sb.append("[android-chroot-cli] 超时（${request.timeoutSeconds} 秒），")
                .append("已终止本次 chroot 客户端进程，退出码 124。")
        }
        if (run.truncated) {
            newlineIfNeeded()
            sb.append("[android-chroot-cli] 输出超过单流 ${ChrootClientProcess.MAX_CAPTURED_BYTES / 1024} KiB，已截断。")
        }
        for (note in run.notes) {
            if (note.isBlank()) continue
            newlineIfNeeded()
            sb.append("[android-chroot-cli] ").append(note)
        }
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb.toString()
    }

    companion object {
        /** Kept in sync with the guest wrapper that PRoot/chroot install. */
        const val NAME = "android-chroot-cli"

        private val HELP: String get() = ChrootCliPresentation.help()
    }
}

/**
 * Runs the app-uid chroot client for one command.
 *
 * - stdin is `/dev/null`: the offload call has no terminal;
 * - stdout/stderr are drained by two threads, each capped independently, so a
 *   chatty command can never block on a full 64 KiB pipe;
 * - on timeout only THIS child (`destroyForcibly`) is killed and the real
 *   exit code is replaced by 124; a completed command returns the client's
 *   actual status.
 */
private object ChrootClientProcess {
    /** Per-stream capture cap. The rest is still drained and discarded. */
    const val MAX_CAPTURED_BYTES = 256 * 1024

    /** How long a pump may take to reach EOF after the child has exited. */
    private const val PUMP_JOIN_MS = 2_000L

    /** How long SIGKILL is given to be reaped. */
    private const val KILL_WAIT_MS = 5_000L

    /**
     * Stop seam shared with [SandboxActivityRegistry]: it can kill only the
     * client process this run started, never the helper or another session.
     */
    class Handle {
        @Volatile
        private var process: java.lang.Process? = null

        @Volatile
        var stopRequested: Boolean = false
            private set

        fun stop() {
            stopRequested = true
            runCatching { process?.destroyForcibly() }
        }

        fun attach(p: java.lang.Process) {
            process = p
            // stop() may race the spawn; honour it immediately.
            if (stopRequested) runCatching { p.destroyForcibly() }
        }

        fun detach() {
            process = null
        }
    }

    fun run(spawn: SandboxSpawn, timeoutSeconds: Int, handle: Handle): ChrootCliRun {
        val pb = ProcessBuilder(spawn.argv)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        pb.redirectErrorStream(false)
        // No inherited environment: spawn.env is the complete guest-side
        // environment (PATH/HOME/TZ/proxy/MINIS_NOFF_SOCKET/...). Nothing
        // privileged is injected here.
        pb.environment().apply {
            clear()
            putAll(spawn.env)
        }

        val process = SpawnSignalMask.start(pb)
        handle.attach(process)
        try {
            val stdout = ByteSink(MAX_CAPTURED_BYTES)
            val stderr = ByteSink(MAX_CAPTURED_BYTES)
            val outThread = pump("chroot-cli-stdout", process.inputStream, stdout)
            val errThread = pump("chroot-cli-stderr", process.errorStream, stderr)

            val finished = runCatching {
                process.waitFor(timeoutSeconds.toLong().coerceAtLeast(1L), TimeUnit.SECONDS)
            }.getOrDefault(false)

            // A stop is an explicit cancellation, not a timeout: only count it
            // while the child was still running, so a natural exit that races
            // the stop still reports its real code.
            val cancelled = !finished && handle.stopRequested
            var timedOut = false
            if (!finished) {
                if (!cancelled) timedOut = true
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
            }

            // EOF normally arrives with the child. A descendant that inherited
            // the pipe may hold it open, so the join is bounded and the pipe
            // is closed.
            runCatching { outThread.join(PUMP_JOIN_MS) }
            runCatching { errThread.join(PUMP_JOIN_MS) }
            if (outThread.isAlive) runCatching { process.inputStream.close() }
            if (errThread.isAlive) runCatching { process.errorStream.close() }

            val exit = when {
                cancelled -> 130
                timedOut -> 124
                else -> runCatching { process.exitValue() }.getOrDefault(125)
            }
            return ChrootCliRun(
                exitCode = exit,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = timedOut,
                cancelled = cancelled,
                truncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            handle.detach()
        }
    }

    private fun pump(name: String, stream: InputStream, sink: ByteSink): Thread =
        Thread({ sink.drain(stream) }, name).apply {
            isDaemon = true
            start()
        }

    private class ByteSink(private val cap: Int) {
        private val buffer = ByteArrayOutputStream()
        @Volatile var truncated: Boolean = false

        fun drain(stream: InputStream) {
            val chunk = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) {
                        val remaining = cap - buffer.size()
                        if (remaining <= 0) {
                            truncated = true
                        } else {
                            val take = minOf(remaining, n)
                            buffer.write(chunk, 0, take)
                            if (take < n) truncated = true
                        }
                    }
                }
            } catch (_: Exception) {
                // Closed by the timeout path: keep everything already read.
            }
        }

        fun text(): String = synchronized(buffer) {
            buffer.toByteArray().toString(StandardCharsets.UTF_8)
        }
    }
}
