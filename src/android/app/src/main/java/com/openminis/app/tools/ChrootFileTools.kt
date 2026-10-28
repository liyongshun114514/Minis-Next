package com.openminis.app.tools

import android.content.Context
import android.system.Os
import com.openminis.app.sandbox.ChrootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SandboxBackend
import com.openminis.app.sandbox.SandboxBackendStore
import com.openminis.app.sandbox.SandboxCommandSpec
import com.openminis.app.sandbox.SessionMounts
import com.openminis.app.sandbox.SpawnSignalMask
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** File tools share CHROOT's identity, namespace and per-operation Root gate.
 * Existing inode metadata stays intact. New objects in App-managed roots are
 * handed to the App UID/GID; no recursive repair or retry of partial writes.
 * Payloads stay in private App-owned staging files, never argv, logs or stdout.
 */
internal object ChrootFileTools {
    private const val ASSET = "file-tools/chroot_file_tools.py"
    internal const val STAGING = ".minis-file-tools"
    internal const val MAX_RESPONSE_BYTES = 1024L * 1024
    internal data class Exit(val code: Int, val diagnostic: String = "")

    private class Active(val sessionId: String) {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var process: Process? = null
        fun stop() { stopped.set(true); process?.destroyForcibly() }
    }
    private val active = java.util.concurrent.ConcurrentHashMap<String, Active>()
    fun stopForSession(sessionId: String? = null): Int {
        val targets = active.values.filter { sessionId == null || it.sessionId == sessionId }
        targets.forEach { it.stop() }
        return targets.size
    }

    fun selected(context: Context): Boolean = SandboxBackendStore.selected(context) == SandboxBackend.CHROOT

