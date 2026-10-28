# Android guest: Ubuntu 24.04 LTS (arm64)

Status: **source-complete; device validation is tracked separately.** This
document was originally written and reviewed on a Linux host without an Android
SDK/NDK. Host checks against the real Ubuntu archive are marked “verified”;
anything that needs a device is marked “review only” or listed in §9. The 1.2
directory scheme has JVM coverage (rename, empty-directory and two-directory
conflict cases); this text does not claim an on-device run.

iOS is **unchanged**: it keeps its Alpine/iSH guest (`src/ios/iSH`,
`deps/prepare_alpine_rootfs.sh`). This document is Android-only.

This revision describes the **1.2 / build 47** directory contract. The matching
Actions APK is `MinisNext-1.2-build47-arm64-v8a.apk`; CI builds and verifies it
but does not install or launch it on a device.

---

## 1. What changed

| Area | Before | After |
|---|---|---|
| Guest distro | Alpine 3.21.3 (musl/busybox) | Ubuntu 24.04 LTS “noble” (glibc/apt) |
| Rootfs asset | `alpine-minirootfs.tar.gz` | **`ubuntu-base-arm64.tar.gz`** |
| Rootfs source | dl-cdn.alpinelinux.org, no checksum | cdimage.ubuntu.com, **signed SHA256SUMS + pinned SHA-256** |
| Package manager | `apk` | `apt` / deb822 `ubuntu.sources` |
| Init/system | n/a | `_apt` sandbox + seccomp disabled for PRoot; bootstrap list |
| Live rootfs directory | `filesDir/alpine-rootfs` | **`filesDir/Ubuntu-rootfs`** (1.2) |
| Staging directory | `filesDir/alpine-rootfs.staging` | **`filesDir/Ubuntu-rootfs.staging`** (1.2) |
| Backup directory | `filesDir/alpine-rootfs.pre-ubuntu-<stamp>` | **`filesDir/Ubuntu-rootfs.previous-<stamp>`** (1.2) |
| Existing data | deleted on reinstall | **renamed aside, never deleted** |

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

## 3. Installation flow and “never delete the previous guest silently”

`RootfsManager` constructs `RootfsDirectoryMigration.migrate(filesDir)` before
any state or reader can observe the rootfs path. Migration is a one-time,
byte-preserving rename:

1. **Serialize.** A lock file (`filesDir/.ubuntu-rootfs-migration.lock`) is
   opened with `NOFOLLOW_LINKS` and locked, so two app processes cannot migrate
   (or install) concurrently.
2. **Validate.** A symlink or non-directory at `alpine-rootfs` / `Ubuntu-rootfs`
   is an error; nothing is changed.
3. **Rename, never copy.** If only `alpine-rootfs` exists it is renamed to
   `Ubuntu-rootfs` with `Files.move` (no `REPLACE_EXISTING`, no copy fallback,
   no traversal). Bytes, mtimes, installed packages and settings are untouched;
   nothing is unpacked over the old tree.
4. **Compatibility alias.** PRoot `--link2symlink` `.l2s` records and older
   host-absolute references can still name the old path after a pure rename.
   The migration therefore creates exactly one relative symlink
   `alpine-rootfs → Ubuntu-rootfs`. It is an alias, **not** a data copy, and all
   current readers use `Ubuntu-rootfs`. A fresh install (no legacy directory)
   does not create it. Only this exact relative link is accepted as an alias; a
   real directory there is validated as legacy data.
5. **Journaled recovery.** Before the rename, a journal
   `filesDir/.ubuntu-rootfs-migration.pending` is written with the exact content
   `Ubuntu-rootfs\n` and fsynced. It is deleted after the alias exists. If the
   process dies between rename and alias, the next launch sees the journal and
   the existing target, creates the alias and clears the journal. A missing
   target with a pending journal, or an unrecognised journal, aborts with no
   changes. If creating the alias fails, the rename is rolled back.
6. **Empty directory only.** If both paths exist and one is empty, that empty
   directory is deleted and the rename proceeds. If both are non-empty, both
   are left intact and `migrate` throws. `RootfsManager.rootfsMigrationError`
   exposes the conflict in the UI, and `installIfNeeded()`, `reset()` and the
   rootfs writers refuse to run until the user resolves it.

