package com.openminis.app.offload

import android.content.Context
import android.os.Looper
import app.openminis.rootbridge.RootExecutor
import app.openminis.rootbridge.RootExecutorConfig
import app.openminis.rootbridge.ShellCommand
import app.openminis.rootbridge.WireProtocol
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** Native su backend, parallel to ShizukuManager. Startup/refresh never execute su. */
object RootManager {
    enum class State { DISABLED, NOT_FOUND, NEED_PERMISSION, READY, DENIED }
    data class Snapshot(val state: State, val suPath: String? = null, val uid: Int = -1,
                        val message: String = "Not tested; no su command executed")
    private val _snapshot = MutableStateFlow(Snapshot(State.DISABLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    @Volatile private var appContext: Context? = null
    private const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // refresh() reads the permission level through SharedPreferences;
        // initialising it here makes RootManager usable even if a future
        // startup reorder (or a standalone activity) calls us first.
        OffloadPermissionManager.init(app)
        refresh()
    }
    fun isEnabled(): Boolean = OffloadPermissionManager.isInitialized() &&
        OffloadPermissionManager.getLevel("root_cli") !=
        OffloadPermissionManager.PermissionLevel.NOT_ALLOWED
    fun setEnabled(enabled: Boolean) {
        OffloadPermissionManager.setLevel("root_cli", if (enabled)
            OffloadPermissionManager.PermissionLevel.ASK_ONCE else
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        refresh()
    }
    private fun config(timeoutMs: Long = 120_000): RootExecutorConfig = RootExecutorConfig().apply {
        enabled = isEnabled()
        suPath = appContext?.getSharedPreferences("native_root", Context.MODE_PRIVATE)
            ?.getString("su_path", "").orEmpty()
        timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(10, 900).toInt()
    }
    fun refresh() {
        if (!isEnabled()) { _snapshot.value = Snapshot(State.DISABLED); return }
        val path = RootExecutor(config()).resolveSu()
        val previous = _snapshot.value
        _snapshot.value = when {
            path == null -> Snapshot(State.NOT_FOUND, message = "No executable su found; Root is not available")
            previous.suPath == path && previous.state in setOf(State.READY, State.DENIED) -> previous
            else -> Snapshot(State.NEED_PERMISSION, path)
        }
    }

    /** Every invocation, including id, has a fresh native decision displaying the exact argv. */
    fun runProcess(argv: Array<String>, env: Array<String>? = null, cwd: String? = null,
                   timeoutMs: Long = 5_000): ShizukuManager.ProcessResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return failure("Root commands cannot run on the UI thread")
        if (!isEnabled()) return failure("Root is disabled. Open Settings → Permissions → Native Root.")
        if (argv.isEmpty() || argv.any { '\u0000' in it }) return failure("Invalid argv")
        // Host environment is intentionally not imported from the Linux guest.
        if (!env.isNullOrEmpty()) return failure("Custom environment is not accepted by the native Root backend")
        val request = WireProtocol.Request(argv.toList(), cwd.orEmpty(), false)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit = 126
        // The UI text, the executor override and the kill deadline must all
        // describe the same number; the old copy showed 1s for a 5s request
        // clamped to 10s by RootExecutorConfig.
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 900).toInt()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        // Capture never answers "client gone": exceeding the cap truncates the
        // stream instead of making RootExecutor kill the running command.
        fun captureInto(out: ByteArrayOutputStream, data: ByteArray): Boolean {
            synchronized(out) {
                val remaining = MAX_CAPTURE_BYTES - out.size()
                if (remaining <= 0) {
                    truncated.set(true)
                    return true
                }
                val take = minOf(remaining, data.size)
                out.write(data, 0, take)
                if (take < data.size) truncated.set(true)
            }
            return true
        }
        val executor = RootExecutor(config(timeoutMs), timeoutSec, RootExecutor.Authorizer { exact ->
            if (!isEnabled()) false else runBlocking {
                // The command is the request title so the dialog can render it
                // verbatim (collapsed with an explicit expand action); the
                // description carries only the risk line and execution context.
                val command = ShellCommand.joinQuoted(exact.argv)
                OffloadPermissionManager.confirmPrivilegedOperation(
                    "root_cli", command, rootConfirmRisk(exact.cwd, timeoutSec),
                ) && isEnabled()
            }
        })
        executor.execute(request, object : RootExecutor.Sink {
            override fun stdout(data: ByteArray): Boolean = captureInto(stdout, data)
            override fun stderr(data: ByteArray): Boolean = captureInto(stderr, data)
            override fun finish(code: Int) { exit = code }
        })
        val stderrText = buildString {
            append(stderr.toString("UTF-8"))
            if (truncated.get()) {
                if (isNotEmpty()) append('\n')
                append("[minis-root: output truncated at 2 MiB per stream; the command was not killed]\n")
            }
        }
        val result = ShizukuManager.ProcessResult(exit, stdout.toString("UTF-8"), stderrText)
        if (argv.contentEquals(arrayOf("/system/bin/id")) || argv.contentEquals(arrayOf("id"))) {
            val uid = if (exit == 0) ShellCommand.parseUid(result.stdout) else -1
            _snapshot.value = Snapshot(if (uid == 0) State.READY else State.DENIED,
                executor.resolveSu(), uid, if (uid == 0) "Root verified for this probe; future commands still require approval" else result.combined)
        }
        return result
    }
    private fun failure(message: String) = ShizukuManager.ProcessResult(126, "", message)
}
