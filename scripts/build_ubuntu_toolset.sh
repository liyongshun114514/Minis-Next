#!/usr/bin/env bash
#
# Build a cloud-preinstalled Ubuntu 24.04 LTS arm64 guest toolset.
#
# THIS IS A CI BUILD ARTIFACT, NOT A SOURCE FILE.
# The generated tarball is written to deps/build/ubuntu-toolset/ (git-ignored
# build output). Never commit a rootfs produced by this script.
#
# Where it runs (hard fail-closed gates):
#   * GitHub Hosted Linux ARM64 only: GITHUB_ACTIONS=true, RUNNER_OS=Linux,
#     RUNNER_ARCH=ARM64, RUNNER_ENVIRONMENT=github-hosted (self-hosted is
#     refused), uname Linux/aarch64, Ubuntu 24.04.
#   * Android, a phone, PRoot and any local workstation are refused before
#     sudo is requested. There is no on-device root/Shizuku path.
#   * The only privileged operations are `unshare --mount` + `chroot` inside a
#     throw-away temp tree created by this script. Nothing on the runner is
#     modified outside that tree and its private mount namespace.
#
# What it does:
#   1. scripts/prepare_android_ubuntu.sh downloads the pinned official
#      ubuntu-base 24.04.5 arm64 tarball into an isolated temp assets dir and
#      verifies the signed SHA256SUMS + pinned digest.
#   2. The verified tarball is extracted into $TMP/ubuntu-preinstall-agent.XXX.
#   3. In a private mount namespace (procfs + a tiny tmpfs /dev), apt installs
#      the 19-package toolset from scripts/ubuntu-toolset-packages.txt.
#      Bootstrap uses the official http://ports.ubuntu.com archive (ubuntu-base
#      has no ca-certificates yet); once ca-certificates is installed the
#      sources are switched to https://ports.ubuntu.com and refreshed before
#      the full install. Signature checking is never disabled.
#   4. dpkg --audit must be clean, every package must be `install ok installed`,
#      and version probes must actually run inside the guest.
#   5. usr/local/share/minis/toolset-manifest.json (schema 1) and
#      var/lib/minis/bootstrap.done are written, apt caches are removed, and
#      the tree is archived as ubuntu-base-arm64.tar.gz + .sha256.
#
# Usage (CI):
#   GITHUB_ACTIONS=true RUNNER_OS=Linux RUNNER_ARCH=ARM64 \
#     scripts/build_ubuntu_toolset.sh
#
#   UBUNTU_TOOLSET_OUTPUT=/path/to/ubuntu-base-arm64.tar.gz \
#     scripts/build_ubuntu_toolset.sh --output /path/to/out-dir
#
# Output default: deps/build/ubuntu-toolset/ubuntu-base-arm64.tar.gz
#                 deps/build/ubuntu-toolset/ubuntu-base-arm64.tar.gz.sha256
#
# The recorded hash is the digest of *this* artifact. Package versions are
# pinned by the Ubuntu archive at build time, but the tarball is not claimed to
# be bit-for-bit reproducible (timestamps and archive ordering are not frozen).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PACKAGE_LIST="${UBUNTU_TOOLSET_PACKAGES:-$SCRIPT_DIR/ubuntu-toolset-packages.txt}"
BASE_VERSION="24.04.5"
# Trust anchor: official ubuntu-base 24.04.5 arm64 (see
# scripts/prepare_android_ubuntu.sh). Bump both scripts together.
BASE_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
EXPECTED_PACKAGE_COUNT=19

DEFAULT_OUTPUT="$PROJECT_ROOT/deps/build/ubuntu-toolset/ubuntu-base-arm64.tar.gz"
OUTPUT="${UBUNTU_TOOLSET_OUTPUT:-$DEFAULT_OUTPUT}"
MANIFEST_GUEST_PATH="usr/local/share/minis/toolset-manifest.json"
BOOTSTRAP_DONE_GUEST_PATH="var/lib/minis/bootstrap.done"

log()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
Usage: scripts/build_ubuntu_toolset.sh [--output PATH] [--check-host]

CI-only builder for the preinstalled Ubuntu 24.04 arm64 toolset rootfs.
Requires GitHub Hosted Linux ARM64 (GITHUB_ACTIONS=true, RUNNER_OS=Linux,
RUNNER_ARCH=ARM64) and an Ubuntu 24.04 aarch64 host. Android/PRoot/local
machines are refused.

Options:
  --output PATH   tarball path, or a directory (default:
                  deps/build/ubuntu-toolset/ubuntu-base-arm64.tar.gz)
  --check-host    run only the host safety gate (no sudo, no network)
  --help          show this help

Environment:
  UBUNTU_TOOLSET_OUTPUT    same as --output
  UBUNTU_TOOLSET_PACKAGES  package list path (default scripts/ubuntu-toolset-packages.txt)
  UBUNTU_TOOLSET_VALIDATE  must stay 1; 0 is rejected because the
                           verify_ubuntu_apk.py require_toolset=True
                           precheck is mandatory
USAGE
}

