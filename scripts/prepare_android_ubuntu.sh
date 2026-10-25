#!/usr/bin/env bash
#
# Prepare the Android sandbox guest asset: Ubuntu 24.04 LTS (noble) arm64.
#
# Output: $ASSETS_DIR/ubuntu-base-arm64.tar.gz  (default:
#         src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz)
#         untouched and unmodified — the official ubuntu-base tarball.
#
# Verification (fail-closed):
#   1. The tarball is fetched from cdimage.ubuntu.com over HTTPS.
#   2. The official SHA256SUMS is fetched and, when a keyring with the Ubuntu
#      CD Image key is available, its detached signature (SHA256SUMS.gpg) is
#      verified with gpgv before the checksum is trusted.
#   3. The tarball's SHA-256 must equal the value published in SHA256SUMS *and*
#      the digest pinned in this script (protects against a rotated or
#      compromised mirror).
#   4. `gzip -t` checks the stream is intact.
#
# NOTHING from the archive is extracted or executed on the build host: the
# guest binaries are ARM64 Ubuntu toolchain output and must never run here.
# Extraction happens on-device in RootfsManager (see docs/android-ubuntu.md).
#
# Usage:
#   scripts/prepare_android_ubuntu.sh                 # pinned 24.04.5
#   UBUNTU_BASE_VERSION=24.04.5 scripts/prepare_android_ubuntu.sh
#   ASSETS_DIR=/tmp/assets scripts/prepare_android_ubuntu.sh --force
#   REQUIRE_SIGNATURE=1 scripts/prepare_android_ubuntu.sh
#
# Environment:
#   ASSETS_DIR               target directory (default: app assets dir)
#   UBUNTU_BASE_VERSION      point release to fetch (default 24.04.5)
#   UBUNTU_BASE_SHA256       override the pinned digest (still checked against
#                            the official signed SHA256SUMS)
#   UBUNTU_CDIMAGE_KEYRING   keyring for gpgv (a .gpg file, not a GNUPGHOME)
#   REQUIRE_SIGNATURE=1      abort when the signature cannot be verified
#   UBUNTU_MIRROR_BASE       override the cdimage base URL (https only)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ASSETS_DIR="${ASSETS_DIR:-$PROJECT_ROOT/src/android/app/src/main/assets}"
# Persistent download cache. Matches the CI cache path in
# .github/workflows/android-ubuntu.yml (`deps/build/ubuntu-base`), so a warm
# cache skips the ~28 MB download entirely. Override with UBUNTU_CACHE_DIR.
UBUNTU_CACHE_DIR="${UBUNTU_CACHE_DIR:-$PROJECT_ROOT/deps/build/ubuntu-base}"

UBUNTU_SERIES="24.04"
UBUNTU_BASE_VERSION="${UBUNTU_BASE_VERSION:-24.04.5}"
CDIMAGE_BASE="${UBUNTU_MIRROR_BASE:-https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_SERIES/release}"
REQUIRE_SIGNATURE="${REQUIRE_SIGNATURE:-0}"

ASSET_NAME="ubuntu-base-arm64.tar.gz"
TARBALL="ubuntu-base-${UBUNTU_BASE_VERSION}-base-arm64.tar.gz"
ROOTFS_FILE="$ASSETS_DIR/$ASSET_NAME"

log()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

# The APK packages every file under assets/. scripts/verify_ubuntu_apk.py fails
# the build when the old Alpine payload is still bundled, so an existing asset is
# never left in place silently: the script stops with instructions, or moves it
# aside (preserved) when MOVE_LEGACY_ASSET=1.
check_legacy_asset() {
    LEGACY_ASSET="$ASSETS_DIR/alpine-minirootfs.tar.gz"
    [ -f "$LEGACY_ASSET" ] || return 0
    if [ "${MOVE_LEGACY_ASSET:-0}" = "1" ]; then
        legacy_dir="$PROJECT_ROOT/deps/build/legacy-alpine"
        mkdir -p "$legacy_dir"
        mv -f "$LEGACY_ASSET" "$legacy_dir/alpine-minirootfs.tar.gz"
        warn "moved the old Alpine asset to $legacy_dir/alpine-minirootfs.tar.gz (kept, not deleted)"
        return 0
    fi
    warn "$LEGACY_ASSET is still in the assets directory."
    warn "The APK would bundle it and scripts/verify_ubuntu_apk.py would reject the build."
    die "Remove it, or rerun with MOVE_LEGACY_ASSET=1 to move it to deps/build/legacy-alpine/."
}

