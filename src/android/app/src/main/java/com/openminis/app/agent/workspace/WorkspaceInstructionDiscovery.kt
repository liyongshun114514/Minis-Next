package com.openminis.app.agent.workspace

import android.content.Context
import com.openminis.app.agent.workspace.WorkspaceInstructionRender.InstructionFile
import com.openminis.app.sandbox.PRootKernel
import java.io.File

/**
 * [T-workspace-instructions] Discovery of AGENTS.md / CLAUDE.md files, ported
 * from DeepSeek Harness (`@deepseek-ai/dsh-agent-instructions`, src/files.ts).
 *
 * Path mapping on Android. dsh reads `$DSH_HOME/AGENTS.md` and
 * `<agentsHome>/AGENTS.md` from the process environment; Mobile Minis has no
 * such env, so the two roots map onto the shared Minis tree the rest of the app
 * already owns:
 *
 *   $DSH_HOME          -> /var/minis/memory          (host: filesDir/minis-global/memory)
 *   <agentsHome>       -> /var/minis/shared/.agents  (host: filesDir/minis-global/shared/.agents)
 *
 * The project chain is the inclusive project-root -> working-directory chain,
 * broad to specific. The project root is the nearest ancestor carrying a
 * configured marker (`.git`) from the working directory; when no marker exists
 * the working directory itself is the root, exactly as in dsh.
 *
 * Candidate files per project directory keep dsh's default order:
 * `AGENTS.md`, `CLAUDE.md`, then the additive `AGENTS.local.md`,
 * `CLAUDE.local.md`. User-global roots carry only `AGENTS.md` — dsh does not
 * load a `CLAUDE.md` there and this port does not invent one.
 *
 * Content-based deduplication keeps dsh's semantics: candidates collapse only
 * when byte-identical after trimming leading and trailing whitespace, and the
 * two user-global roots share one candidate group so a shared-root file that
 * duplicates the harness-home file collapses behind it.
 */
internal object WorkspaceInstructionDiscovery {

    /** Linux-visible roots; resolved to host files through the bind-mount table. */
    const val DSH_HOME_LINUX = "/var/minis/memory"

    const val USER_GLOBAL_FILE = "AGENTS.md"

    val PROJECT_CANDIDATES = listOf("AGENTS.md", "CLAUDE.md", "AGENTS.local.md", "CLAUDE.local.md")
    val PROJECT_ROOT_MARKERS = listOf(".git")

    /** dsh's default `maxSourceBytes`: a single instruction file larger than this is refused. */
    const val MAX_SOURCE_BYTES = 1_048_576

    /** Resolve a Linux path to the host File the app process can read. */
    fun hostFile(linuxPath: String): File? = PRootKernel.resolveHostPath(linuxPath)

    /** Host file for a Linux path, or null when the path is not mounted in this build. */
    fun existingFile(linuxPath: String): File? = hostFile(linuxPath)?.takeIf { it.isFile }

