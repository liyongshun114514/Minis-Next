#!/usr/bin/env bash
#
# Prepare the Android sandbox assets in src/android/app/src/main/assets/:
#   1. Ubuntu 24.04 LTS (noble) arm64 rootfs — ubuntu-base-arm64.tar.gz
#   2. Check that the PRoot binary is present (deps/build_proot.sh builds it)
#
# This entry point is kept because BUILDING.md / CI call it by name; the
# download + verification work lives in scripts/prepare_android_ubuntu.sh,
# which verifies the Ubuntu-signed SHA256SUMS and a pinned digest and never
# extracts or executes the downloaded guest.
#
# The old Alpine minirootfs (alpine-minirootfs.tar.gz) is no longer fetched or
# packaged. An existing copy is left on disk, untouched, and reported — the
# app no longer reads it (RootfsManager extracts ubuntu-base-arm64.tar.gz).
#
# Usage: ./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
#        ASSETS_DIR=/tmp/assets ./scripts/prepare_android_sandbox.sh --force
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="${ASSETS_DIR:-$PROJECT_ROOT/src/android/app/src/main/assets}"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

mkdir -p "$ASSETS_DIR"

# --- Ubuntu rootfs (download + signature/checksum verification) ---
"$SCRIPT_DIR/prepare_android_ubuntu.sh" "$@"

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary present: $PROOT_FILE"
else
    echo "Error: $PROOT_FILE is missing." >&2
    echo "       It is built from source by ./deps/build_proot.sh — run that first," >&2
    echo "       then rerun this script." >&2
    exit 1
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
