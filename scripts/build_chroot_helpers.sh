#!/usr/bin/env bash
# Cross-build only; never run the produced Android/guest executables on the host.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to NDK r28+}"
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
GUEST_CC="${GUEST_CC:-aarch64-linux-gnu-gcc}"
OUT="$ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/src/android/app/src/main/assets/chroot"
mkdir -p "$OUT" "$ASSETS"
"$CC" -O2 -Wall -Wextra -fPIE -pie -Wl,-z,max-page-size=16384 \
  "$ROOT/src/android/app/src/main/cpp/minis_chroot.c" -o "$OUT/libminis-chroot.so"
"$GUEST_CC" -O2 -Wall -Wextra -static \
  "$ROOT/src/android/app/src/main/cpp/minis_offload_client.c" -o "$ASSETS/minis-offload-client"
python3 "$ROOT/scripts/check_chroot_elf.py" "$OUT/libminis-chroot.so" "$ASSETS/minis-offload-client"
