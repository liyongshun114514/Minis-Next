package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes shell commands inside the PRoot sandbox via ProcessBuilder.
 * Corresponds to iOS ISHShellExecutor.
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L // 10 minutes

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    /** The currently running process, if any. Can be destroyed to stop execution. */
    @Volatile
    var currentProcess: Process? = null
        private set

    /** [FEAT-261025-CHROOT] Unique activity id per [execute] call. */
    private val activitySequence = AtomicLong()

    /**
     * [FEAT-261025-CHROOT] Per-call stop flag.
     *
     * A switch that the user confirmed can stop this task before
     * `SpawnSignalMask.start` has produced a process; `destroyCurrent()` alone
     * would be a no-op and the command would then run under the backend the
     * user just left. The flag closes that window.
     */
    private class ExecutionGuard {
        val stopped = AtomicBoolean(false)
    }

    /**
     * Execute a command inside the PRoot sandbox.
     *
     * @param context Android context for resolving PROOT_TMP_DIR
     * @param command Shell command string to execute
     * @param timeout Timeout in milliseconds (default 10 minutes)
     * @param environment Additional environment variables
     * @param lineCallback Called for each line of output (on IO dispatcher)
     * @return ShellResult with combined stdout+stderr, exit code, and duration
     */
    suspend fun execute(
        context: Context,
        command: String,
        timeout: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before executing commands" }

        // [FEAT-261025-CHROOT] This path is a fifth spawn site next to the
        // fresh / warm / terminal ones, but unlike them it was invisible to
        // a backend switch: a command started here would keep running while
        // the user flipped backends and could be cut by ChrootKernel.shutdown
        // without ever appearing in the confirmation dialog. Register before
        // the first spawn and unregister in `finally` so every exit path
        // (timeout, error, seccomp retry) clears it.
        val guard = ExecutionGuard()
        val activityId = SandboxActivityRegistry.begin(
            ActiveSandboxTask(
                id = "executor-${activitySequence.incrementAndGet()}",
                label = "shell command: ${command.lineSequence().firstOrNull()?.take(80).orEmpty()}",
                stop = {
                    guard.stopped.set(true)
                    destroyCurrent()
                },
            ),
        )
        try {
            executeRegistered(context, command, timeout, environment, lineCallback, guard)
        } finally {
            SandboxActivityRegistry.end(activityId)
        }
    }

    /** [FEAT-261025-CHROOT] [execute]'s body, kept separate so the activity
     *  registration above wraps the seccomp retry as ONE task. */
    private suspend fun executeRegistered(
        context: Context,
        command: String,
        timeout: Long,
        environment: Map<String, String>,
        lineCallback: ((String) -> Unit)?,
        guard: ExecutionGuard,
    ): ShellResult {
        val first = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = false, guard = guard)

        // [T-android-seccomp-selfheal / GH#186] If the child died on an early
        // fatal signal with no output at all, the host kernel's seccomp fast
        // path is the prime suspect — retry once without it. Gated hard so a
        // normal failing command is never re-run; see SeccompFallbackPolicy.
        if (!SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = first.exitCode,
                durationMs = first.durationMs,
                producedOutput = first.output.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            return first
        }

        com.openminis.app.logging.AppLogger.warning(
            TAG,
            SeccompFallbackPolicy.retryLogLine(first.exitCode, first.durationMs, "shell command"),
        )
        // The retry deliberately reuses the caller's lineCallback: the first
        // attempt produced no output (that is a precondition of retrying), so
        // there is nothing to duplicate.
        val retried = runOnce(context, command, timeout, environment, lineCallback, noSeccomp = true, guard = guard)
        if (retried.exitCode == 0) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                "[proot-retry] succeeded with ${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — " +
                    "this device needs the seccomp workaround (GH#186)",
            )
        }
        // Whatever the retry returned is what the caller sees: if it failed
        // too, the error surfaces normally rather than being swallowed.
        return retried
    }

    /**
     * One proot invocation. Split out of [execute] so the GH#186 seccomp
     * fallback can re-run the identical command instead of duplicating the
     * spawn/plumbing logic.
     */
    private suspend fun runOnce(
        context: Context,
        command: String,
        timeout: Long,
        environment: Map<String, String>,
        lineCallback: ((String) -> Unit)?,
        noSeccomp: Boolean,
        guard: ExecutionGuard,
    ): ShellResult = withContext(Dispatchers.IO) {
        // [FEAT-261025-CHROOT] Backend routing lives in one place. PRoot's
        // argv is unchanged (buildProotCommand, global mount table); chroot
        // returns the unprivileged client argv instead.
        val spawn = SandboxRouter.build(context, SandboxCommandSpec(command = command))
        if (spawn.warning != null) Log.w(TAG, "bootstrap: ${spawn.warning}")

        Log.d(TAG, "Executing: $command (backend=${spawn.backend}, offload=${spawn.offload})")

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(spawn.argv)
        processBuilder.redirectErrorStream(true)

        val env = processBuilder.environment()
        if (spawn.backend == SandboxBackend.PROOT) {
            // Set required environment for PRoot
            env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
            if (PRootKernel.nativeLibDir.isNotEmpty()) {
                env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
            }
            if (PRootKernel.prootLoaderPath.isNotEmpty()) {
                env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
            }
            if (PRootKernel.prootLoader32Path.isNotEmpty()) {
                env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
            }
        }
        // Backend-owned environment (chroot: socket name, TZ, PATH, proxy).
        env.putAll(spawn.env)

        // Apply custom environment from PRootKernel. [FEAT-261025-CHROOT
        // account] Under chroot its HOME is "/root", which the app uid cannot
        // enter; the backend already named the guest account's home, so PRoot's
        // map must not overwrite it there.
        for ((key, value) in PRootKernel.customEnvironment) {
            if (spawn.backend == SandboxBackend.CHROOT && key == "HOME") continue
            env[key] = value
        }
        // [T-net-android-phase3] Settings > Network "Shell" route: proxy
        // variables for the local entry (empty = direct, unchanged). Before
        // the caller's / user's own variables, which may override them.
        env.putAll(com.openminis.app.network.NetworkShellEnvironment.proxyVariables())

        // Apply per-call environment overrides
        for ((key, value) in environment) {
            env[key] = value
        }

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it on the retry attempt.
        if (noSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val output = StringBuilder()
        var exitCode = -1

        // [FEAT-261025-CHROOT] A confirmed backend switch may have stopped
        // this task while the argv/env above were being prepared. Do not
        // spawn into the backend the user just left.
        if (guard.stopped.get()) {
            return@withContext ShellResult(
                output = "[Cancelled]",
                exitCode = 130,
                durationMs = System.currentTimeMillis() - startTime,
            )
        }

        try {
            withTimeout(timeout) {
                // [T-shell-stall-yes-head] SIGPIPE unblocked in the child; see SpawnSignalMask.
                val process = SpawnSignalMask.start(processBuilder)
                currentProcess = process
                // The stop may have landed between the check above and
                // start(); kill the child, then let the normal read/wait
                // report its real signal status instead of a fabricated one.
                if (guard.stopped.get()) process.destroyForcibly()

                try {
                    // Read raw chars to preserve \r for TerminalSanitizer CR-folding.
                    // readLine() would consume \r as line terminator, losing progress overwrites.
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var lastLineForCallback = StringBuilder()
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            output.append(buf, 0, n)
                            // Feed lines to callback for UI updates
                            if (lineCallback != null) {
                                for (i in 0 until n) {
                                    val c = buf[i]
                                    if (c == '\n') {
                                        lineCallback.invoke(lastLineForCallback.toString())
                                        lastLineForCallback.clear()
                                    } else if (c != '\r') {
                                        lastLineForCallback.append(c)
                                    }
                                }
                            }
                        }
                        if (lineCallback != null && lastLineForCallback.isNotEmpty()) {
                            lineCallback.invoke(lastLineForCallback.toString())
                        }
                    }

                    exitCode = process.waitFor()
                } finally {
                    currentProcess = null
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Command timed out after ${timeout}ms: $command")
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Command timed out after ${timeout / 1000}s]")
            exitCode = 124 // Standard timeout exit code
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            currentProcess?.destroyForcibly()
            currentProcess = null
            output.appendLine("\n[Error: ${e.message}]")
            exitCode = -1
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Command completed in ${durationMs}ms with exit code $exitCode")

        ShellResult(
            output = output.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = durationMs
        )
    }

    /**
     * Forcibly destroy the currently running process.
     */
    fun destroyCurrent() {
        currentProcess?.let { process ->
            Log.i(TAG, "Destroying current process")
            process.destroyForcibly()
            currentProcess = null
        }
    }
}
