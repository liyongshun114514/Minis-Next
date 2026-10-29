package com.openminis.app.agent.workspace

import android.content.Context
import com.openminis.app.agent.workspace.WorkspaceInstructionRender.InstructionFile
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.security.MessageDigest

/**
 * [T-workspace-instructions] Configuration and file access for the
 * AGENTS.md / CLAUDE.md feature, plus the state needed to reconcile one
 * session against the files on disk.
 *
 * Scope configuration is explicit rather than inferred. dsh derives the
 * project chain from the process working directory; an Android app has no
 * user shell to inherit, so the Settings page owns two Linux paths:
 *
 *  - `projectRoot`  — where the broad-to-specific chain starts;
 *  - `workingDirectory` — its most specific end, the session's working scope.
 *
 * Both default to `/var/minis/shared`, the one Minis tree that exists outside
 * a single chat's private sandbox. A user working on one project points both at
 * `/var/minis/shared/myproject`; a user whose project spans a mount points them
 * at `/var/minis/mounts/<name>`.
 */
internal object WorkspaceInstructionStore {

    const val DEFAULT_PROJECT_ROOT = "/var/minis/shared"
    const val DEFAULT_WORKING_DIRECTORY = "/var/minis/shared"

    /** dsh's `dsh-base` budget: 65,536 bytes per instruction chain. */
    const val DEFAULT_MAX_BYTES = 65_536

    private const val PREFS = "workspace_instructions"
    private const val KEY_PROJECT_ROOT = "project_root"
    private const val KEY_WORKING_DIRECTORY = "working_directory"
    private const val KEY_MAX_BYTES = "max_bytes"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun projectRoot(context: Context): String =
        prefs(context).getString(KEY_PROJECT_ROOT, DEFAULT_PROJECT_ROOT)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PROJECT_ROOT

    fun workingDirectory(context: Context): String =
        prefs(context).getString(KEY_WORKING_DIRECTORY, DEFAULT_WORKING_DIRECTORY)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WORKING_DIRECTORY

    fun maxBytes(context: Context): Int =
        prefs(context).getInt(KEY_MAX_BYTES, DEFAULT_MAX_BYTES).takeIf { it > 0 } ?: DEFAULT_MAX_BYTES

    fun setScope(context: Context, projectRoot: String, workingDirectory: String) {
        prefs(context).edit()
            .putString(KEY_PROJECT_ROOT, normalizeLinuxPath(projectRoot))
            .putString(KEY_WORKING_DIRECTORY, normalizeLinuxPath(workingDirectory))
            .apply()
    }

    fun setMaxBytes(context: Context, maxBytes: Int) {
        prefs(context).edit().putInt(KEY_MAX_BYTES, if (maxBytes > 0) maxBytes else DEFAULT_MAX_BYTES).apply()
    }

    fun normalizeLinuxPath(path: String): String {
        val trimmed = path.trim().ifEmpty { DEFAULT_PROJECT_ROOT }
        val withRoot = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return withRoot.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * Resolve a Linux path to the host file. Per-session roots
     * (`/var/minis/workspace`, attachments, offloads, browser) need the chat id
     * that owns them; callers without one pass null and get null back rather
     * than another session's directory.
     */
    fun resolve(context: Context, linuxPath: String, sessionId: String? = null): File? {
        if (PRootKernel.isPerSessionPath(linuxPath)) {
            val sid = sessionId ?: return null
            return PRootKernel.resolveSessionHostPath(sid, linuxPath, context)
        }
        return PRootKernel.resolveHostPath(linuxPath)
    }

    /** Resolve and require an existing file. */
    fun resolveExisting(context: Context, linuxPath: String, sessionId: String? = null): File? =
        resolve(context, linuxPath, sessionId)?.takeIf { it.isFile }

    /**
     * Write an instruction file, creating parents. Content is written verbatim
     * — no normalization — because these are user-authored Markdown documents.
     */
    fun write(context: Context, linuxPath: String, content: String, sessionId: String? = null): Result<File> =
        runCatching {
            val file = resolve(context, linuxPath, sessionId)
                ?: error("Path is not available in this build: $linuxPath")
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            file
        }

    /** SHA-1 of the exact bytes, the identity dsh uses for change detection. */
    fun digest(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** One discovery pass: the ordered files plus the scope they were read under. */
    data class Snapshot(
        val files: List<InstructionFile>,
        val projectRootLinux: String,
        val workingDirectoryLinux: String,
        val error: String? = null,
    )

    /**
     * Read the current baseline chain. A project root that does not resolve
     * yields a snapshot holding only the user-global chain, with the reason in
     * [Snapshot.error] so callers can surface it instead of silently pretending
     * the project has no instructions.
     */
    fun snapshot(context: Context, sessionId: String? = null): Snapshot {
        val projectRootLinux = projectRoot(context)
        val workingDirectoryLinux = workingDirectory(context)
        val projectRootHost = resolve(context, projectRootLinux, sessionId)
        if (projectRootHost == null || !projectRootHost.isDirectory) {
            return Snapshot(
                files = WorkspaceInstructionDiscovery.globalChain(),
                projectRootLinux = projectRootLinux,
                workingDirectoryLinux = workingDirectoryLinux,
                error = "Project root is not reachable: $projectRootLinux",
            )
        }
        val workingDirectoryHost = resolve(context, workingDirectoryLinux, sessionId)
            ?: projectRootHost
        // The configured root is the outer bound; a `.git` marker can only
        // NARROW the chain to a subdirectory inside it. A marker above the
        // configured root never widens the scope — that would load files the
        // user did not point this chat at.
        val markerRoot = WorkspaceInstructionDiscovery.findProjectRoot(workingDirectoryHost)
        val chainRoot = if (markerRoot != null && isUnder(markerRoot, projectRootHost)) {
            markerRoot
        } else {
            projectRootHost
        }
        val chainRootLinux = if (chainRoot == projectRootHost) {
            projectRootLinux
        } else {
            val relative = runCatching {
                chainRoot.canonicalFile.toRelativeString(projectRootHost.canonicalFile)
            }.getOrNull()?.replace(File.separatorChar, '/')
            if (relative.isNullOrBlank()) projectRootLinux else projectRootLinux.trimEnd('/') + "/" + relative
        }
        val files = WorkspaceInstructionDiscovery.globalChain() +
            WorkspaceInstructionDiscovery.projectChain(chainRoot, workingDirectoryHost, chainRootLinux)
        return Snapshot(files, projectRootLinux, workingDirectoryLinux)
    }

    /**
     * The mandatory system-channel block for one turn, or null when no
     * instruction file exists. Read once per request so an edit lands on the
     * next request, exactly like the previous user-role implementation.
     */
    fun mandatoryBlock(context: Context, sessionId: String? = null): String? {
        val snapshot = snapshot(context, sessionId)
        return WorkspaceInstructionRender.renderMandatoryBlock(snapshot.files, maxBytes(context))
    }

    private fun isUnder(candidate: File, root: File): Boolean {
        var current: File? = runCatching { candidate.canonicalFile }.getOrDefault(candidate)
        val target = runCatching { root.canonicalFile }.getOrDefault(root)
        while (current != null) {
            if (current == target) return true
            current = current.parentFile
        }
        return false
    }
}