# ── host safety gate ────────────────────────────────────────────────────────

# Pure check, unit-testable without touching the host.
# Args: uname_s uname_m os_id os_version android_marker proot_marker \
#       github_actions runner_os runner_arch
host_guard_check() {
    local uname_s="$1" uname_m="$2" os_id="$3" os_ver="$4"
    local android="$5" proot="$6" gh_actions="$7" runner_os="$8" runner_arch="$9"

    if [ "$uname_s" != "Linux" ]; then
        printf 'host gate: not Linux (uname -s=%s)\n' "$uname_s" >&2
        return 1
    fi
    case "$uname_m" in
        aarch64|arm64) ;;
        *)
            printf 'host gate: not aarch64/arm64 (uname -m=%s); this builder never runs on x86 or a phone\n' "$uname_m" >&2
            return 1
            ;;
    esac
    if [ "$os_id" != "ubuntu" ] || [ "$os_ver" != "24.04" ]; then
        printf 'host gate: require Ubuntu 24.04 (got ID=%s VERSION_ID=%s)\n' "$os_id" "$os_ver" >&2
        return 1
    fi
    if [ "$android" != "0" ]; then
        printf 'host gate: Android environment detected; on-device root/sudo chroot is forbidden\n' >&2
        return 1
    fi
    if [ "$proot" != "0" ]; then
        printf 'host gate: PRoot environment detected; run this only on a native cloud arm64 runner\n' >&2
        return 1
    fi
    if [ "$gh_actions" != "true" ] || [ "$runner_os" != "Linux" ] || [ "$runner_arch" != "ARM64" ]; then
        printf 'host gate: require GitHub Hosted Linux ARM64 CI (GITHUB_ACTIONS=true RUNNER_OS=Linux RUNNER_ARCH=ARM64; got %s/%s/%s)\n' \
            "$gh_actions" "$runner_os" "$runner_arch" >&2
        return 1
    fi
    return 0
}

probe_android() {
    local marker=0
    [ -n "${ANDROID_ROOT:-}" ] && marker=1
    [ -n "${ANDROID_DATA:-}" ] && marker=1
    [ -n "${ANDROID_STORAGE:-}" ] && marker=1
    [ -e /system/build.prop ] && marker=1
    [ -e /system/bin/app_process ] && marker=1
    [ -e /data/data ] && marker=1
    command -v getprop >/dev/null 2>&1 && marker=1
    printf '%s' "$marker"
}

probe_proot() {
    local marker=0
    [ -n "${PROOT_TMP_DIR:-}" ] && marker=1
    [ -n "${PROOT_LOADER:-}" ] && marker=1
    [ -n "${PROOT_NO_SECCOMP:-}" ] && marker=1
    if [ -r "/proc/${PPID:-0}/cmdline" ] && tr '\0' ' ' < "/proc/${PPID:-0}/cmdline" 2>/dev/null | grep -q 'proot'; then
        marker=1
    fi
    printf '%s' "$marker"
}

host_guard() {
    local os_id os_ver
    if [ "${RUNNER_ENVIRONMENT:-github-hosted}" = "self-hosted" ]; then
        printf 'host gate: self-hosted runners are refused; use GitHub Hosted Linux ARM64\n' >&2
        return 1
    fi
    os_id="$(sed -n 's/^ID=//p' /etc/os-release 2>/dev/null | head -n1 | tr -d '"')"
    os_ver="$(sed -n 's/^VERSION_ID=//p' /etc/os-release 2>/dev/null | head -n1 | tr -d '"')"
    host_guard_check \
        "$(uname -s)" "$(uname -m)" "$os_id" "$os_ver" \
        "$(probe_android)" "$(probe_proot)" \
        "${GITHUB_ACTIONS:-}" "${RUNNER_OS:-}" "${RUNNER_ARCH:-}"
}

ensure_root() {
    if [ "$(id -u)" -eq 0 ]; then
        return 0
    fi
    command -v sudo >/dev/null 2>&1 || die "root is required and sudo is unavailable"
    log "==> elevating with sudo (GitHub Hosted runner only)"
    # Forward only the build parameters we define; the CI identity variables
    # come from the runner and are checked again after elevation. sudo is
    # allowed on the hosted runner; this script never touches a device.
    exec sudo -n env \
        GITHUB_ACTIONS="${GITHUB_ACTIONS:-}" \
        RUNNER_OS="${RUNNER_OS:-}" \
        RUNNER_ARCH="${RUNNER_ARCH:-}" \
        RUNNER_ENVIRONMENT="${RUNNER_ENVIRONMENT:-}" \
        UBUNTU_TOOLSET_OUTPUT="$OUTPUT" \
        UBUNTU_TOOLSET_PACKAGES="$PACKAGE_LIST" \
        UBUNTU_TOOLSET_VALIDATE="${UBUNTU_TOOLSET_VALIDATE:-1}" \
        bash "$SCRIPT_DIR/$(basename "$0")"
}

