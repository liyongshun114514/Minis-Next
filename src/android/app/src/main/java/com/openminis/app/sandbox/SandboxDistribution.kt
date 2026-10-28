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
 * ## On-disk layout
 *
 * [ROOTFS_DIR_NAME] is `Ubuntu-rootfs` (case-sensitive). Before any reader
 * observes it, [RootfsDirectoryMigration] renames the legacy directory without
 * copying or unpacking over user data. Historical names are kept only for
 * migration/backup discovery. The `.distro` marker, not a directory rename,
 * identifies the actual guest distribution — see [isUbuntuRootfs].
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

    /** Canonical Android guest directory. All current readers use this path. */
    const val ROOTFS_DIR_NAME = "Ubuntu-rootfs"

    /** Read only for an upgrade rename; never the destination of a new install. */
    const val LEGACY_ROOTFS_DIR_NAME = "alpine-rootfs"

    /** Sibling that a previous or broken rootfs is preserved in. Never auto-deleted. */
    const val LEGACY_BACKUP_PREFIX = "Ubuntu-rootfs.previous-"

    /** Historical backups remain discoverable without renaming user backups. */
    const val HISTORICAL_BACKUP_PREFIX = "alpine-rootfs.pre-ubuntu-"

    /** Extraction staging directory; swapped in only after a complete extract. */
    const val STAGING_DIR_NAME = "Ubuntu-rootfs.staging"

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

    /** Sourced by interactive shells: reports toolset status; never installs. */
    const val BOOTSTRAP_PROFILE_PATH = "etc/profile.d/99-minis-bootstrap.sh"

    /** Guest paths used by [bootstrapScript]. */
    const val BOOTSTRAP_DONE_MARKER = "var/lib/minis/bootstrap.done"
    const val BOOTSTRAP_FAILED_MARKER = "var/lib/minis/bootstrap.failed"

    /**
     * Written when [bootstrapScript] declines to install because the guest is
     * not root (e.g. the chroot backend). Deliberately distinct from
     * [BOOTSTRAP_FAILED_MARKER]: nothing was attempted, so nothing failed.
     */
    const val BOOTSTRAP_SKIPPED_MARKER = "var/lib/minis/bootstrap.skipped"
    const val BOOTSTRAP_LOG = "var/log/minis-bootstrap.log"
    const val BOOTSTRAP_LOCK_DIR = "var/lib/minis/bootstrap.lock"

    /**
     * Default guest toolset. ubuntu-base ships bash/apt/dpkg/perl/ps/gzip/tar
     * but **no** python3, curl, wget, git, ssh, less, ip, unzip, jq or rg —
     * which the agent prompt and the bundled `minis-mcp-cli` (python3) assume.
     * The build preinstalls exactly this list into the shipped rootfs; nothing
     * installs it at first run. [bootstrapScript] is only the explicit repair
     * entry point for a changed rootfs.
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
        append("# Never give dpkg a pty. Without this, apt's terminal restore fails at\n")
        append("# exit in a guest with no usable stdin tty (TCSAFLUSH / tcsetattr EIO)\n")
        append("# after all packages were configured, so a successful run exits 100.\n")
        append("DPkg::Use-Pty \"false\";\n")
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
     * Explicit install/repair entry point for the default toolset
     * (`/usr/local/sbin/minis-bootstrap`).
     *
     * The shipped rootfs already contains [BOOTSTRAP_PACKAGES] (CI preinstalls
     * them before packaging), so nothing runs this automatically: not
     * extraction, not the app, and not [bootstrapProfileScript]. It exists for
     * a deliberate repair (`minis-bootstrap --force`) of a changed rootfs.
     *
     * Contract:
     *  * **Verified completion.** [BOOTSTRAP_DONE_MARKER] is written only
     *    after `dpkg-query` reports every requested package as
     *    `install ok installed`; an existing marker is re-verified instead of
     *    trusted, and a stale one is removed before a repair.
     *  * **Unattended apt.** `DPkg::Use-Pty=false` plus stdin from `/dev/null`.
     *    With apt's own pty, the terminal restore at exit fails on a guest
     *    without a usable stdin tty
     *    (`E: Setting in Stop via TCSAFLUSH for stdin failed! - tcsetattr
     *    (5: Input/output error)`) *after* every package was configured, and a
     *    successful install becomes exit 100. The switches are also written to
     *    [APT_PROOT_CONF_PATH].
     *  * **Real failures stay failures.** apt's exit status is returned, the
     *    reason is logged, and the tail of [BOOTSTRAP_LOG] is printed to the
     *    caller's stderr so the terminal is not just a generic "FAILED".
     *  * **Root guard on `id -u`.** apt/dpkg need real uid 0. The guard does
     *    not trust `MINIS_SANDBOX_BACKEND` for this decision (a chroot runs
     *    unprivileged regardless of its environment): a non-root run writes
     *    [BOOTSTRAP_SKIPPED_MARKER] and exits 3 without touching apt and
     *    without a misleading [BOOTSTRAP_FAILED_MARKER].
     */
    fun bootstrapScript(): String = buildString {
        append("#!/bin/sh\n")
        append("# Managed by Minis. Explicit install/repair of the Ubuntu guest toolset.\n")
        append("# The shipped rootfs already has these packages; no profile hook runs this.\n")
        append("set -u\n")
        append("list=/").append(BOOTSTRAP_PACKAGES_PATH).append('\n')
        append("done_marker=/").append(BOOTSTRAP_DONE_MARKER).append('\n')
        append("failed_marker=/").append(BOOTSTRAP_FAILED_MARKER).append('\n')
        append("skipped_marker=/").append(BOOTSTRAP_SKIPPED_MARKER).append('\n')
        append("log=/").append(BOOTSTRAP_LOG).append('\n')
        append("lock=/").append(BOOTSTRAP_LOCK_DIR).append('\n')
        append("[ -f \"\$list\" ] || { echo 'minis-bootstrap: missing '\"\$list\" >&2; exit 1; }\n")
        append("mkdir -p /var/lib/minis /var/log\n")
        append("# Keep the caller's stderr (terminal or agent capture): stdout/stderr go to\n")
        append("# the log below, but a failure must still show the real end of that log.\n")
        append("exec 9>&2\n")
        append("force=0\n")
        append("[ \"\${1:-}\" = \"--force\" ] && force=1\n")
        append("# One package per line -> positional params, so no word splitting of a\n")
        append("# command substitution is needed anywhere below.\n")
        append("set --\n")
        append("while IFS= read -r pkg; do\n")
        append("    [ -n \"\$pkg\" ] || continue\n")
        append("    set -- \"\$@\" \"\$pkg\"\n")
        append("done < \"\$list\"\n")
        append("[ \"\$#\" -gt 0 ] || { echo 'minis-bootstrap: empty package list' >&2; exit 1; }\n")
        append("# dpkg's own database is the only trustworthy 'installed' check; the done\n")
        append("# marker is a hint that is re-verified on every run.\n")
        append("missing_packages() {\n")
        append("    while IFS= read -r pkg; do\n")
        append("        [ -n \"\$pkg\" ] || continue\n")
        append("        status=\$(dpkg-query -W -f='\${Status}' \"\$pkg\" 2>/dev/null) || status=''\n")
        append("        [ \"\$status\" = 'install ok installed' ] || printf '%s\\n' \"\$pkg\"\n")
        append("    done < \"\$list\"\n")
        append("}\n")
        append("missing=\$(missing_packages)\n")
        append("if [ -z \"\$missing\" ] && [ \"\$force\" = 0 ]; then\n")
        append("    echo 'minis-bootstrap: toolset already installed and verified (--force to reinstall)' >&2\n")
        append("    exit 0\n")
        append("fi\n")
        append("guest_uid=\$(id -u)\n")
        append("backend=\${MINIS_SANDBOX_BACKEND:-unset}\n")
        append("if [ \"\$guest_uid\" != 0 ]; then\n")
        append("    {\n")
        append("        echo \"minis-bootstrap: SKIPPED: guest uid \$guest_uid (backend=\$backend) is not root.\"\n")
        append("        echo 'minis-bootstrap: apt/dpkg cannot install packages without real uid 0.'\n")
        append("        echo 'minis-bootstrap: the chroot backend runs unprivileged - switch to the PRoot'\n")
        append("        echo 'minis-bootstrap: backend in Settings > Sandbox, then run: minis-bootstrap --force'\n")
        append("    } >&2\n")
        append("    printf 'uid=%s backend=%s reason=not-root\\n' \"\$guest_uid\" \"\$backend\" > \"\$skipped_marker\"\n")
        append("    printf '%s minis-bootstrap: skipped: uid=%s backend=%s\\n' \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" \"\$guest_uid\" \"\$backend\" >> \"\$log\"\n")
        append("    exit 3\n")
        append("fi\n")
        append("rm -f \"\$skipped_marker\"\n")
        append("if ! mkdir \"\$lock\" 2>/dev/null; then\n")
        append("    echo 'minis-bootstrap: another install is already running' >&2\n")
        append("    exit 0\n")
        append("fi\n")
        append("trap 'rmdir \"\$lock\" 2>/dev/null' EXIT INT TERM\n")
        append("exec >>\"\$log\" 2>&1\n")
        append("show_log_tail() {\n")
        append("    {\n")
        append("        echo \"minis-bootstrap: last lines of \$log:\"\n")
        append("        tail -n 20 \"\$log\" 2>/dev/null\n")
        append("    } >&9 2>/dev/null || true\n")
        append("}\n")
        append("fail() {\n")
        append("    rc=\$1\n")
        append("    shift\n")
        append("    echo \"minis-bootstrap: FAILED (exit \$rc): \$*\"\n")
        append("    date -u +%Y-%m-%dT%H:%M:%SZ > \"\$failed_marker\"\n")
        append("    show_log_tail\n")
        append("    exit \"\$rc\"\n")
        append("}\n")
        append("echo \"== minis-bootstrap \$(date -u +%Y-%m-%dT%H:%M:%SZ) ==\"\n")
        append("echo \"diag: backend=\$backend uid=\$guest_uid force=\$force packages=\$#\"\n")
        append("echo \"diag: apt=\$(apt-get --version 2>/dev/null | head -n 1)\"\n")
        append("if [ -f \"\$done_marker\" ]; then\n")
        append("    echo \"minis-bootstrap: re-verifying, done marker not trusted; missing:\$(printf '%s' \"\$missing\" | tr '\\n' ' ')\"\n")
        append("    rm -f \"\$done_marker\"\n")
        append("fi\n")
        append("if [ -n \"\$missing\" ]; then\n")
        append("    echo \"minis-bootstrap: installing missing:\$(printf '%s' \"\$missing\" | tr '\\n' ' ')\"\n")
        append("fi\n")
        append("# Unattended, non-interactive apt:\n")
        append("#  * DEBIAN_FRONTEND=noninteractive -> no debconf questions.\n")
        append("#  * DPkg::Use-Pty=false -> apt must not hand dpkg a pty; with a pty its\n")
        append("#    terminal restore at exit fails on a guest without a usable stdin tty\n")
        append("#    (TCSAFLUSH ... tcsetattr (5: Input/output error)) after all packages\n")
        append("#    were configured, turning success into exit 100.\n")
        append("#  * stdin from /dev/null -> nothing can prompt or read the terminal.\n")
        append("#  * Sandbox/Seccomp also live in /etc/apt/apt.conf.d/99minis-proot.conf.\n")
        append("DEBIAN_FRONTEND=noninteractive\n")
        append("export DEBIAN_FRONTEND\n")
        append("apt-get -o DPkg::Use-Pty=false -o APT::Sandbox::User=root -o APT::Sandbox::Seccomp=false update </dev/null || fail \$? 'apt-get update'\n")
        append("rc=0\n")
        append("apt-get -y -o DPkg::Use-Pty=false -o APT::Sandbox::User=root -o APT::Sandbox::Seccomp=false -o DPkg::Lock::Timeout=600 install --no-install-recommends \"\$@\" </dev/null || rc=\$?\n")
        append("if [ \"\$rc\" != 0 ]; then\n")
        append("    if grep -q 'TCSAFLUSH' \"\$log\" 2>/dev/null; then\n")
        append("        echo 'minis-bootstrap: hint: apt failed to restore a terminal (TCSAFLUSH); DPkg::Use-Pty must stay false'\n")
        append("    fi\n")
        append("    fail \"\$rc\" 'apt-get install'\n")
        append("fi\n")
        append("missing=\$(missing_packages)\n")
        append("[ -z \"\$missing\" ] || fail 1 \"package verification failed; still missing:\$(printf '%s' \"\$missing\" | tr '\\n' ' ')\"\n")
        append("rm -f \"\$failed_marker\"\n")
        append("date -u +%Y-%m-%dT%H:%M:%SZ > \"\$done_marker\"\n")
        append("echo \"minis-bootstrap: done: \$# packages verified installed and configured\"\n")
    }

    /**
     * Interactive-shell hook. **Reports status; installs nothing.**
     *
     * The shipped rootfs is built with [BOOTSTRAP_PACKAGES] already installed
     * (CI preinstalls them before packaging) and writes
     * [BOOTSTRAP_DONE_MARKER] only after verifying them, so there is no
     * first-login install any more: neither the interactive nor the
     * non-interactive path may run apt or [bootstrapScript]. The hook only:
     *  * stays completely silent when every package is verified installed
     *    (a preinstalled done marker plus intact packages is success, also on
     *    the unprivileged chroot backend),
     *  * names the missing packages, verified with `dpkg-query` so a stale
     *    marker cannot hide a broken rootfs,
     *  * points at the explicit repair command, which itself refuses to run
     *    without real uid 0.
     */
    fun bootstrapProfileScript(): String = buildString {
        append("# Managed by Minis. Status only: the shipped rootfs includes the toolset.\n")
        append("# Never installs anything: repair is explicit (minis-bootstrap --force).\n")
        append("case \"\$-\" in\n")
        append("    *i*)\n")
        append("        if command -v dpkg-query >/dev/null 2>&1 && [ -f /")
            .append(BOOTSTRAP_PACKAGES_PATH).append(" ]; then\n")
        append("            minis_missing=''\n")
        append("            while IFS= read -r minis_pkg; do\n")
        append("                [ -n \"\$minis_pkg\" ] || continue\n")
        append("                minis_status=\$(dpkg-query -W -f='\${Status}' \"\$minis_pkg\" 2>/dev/null) || minis_status=''\n")
        append("                [ \"\$minis_status\" = 'install ok installed' ] || minis_missing=\"\$minis_missing \$minis_pkg\"\n")
        append("            done < /").append(BOOTSTRAP_PACKAGES_PATH).append('\n')
        append("            if [ -n \"\$minis_missing\" ]; then\n")
        append("                echo \"minis: guest toolset incomplete:\$minis_missing\" >&2\n")
        append("                echo 'minis: nothing is installed automatically; if you need it, run: minis-bootstrap --force' >&2\n")
        append("                echo 'minis: that needs real uid 0 - the PRoot backend, not chroot' >&2\n")
        append("            elif [ ! -f /").append(BOOTSTRAP_DONE_MARKER).append(" ] && [ -f /")
            .append(BOOTSTRAP_FAILED_MARKER).append(" ]; then\n")
        append("                echo 'minis: all toolset packages are verified installed, but an earlier install attempt failed' >&2\n")
        append("                echo 'minis: details: /").append(BOOTSTRAP_LOG).append("' >&2\n")
        append("            fi\n")
        append("            unset minis_missing minis_pkg minis_status\n")
        append("        fi\n")
        append("        ;;\n")
        append("esac\n")
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