    suspend fun execute(
        operation: String, args: JSONObject, sessionId: String, context: Context,
        executionSessionId: String = sessionId,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args.optString("path", "")
        val title = args.optString("tool_title", "file_$operation")
        val call = Active(executionSessionId)
        val id = "file-${UUID.randomUUID()}"
        active[id] = call
        com.openminis.app.sandbox.SandboxActivityRegistry.begin(
            com.openminis.app.sandbox.ActiveSandboxTask(id, "file_$operation", call::stop),
        )
        try {
            if (!selected(context)) return@withContext ToolExecutionResult(
                "Error: execution backend changed before file_$operation; retry with the current backend.", false, toolTitle = title,
            )
            val manager = RootfsManager.getInstance(context)
            manager.rootfsMigrationError?.let { error(it) }
            manager.installIfNeeded()
            val mounts = SessionMounts.forContext(context, sessionId)
            val unavailable = mounts.skipped.firstOrNull { path == it || path.startsWith("$it/") }
            if (unavailable != null) return@withContext ToolExecutionResult(
                "Error: session mount $unavailable is unavailable; file_$operation was not started.", false, toolTitle = title,
            )
            val rootfs = manager.rootfsDir
            val script = context.assets.open(ASSET).use { it.readBytes() }
            val managedRoots = (SessionMounts.SESSION_SUBDIRS + SessionMounts.GLOBAL_SUBDIRS)
                .map { "/var/minis/$it" }.filter { mounts.mounts.containsKey(it) }
            val handoff = appHandoff(android.os.Process.myUid(), Os.getgid(), managedRoots)
            runStaged(rootfs, operation, args, script, title, handoff) { command, job ->
                // Direct CHROOT route: switching backends cannot silently run this as App UID.
                if (call.stopped.get()) return@runStaged Exit(130, "Cancelled before Root authorization.")
                val spawn = ChrootKernel.spawn(context, SandboxCommandSpec(
                    command = command, sessionId = executionSessionId, sessionMounts = mounts.mounts,
                    rootRequired = true,
                ))
                val diagnostic = File(job, "diagnostic")
                diagnostic.createNewFile()
                val pb = ProcessBuilder(spawn.argv).redirectErrorStream(true).redirectOutput(diagnostic)
                pb.environment().putAll(spawn.env)
                if (call.stopped.get()) return@runStaged Exit(130, "Cancelled before file operation started.")
                val process = SpawnSignalMask.start(pb)
                call.process = process
                if (call.stopped.get()) process.destroyForcibly()
                try {
                    process.outputStream.close()
                    val finished = process.waitFor(FileToolIO.IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroyForcibly() // client disconnect kills the helper child group
                        process.waitFor(2, TimeUnit.SECONDS)
                        Exit(124, "File operation timed out; re-read the target before retrying a write.")
                    } else Exit(process.exitValue(), diagnostic.inputStream().use {
                        val b = ByteArray(4096); val n = it.read(b)
                        if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
                    })
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            ToolExecutionResult("Error: CHROOT file_$operation for $path: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = title)
        } finally {
            call.stop()
            active.remove(id)
            com.openminis.app.sandbox.SandboxActivityRegistry.end(id)
        }
    }

    internal fun appHandoff(uid: Int, gid: Int, roots: List<String>): JSONObject {
        require(uid > 0 && gid > 0) { "App identity is unavailable" }
        return JSONObject().put("uid", uid).put("gid", gid)
            .put("roots", org.json.JSONArray(roots))
    }

    internal suspend fun runStaged(
        rootfs: File, operation: String, args: JSONObject, script: ByteArray, title: String,
        appHandoff: JSONObject? = null,
        run: suspend (String, File) -> Exit,
    ): ToolExecutionResult {
        val path = args.optString("path", "")
        require(operation in setOf("read", "write", "edit"))
        require(path.startsWith("/") && '\u0000' !in path) { "An absolute path without NUL is required" }
        val normalized = java.nio.file.Paths.get(path).normalize().toString()
        require(normalized != "/$STAGING" && !normalized.startsWith("/$STAGING/")) { "Internal file-tool staging is not a target path" }
        val base = File(rootfs, STAGING)
        if (!base.isDirectory && !base.mkdir()) error("Cannot create private file-tool staging directory")
        require(!java.nio.file.Files.isSymbolicLink(base.toPath())) { "Staging directory is a symlink" }
        Os.chmod(base.path, 448) // 0700; staging only, never target paths
        val job = File(base, UUID.randomUUID().toString())
        check(job.mkdir()) { "Cannot create file-tool request directory" }
        Os.chmod(job.path, 448)
        try {
            val helper = File(job, "helper.py")
            helper.writeBytes(script)
            val request = File(job, "request.json")
            request.writeText(JSONObject().put("operation", operation).put("path", path).put("args", args)
                .apply { if (appHandoff != null) put("app_handoff", appHandoff) }.toString())
            val response = File(job, "response.json")
            check(response.createNewFile())
            for (f in listOf(helper, request, response)) Os.chmod(f.path, 384) // 0600
            val guest = "/$STAGING/${job.name}"
            // The approval displays the real operation/path; content stays out of argv.
            val command = ": ${quote("file_$operation $path")}; exec /usr/bin/python3 -I " +
                listOf("$guest/helper.py", "$guest/request.json", "$guest/response.json").joinToString(" ", transform = ::quote)
            val exit = run(command, job)
            if (exit.code != 0) return ToolExecutionResult(
                "Error: CHROOT file_$operation for $path exited ${exit.code}. ${exit.diagnostic.trim()}" +
                    if (operation != "read") " The write may be incomplete; re-read before retrying." else "",
                false, toolTitle = title, timedOut = exit.code == 124,
            )
            if (response.length() !in 1..MAX_RESPONSE_BYTES) return ToolExecutionResult(
                "Error: CHROOT file_$operation returned an empty or oversized response; verify the target before retrying.", false, toolTitle = title,
            )
            val result = JSONObject(response.readText())
            require(result.has("success") && result.get("success") is Boolean && result.get("text") is String) { "Invalid file-tool response" }
            val identity = result.optJSONObject("execution")
            val details = identity?.let {
                "CHROOT euid=${it.optInt("euid", -1)} egid=${it.optInt("egid", -1)}; " +
                    "target uid=${it.optInt("uid", -1)} gid=${it.optInt("gid", -1)} " +
                    "mode=${it.optString("mode", "unknown")}; App handoff=${it.optInt("handed_off", 0)}"
            } ?: "CHROOT; identity metadata unavailable"
            com.openminis.app.logging.AppLogger.info("ChrootFileTools", "file_$operation $path: $details")
            // Prefix preserves the read body and its pagination header at the end.
            return ToolExecutionResult("[$details]\n" + result.getString("text"), result.getBoolean("success"), toolTitle = title)
        } finally {
            // App pre-creates every transport file; root opens in place, so cleanup needs no Root.
            if (!job.deleteRecursively()) com.openminis.app.logging.AppLogger.warning("ChrootFileTools", "could not remove request staging ${job.name}")
        }
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