require_commands() {
    local c missing=""
    for c in curl tar gzip sha256sum awk sed grep mktemp unshare mount umount chroot gpgv; do
        command -v "$c" >/dev/null 2>&1 || missing="$missing $c"
    done
    [ -z "$missing" ] || die "missing required host commands:$missing"
    [ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ] || \
        die "ubuntu-keyring is required to verify the official base signature"
}

# ── package list / base fetch ───────────────────────────────────────────────

load_package_list() {
    [ -f "$PACKAGE_LIST" ] || die "package list not found: $PACKAGE_LIST"
    PKGS=""
    local pkg count=0
    while IFS= read -r pkg || [ -n "$pkg" ]; do
        pkg="${pkg%$'\r'}"
        case "$pkg" in ""|"#"*) continue ;; esac
        case "$pkg" in
            *[!a-z0-9+.-]*) die "unsafe package name in $PACKAGE_LIST: $pkg" ;;
        esac
        count=$((count + 1))
        PKGS="$PKGS $pkg"
    done < "$PACKAGE_LIST"
    [ "$count" -eq "$EXPECTED_PACKAGE_COUNT" ] || \
        die "expected $EXPECTED_PACKAGE_COUNT packages in $PACKAGE_LIST, found $count"
    local unique
    unique="$(printf '%s\n' $PKGS | sort -u | wc -l)"
    [ "$unique" -eq "$EXPECTED_PACKAGE_COUNT" ] || die "duplicate package names in $PACKAGE_LIST"
    PKGS="${PKGS# }"
    PKG_ARGS="$PKGS"
    # shellcheck disable=SC2086
    set -- $PKGS
    [ "$#" -eq "$EXPECTED_PACKAGE_COUNT" ] || die "package list parsing failed"
}

fetch_verified_base() {
    local work="$1"
    local assets="$work/base-assets" cache="$work/base-cache"
    mkdir -p "$assets" "$cache"
    log "==> fetching and verifying official ubuntu-base $BASE_VERSION arm64"
    ASSETS_DIR="$assets" \
    UBUNTU_CACHE_DIR="$cache" \
    UBUNTU_BASE_VERSION="$BASE_VERSION" \
    REQUIRE_SIGNATURE=1 \
        bash "$SCRIPT_DIR/prepare_android_ubuntu.sh" || \
        die "prepare_android_ubuntu.sh failed (signature/pin verification is mandatory)"

    BASE_TARBALL="$assets/ubuntu-base-arm64.tar.gz"
    [ -f "$BASE_TARBALL" ] || die "verified base tarball missing: $BASE_TARBALL"
    local actual
    actual="$(sha256sum "$BASE_TARBALL" | awk '{print $1}')"
    [ "$actual" = "$BASE_SHA256" ] || \
        die "base SHA-256 mismatch: expected $BASE_SHA256, got $actual"
    gzip -t "$BASE_TARBALL" || die "base tarball gzip stream is corrupt"
    log "    base sha256 verified: $actual"
}

extract_base() {
    local tarball="$1" root="$2"
    mkdir -p "$root"
    log "==> extracting verified base into the disposable temp root"
    tar --numeric-owner -xzf "$tarball" -C "$root" || die "failed to extract verified base"
    [ -f "$root/usr/bin/apt" ] || die "base rootfs has no /usr/bin/apt"
    [ -f "$root/var/lib/dpkg/status" ] || die "base rootfs has no dpkg status"
    [ -f "$root/usr/lib/os-release" ] || die "base rootfs has no os-release"
    grep -q '^ID=ubuntu' "$root/usr/lib/os-release" || die "base rootfs is not Ubuntu"
    grep -Eq '^VERSION_ID="?24\.04"?$' "$root/usr/lib/os-release" || die "base rootfs is not Ubuntu 24.04"
}

# ── private mount namespace: proc + minimal /dev ────────────────────────────

MOUNTS=()

record_mount() { MOUNTS+=("$1"); }

