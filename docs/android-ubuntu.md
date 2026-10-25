# Android guest: Ubuntu 24.04 LTS (arm64)

Status: **source-complete, not device-verified.** This change was written and
reviewed on a Linux host without an Android SDK/NDK, and no APK was built or
run. Everything below marked “verified” was verified on the host against the
real Ubuntu archive; everything marked “review only” still needs a device run.

iOS is **unchanged**: it keeps its Alpine/iSH guest (`src/ios/iSH`,
`deps/prepare_alpine_rootfs.sh`). This document is Android-only.

---

## 1. What changed

| Area | Before | After |
|---|---|---|
| Guest distro | Alpine 3.21.3 (musl/busybox) | Ubuntu 24.04 LTS “noble” (glibc/apt) |
| Rootfs asset | `alpine-minirootfs.tar.gz` | **`ubuntu-base-arm64.tar.gz`** |
| Rootfs source | dl-cdn.alpinelinux.org, no checksum | cdimage.ubuntu.com, **signed SHA256SUMS + pinned SHA-256** |
| Package manager | `apk` | `apt` / deb822 `ubuntu.sources` |
| Init/system | n/a | `_apt` sandbox + seccomp disabled for PRoot; bootstrap list |
| Existing Alpine data | deleted on reinstall | **renamed aside, never deleted** |

Touched files (all within the delegated scope):

- `scripts/prepare_android_sandbox.sh` — now delegates rootfs download to the
  Ubuntu script and keeps the PRoot presence check.
- `scripts/prepare_android_ubuntu.sh` — **new**: download + verify + install asset.
- `src/.../sandbox/SandboxDistribution.kt` — **new**: distro constants, apt
  sources, guest defaults, archive-path sanitiser.
- `src/.../sandbox/TarExtractor.kt` — **new**: hardened, Android-free tar reader
  (extracted from `RootfsManager` so JVM tests exercise production code).
- `src/.../sandbox/RootfsManager.kt` — staging install, legacy preservation,
  Ubuntu configuration, symlink-safe resolv.conf, delegates tar parsing.
- `src/.../sandbox/ContainerDns.kt` — comments/limits corrected for glibc.
- `src/.../ui/sandbox/MirrorSettingsScreen.kt` — Ubuntu arm64 (`ubuntu-ports`)
  mirrors, deb822 writer, official restore.
- `src/.../ui/sandbox/RootfsManagementViewModel.kt` — distro label, preserved
  backup path, migration-aware result messages.
- `src/.../sandbox/UbuntuNormalCompatibilityTest.kt` — ordinary asset/source/bootstrap configuration checks; `RootfsManagerInstrumentedTest.kt` updated to the new marker/asset contract (not device-run).
- `scripts/tests/test_prepare_android_ubuntu.py`,
  `scripts/tests/test_ubuntu_rootfs_contract.py` — **new** (stdlib only).
- `docs/android-ubuntu.md` — this file.

`RootfsManagementScreen.kt`, `PRootKernel.kt`, chat UI, Gradle config and
GitHub Actions were **not** modified (see §9 for what they still need).

---

## 2. The asset: download and verification

`scripts/prepare_android_ubuntu.sh` writes
`src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz`.

