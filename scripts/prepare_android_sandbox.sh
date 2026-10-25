#!/usr/bin/env bash
#
# Prepare the Android sandbox assets in src/android/app/src/main/assets/:
#   1. Ubuntu 24.04 LTS (noble) arm64 rootfs — ubuntu-base-arm64.tar.gz
#   2. Check that the PRoot binary is present (deps/build_proot.sh builds it)
#
# This entry point is kept because BUILDING.md / CI call it by name; the
# official base download/verification lives in prepare_android_ubuntu.sh.
# The final asset MUST be the preinstalled image from the native ARM64 CI job
# (build_ubuntu_toolset.sh), not the bare official base.
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

# --- Ready image from the native ARM64 preinstall job; no bare-base fallback ---
TOOLSET_SOURCE="${UBUNTU_TOOLSET_SOURCE:-$PROJECT_ROOT/deps/build/ubuntu-toolset/ubuntu-base-arm64.tar.gz}"
[ -s "$TOOLSET_SOURCE" ] || { echo "Missing preinstalled Ubuntu image: $TOOLSET_SOURCE" >&2; exit 1; }
[ -f "$TOOLSET_SOURCE.sha256" ] || { echo "Missing toolset checksum" >&2; exit 1; }
( cd "$(dirname "$TOOLSET_SOURCE")" && sha256sum -c "$(basename "$TOOLSET_SOURCE").sha256" )
cp "$TOOLSET_SOURCE" "$ASSETS_DIR/ubuntu-base-arm64.tar.gz"
python3 - "$PROJECT_ROOT" "$ASSETS_DIR/ubuntu-base-arm64.tar.gz" <<'PY'
import importlib.util, pathlib, sys, tempfile, zipfile
repo, asset = map(pathlib.Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location('verify_ubuntu_apk', repo / 'scripts/verify_ubuntu_apk.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
with tempfile.TemporaryDirectory(prefix='minis-preinstalled-check-') as directory:
    path = pathlib.Path(directory) / 'payload.zip'
    with zipfile.ZipFile(path, 'w') as z:
        z.write(asset, v.ROOTFS_ASSETS[0])
    with zipfile.ZipFile(path) as z:
        v.validate_rootfs_archive(z, v.ROOTFS_ASSETS[0], require_toolset=True)
print('PASS preinstalled Ubuntu toolset; no first-terminal installation needed')
PY

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