cleanup_mounts() {
    local i target
    for (( i=${#MOUNTS[@]}-1; i>=0; i-- )); do
        target="${MOUNTS[i]}"
        case "$target" in
            "$ROOT"/*) ;;
            *) warn "refusing to unmount path outside the temp root: $target"; continue ;;
        esac
        umount -- "$target" 2>/dev/null || umount -l -- "$target" 2>/dev/null || true
    done
    MOUNTS=()
}

prepare_mounts() {
    mkdir -p "$ROOT/proc"
    mount -t proc proc "$ROOT/proc" || die "cannot mount procfs inside the private namespace"
    record_mount "$ROOT/proc"

    mkdir -p "$ROOT/dev"
    mount -t tmpfs -o mode=755,size=16m tmpfs "$ROOT/dev" || die "cannot mount tmpfs on /dev"
    record_mount "$ROOT/dev"

    local spec name major minor
    for spec in "null 1 3" "zero 1 5" "full 1 7" "random 1 8" "urandom 1 9" "tty 5 0"; do
        set -- $spec
        name="$1"; major="$2"; minor="$3"
        mknod -m 666 "$ROOT/dev/$name" c "$major" "$minor" 2>/dev/null || true
    done
    # Some sandboxes forbid mknod even for root; bind only the individual
    # device nodes actually needed, never the host's whole /dev.
    for spec in "null 1 3" "zero 1 5" "full 1 7" "random 1 8" "urandom 1 9" "tty 5 0"; do
        set -- $spec
        name="$1"
        if [ ! -e "$ROOT/dev/$name" ]; then
            [ -e "/dev/$name" ] || die "host lacks /dev/$name and mknod is not permitted"
            : > "$ROOT/dev/$name"
            mount --bind "/dev/$name" "$ROOT/dev/$name" || die "cannot bind /dev/$name"
            record_mount "$ROOT/dev/$name"
        fi
    done
    mkdir -p "$ROOT/dev/pts" "$ROOT/dev/shm"
    ln -sfn /proc/self/fd "$ROOT/dev/fd"
    ln -sfn /proc/self/fd/0 "$ROOT/dev/stdin"
    ln -sfn /proc/self/fd/1 "$ROOT/dev/stdout"
    ln -sfn /proc/self/fd/2 "$ROOT/dev/stderr"
}

prepare_resolv_conf() {
    RESOLV_SAVED=0
    RESOLV_ACTIVE=1
    if [ -e "$ROOT/etc/resolv.conf" ] || [ -L "$ROOT/etc/resolv.conf" ]; then
        mv -f "$ROOT/etc/resolv.conf" "$WORK/resolv.conf.saved"
        RESOLV_SAVED=1
    fi
    cp /etc/resolv.conf "$ROOT/etc/resolv.conf" || die "cannot seed /etc/resolv.conf"
}

restore_resolv_conf() {
    [ "${RESOLV_ACTIVE:-0}" = "1" ] || return 0
    rm -f "$ROOT/etc/resolv.conf"
    if [ "${RESOLV_SAVED:-0}" = "1" ] && [ -e "$WORK/resolv.conf.saved" ]; then
        mv -f "$WORK/resolv.conf.saved" "$ROOT/etc/resolv.conf"
    fi
    RESOLV_ACTIVE=0
}

prepare_policy_rc_d() {
    POLICY_SAVED=0
    if [ -e "$ROOT/usr/sbin/policy-rc.d" ]; then
        cp -f "$ROOT/usr/sbin/policy-rc.d" "$WORK/policy-rc.d.saved"
        POLICY_SAVED=1
    fi
    mkdir -p "$ROOT/usr/sbin"
    printf '#!/bin/sh\nexit 101\n' > "$ROOT/usr/sbin/policy-rc.d"
    chmod 0755 "$ROOT/usr/sbin/policy-rc.d"
}

restore_policy_rc_d() {
    rm -f "$ROOT/usr/sbin/policy-rc.d"
    if [ "${POLICY_SAVED:-0}" = "1" ] && [ -e "$WORK/policy-rc.d.saved" ]; then
        cp -f "$WORK/policy-rc.d.saved" "$ROOT/usr/sbin/policy-rc.d"
    fi
}

# ── apt: signed repositories only, no services, no PTY ──────────────────────

APT_OPTS="-o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false"

root_run() {
    local cmd="$1"
    chroot "$ROOT" /usr/bin/env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        HOME=/root TERM=dumb LANG=C LC_ALL=C TZ=UTC RUNLEVEL=1 \
        DEBIAN_FRONTEND=noninteractive \
        /bin/bash -c "$cmd" </dev/null
}

root_log() {
    local cmd="$1"
    log "    [guest] $cmd"
    # stdin is /dev/null: dpkg/apt must never prompt or grab a terminal.
    chroot "$ROOT" /usr/bin/env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        HOME=/root TERM=dumb LANG=C LC_ALL=C TZ=UTC RUNLEVEL=1 \
        DEBIAN_FRONTEND=noninteractive \
        /bin/bash -c "$cmd" </dev/null 2>&1 | tee -a "$WORK/build.log"
    return "${PIPESTATUS[0]}"
}

set_sources_scheme() {
    local scheme="$1" f
    for f in "$ROOT/etc/apt/sources.list" "$ROOT"/etc/apt/sources.list.d/*.sources; do
        [ -f "$f" ] || continue
        sed -i -E "s#https?://(ports|archive|security)\.ubuntu\.com#${scheme}://\1.ubuntu.com#g" "$f"
    done
}

assert_sources_secure() {
    local f bad
    f="$ROOT/etc/apt/sources.list.d/ubuntu.sources"
    [ -f "$f" ] || [ -f "$ROOT/etc/apt/sources.list" ] || die "no Ubuntu apt sources found"
    if [ -f "$f" ]; then
        grep -q '^Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' "$f" || \
            die "ubuntu.sources does not pin Signed-By to the official keyring"
    fi
    bad="$(grep -RInE 'trusted[[:space:]]*=[[:space:]]*yes|allow-insecure|allow-unauthenticated|Verify-Peer[[:space:]]+"?false|Verify-Host[[:space:]]+"?false|Check-Valid-Until[[:space:]]+"?false' \
        "$ROOT/etc/apt" 2>/dev/null | grep -v ':[[:space:]]*#' || true)"
    [ -z "$bad" ] || die "apt configuration weakens verification:
$bad"
    bad="$(grep -RhE '^[[:space:]]*(URIs:|deb )' "$ROOT/etc/apt" 2>/dev/null | \
        grep -Ev 'https?://(ports|archive|security)\.ubuntu\.com/' || true)"
    [ -z "$bad" ] || die "unexpected apt repository (only official Ubuntu hosts are allowed):
$bad"
}

assert_https_sources() {
    local hits
    hits="$(grep -RhE '^[^#]*http://' "$ROOT/etc/apt/sources.list" "$ROOT"/etc/apt/sources.list.d/*.sources 2>/dev/null || true)"
    [ -z "$hits" ] || die "apt sources still use http after ca-certificates was installed:
$hits"
}

install_packages() {
    log "==> bootstrap apt over http://ports.ubuntu.com (no ca-certificates yet)"
    set_sources_scheme http
    assert_sources_secure
    root_log "apt-get $APT_OPTS update"
    root_log "apt-get $APT_OPTS install -y --no-install-recommends ca-certificates"
    root_run 'test -s /etc/ssl/certs/ca-certificates.crt' || die "ca-certificates store missing after bootstrap"

    log "==> switching official ports sources to https and refreshing signed indexes"
    set_sources_scheme https
    assert_sources_secure
    assert_https_sources
    root_log "apt-get $APT_OPTS update"

    log "==> installing the full $EXPECTED_PACKAGE_COUNT-package toolset"
    # shellcheck disable=SC2086
    root_log "apt-get $APT_OPTS install -y --no-install-recommends $PKG_ARGS"
    root_log "dpkg --configure -a"
    root_run 'test -s /etc/ssl/certs/ca-certificates.crt' || die "ca-certificates store disappeared"
}

# ── package / command verification (pure helpers are unit-tested) ───────────

dpkg_field() {
    local status_file="$1" pkg="$2" field="$3"
    awk -v pkg="$pkg" -v field="$field" '
        /^Package: / { cur = substr($0, 10); sub(/\r$/, "", cur); next }
        cur == pkg && index($0, field ": ") == 1 {
            value = substr($0, length(field) + 3)
            sub(/\r$/, "", value)
            print value
            exit
        }
    ' "$status_file"
}

package_version() {
    dpkg_field "$1" "$2" "Version"
}

# Prints "package<TAB>version" for every installed package; exits non-zero with
# a clear message for a missing/deinstalled package.
verify_package_state() {
    local status_file="$1" list_file="$2" pkg status ver
    [ -f "$status_file" ] || { printf 'error: missing dpkg status: %s\n' "$status_file" >&2; return 1; }
    while IFS= read -r pkg || [ -n "$pkg" ]; do
        pkg="${pkg%$'\r'}"
        case "$pkg" in ""|"#"*) continue ;; esac
        status="$(dpkg_field "$status_file" "$pkg" "Status")"
        ver="$(package_version "$status_file" "$pkg")"
        if [ "$status" != "install ok installed" ]; then
            printf 'error: %s is not installed (Status: %s)\n' "$pkg" "${status:-<missing>}" >&2
            return 1
        fi
        if [ -z "$ver" ]; then
            printf 'error: %s has no dpkg Version\n' "$pkg" >&2
            return 1
        fi
        printf '%s\t%s\n' "$pkg" "$ver"
    done < "$list_file"
}

verify_guest_state() {
    local arch
    arch="$(root_run 'dpkg --print-architecture')"
    [ "$arch" = "arm64" ] || die "guest dpkg architecture is '$arch', expected arm64"

    verify_package_state "$ROOT/var/lib/dpkg/status" "$PACKAGE_LIST" > "$WORK/package-versions.tsv" || \
        die "package state verification failed"

    local pkg status
    while IFS= read -r pkg || [ -n "$pkg" ]; do
        pkg="${pkg%$'\r'}"
        case "$pkg" in ""|"#"*) continue ;; esac
        status="$(root_run "dpkg-query -W -f='\${Status}' '$pkg'")" || die "dpkg-query failed for $pkg"
        [ "$status" = "install ok installed" ] || die "dpkg-query says $pkg is '$status'"
    done < "$PACKAGE_LIST"

    local audit
    audit="$(root_run 'dpkg --audit')" || die "dpkg --audit exited non-zero"
    [ -z "$audit" ] || die "dpkg --audit is not clean:
$audit"
    log "    dpkg --audit: clean; all $EXPECTED_PACKAGE_COUNT packages install ok installed"
}

# key|command pairs. Output must be non-empty and the command must exit 0.
COMMAND_SPECS=(
    'bash|bash --version'
    'python3|python3 --version'
    'mcp-http|/usr/bin/python3 -I -c "import httpx, httpcore, socksio; httpx.Client(trust_env=False).close(); print(httpx.__version__)"'
    'git|git --version'
    'curl|curl --version'
    'wget|wget --version'
    'ssh|ssh -V'
    'rg|rg --version'
    'jq|jq --version'
    'pip|python3 -m pip --version 2>/dev/null'
    'venv|rm -rf /tmp/minis-venv-check; python3 -m venv /tmp/minis-venv-check && /tmp/minis-venv-check/bin/python -V; rc=$?; rm -rf /tmp/minis-venv-check; exit $rc'
    'less|less --version'
    'ps|ps --version'
    'ip|ip -V'
    'unzip|unzip -v'
    'xz|xz --version'
    'ca-certificates|test -s /etc/ssl/certs/ca-certificates.crt && echo "ca-certificates.crt present ($(wc -c < /etc/ssl/certs/ca-certificates.crt) bytes)"'
)

verify_commands() {
    : > "$WORK/commands.tsv"
    local spec key cmd out rc first
    for spec in "${COMMAND_SPECS[@]}"; do
        key="${spec%%|*}"
        cmd="${spec#*|}"
        if out="$(root_run "$cmd" 2>&1)"; then rc=0; else rc=$?; fi
        [ "$rc" -eq 0 ] || die "tool verification failed: $key ($cmd), exit $rc:
$out"
        first="$(printf '%s\n' "$out" | sed -n '1p' | tr -d '\r' | tr '\t' ' ')"
        [ -n "$first" ] || die "tool verification produced no version output: $key ($cmd)"
        printf '%s\t%s\t%s\t%s\n' "$key" "$cmd" "$rc" "$first" >> "$WORK/commands.tsv"
    done
    local required
    for required in python3 git curl wget ssh rg jq pip venv; do
        grep -q "^${required}"$'\t' "$WORK/commands.tsv" || die "missing command evidence for $required"
    done
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/ /g' | tr -d '\r\n'
}

# render_manifest ROOTFS BASE_SHA COMMANDS_TSV OUTPUT
render_manifest() {
    local rootfs="$1" base_sha="$2" commands_tsv="$3" out="$4"
    local status_file="$rootfs/var/lib/dpkg/status"
    local pkg ver key cmd rc text first=1

    [ -f "$status_file" ] || { printf 'error: missing dpkg status: %s\n' "$status_file" >&2; return 1; }
    mkdir -p "$(dirname "$out")"
    {
        printf '{\n'
        printf '  "schema": 1,\n'
        printf '  "distro": "ubuntu24.04",\n'
        printf '  "arch": "arm64",\n'
        printf '  "official_base_sha256": "%s",\n' "$base_sha"
        printf '  "packages": {'
        while IFS= read -r pkg || [ -n "$pkg" ]; do
            pkg="${pkg%$'\r'}"
            case "$pkg" in ""|"#"*) continue ;; esac
            ver="$(package_version "$status_file" "$pkg")"
            [ -n "$ver" ] || { printf 'error: no Version for %s\n' "$pkg" >&2; return 1; }
            if [ "$first" -eq 1 ]; then printf '\n'; else printf ',\n'; fi
            printf '    "%s": "%s"' "$(json_escape "$pkg")" "$(json_escape "$ver")"
            first=0
        done < "$PACKAGE_LIST"
        printf '\n  },\n'
        printf '  "commands": {'
        first=1
        while IFS=$'\t' read -r key cmd rc text || [ -n "$key" ]; do
            [ -n "$key" ] || continue
            [ -n "$text" ] || { printf 'error: empty command output for %s\n' "$key" >&2; return 1; }
            if [ "$first" -eq 1 ]; then printf '\n'; else printf ',\n'; fi
            printf '    "%s": "%s"' "$(json_escape "$key")" "$(json_escape "$text")"
            first=0
        done < "$commands_tsv"
        printf '\n  }\n'
        printf '}\n'
    } > "$out.tmp" || return 1
    mv -f "$out.tmp" "$out" || return 1
    chmod 0644 "$out"
}

write_manifest_and_marker() {
    local manifest="$ROOT/$MANIFEST_GUEST_PATH"
    render_manifest "$ROOT" "$BASE_SHA256" "$WORK/commands.tsv" "$manifest" || die "manifest generation failed"
    root_run "jq -e '.schema == 1 and .distro == \"ubuntu24.04\" and .arch == \"arm64\" and .official_base_sha256 == \"$BASE_SHA256\" and (.packages | type == \"object\") and (.commands | type == \"object\")' /$MANIFEST_GUEST_PATH" \
        >/dev/null || die "manifest failed its own JSON/schema check"
    mkdir -p "$ROOT/var/lib/minis"
    date -u +%Y-%m-%dT%H:%M:%SZ > "$ROOT/$BOOTSTRAP_DONE_GUEST_PATH"
    chmod 0644 "$ROOT/$BOOTSTRAP_DONE_GUEST_PATH"
    log "    wrote /$MANIFEST_GUEST_PATH and /$BOOTSTRAP_DONE_GUEST_PATH"
}

clean_rootfs() {
    root_run 'apt-get clean' || warn "apt-get clean failed"
    rm -rf "$ROOT/var/lib/apt/lists" "$ROOT/var/cache/apt/archives" "$ROOT/var/log/apt"
    mkdir -p "$ROOT/var/lib/apt/lists/partial" "$ROOT/var/cache/apt/archives/partial"
    rm -rf "$ROOT/tmp"/* 2>/dev/null || true
    rm -rf "$ROOT/root/.cache/pip" 2>/dev/null || true
    rm -f "$ROOT/var/lib/dpkg/status-old" "$ROOT"/var/lib/dpkg/*-old 2>/dev/null || true
    log "    apt lists/cache and build temp files removed"
}

# ── privileged phase (runs only inside the private mount namespace) ─────────

install_phase() {
    WORK="$1"
    ROOT="$WORK/rootfs"
    [ "${UBUNTU_TOOLSET_IN_NS:-0}" = "1" ] || die "internal phase invoked without the mount namespace"
    [ "$(id -u)" -eq 0 ] || die "internal phase must run as root inside the CI runner"
    case "$WORK" in
        "${TMPDIR:-/tmp}"/ubuntu-preinstall-agent.*) ;;
        *) die "refusing to operate on a tree this script did not create: $WORK" ;;
    esac
    [ -d "$ROOT" ] || die "temp rootfs missing: $ROOT"
    # This process is a fresh `bash` under `unshare`; shell state from the
    # parent is intentionally not inherited. Rebuild PKG_ARGS here.
    load_package_list

    trap 'cleanup_install' EXIT INT TERM
    prepare_mounts
    prepare_resolv_conf
    prepare_policy_rc_d
    install_packages
    verify_guest_state
    verify_commands
    write_manifest_and_marker
    clean_rootfs
    restore_policy_rc_d
    restore_resolv_conf
    log "==> guest toolset prepared"
}

cleanup_install() {
    restore_resolv_conf || true
    restore_policy_rc_d || true
    cleanup_mounts
}

# ── artifact packaging ──────────────────────────────────────────────────────

resolve_output() {
    case "$OUTPUT" in
        *.tar.gz) ;;
        *) OUTPUT="${OUTPUT%/}/ubuntu-base-arm64.tar.gz" ;;
    esac
    case "$OUTPUT" in
        *$'\n'*) die "output path contains a newline" ;;
    esac
}

package_output() {
    local out="$1" root="$2" dir base
    [ -f "$root/$MANIFEST_GUEST_PATH" ] || die "manifest missing before packaging"
    [ -f "$root/$BOOTSTRAP_DONE_GUEST_PATH" ] || die "bootstrap.done missing before packaging"
    dir="$(dirname "$out")"
    base="$(basename "$out")"
    mkdir -p "$dir"
    OUT_TMP="$out.partial.$$"
    rm -f "$OUT_TMP"

    log "==> creating $out"
    tar -C "$root" --numeric-owner --sort=name -czf "$OUT_TMP" . || die "tar failed"
    gzip -t "$OUT_TMP" || die "gzip integrity check failed on the produced archive"
    tar -tzf "$OUT_TMP" > "$WORK/archive.list" || die "tar listing failed (archive corrupt)"
    sed 's#^\./##' "$WORK/archive.list" > "$WORK/archive.list.norm"

    local required
    for required in \
        "$MANIFEST_GUEST_PATH" "$BOOTSTRAP_DONE_GUEST_PATH" \
        usr/lib/os-release usr/bin/apt usr/bin/dpkg usr/bin/bash \
        usr/bin/python3 usr/bin/git usr/bin/curl usr/bin/wget usr/bin/ssh \
        usr/bin/rg usr/bin/jq usr/bin/less usr/bin/ps \
        usr/bin/unzip usr/bin/xz usr/bin/pip3; do
        grep -qxF "$required" "$WORK/archive.list.norm" || \
            die "archive is missing required guest path: $required"
    done
    # iproute2 installs `ip` in /usr/sbin on Debian/Ubuntu and in /usr/bin
    # elsewhere; the command probe below runs it via PATH, so accept both.
    if ! grep -qxF usr/bin/ip "$WORK/archive.list.norm" && \
       ! grep -qxF usr/sbin/ip "$WORK/archive.list.norm"; then
        die "archive is missing required guest path: usr/bin/ip or usr/sbin/ip"
    fi
    if grep -Eq '^\./(proc|sys)/.' "$WORK/archive.list"; then
        die "archive contains live proc/sys content; mounts were not cleaned up"
    fi
    if grep -Eq '^\./dev/(null|zero|random|urandom|full|tty)$' "$WORK/archive.list"; then
        die "archive contains device nodes; /dev cleanup failed"
    fi

    mv -f "$OUT_TMP" "$out" || die "cannot move archive into place"
    chmod 0644 "$out"
    ( cd "$dir" && sha256sum "$base" > "$base.sha256" ) || die "cannot write checksum file"
    chmod 0644 "$dir/$base.sha256"
    ( cd "$dir" && sha256sum -c "$base.sha256" >/dev/null ) || die "checksum self-check failed"
    log "    $(du -h "$out" | cut -f1)  $out"
    log "    sha256 $(cut -d' ' -f1 < "$dir/$base.sha256")"
}

run_optional_validator() {
    local out="$1"
    # This precheck is mandatory: verify_ubuntu_apk.py implements
    # validate_rootfs_archive(..., require_toolset=True). A missing module,
    # import error, old signature or validation failure must stop the build.
    if [ "${UBUNTU_TOOLSET_VALIDATE:-1}" != "1" ]; then
        printf 'error: UBUNTU_TOOLSET_VALIDATE=0 is not allowed: the toolset precheck is mandatory\n' >&2
        return 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        printf 'error: python3 is required for the toolset validator precheck\n' >&2
        return 1
    fi
    python3 - "$out" "$SCRIPT_DIR" <<'PY'
import importlib.util
import os
import sys
import tempfile
import zipfile

out, script_dir = sys.argv[1], sys.argv[2]
module_path = os.path.join(script_dir, 'verify_ubuntu_apk.py')
if not os.path.isfile(module_path):
    print(f'validator: {module_path} is missing; toolset precheck cannot run', file=sys.stderr)
    raise SystemExit(1)
spec = importlib.util.spec_from_file_location('verify_ubuntu_apk', module_path)
module = importlib.util.module_from_spec(spec)
try:
    spec.loader.exec_module(module)
except Exception as exc:
    print(f'validator: verify_ubuntu_apk.py import failed: {type(exc).__name__}: {exc}',
          file=sys.stderr)
    raise SystemExit(1)
validate = getattr(module, 'validate_rootfs_archive', None)
if validate is None:
    print('validator: validate_rootfs_archive() is missing; toolset precheck cannot run',
          file=sys.stderr)
    raise SystemExit(1)
name = 'assets/ubuntu-base-arm64.tar.gz'
try:
    with tempfile.TemporaryDirectory(prefix='minis-toolset-validate-') as tmp:
        probe = os.path.join(tmp, 'probe.apk')
        with zipfile.ZipFile(probe, 'w', zipfile.ZIP_STORED) as z:
            z.write(out, name)
        with zipfile.ZipFile(probe) as z:
            validate(z, name, min_bytes=16 * 1024 * 1024, require_toolset=True)
except Exception as exc:
    print(f'validator: preinstalled toolset precheck FAILED: {type(exc).__name__}: {exc}',
          file=sys.stderr)
    raise SystemExit(1)
print('validator: preinstalled toolset archive OK (require_toolset=True)')
PY
}
# ── cleanup ─────────────────────────────────────────────────────────────────

cleanup_work() {
    local work="${WORK:-}"
    if [ -n "$work" ]; then
        case "$work" in
            "${TMPDIR:-/tmp}"/ubuntu-preinstall-agent.*) rm -rf -- "$work" ;;
            *) warn "refusing to remove unexpected work directory: $work" ;;
        esac
    fi
    if [ -n "${OUT_TMP:-}" ]; then
        rm -f -- "$OUT_TMP"
    fi
}

# ── main ────────────────────────────────────────────────────────────────────

main() {
    local check_host=0 namespace_work=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --help|-h) usage; exit 0 ;;
            --check-host) check_host=1; shift ;;
            --output)
                [ "$#" -ge 2 ] || die "--output requires a path"
                OUTPUT="$2"; shift 2 ;;
            --in-namespace)
                [ "$#" -ge 2 ] || die "--in-namespace requires a work directory"
                namespace_work="$2"; shift 2 ;;
            *)
                printf 'error: unknown argument: %s\n' "$1" >&2
                usage >&2
                exit 2 ;;
        esac
    done

    if [ -n "$namespace_work" ]; then
        install_phase "$namespace_work"
        return 0
    fi

    host_guard || die "host safety gate failed; refusing to run"
    if [ "$check_host" -eq 1 ]; then
        log "host gate: ok (GitHub Hosted Linux ARM64 / Ubuntu 24.04 aarch64)"
        return 0
    fi

    load_package_list
    require_commands
    resolve_output
    ensure_root

    WORK="$(mktemp -d "${TMPDIR:-/tmp}/ubuntu-preinstall-agent.XXXXXX")" || die "mktemp failed"
    trap cleanup_work EXIT INT TERM
    log "==> work tree: $WORK (removed on exit)"
    log "==> output:    $OUTPUT"

    fetch_verified_base "$WORK"
    extract_base "$BASE_TARBALL" "$WORK/rootfs"

    log "==> entering private mount namespace and installing the guest toolset"
    if ! unshare --mount --propagation private env UBUNTU_TOOLSET_IN_NS=1 \
            bash "$0" --in-namespace "$WORK"; then
        die "toolset installation failed; see the log above"
    fi

    package_output "$OUTPUT" "$WORK/rootfs"
    if ! run_optional_validator "$OUTPUT"; then
        rm -f -- "$OUTPUT" "$OUTPUT.sha256"
        die "toolset validator precheck failed; artifact removed"
    fi
    log "==> done"
    log "    tarball: $OUTPUT"
    log "    sha256:  $OUTPUT.sha256"
    log "    This tarball is a CI build artifact; do not commit it."
}

if [ "${UBUNTU_TOOLSET_LIB_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

main "$@"