`installIfNeeded()` then:

1. **Stage.** Extract into `filesDir/Ubuntu-rootfs.staging`, never into the
   live directory. A crash or a corrupt archive leaves the previous guest
   untouched (only the staging tree is removed).
2. **Marker check.** After extraction, require `<staging>/bin/sh` (dash),
   `<staging>/lib/ld-linux-aarch64.so.1` (glibc loader) and a complete
   preinstalled toolset (`usr/local/share/minis/toolset-manifest.json`,
   `var/lib/minis/bootstrap.done`, the package set in `/var/lib/dpkg/status`
   and the expected commands). A truncated archive fails here instead of being
   promoted to “installed”.
3. **Markers.** `.arch` = `aarch64` (unchanged contract) and `.distro` =
   `ubuntu-24.04-arm64`. `isInstalled` requires both, the toolset check, and no
   pending directory-migration error; `SandboxDistribution.isUbuntuRootfs`
   also recognises an Ubuntu tree that only has `.arch` by checking
   `/usr/bin/apt` + `/var/lib/dpkg/status` + `/etc/os-release`, so an
   interrupted future build is not needlessly re-extracted.
4. **Preserve.** The live `Ubuntu-rootfs` (Alpine, older Ubuntu, or partial) is
   renamed to **`Ubuntu-rootfs.previous-<yyyyMMdd-HHmmss>[-n]`**. Rename, not
   copy, then not delete. If the rename fails, the install aborts — there is no
   fallback to `deleteRecursively`.
5. **Swap.** `renameTo(rootfsDir)`; on failure the preserved tree is renamed
   back.
6. **Configure.** DNS and `mirror.freshInstall` are applied to the live tree.

Consequences / limits:

- A user upgrading from Alpine keeps every file under
  `Ubuntu-rootfs.previous-*`; older installs may also have
  `alpine-rootfs.pre-ubuntu-*` backups. `RootfsManager.legacyBackupDirs`
  reports all of these, plus a leftover `alpine-rootfs` directory and the
  historical `alpine-rootfs.staging` name, so none of them becomes invisible.
- The `alpine-rootfs` compatibility symlink is deliberately excluded from that
  backup listing: it is not a backup, is never counted as one and must not be
  deleted as one (removing it only breaks stale old-path references).
- `reset()` remains an explicit destructive action (the UI asks for
  confirmation); it deletes only `rootfsDir`, never the sibling backups.
- `isInstalled` is false for an Alpine tree, which is what makes
  `PRootKernel.boot()` migrate on the first launch after the update. That is
  intentional (the default guest is now Ubuntu) and logged as
  `[Migration] legacy non-Ubuntu rootfs moved to …`.

### Directory names and historical paths

`Ubuntu-rootfs` (capital U) is the only live destination;
`Ubuntu-rootfs.staging` is the only extraction target;
`Ubuntu-rootfs.previous-` is the only new backup prefix. These constants live in
`SandboxDistribution`. `alpine-rootfs` and `alpine-rootfs.pre-ubuntu-` remain as
**legacy identifiers** for the one-time rename, for backup discovery, and for
compatibility with data created before 1.2. They no longer identify the live
guest and are not the install path. The `.distro`/`.arch` markers, not the
directory name, identify the distribution.

On an upgraded install the old name still exists on disk as exactly one relative
symlink `alpine-rootfs → Ubuntu-rootfs`; a fresh install has no such entry. This
document therefore does **not** claim that the string `alpine` is absent from
the device. It can legitimately remain in: that compatibility alias, historical
`alpine-rootfs.pre-ubuntu-*` backups or an old `alpine-rootfs.staging`, guest
files or PRoot `.l2s` sentinels that recorded old host-absolute paths, the iOS
iSH guest and its `deps/prepare_alpine_rootfs.sh`, and comments/tests that
describe real Alpine history or legacy-compatibility fallbacks.

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
  without `/etc/profile`. Its guest-distribution comments were corrected for
  Ubuntu in 1.2; legacy busybox fallbacks remain for old trees.
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