- Default point release: **24.04.5** (arm64).
  URL: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz`
- Checksums are cross-checked three ways:
  1. `SHA256SUMS` fetched over HTTPS from cdimage;
  2. its detached signature `SHA256SUMS.gpg` verified with `gpgv` when a keyring
     with the CD Image key is available (default: `ubuntu-archive-keyring.gpg`
     or `ubuntu-keyring-2012-cdimage.gpg`; override with
     `UBUNTU_CDIMAGE_KEYRING`). Expected signer:
     `8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092`
     (“Ubuntu CD Image Automatic Signing Key (2012) <cdimage@ubuntu.com>”);
  3. the tarball's SHA-256 must match the line in `SHA256SUMS` **and** the
     digest pinned in the script.
- `REQUIRE_SIGNATURE=1` makes signature verification mandatory (the default
  warns and relies on HTTPS + the pinned digest, because CI images often lack
  `ubuntu-keyring`).
- An unknown/rotated point release **fails closed**: add its digest to
  `pinned_sha256()` or pass `UBUNTU_BASE_SHA256`. A re-spun release whose digest
  differs from the pin is rejected.
- `gzip -t` checks stream integrity. **Nothing is extracted or executed on the
  build host** — no `tar -x`, no `chroot`, no invocation of the guest.
- **Download cache:** `deps/build/ubuntu-base/<tarball>` (override
  `UBUNTU_CACHE_DIR`) — the path the `android-ubuntu.yml` workflow caches. A
  cache hit with the pinned digest installs the asset without any network call;
  a mismatch is discarded and re-downloaded. The asset itself is installed into
  `assets/` with an atomic `mv`. `--force` re-downloads.
- An old `alpine-minirootfs.tar.gz` in `assets/` is **not** silently left there:
  the APK bundles every asset, and `scripts/verify_ubuntu_apk.py` rejects an APK
  that still contains it. The script stops with instructions, or moves it to
  `deps/build/legacy-alpine/` when `MOVE_LEGACY_ASSET=1` (kept, not deleted).

Verified on the host (2026-10-25):

```
$ gpg --verify SHA256SUMS.gpg SHA256SUMS
gpg: Good signature from "Ubuntu CD Image Automatic Signing Key (2012) <cdimage@ubuntu.com>"
$ sha256sum ubuntu-base-24.04.5-base-arm64.tar.gz
a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
$ ASSETS_DIR=/tmp/ubuntu-assets-test REQUIRE_SIGNATURE=1 \
    UBUNTU_CDIMAGE_KEYRING=<exported key> scripts/prepare_android_ubuntu.sh
✓ SHA256SUMS signature verified
✓ SHA-256 verified: a91d5a9301…14f2
✓ gzip stream intact
✓ Installed: …/ubuntu-base-arm64.tar.gz (28.6M)
```

Pinned arm64 digests (from the signed SHA256SUMS):

| Point release | SHA-256 |
|---|---|
| 24.04.5 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` |
| 24.04.4 | `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2` |
| 24.04.3 | `7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048` |

AGP 8.7.3's asset merger inflates `.gz` assets and removes the suffix before
APK packaging: `ubuntu-base-arm64.tar.gz` normally becomes
`assets/ubuntu-base-arm64.tar`. `androidResources.noCompress` controls ZIP
compression, not this merge-stage conversion. `RootfsManager` accepts either
name and decompresses only the `.gz` form. `verify_ubuntu_apk.py` checks the
actual archive and compares its decompressed SHA-256 with the checksum-verified
source asset; accepting `.tar` does not skip the payload check.

---

## 3. Installation flow and “never delete Alpine silently”

`RootfsManager.installIfNeeded()`:

1. **Stage.** Extract into `filesDir/alpine-rootfs.staging`, never into the
   live directory. A crash or a corrupt archive leaves the previous guest
   untouched (only the staging tree is removed).
2. **Marker check.** After extraction, require `<staging>/bin/sh` (dash) and
   `<staging>/lib/ld-linux-aarch64.so.1` (glibc loader). A truncated archive
   fails here instead of being promoted to “installed”.
3. **Markers.** `.arch` = `aarch64` (unchanged contract) and `.distro` =
   `ubuntu-24.04-arm64` (new). `isInstalled` requires both;
   `SandboxDistribution.isUbuntuRootfs` also recognises an Ubuntu tree that only
   has `.arch` by checking `/usr/bin/apt` + `/var/lib/dpkg/status` +
   `/etc/os-release`, so an interrupted future build is not needlessly
   re-extracted.
4. **Preserve.** The live `alpine-rootfs` (Alpine, older Ubuntu, or partial) is
   renamed to **`alpine-rootfs.pre-ubuntu-<yyyyMMdd-HHmmss>[-n]`**. Rename, not
   copy, then not delete. If the rename fails, the install aborts — there is no
   fallback to `deleteRecursively`.
5. **Swap.** `renameTo(rootfsDir)`; on failure the preserved tree is renamed
   back.
6. **Configure.** DNS and `mirror.freshInstall` are applied to the live tree.

Consequences / limits:

- A user upgrading from Alpine keeps every file under
  `filesDir/alpine-rootfs.pre-ubuntu-*`. The parent agent still needs a UI
  affordance to show/free those backups (see §9).
- `reset()` remains an explicit destructive action (the UI asks for
  confirmation); it deletes only `rootfsDir`, never the sibling backups.