usage() {
    cat <<'USAGE'
Usage: scripts/prepare_android_ubuntu.sh [--force]

Downloads the official Ubuntu 24.04 LTS arm64 base tarball into the Android
app assets as ubuntu-base-arm64.tar.gz, after verifying the Ubuntu-signed
SHA256SUMS and a pinned SHA-256 digest. Nothing is extracted or executed.

Options:
  --force    re-download even when a verified asset already exists
  --help     show this help

Environment:
  ASSETS_DIR              target directory (default: src/android/app/src/main/assets)
  UBUNTU_BASE_VERSION     point release (default 24.04.5)
  UBUNTU_BASE_SHA256      override the pinned digest (still cross-checked)
  UBUNTU_CDIMAGE_KEYRING  keyring.gpg for gpgv
  REQUIRE_SIGNATURE=1     fail when the signature cannot be verified
USAGE
}


FORCE=0
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"
command -v gzip >/dev/null 2>&1 || die "gzip is required"

# ── Pinned digests (from the official, signed SHA256SUMS) ────────────────────
# 24.04.5 arm64 — SHA256SUMS published 2026-09-10, signature verified against
# the Ubuntu CD Image Automatic Signing Key (2012):
#   8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092
# Add a line here when bumping UBUNTU_BASE_VERSION; an unpinned version fails
# closed unless UBUNTU_BASE_SHA256 or UBUNTU_ALLOW_UNPINNED=1 is given.
pinned_sha256() {
    case "$1" in
        24.04.5) echo "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2" ;;
        24.04.4) echo "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2" ;;
        24.04.3) echo "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048" ;;
        *) return 1 ;;
    esac
}

EXPECTED_SHA256="${UBUNTU_BASE_SHA256:-$(pinned_sha256 "$UBUNTU_BASE_VERSION" || true)}"
if [ -z "$EXPECTED_SHA256" ]; then
    if [ "${UBUNTU_ALLOW_UNPINNED:-0}" = "1" ]; then
        warn "no pinned digest for $UBUNTU_BASE_VERSION — relying on the signed SHA256SUMS only"
    else
        die "no pinned SHA-256 for ubuntu-base $UBUNTU_BASE_VERSION.
       Add it to scripts/prepare_android_ubuntu.sh (pinned_sha256) after verifying
       SHA256SUMS.gpg, or set UBUNTU_BASE_SHA256=<digest> explicitly."
    fi
fi

mkdir -p "$ASSETS_DIR"

mkdir -p "$UBUNTU_CACHE_DIR"

# ── Reuse an already-correct asset or cache entry ───────────────────────────
if [ "$FORCE" -eq 0 ]; then
    for candidate in "$ROOTFS_FILE" "$UBUNTU_CACHE_DIR/$TARBALL"; do
        if [ -f "$candidate" ]; then
            have="$(sha256sum "$candidate" | awk '{print $1}')"
            if [ -n "$EXPECTED_SHA256" ] && [ "$have" = "$EXPECTED_SHA256" ]; then
                log "✓ Verified $TARBALL already available: $candidate"
                if [ "$candidate" != "$ROOTFS_FILE" ]; then
                    cp -f "$candidate" "$ROOTFS_FILE.tmp" && mv -f "$ROOTFS_FILE.tmp" "$ROOTFS_FILE"
                    log "✓ Installed from cache: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
                else
                    ls -lh "$ROOTFS_FILE"
                fi
                check_legacy_asset
                exit 0
            fi
            warn "$candidate does not match the pinned digest — ignoring it"
            [ "$candidate" = "$UBUNTU_CACHE_DIR/$TARBALL" ] && rm -f "$candidate"
        fi
    done
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ubuntu-base.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

# ── Fetch SHA256SUMS + signature ─────────────────────────────────────────────
log "Fetching official SHA256SUMS for Ubuntu $UBUNTU_BASE_VERSION…"
curl -fSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 \
    -o "$WORK/SHA256SUMS" "$CDIMAGE_BASE/SHA256SUMS"
