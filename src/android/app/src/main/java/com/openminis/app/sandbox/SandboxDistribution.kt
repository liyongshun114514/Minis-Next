package com.openminis.app.sandbox

import java.io.File

/**
 * The Linux distribution the Android sandbox installs, plus the pure
 * (no-Context) configuration that belongs to it.
 *
 * History: Android used to ship Alpine Linux (apk/busybox/musl). The default
 * guest is now **Ubuntu 24.04 LTS (noble) arm64** so the sandbox matches the
 * tools agents expect (`apt`, bash, glibc). iOS keeps its Alpine/iSH guest —
 * this object is Android-only and nothing here is shared with `src/ios`.
 *
 * Everything in this file is either a constant or a pure function so it can be
 * unit-tested on the JVM without an Android runtime.
 *
 * ## On-disk layout (deliberately unchanged)
 *
 * [ROOTFS_DIR_NAME] is still `alpine-rootfs`. That name is a *path*, not a
 * distribution statement: five files outside this module's ownership
 * (`ShellTransparentNet`, `StorageUsageViewModel`, `FileBrowserViewModel`,
 * `AppNavigation`, `RootfsManagementScreen`) and one instrumented test hardcode
 * it, and a rename would make them read a directory that no longer exists.
 * Which distribution actually lives there is recorded by [MARKER_FILE]
 * (`.distro`) — see [isUbuntuRootfs]. The rename is tracked as an external
 * follow-up in `docs/android-ubuntu.md`.
 */
object SandboxDistribution {

    /** Stable id used in prefs, logs and the docs. */
    const val ID = "ubuntu"

    /** Human-readable name for UI/log strings. */
    const val DISPLAY_NAME = "Ubuntu 24.04 LTS"

    const val VERSION = "24.04"
    const val CODENAME = "noble"
    const val ARCH = "aarch64"

    /** Written to [MARKER_FILE]; a mismatch means "not the shipped guest". */
    const val MARKER_VALUE = "ubuntu-24.04-arm64"

    // ── rootfs paths / markers ───────────────────────────────────────────────

    /**
     * Legacy on-disk directory name. Kept so every unmodified reader
     * (`filesDir/alpine-rootfs/...`) keeps working; the marker inside decides
     * which distro is installed. See the class comment.
     */
    const val ROOTFS_DIR_NAME = "alpine-rootfs"

    /** Sibling that a pre-Ubuntu (Alpine) or broken rootfs is moved to. Never deleted. */
    const val LEGACY_BACKUP_PREFIX = "alpine-rootfs.pre-ubuntu-"

    /** Extraction staging directory; swapped in only after a complete extract. */
    const val STAGING_DIR_NAME = "alpine-rootfs.staging"

    /** Rootfs marker: exact contents of `.arch` for a usable install. */
    const val ARCH_MARKER_FILE = ".arch"

    /** Rootfs marker: exact contents of `.distro` for a usable install. */
    const val MARKER_FILE = ".distro"

    // ── bundled asset ────────────────────────────────────────────────────────

    /** Name inside `src/android/app/src/main/assets/`, produced by the prepare script. */
    const val ROOTFS_ASSET = "ubuntu-base-arm64.tar.gz"

    /** Fallback if AAPT ever decompresses the asset (see build.gradle noCompress). */
    const val ROOTFS_ASSET_TAR = "ubuntu-base-arm64.tar"

    /** No longer installed. Only referenced so stale assets can be reported. */
    const val LEGACY_ASSET = "alpine-minirootfs.tar.gz"

    // ── apt ─────────────────────────────────────────────────────────────────

    /** Official Ubuntu archive for **arm64**: ports.ubuntu.com (not archive.ubuntu.com). */
    const val OFFICIAL_UBUNTU_PORTS = "https://ports.ubuntu.com/ubuntu-ports/"

    /** deb822 sources file shipped by ubuntu-base 24.04 (replaces `/etc/apt/sources.list`). */
    const val APT_SOURCES_PATH = "etc/apt/sources.list.d/ubuntu.sources"

    /** PRoot-specific apt compatibility switches (no real uid 0 in the guest). */
    const val APT_PROOT_CONF_PATH = "etc/apt/apt.conf.d/99minis-proot.conf"

    // ── guest defaults written at install time ──────────────────────────────

    const val HOSTNAME = "minis"

    const val PROFILE_PATH = "etc/profile.d/50-minis-ubuntu.sh"
    const val BOOTSTRAP_PACKAGES_PATH = "etc/minis/bootstrap-packages"
    const val BOOTSTRAP_SCRIPT_PATH = "usr/local/sbin/minis-bootstrap"

