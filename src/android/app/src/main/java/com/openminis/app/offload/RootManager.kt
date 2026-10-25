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

    fun init(context: Context) { appContext = context.applicationContext; refresh() }
    fun isEnabled(): Boolean = OffloadPermissionManager.getLevel("root_cli") !=
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
        val executor = RootExecutor(config(timeoutMs), ((timeoutMs + 999) / 1000).coerceIn(1, 900).toInt(), RootExecutor.Authorizer { exact ->
            if (!isEnabled()) false else runBlocking {
                OffloadPermissionManager.confirmPrivilegedOperation(
                    "root_cli", "Root · ${ShellCommand.joinQuoted(exact.argv)}",
                    "Execute this exact Android command via su with full device privileges?\n\n" +
                        ShellCommand.joinQuoted(exact.argv) + "\nWorking directory: ${exact.cwd.ifEmpty { "Android default" }}\n" +
                        "Timeout: ${(timeoutMs / 1000).coerceAtLeast(1)}s. Commands may modify or delete system/user data. " +
                        "This approval covers only this invocation, not later commands. Reject if the target or recovery plan is unclear.",
                ) && isEnabled()
            }
        })
        executor.execute(request, object : RootExecutor.Sink {
            override fun stdout(data: ByteArray): Boolean = capture(stdout, data)
            override fun stderr(data: ByteArray): Boolean = capture(stderr, data)
            override fun finish(code: Int) { exit = code }
        })
        val result = ShizukuManager.ProcessResult(exit, stdout.toString("UTF-8"), stderr.toString("UTF-8"))
        if (argv.contentEquals(arrayOf("/system/bin/id")) || argv.contentEquals(arrayOf("id"))) {
            val uid = if (exit == 0) ShellCommand.parseUid(result.stdout) else -1
            _snapshot.value = Snapshot(if (uid == 0) State.READY else State.DENIED,
                executor.resolveSu(), uid, if (uid == 0) "Root verified for this probe; future commands still require approval" else result.combined)
        }
        return result
    }
    @Synchronized private fun capture(out: ByteArrayOutputStream, data: ByteArray): Boolean {
        if (out.size() + data.size > MAX_CAPTURE_BYTES) return false
        out.write(data)
        return true
    }
    private fun failure(message: String) = ShizukuManager.ProcessResult(126, "", message)
}