curl -fSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 \
    -o "$WORK/SHA256SUMS.gpg" "$CDIMAGE_BASE/SHA256SUMS.gpg"

# ── Verify the detached signature when possible ──────────────────────────────
signature_verified=0
KEYRING="${UBUNTU_CDIMAGE_KEYRING:-}"
if [ -z "$KEYRING" ]; then
    for candidate in \
        /usr/share/keyrings/ubuntu-archive-keyring.gpg \
        /etc/apt/trusted.gpg.d/ubuntu-keyring-2012-cdimage.gpg \
        /usr/share/keyrings/ubuntu-master-keyring.gpg
    do
        if [ -f "$candidate" ]; then KEYRING="$candidate"; break; fi
    done
fi

if command -v gpgv >/dev/null 2>&1 && [ -n "$KEYRING" ] && [ -f "$KEYRING" ]; then
    if gpgv --keyring "$KEYRING" "$WORK/SHA256SUMS.gpg" "$WORK/SHA256SUMS" >"$WORK/gpgv.log" 2>&1; then
        signature_verified=1
        log "✓ SHA256SUMS signature verified with $KEYRING"
    else
        cat "$WORK/gpgv.log" >&2
        die "SHA256SUMS signature verification FAILED — refusing to use this download."
    fi
else
    if [ "$REQUIRE_SIGNATURE" = "1" ]; then
        die "REQUIRE_SIGNATURE=1 but gpgv or an Ubuntu CD image keyring is unavailable.
       Install gnupg / ubuntu-keyring, or set UBUNTU_CDIMAGE_KEYRING=<keyring.gpg>.
       Expected signer: 843938DF228D22F7B3742BC0D94AA3F0EFE21092"
    fi
    warn "signature not verified (no gpgv/keyring); relying on the pinned SHA-256 + HTTPS"
fi

# ── Check the checksum against BOTH SHA256SUMS and the pin ───────────────────
expected_from_sums="$(awk -v f="$TARBALL" '$2 == "*" f || $2 == f {print $1}' "$WORK/SHA256SUMS" | head -n1)"
[ -n "$expected_from_sums" ] || die "$TARBALL is not listed in the official SHA256SUMS"
if [ -n "$EXPECTED_SHA256" ] && [ "$expected_from_sums" != "$EXPECTED_SHA256" ]; then
    die "official SHA256SUMS lists $expected_from_sums for $TARBALL,
       but this script pins $EXPECTED_SHA256 — the point release was re-spun.
       Verify manually, then update the pin (or set UBUNTU_BASE_SHA256)."
fi
if [ "$signature_verified" = "1" ]; then
    log "✓ SHA256SUMS is authentic (signed) and matches the pinned digest"
fi

# ── Download the tarball ─────────────────────────────────────────────────────
log "Downloading Ubuntu Base $UBUNTU_BASE_VERSION arm64…"
curl -fSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 \
    -o "$WORK/$TARBALL" "$CDIMAGE_BASE/$TARBALL"

actual="$(sha256sum "$WORK/$TARBALL" | awk '{print $1}')"
if [ "$actual" != "$expected_from_sums" ]; then
    die "SHA-256 mismatch for $TARBALL
       expected: $expected_from_sums
       actual:   $actual"
fi
log "✓ SHA-256 verified: $actual"

# Integrity only — reads the gzip stream, extracts and executes nothing.
gzip -t "$WORK/$TARBALL" || die "gzip integrity check failed for $TARBALL"
log "✓ gzip stream intact"

# ── Cache + install ─────────────────────────────────────────────────────────
cp -f "$WORK/$TARBALL" "$UBUNTU_CACHE_DIR/$TARBALL.tmp" \
    && mv -f "$UBUNTU_CACHE_DIR/$TARBALL.tmp" "$UBUNTU_CACHE_DIR/$TARBALL" \
    && log "✓ Cached: $UBUNTU_CACHE_DIR/$TARBALL" \
    || warn "could not write the download cache at $UBUNTU_CACHE_DIR (continuing)"

mv -f "$WORK/$TARBALL" "$ROOTFS_FILE"
log "✓ Installed: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"

check_legacy_asset

log ""
log "Rootfs asset ready. Next: ./scripts/prepare_android_sandbox.sh"