    /** Sourced by interactive shells: runs the one-time toolset install and says so. */
    const val BOOTSTRAP_PROFILE_PATH = "etc/profile.d/99-minis-bootstrap.sh"

    /** Guest paths used by [bootstrapScript]. */
    const val BOOTSTRAP_DONE_MARKER = "var/lib/minis/bootstrap.done"
    const val BOOTSTRAP_FAILED_MARKER = "var/lib/minis/bootstrap.failed"
    const val BOOTSTRAP_LOG = "var/log/minis-bootstrap.log"
    const val BOOTSTRAP_LOCK_DIR = "var/lib/minis/bootstrap.lock"

    /**
     * Default guest toolset. ubuntu-base ships bash/apt/dpkg/perl/ps/gzip/tar
     * but **no** python3, curl, wget, git, ssh, less, ip, unzip, jq or rg —
     * which the agent prompt and the bundled `minis-mcp-cli` (python3) assume.
     * Installed on demand by [bootstrapScript] after the rootfs is in place,
     * never during extraction (network + minutes; the install must stay
     * deterministic and offline-capable).
     */
    val BOOTSTRAP_PACKAGES: List<String> = listOf(
        "bash",
        "ca-certificates",
        "curl",
        "wget",
        "git",
        "openssh-client",
        "less",
        "procps",
        "iproute2",
        "unzip",
        "xz-utils",
        "jq",
        "ripgrep",
        "python3",
        "python3-pip",
        "python3-venv",
    )

    // ── detection ────────────────────────────────────────────────────────────

    fun readTextOrNull(file: File): String? = try {
        if (file.isFile) file.readText().trim() else null
    } catch (_: Throwable) {
        null
    }

    /** True when [dir] holds the rootfs this build ships (both markers match). */
    fun isUbuntuRootfs(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (readTextOrNull(File(dir, ARCH_MARKER_FILE)) != ARCH) return false
        if (readTextOrNull(File(dir, MARKER_FILE)) == MARKER_VALUE) return true
        // Future/older Minis builds may have written only the arch marker for a
        // Ubuntu rootfs; confirm with the guest itself before claiming it.
        return looksLikeUbuntuFilesystem(dir) && File(dir, MARKER_FILE).exists().not()
    }

    /** Heuristic: Ubuntu base with glibc + dpkg present. */
    fun looksLikeUbuntuFilesystem(dir: File): Boolean {
        if (!File(dir, "usr/bin/apt").isFile) return false
        if (!File(dir, "var/lib/dpkg/status").isFile) return false
        val osRelease = File(dir, "etc/os-release")
        val text = readTextOrNull(osRelease)
            ?: readTextOrNull(File(dir, "usr/lib/os-release"))
            ?: ""
        return text.contains("Ubuntu", ignoreCase = true)
    }

    /**
     * True when [dir] looks like the **old Alpine** guest (or any non-Ubuntu
     * rootfs). Such a directory must never be deleted or overwritten in place:
     * `installIfNeeded` renames it to a [LEGACY_BACKUP_PREFIX] sibling first.
     */
    fun isLegacyNonUbuntuRootfs(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (isUbuntuRootfs(dir)) return false
        return File(dir, "etc/alpine-release").exists() ||
            File(dir, "etc/apk/world").exists() ||
            File(dir, "sbin/apk").exists() ||
            File(dir, "bin/busybox").exists() ||
            File(dir, ARCH_MARKER_FILE).exists() ||
            looksLikeUbuntuFilesystem(dir)
    }

    // ── apt sources (deb822) ────────────────────────────────────────────────