The 1.2 directory tests are JVM tests wired into `android-ubuntu.yml`.
`RootfsDirectoryMigrationTest` builds a real hard link with
`Files.createLink`, migrates the tree and asserts `Files.isSameFile` on the
linked pair — it has no `assume`/skip branch, so an unsupported filesystem fails
rather than silently passing. It also asserts the old name is one relative
symlink (`isSymbolicLink` true, `isDirectory(NOFOLLOW_LINKS)` false), that the
journal recovers a rename without an alias, and that a misdirected or absolute
alias is rejected. `RootfsDirectoryMigrationIntegrationTest` covers the same
contract through `RootfsManager`.

---

## 9. Remaining work outside this documentation change

The 1.2 directory work is in place: `RootfsDirectoryMigration`,
`SandboxDistribution`, `RootfsManager`, `StorageUsageViewModel` (counts live +
preserved trees), the rootfs management screen/state (shows the migration error
and preserved path), and `ShellTransparentNet` (resolves `etc/resolv.conf`
through `RootfsManager.rootfsDir`). The agent prompt no longer claims Alpine.

Still open:

1. `ui/sandbox/RootfsManagementScreen.kt` shows only the latest preserved
   backup path. It has no list or delete affordance for every
   `Ubuntu-rootfs.previous-*` / historical `alpine-rootfs.pre-ubuntu-*` tree,
   although `RootfsManager.legacyBackupDirs` already returns the complete list.
2. `BUILDING.md:10,184` still says the Android build produces
   `assets/alpine-minirootfs.tar.gz` and calls the guest “Alpine”. The command
   block is obsolete; `BUILDING.md` was outside this change's scope.
3. Non-interactive agent shells still do not run `minis-bootstrap`; only
   `/etc/profile.d/99-minis-bootstrap.sh` triggers it for interactive shells.
   This matters only if a plain base image (rather than the preinstalled
   toolset image) is ever shipped again.
4. Cosmetic “Alpine” comments/log strings in files not owned by this change:
   `ShellTimeoutPolicy.kt`, `SeccompFallbackPolicy.kt`, `SpawnSignalMask.kt`,
   `NetworkShellEnvironment.kt:121`.

Intentionally **not** changed:

- `deps/prepare_alpine_rootfs.sh`, `deps/build_ish.sh` and all `src/ios/` files —
  iOS/iSH still runs Alpine; relabelling them as Ubuntu would be false.
- `deps/aot/**` and `src/ios/AOJITRestore/**` — Alpine 3.21 packages pinned to
  the AOT images; genuine historical/other-platform facts.
- Historical-measurement comments such as `ForegroundCommandGroup.kt`'s
  `/proc/<pid>/stat` numbers and `SessionStorage.kt`'s “Alpine rootfs shipped
  ~335 symlinks” — they describe measurements taken on the Alpine guest.
- Legacy-compatibility fallbacks in `PRootKernel` (`/bin/busybox` presence check)
  and `SandboxDistribution.LEGACY_ASSET` / `LEGACY_ROOTFS_DIR_NAME`, which exist
  only to recognise or migrate old data.

## 10. Rebuild / release checklist

```sh
./deps/build_proot.sh                     # unchanged, produces proot + loaders
./scripts/prepare_android_sandbox.sh      # verifies + installs ubuntu-base-arm64.tar.gz
cd src/android && ./gradlew assembleDebug  # not run here
```

On a device: fresh install (no `alpine-rootfs` entry is created), upgrade from
Alpine (confirm the old tree became `Ubuntu-rootfs` in place — same files/mtimes
— that `alpine-rootfs` is now a relative symlink to it, and that a
`Ubuntu-rootfs.previous-*` / historical `alpine-rootfs.pre-ubuntu-*` backup
exists with its files intact), an interrupted migration (force-stop between
rename and alias; the next launch must restore the alias without copying), a
both-directories-non-empty conflict (install and reset must refuse with a
visible error), `apt-get update` in the shell, `minis-bootstrap`, mirror switch
on/off, DNS change, reset with/without `/root` backup.
