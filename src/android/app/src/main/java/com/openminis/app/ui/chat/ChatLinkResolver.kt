package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *     (a folder → SandboxFolder, opened in the in-app file browser)
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    /** [T-minis-folder-link] A sandbox folder, as its Linux path (/var/minis/workspace/dir). */
    data class SandboxFolder(val linuxPath: String) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    /**
     * [T-skills-link-blank-webview] The `/var/minis/...` subdirectories whose
     * host location is global (never per-session): they live in
     * `filesDir/minis-global/<subdir>` no matter which chat is open or which
     * shell backend happens to be running.
     */
    private val GLOBAL_SANDBOX_SUBDIRS = setOf("memory", "skills", "shared", "mcp-servers")

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — prefer a session-scoped resolver when
        //    the caller knows which chat this link belongs to. The global
        //    `PRootKernel.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }
        // [T-minis-folder-link] Agents link folders too (minis://workspace/dir/).
        // Before, a folder fell through to Web, and a web preview of a
        // minis:// folder URL renders nothing — the link looked dead.
        if (hostFile != null && hostFile.isDirectory) {
            sandboxFolderLinuxPath(trimmed, scheme, sessionId, context)
                ?.let { return ChatLinkAction.SandboxFolder(it) }
        }

        // An unresolved Minis resource is not an external application scheme.
        // Keep it on the guarded Web fallback so callers show a file error.
        if (scheme == "minis") return ChatLinkAction.Web(trimmed)

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    /**
     * [T-minis-folder-link] The Linux path of the folder a sandbox link names,
     * so the file browser can open at it. Picks the same decode candidate
     * [resolveSandboxFile] would. `file://` links carry a host path, not a
     * Linux one, so they are not handled here.
     */
    private fun sandboxFolderLinuxPath(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): String? {
        fun isDir(linuxPath: String): Boolean {
            val subdir = linuxPath.removePrefix("/var/minis/").substringBefore('/')
            if (context != null && linuxPath.startsWith("/var/minis/") &&
                subdir in GLOBAL_SANDBOX_SUBDIRS
            ) return globalHostFile(linuxPath, context)?.isDirectory == true
            val mounted = if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
            if (mounted != null && mounted.isDirectory) return true
            // [T-skills-link-blank-webview] global dirs resolve without the
            // mount table, same as [resolveSandboxFile].
            return globalHostFile(linuxPath, context)?.isDirectory == true
        }
        val candidates = when (scheme) {
            "minis" -> minisPathCandidates(raw.removePrefix("minis://").substringBefore('?'))
                .map { if (it.startsWith("/")) it else "/var/minis/$it" }
            null -> listOf(raw).filter { it.startsWith("/") }
            else -> emptyList()
        }
        return candidates.firstOrNull { isDir(it) }?.trimEnd('/')?.ifEmpty { "/" }
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        // [T-skills-link-blank-webview] The PRoot bind-mount table is empty on
        // a cold start under the CHROOT backend (no PRoot shell has booted),
        // so `/var/minis/skills/...` used to resolve to a path inside the
        // rootfs that never exists. The link then fell through to
        // [ChatLinkAction.Web] and the preview WebView showed a blank page.
        // Global subdirs have a fixed host location, so try that directly and
        // keep the mount table as a fallback rather than the only source.
        fun viaMountTable(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }

        fun lookup(linuxPath: String): File? {
            val subdir = linuxPath.removePrefix("/var/minis/").substringBefore('/')
            if (context != null && linuxPath.startsWith("/var/minis/") &&
                subdir in GLOBAL_SANDBOX_SUBDIRS
            ) {
                // This fixed app-owned location is authoritative even if a stale
                // mount entry points at an existing, empty rootfs placeholder.
                return globalHostFile(linuxPath, context)
            }
            return viaMountTable(linuxPath)
        }

        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                // [T-android-minis-url-double-encoding] Try each decode
                // candidate and take the first that exists on disk. See
                // [minisPathCandidates] for why one decode pass isn't enough.
                minisPathCandidates(stripped)
                    .asSequence()
                    .map { candidate ->
                        val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                        lookup(linuxPath)
                    }
                    .firstOrNull { it != null && it.exists() }
                    // Nothing existed — hand back the primary candidate so the
                    // caller's own exists() check reports against the path the
                    // user actually meant, and diagnostics stay readable.
                    ?: lookup(
                        minisPathCandidates(stripped).first().let {
                            if (it.startsWith("/")) it else "/var/minis/$it"
                        },
                    )
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) {
                    null
                } else {
                    // [T-skills-link-blank-webview] Decode with the same
                    // %XX-only rules as `minis://` — URLDecoder implements
                    // form semantics, so a `a+b` directory became `a b` and
                    // resolved to nothing. A `file:///var/minis/...` URL names
                    // the guest's view of a bind mount, not a host path, so
                    // route it through [lookup].
                    fun hostFile(decoded: String): File? =
                        if (decoded.startsWith("/var/minis/")) lookup(decoded) else File(decoded)
                    val candidates = minisPathCandidates(path)
                    candidates.asSequence()
                        .mapNotNull { hostFile(it) }
                        .firstOrNull { it.exists() }
                        ?: candidates.firstOrNull()?.let { hostFile(it) }
                }
            }
            null -> {
                if (!raw.startsWith("/")) null else {
                    val candidates = minisPathCandidates(raw)
                    candidates.asSequence().mapNotNull { lookup(it) }
                        .firstOrNull { it.exists() } ?: lookup(candidates.first())
                }
            }
            else -> null
        }
    }

    /**
     * [T-skills-link-blank-webview] Host file for a global (session-independent)
     * `/var/minis/<subdir>/...` path, resolved straight from
     * `filesDir/minis-global/<subdir>`.
     *
     * Deliberately independent of [PRootKernel.bindMounts]: on a cold start
     * with the CHROOT backend nothing has booted PRoot, the table can be
     * empty, and the old answer was a rootfs path that does not exist — which
     * is exactly how a skills link ended up in a WebView and rendered blank.
     *
     * Returns null for per-session subdirs (attachments/offloads/workspace/
     * browser): those must come from the owning session, and falling back to
     * "some other session" is the cross-chat leak this must not introduce.
     * `..` / `.` segments and any path that escapes the base directory
     * (symlinks included) are rejected rather than guessed at.
     */
    internal fun globalHostFile(linuxPath: String, context: Context?): File? {
        if (context == null) return null
        val prefix = "/var/minis/"
        if (!linuxPath.startsWith(prefix)) return null
        val rest = linuxPath.removePrefix(prefix)
        if (rest.isEmpty() || rest.contains('\u0000')) return null
        val subdir = rest.substringBefore('/')
        if (subdir !in GLOBAL_SANDBOX_SUBDIRS) return null
        val tail = rest.substring(subdir.length).removePrefix("/")
        if (tail.split('/').any { it == ".." || it == "." }) return null
        val base = File(context.filesDir, "minis-global/$subdir")
        if (tail.isEmpty()) return base
        return runCatching {
            val root = base.canonicalFile
            val target = File(base, tail).canonicalFile
            if (target.path.startsWith(root.path + File.separator)) target else null
        }.getOrNull()
    }

    /**
     * [T-skills-link-blank-webview] True when [rawUrl] names a sandbox
     * resource (an unknown `minis://` link, a `file://` URL into
     * `/var/minis`, or a bare `/var/minis|/root` Linux path) rather than
     * something a WebView can load.
     *
     * Pure: no disk I/O, no resolution, no mount-table access. It is meant
     * for the `ChatLinkAction.Web` branch, which by construction means
     * [resolve] found no existing file — handing such a URL to the preview
     * WebView is what produced the blank screen, so the caller should show
     * an error instead of navigating.
     */
    fun isSandboxUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { trimmed.toUri() }.getOrNull()
        return when (uri?.scheme?.lowercase()) {
            // Known minis:// actions already returned DeepLink from [resolve],
            // so an Unknown parse here is a sandbox path (minis://skills/...).
            "minis" -> DeepLinkHandler.parse(uri) == DeepLinkAction.Unknown
            "file" -> {
                val path = trimmed.removePrefix("file://").substringBefore('?')
                minisPathCandidates(path).any { isSandboxLinuxPath(it) }
            }
            null -> isSandboxLinuxPath(trimmed)
            else -> false
        }
    }

    private fun isSandboxLinuxPath(path: String): Boolean =
        path == "/var/minis" || path.startsWith("/var/minis/") ||
            path == "/root" || path.startsWith("/root/")

    /**
     * [T-android-minis-url-double-encoding] Decode candidates for the path part
     * of a `minis://` URL, in priority order. Ported from iOS
     * `MinisURLPathDecoding` (T-fix-double-encoding).
     *
     * A correctly-formed minis URL percent-encodes each segment exactly once,
     * and one decode pass recovers the real UTF-8 name. But links reach us
     * double-encoded when the agent — or an intermediate Markdown
     * autolink/sanitize step — re-encodes the literal `%` of an
     * already-encoded URL, turning `%E5` into `%25E5`. One decode pass then
     * yields a literal `%E5…`, which matches no file on disk.
     *
     * The user-visible symptom is a tap that does NOTHING, which is why this
     * is worth the tolerance: [resolve] falls through to `ChatLinkAction.Web`,
     * and a web preview of a `minis://` URL renders nothing at all. There is
     * no error, no toast, no navigation — the link just looks dead.
     *
     * Note this is NOT specific to CJK. Any non-ASCII segment percent-encodes
     * to `%XX` bytes and is equally affected; CJK paths merely make it far
     * more likely, since every character encodes (an ASCII path often survives
     * because it has nothing to encode in the first place).
     *
     * Disk existence is the disambiguator, so the extra candidate is only ever
     * reached when the correct single decode found nothing — a filename that
     * legitimately contains a `%` still resolves via the first candidate and
     * never sees the second decode.
     *
     * Percent-decoding is done by hand rather than with
     * [java.net.URLDecoder], which implements
     * `application/x-www-form-urlencoded` — where `+` means SPACE. A path
     * segment like `a+b/file.pdf` is a real directory name on disk, and
     * URLDecoder silently turns it into `a b/file.pdf`, resolving to nothing
     * and producing the same dead-link symptom. (`java.net.URI` is no help
     * either: its multi-arg constructor ENCODES its input, so `getPath()`
     * hands the string straight back undecoded.)
     */
    internal fun minisPathCandidates(strippedPath: String): List<String> {
        // Decode %XX only, never mapping '+' to space (see KDoc). Done by hand
        // rather than with URLDecoder (form semantics: '+' → space) or
        // java.net.URI (its multi-arg constructor ENCODES its input, so
        // getPath() hands the string straight back). Invalid escapes are
        // emitted verbatim so a stray '%' degrades instead of throwing.
        fun decodeOnce(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                // Non-escape (or malformed escape): keep the character's own
                // UTF-8 bytes so already-decoded CJK passes through intact.
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        val once = decodeOnce(strippedPath)
        val candidates = mutableListOf(once)
        val twice = decodeOnce(once)
        if (twice != once) candidates.add(twice)
        return candidates
    }

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