    /**
     * Accept only a plain http(s) base URL and normalise it to one trailing
     * slash. The value ends up verbatim in a root-owned apt source file, so a
     * newline or a `Signed-By:` injection must be impossible here.
     */
    fun normalizeMirrorBase(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 512) return null
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
        if (trimmed.any { it.isWhitespace() || it == '\n' || it == '\r' }) return null
        val base = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        // Reject userinfo / query / fragment which would change apt's request.
        if (base.contains("@") || base.contains("?") || base.contains("#")) return null
        return base
    }

    /**
     * deb822 `ubuntu.sources` for a mirror. Two stanzas mirror the official
     * ubuntu-base 24.04 arm64 file: archive suites and `noble-security`.
     *
     * `Suites:` deliberately excludes `noble-proposed` (not a default) and the
     * source types are omitted (`Types: deb` only) to halve the index size on
     * mobile. `Signed-By:` keeps apt's stock keyring, so mirror switching never
     * changes what signs packages.
     */
    fun aptSources(mirrorBaseUrl: String = OFFICIAL_UBUNTU_PORTS): String {
        val base = normalizeMirrorBase(mirrorBaseUrl) ?: OFFICIAL_UBUNTU_PORTS
        return buildString {
            append("# Managed by Minis — Ubuntu 24.04 LTS ($CODENAME) arm64, deb822 format.\n")
            append("# Suites/builds follow ports.ubuntu.com; edit via Settings > Rootfs > Mirrors.\n")
            append("Types: deb\n")
            append("URIs: ").append(base).append('\n')
            append("Suites: ").append(CODENAME).append(' ').append(CODENAME).append("-updates ")
                .append(CODENAME).append("-backports\n")
            append("Components: main restricted universe multiverse\n")
            append("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n")
            append('\n')
            append("# Security updates.\n")
            append("Types: deb\n")
            append("URIs: ").append(base).append('\n')
            append("Suites: ").append(CODENAME).append("-security\n")
            append("Components: main restricted universe multiverse\n")
            append("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n")
        }
    }

    /**
     * apt under PRoot has no real privilege boundary: the guest uid is faked,
     * so apt cannot `setuid` to `_apt` and its seccomp filter fights PRoot's
     * ptrace-based syscall rewriting. Both are upstream-supported switches.
     */
    fun aptProotConf(): String = buildString {
        append("# Managed by Minis. PRoot guest: no real uid 0 and no seccomp passthrough.\n")
        append("APT::Sandbox::User \"root\";\n")
        append("APT::Sandbox::Seccomp \"false\";\n")
        append("Acquire::Languages \"none\";\n")
        append("DPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n")
    }

    // ── guest files ─────────────────────────────────────────────────────────

    fun hostnameFile(): String = "$HOSTNAME\n"

    /**
     * ubuntu-base ships `/etc/hosts` **empty** (0 bytes), unlike Alpine.
     * Without a localhost entry `sudo`-less tools, apt's `Acquire` retries and
     * anything that resolves `localhost` (python requests, node) misbehave.
     */
    fun hostsFile(): String = buildString {
        append("127.0.0.1\tlocalhost\n")
        append("::1\t\tlocalhost ip6-localhost ip6-loopback\n")
        append("127.0.1.1\t").append(HOSTNAME).append('\n')
    }

    /**
     * `/etc/profile.d/50-minis-ubuntu.sh`. `/etc/profile` is sourced by login
     * shells only; PRootKernel already seeds PATH/HOME for non-login ones.
     * Written once at install time (not on every boot) so a user edit sticks.
     */
    fun profileScript(): String = buildString {
        append("# Managed by Minis. Ubuntu 24.04 guest defaults.\n")
        append("export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin\"\n")
        append("export PAGER=\"\${PAGER:-less}\"\n")
        append("# glibc has C.UTF-8 built in; no locales package needed.\n")
        append("export LANG=\"\${LANG:-C.UTF-8}\"\n")
        append("if [ -n \"\${BASH_VERSION-}\" ]; then\n")
        append("    export HISTFILE=\"\$HOME/.bash_history\"\n")
        append("    export HISTSIZE=1000\n")
        append("fi\n")
    }

    /** One package per line; consumed by [bootstrapScript]. */
    fun bootstrapPackageList(): String = BOOTSTRAP_PACKAGES.joinToString("\n", postfix = "\n")

    /**
     * Idempotent package install for the default toolset.
     *
     * Run once per rootfs, never during extraction: extraction must stay
     * offline and deterministic, while apt needs the network and minutes.
     * Because the toolset is what users expect to exist immediately, the
     * failure mode is explicit rather than silent: progress and apt output go
     * to [BOOTSTRAP_LOG], a failed run writes [BOOTSTRAP_FAILED_MARKER], and
     * the profile hook prints the log path and the retry command.
     */
    fun bootstrapScript(): String = buildString {
        append("#!/bin/sh\n")
        append("# Managed by Minis. Install the default Ubuntu guest toolset once.\n")
        append("set -u\n")
        append("list=/").append(BOOTSTRAP_PACKAGES_PATH).append('\n')
        append("done_marker=/").append(BOOTSTRAP_DONE_MARKER).append('\n')
        append("failed_marker=/").append(BOOTSTRAP_FAILED_MARKER).append('\n')
        append("log=/").append(BOOTSTRAP_LOG).append('\n')
        append("lock=/").append(BOOTSTRAP_LOCK_DIR).append('\n')
        append("[ -f \"\$list\" ] || { echo 'minis-bootstrap: missing '\"\$list\" >&2; exit 1; }\n")
        append("if [ -f \"\$done_marker\" ] && [ \"\${1:-}\" != \"--force\" ]; then\n")
        append("    echo 'minis-bootstrap: already installed (use --force to rerun)'\n")
        append("    exit 0\n")
        append("fi\n")
        append("mkdir -p /var/lib/minis /var/log\n")
        append("if ! mkdir \"\$lock\" 2>/dev/null; then\n")
        append("    echo 'minis-bootstrap: another install is already running'\n")
        append("    exit 0\n")
        append("fi\n")
        append("trap 'rmdir \"\$lock\" 2>/dev/null' EXIT INT TERM\n")
        append("exec >>\"\$log\" 2>&1\n")
        append("echo \"== minis-bootstrap \$(date -u +%Y-%m-%dT%H:%M:%SZ) ==\"\n")
        append("fail() {\n")
        append("    echo \"minis-bootstrap: FAILED: \$*\" >&2\n")
        append("    date -u +%Y-%m-%dT%H:%M:%SZ > \"\$failed_marker\"\n")
        append("    exit 1\n")
        append("}\n")
        append("DEBIAN_FRONTEND=noninteractive\n")
        append("export DEBIAN_FRONTEND\n")
        append("apt-get update || fail 'apt-get update'\n")
        append("# Word splitting is intentional: one package name per line.\n")
        append("# shellcheck disable=SC2046\n")
        append("apt-get install -y --no-install-recommends \$(cat \"\$list\") || fail 'apt-get install'\n")
        append("rm -f \"\$failed_marker\"\n")
        append("date -u +%Y-%m-%dT%H:%M:%SZ > \"\$done_marker\"\n")
        append("echo 'minis-bootstrap: done'\n")
    }

    /**
     * Interactive-shell hook. The app's own shells are not guaranteed to be
     * login shells, so this is best-effort for terminals; `ShellExecutor` /
     * `OnDemandBash` should also run [bootstrapScript] once (see
     * docs/android-ubuntu.md §9). It never fails silently: failures print the
     * log path and the retry command.
     */
    fun bootstrapProfileScript(): String = buildString {
        append("# Managed by Minis. First-login toolset install (python3, git, curl, …).\n")
        append("if [ -f /").append(BOOTSTRAP_DONE_MARKER).append(" ]; then\n")
        append("    :\n")
        append("elif [ -f /").append(BOOTSTRAP_FAILED_MARKER).append(" ]; then\n")
        append("    echo 'minis: default toolset install FAILED — see /")
            .append(BOOTSTRAP_LOG).append("; retry: minis-bootstrap --force' >&2\n")
        append("else\n")
        append("    case \"\$-\" in\n")
        append("        *i*)\n")
        append("            echo 'minis: installing default tools (python3, git, curl, …); one-time, needs network…'\n")
        append("            if /usr/local/sbin/minis-bootstrap; then\n")
        append("                echo 'minis: default tools installed'\n")
        append("            else\n")
        append("                echo 'minis: toolset install FAILED — see /")
            .append(BOOTSTRAP_LOG).append("; retry: minis-bootstrap --force' >&2\n")
        append("            fi\n")
        append("            ;;\n")
        append("    esac\n")
        append("fi\n")
    }

    // ── archive path safety ────────────────────────────────────────────────

    /**
     * Validate one tar entry name and return it in canonical relative form, or
     * `null` when it must be rejected.
     *
     * Rejected: empty, absolute (`/etc/passwd`), any `..` segment (`a/../../x`),
     * NUL bytes, and the archive root `.`. Accepted: `./x`, `a//b`, trailing
     * slash (directory entries). This is the primary tar-slip defence; the
     * extractor additionally refuses to write through a symlinked parent.
     *
     * A relative symlink *target* is deliberately NOT sanitised: the Ubuntu
     * base archive legitimately contains 20 absolute symlinks
     * (`etc/rmt -> /usr/sbin/rmt`) and relative ones with `..`
     * (`usr/bin/ld.so -> ../lib/ld-linux-aarch64.so.1`). Those resolve inside
     * the PRoot guest at runtime and are never followed by the extractor.
     */
    fun sanitizeArchivePath(raw: String): String? {
        if (raw.isEmpty() || raw.length > 4096) return null
        if (raw.indexOf('\u0000') >= 0) return null
        var path = raw
        while (path.startsWith("./")) path = path.substring(2)
        if (path.isEmpty() || path == ".") return null
        if (path.startsWith("/")) return null
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val clean = segments.filter { it.isNotEmpty() && it != "." }
        if (clean.isEmpty()) return null
        return clean.joinToString("/")
    }
}