    /**
     * The inclusive root -> working-directory chain. Both arguments are host
     * directories; the result is ordered broadest first and always contains
     * [workingDirectory] last.
     */
    fun ancestorChain(projectRoot: File, workingDirectory: File): List<File> {
        val root = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot)
        var current = runCatching { workingDirectory.canonicalFile }.getOrDefault(workingDirectory)
        val chain = mutableListOf<File>()
        // Walk up until the project root is reached. A working directory that is
        // not under the root contributes itself only, rather than the whole
        // filesystem: silently loading /var/minis/AGENTS.md would apply
        // instructions the user never scoped to this project.
        while (true) {
            chain.add(current)
            if (current == root) break
            val parent = current.parentFile ?: break
            if (parent == current) break
            if (!isUnder(parent, root) && current != root) break
            current = parent
        }
        return chain.reversed()
    }

    private fun isUnder(candidate: File, root: File): Boolean {
        var current: File? = candidate
        while (current != null) {
            if (current == root) return true
            current = current.parentFile
        }
        return false
    }

    /**
     * Nearest ancestor carrying a root marker. When no ancestor has one, the
     * caller treats the working directory itself as the root (dsh's documented
     * fallback). Java's `File.exists()` reports an unreadable probe as absent
     * instead of throwing, so an I/O failure degrades to "no marker here"
     * rather than aborting discovery.
     */
    fun findProjectRoot(workingDirectory: File, markers: List<String> = PROJECT_ROOT_MARKERS): File? {
        var current: File? = runCatching { workingDirectory.canonicalFile }.getOrDefault(workingDirectory)
        while (current != null) {
            for (marker in markers) {
                if (File(current, marker).exists()) return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun readCapped(file: File): String? = runCatching {
        if (file.length() > MAX_SOURCE_BYTES) return null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    /**
     * The single user-global file.
     *
     * dsh loads a second user-global root (`~/.agents/AGENTS.md`). Minis keeps
     * ONE, at `$DSH_HOME/AGENTS.md`: two near-identical paths in Settings read
     * as a duplicate rather than as two scopes, and the shared root has no
     * separate meaning on a phone. The project chain is untouched — that is
     * where per-project scoping actually lives.
     */
    fun globalChain(): List<InstructionFile> {
        val loaded = mutableListOf<InstructionFile>()
        for (linuxRoot in listOf(DSH_HOME_LINUX)) {
            val linuxPath = "$linuxRoot/$USER_GLOBAL_FILE"
            val host = existingFile(linuxPath) ?: continue
            val content = readCapped(host) ?: continue
            loaded += InstructionFile(
                absolutePath = host.absolutePath,
                displayPath = linuxPath,
                linuxPath = linuxPath,
                content = content,
                isUserGlobal = true,
            )
        }
        return dedupeGroup(loaded)
    }

    /**
     * Project candidates for one directory, in dsh's candidate order,
     * deduplicated per group. [chainRootLinux] is the Linux path the working
     * chain starts at, so each file can carry the absolute path the Settings
     * editor writes back to.
     */
    fun projectDirectoryFiles(directory: File, projectRoot: File, chainRootLinux: String): List<InstructionFile> {
        val loaded = mutableListOf<InstructionFile>()
        for (candidate in PROJECT_CANDIDATES) {
            val file = File(directory, candidate)
            if (!file.isFile) continue
            val content = readCapped(file) ?: continue
            val relative = displayPath(projectRoot, file)
            loaded += InstructionFile(
                absolutePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath),
                displayPath = relative,
                linuxPath = chainRootLinux.trimEnd('/') + "/" + relative,
                content = content,
                isUserGlobal = false,
            )
        }
        return dedupeGroup(loaded)
    }

    /**
     * Content-based deduplication within one candidate group: a later candidate
     * whose trimmed bytes equal an earlier one is dropped, so a `CLAUDE.md`
     * that duplicates its `AGENTS.md` renders once.
     */
    private fun dedupeGroup(files: List<InstructionFile>): List<InstructionFile> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<InstructionFile>()
        for (file in files) {
            val key = file.content.trim()
            if (seen.add(key)) out += file
        }
        return out
    }

    /** Project-relative display path with `/` separators, as dsh renders it. */
    fun displayPath(projectRoot: File, file: File): String {
        val rootPath = runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.absolutePath)
        val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (filePath == rootPath) return file.name
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return if (filePath.startsWith(prefix)) {
            filePath.removePrefix(prefix).replace(File.separatorChar, '/')
        } else {
            filePath.replace(File.separatorChar, '/')
        }
    }

    /**
     * Project chain only: every existing candidate from the project root down
     * to the working directory. [chainRootLinux] is the Linux path of
     * [projectRoot], used to build each file's editable path.
     */
    fun projectChain(projectRoot: File, workingDirectory: File, chainRootLinux: String): List<InstructionFile> {
        val files = mutableListOf<InstructionFile>()
        for (directory in ancestorChain(projectRoot, workingDirectory)) {
            files += projectDirectoryFiles(directory, projectRoot, chainRootLinux)
        }
        return files
    }
}