- `isInstalled` is false for an Alpine tree, which is what makes
  `PRootKernel.boot()` migrate on the first launch after the update. That is
  intentional (the default guest is now Ubuntu) and logged as
  `[Migration] legacy Alpine rootfs moved to …`.

### Why the directory is still called `alpine-rootfs`

Five files outside the delegated scope hardcode `filesDir/alpine-rootfs`
(`ShellTransparentNet`, `StorageUsageViewModel`, `FileBrowserViewModel`,
`AppNavigation`, `RootfsManagementScreen`) plus a unit test. Renaming the
directory would silently break resolv.conf writes and storage accounting.
`SandboxDistribution.ROOTFS_DIR_NAME` documents this and is the single place to
change once those files are updated. The marker, not the path, identifies the
distribution.

---

## 4. Extraction safety

`TarExtractor` (pure Kotlin, no Android imports) replaces the previous ~120-line
parser, which:

- built `File(targetDir, name)` from the raw header with **no validation**
  (`../../…` and `/data/…` escaped the rootfs);
- followed symlinked parents (tar-slip);
- wrote through an existing symlink at the target path;
- ignored GNU `L`/`K` and PAX `x`/`g` headers (long names were silently lost);
- did not verify header checksums, so a desynchronised stream was interpreted as
  entries;
- copied hard links with `File.copyTo`, which follows symlinks
  (`usr/bin/perl5.38.2` would have become a 4 MB regular copy of perl).

Now:

| Threat | Mitigation |
|---|---|
| `../`, absolute names | `SandboxDistribution.sanitizeArchivePath` → `IOException`, install fails |
| tar-slip through symlinked dir | every parent component checked; symlink parent aborts |
| write through a file symlink | link deleted, regular file written |
| truncated / corrupted archive | per-header checksum + exact-size reads |
| long names | GNU `L`/`K`, PAX `x`/`g` (`path`, `linkpath`) |
| hard links | copied (with the target's mode), or symlink-cloned when the target is a symlink — `link(2)` avoided (PRoot link2symlink / FUSE storage) |
| device/FIFO/socket members | skipped (PRoot supplies `/dev`) |

Symlink **targets** are intentionally not sanitised: the real archive has 20
absolute links (`etc/rmt -> /usr/sbin/rmt`) and relative links with `..`
(`usr/bin/ld.so -> ../lib/ld-linux-aarch64.so.1`); they are valid inside the
guest and never followed by the extractor.

Facts about the real archive (24.04.5 arm64, verified on the host):
3 413 members — 2 562 files, 655 directories, 194 symlinks, 2 hard links, **no
PAX/GNU long-name headers, no names > 100 bytes, no device nodes**. Hard links
are `usr/bin/perl5.38.2 -> usr/bin/perl` and `usr/bin/uncompress -> usr/bin/gunzip`,
both pointing at symlinks whose target appears earlier. `bin`, `sbin`, `lib` are
usrmerge symlinks and **no** member lives below them, so the symlink-parent rule
never fires on a legitimate archive (asserted by
`scripts/tests/test_ubuntu_rootfs_contract.py`).

---

## 5. glibc / PRoot specifics

- Loader: `/lib/ld-linux-aarch64.so.1` → `/usr/lib/ld-linux-aarch64.so.1` →
  `aarch64-linux-gnu/ld-linux-aarch64.so.1`; `lib` is a symlink to `usr/lib`
  (usrmerge). PRoot executes guest ELF files through its own loader
  (`libproot-loader.so` from `nativeLibraryDir`), which is unchanged and
  distribution-agnostic.
- `/bin/sh` → `dash`, `/usr/bin/bash` present in the base image; `setsid`,
  `ps`, `tar`, `gzip`, `perl`, `locale`, `mount`, `su`, `unshare` are present.
  **Absent** (verified): `python3`, `curl`, `wget`, `git`, `ssh`, `less`, `ip`,
  `vim`, `jq`, `rg`, `unzip`, `ca-certificates` → see the bootstrap list.
- `PRootKernel` already seeds `PATH`, `HOME`, `BROWSER`, `GIT_CONFIG_*`,
  `UV_LINK_MODE` and the POSIX `TZ` for every process, so non-login shells work
  without `/etc/profile`. Its comments still say “Alpine”; cosmetic (§9).
- Ubuntu base ships `/etc/hosts` as a **0-byte file** and `/etc/hostname` with a
  build-host name; both are replaced at install.
- `/etc/os-release` is a symlink to `usr/lib/os-release` (present in the
  archive).

---

## 6. DNS

- `etc/resolv.conf` is written as a **regular file**. The 24.04.5 archive ships
  an empty regular file, but other base images symlink it to
  `../run/systemd/resolve/stub-resolv.conf`; `writeResolvConfTo` deletes any
  symlink first because neither `systemd-resolved` nor `resolvconf` runs under
  PRoot, and `/run` is recreated empty on every boot.
- Behaviour is otherwise unchanged: `ContainerDns` custom servers are written
  verbatim on install/boot/network change; otherwise the active network's
  nameservers, with 8.8.8.8/8.8.4.4 as fallback.
- glibc, like musl, honours at most 3 nameservers (MAXNS); `RESOLVER_MAX_SERVERS`
  stays 3 and the doc comment now says so.
- `ShellTransparentNet` writes the same path directly from host code; it relies
  on the file being a regular file, which install now guarantees. Its own writer
  is not symlink-hardened — see §9.

---

## 7. apt: schema, PRoot switches, bootstrap

**Sources (deb822).** Ubuntu 24.04 moved to
`/etc/apt/sources.list.d/ubuntu.sources`; `/etc/apt/sources.list` contains only
a comment. `SandboxDistribution.aptSources()` emits the same two stanzas the
official image ships:

```
Types: deb
URIs: https://ports.ubuntu.com/ubuntu-ports/
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://ports.ubuntu.com/ubuntu-ports/
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
```

`ports.ubuntu.com` is required for arm64; `archive.ubuntu.com` has no arm64
packages and `security.ubuntu.com` has no arm64 security suite.

**PRoot switches** (`/etc/apt/apt.conf.d/99minis-proot.conf`):

```
APT::Sandbox::User "root";       # fake root cannot setuid to _apt
APT::Sandbox::Seccomp "false";   # apt's seccomp vs PRoot's ptrace rewriting
Acquire::Languages "none";
DPkg::Options { "--force-confdef"; "--force-confold"; };
```

**Bootstrap (default tools).** The base image is minimal. `etc/minis/bootstrap-packages`
lists `bash ca-certificates curl wget git openssh-client less procps iproute2
unzip xz-utils jq ripgrep python3 python3-pip python3-venv`, and
`/usr/local/sbin/minis-bootstrap` runs `apt-get update` +
`apt-get install -y --no-install-recommends $(cat …)` once. It is **not** run
during extraction (extraction must stay offline and deterministic, and an apt
failure must not fail the rootfs install). Instead it runs on the **first
interactive shell** (`etc/profile.d/99-minis-bootstrap.sh`), which prints
`minis: installing default tools (python3, git, curl, …)` before starting:

- progress and full apt output → `/var/log/minis-bootstrap.log`
- a failed run writes `/var/lib/minis/bootstrap.failed`, exits non-zero, and
  the next shell prints the log path and `retry: minis-bootstrap --force`
- `mkdir /var/lib/minis/bootstrap.lock` makes concurrent shells serialise
- success writes `/var/lib/minis/bootstrap.done`; later shells are silent
- `RootfsManager.isBootstrapComplete` / `isBootstrapFailed` /
  `bootstrapCommand()` expose the same state to host code

**Known gap (external, §9):** app-driven (non-interactive) agent shells do not
read `/etc/profile.d`, so until `ShellExecutor`/`OnDemandBash`/`AgentTools` run
`minis-bootstrap` when `isBootstrapComplete == false`, the toolset appears after
the user opens a terminal, not before the first agent command. The install result
message says so explicitly rather than pretending the tools are already there.

**Mirrors.** `MirrorSettingsScreen`'s category is now `UBUNTU` (“Ubuntu APT”)
and writes the deb822 file; `Use Mirror = off` restores the official ports file
even when no `.bak` exists. Catalog (all checked for
`dists/noble/Release` **and** `dists/noble-security/Release`, HTTP 200):
official `ports.ubuntu.com`, TUNA, Alibaba, USTC, Huawei, Tencent, BFSU, NJU,
LeaseWeb, UDX Japan. RWTH/JAIST/Kakao were dropped — they mirror `ubuntu` only
and 404 on `ubuntu-ports`.

---

## 8. Tests actually run (host, 2026-10-25)

```
$ bash -n scripts/prepare_android_ubuntu.sh            # ok
$ bash -n scripts/prepare_android_sandbox.sh           # ok
$ python3 scripts/tests/test_prepare_android_ubuntu.py -v
Ran 10 tests … OK
$ UBUNTU_ROOTFS=/tmp/ubuntu-base-arm64.tar.gz \
    python3 scripts/tests/test_ubuntu_rootfs_contract.py
Ran 14 tests … OK
  archive: 2562 files, 655 dirs, 194 symlinks, 2 hardlinks, 3413 members
```

Covered: script syntax, CLI contract (`--help`, bad arg, unpinned release fails
before network), no `tar -x`/`chroot`/execution of the download, pinned digest
format and value, delegation from `prepare_android_sandbox.sh`; archive member
types, path safety for all 3 413 names, no symlinked-parent entries, directory
ordering, hard-link ordering, required guest paths, usrmerge layout,
resolv.conf type, deb822/arm64 sources, bootstrap-absent packages, size bounds;
and the sanitiser vector parity table.

End-to-end download test (real network, throwaway assets dir) passed with
`REQUIRE_SIGNATURE=1`; the cache path was exercised (second run installed from
`deps/build/ubuntu-base` with no network); negative paths (wrong pin, unknown
point release, missing keyring with `REQUIRE_SIGNATURE=1`) all failed closed as
designed.

Additional host verification of the **production Kotlin** (compiled with the
Kotlin 2.0.21 compiler bundled in Gradle 8.13; only Android-free files can be
compiled outside the Android SDK):

```
$ kotlinc-like K2JVMCompiler SandboxDistribution.kt TarExtractor.kt   # compiled, 0 errors
$ java VerifyMain print        # generated ubuntu.sources / bootstrap / profile.d / hosts
$ sh -n bootstrap bootstrap-profile.sh profile.sh                     # all OK
$ java VerifyMain extract /tmp/ubuntu-base-arm64.tar.gz /tmp/rootfs-verify2
EXTRACT_OK   (10.2 s, 100 MB)
$ # result: 2564 regular files, 194 symlinks, 0 sentinel files,
$ #         usr/bin/bash 0755, etc/passwd 0644, etc/shadow 0640, /root 0700,
$ #         /bin -> usr/bin, /lib/ld-linux-aarch64.so.1 present,
$ #         perl5.38.2/uncompress materialised as 0755 copies
```

**Not run:** Gradle/`assembleDebug`, `testDebugUnitTest`, connected device or
emulator, `apt-get update` inside the guest. `RootfsManager`,
`MirrorSettingsScreen` and the ViewModel could not be compiled here (they need
the Android SDK), and the new JVM tests were not executed because JUnit is not
available in this environment. No APK was produced and no on-device claim is
made. The final integration retains ordinary configuration tests and official-archive metadata inspection only. Security-trigger fixtures were removed and are not part of CI. The integrator separately compiled and ran `UbuntuNormalCompatibilityTest` and `NativeRootPolicyTest`: 7 ordinary tests passed; these do not establish device operation.

---

## 9. Required changes outside the delegated scope (not done here)

Ordered by impact. Files marked ★ are behaviour-breaking for Ubuntu.

1. ★ `sandbox/PRootKernel.kt` — `installShellWrappers()` writes
   `/usr/local/bin/top` that ends in
   `/bin/busybox ps -o pid,user,stat,rss,ppid,comm`. Ubuntu has no
   `/bin/busybox`, so the wrapper shadows a real `top` with a broken script.
2. ★ `sandbox/PRootKernel.kt` — `installMountWriteGuards()` wrappers for
   `touch tee cp mv mkdir rm rmdir ln dd` end in `exec /bin/busybox $name`.
   With any read-only mounted folder configured, every one of those commands
   breaks in the Ubuntu guest. Gate on `File(rootfs, "bin/busybox").exists()`
   and fall back to `exec /usr/bin/$name`, or port the wrappers to coreutils.
3. ★ Hook `RootfsManager.isBootstrapComplete` / `bootstrapCommand()` into
   `ShellExecutor` (or `OnDemandBash` / `AgentTools`) so the first
   non-interactive agent command installs the toolset. Today it runs on the
   first *interactive* shell only (explicit and logged, but later than users
   expect). Without it `python3` is missing and the bundled `minis-mcp-cli`
   cannot run; neither can pip/curl/git.
4. ★ `ui/chat/ChatViewModel.kt` (≈16372–16419) — the agent system prompt still
   instructs `apk add`, `apk search py3-*`, “musllinux_aarch64 wheels”, “Alpine's
   native packages”. Must become apt/Ubuntu guidance
   (`apt-get update && apt-get install -y`, `python3-*` packages, manylinux
   wheels). `agent/SoulStore.kt:541` and `agent/jobs/HelperRunner.kt:320` also
   tell the model it is on “Alpine Linux”.
5. ★ `ui/sandbox/RootfsManagementScreen.kt:306` — hardcoded “The rootfs contains
   the **Alpine Linux** filesystem …” (file was out of scope). Also this screen
   has no way to show or delete `alpine-rootfs.pre-ubuntu-*` backups; storage UI
   (`ui/settings/StorageUsageViewModel.kt:202,223`) only counts
   `filesDir/alpine-rootfs`, so preserved backups are invisible.
6. `agent/shell/OnDemandBash.kt` — already switched to `apt-get` by the main
   agent; the constant name `APK_PROBE_URL` and its comment are now stale.
7. `.gitignore:129` — still ignores only `alpine-minirootfs.tar.gz`. Add
   `src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz` (keep the old
   entry) or a 28 MB binary can be committed by accident.
8. `res/values/strings.xml` + 20 locales — `settings_storage_subtitle` and
   `rootfs_about_description` say “Alpine Linux”.
9. `BUILDING.md` (≈179, 203, 270) and `README.md` (≈142) describe
   `prepare_android_sandbox.sh` as downloading Alpine; the command still works,
   the text is stale.
10. `assets/default_mount/etc/profile.d/minis.sh` — ash-specific (`ENV=.ashrc`,
    `.ash_history`); it sorts after `50-minis-ubuntu.sh`, so it overrides
    `HISTFILE` with `.ash_history`. Harmless but should be split per shell.
11. `network/ShellTransparentNet.kt:80` — writes `alpine-rootfs/etc/resolv.conf`
    with a plain `File.writeText`; add the same symlink/unlink guard as
    `RootfsManager.writeResolvConfTo` for defense in depth. Path itself remains
    valid (see §3).
12. `.github/workflows/android-ubuntu.yml:85` — the cache key is
    `ubuntu-base-24.04.3-arm64-v1` while the pinned script default is 24.04.5;
    bump the key to `ubuntu-base-24.04.5-arm64-v1` or the cache never hits.
13. `THIRD_PARTY_LICENSES.md` — add the Ubuntu base image notice (mixed
    GPL/LGPL/BSD contents) before shipping.
14. Cosmetic “Alpine” comments/log strings: `ForegroundCommandGroup.kt:64`,
    `ShellTimeoutPolicy.kt`, `SeccompFallbackPolicy.kt`, `SpawnSignalMask.kt`,
    `NetworkShellEnvironment.kt:121`, `SessionStorage.kt:226`,
    `FileBrowserViewModel.kt:213`, `AppNavigation.kt:1240`,
    `PRootKernel.kt:154,213,586,701,883`, `SandboxDistribution` consumers.
15. Optional: rename `filesDir/alpine-rootfs` → `filesDir/linux-rootfs`
    (with a one-time migration) once items 5/11 and `FileBrowserViewModel` /
    `AppNavigation` are updated.

---

## 10. Rebuild / release checklist

```sh
./deps/build_proot.sh                     # unchanged, produces proot + loaders
./scripts/prepare_android_sandbox.sh      # verifies + installs ubuntu-base-arm64.tar.gz
cd src/android && ./gradlew assembleDebug  # not run here
```

On a device: fresh install, upgrade-from-Alpine (check the
`alpine-rootfs.pre-ubuntu-*` backup exists and the old files are intact),
`apt-get update` in the shell, `minis-bootstrap`, mirror switch on/off, DNS
change, reset with/without `/root` backup.
