# Minis Next 1.2 / code 47

This update fixes the Android guest directory left over from the Alpine era. The live rootfs is now `filesDir/Ubuntu-rootfs` (capital U), extraction uses `filesDir/Ubuntu-rootfs.staging`, and a replaced guest is preserved as `filesDir/Ubuntu-rootfs.previous-<yyyyMMdd-HHmmss>[-n]`. On first launch an existing `filesDir/alpine-rootfs` is **renamed** to the new path — not copied, not re-extracted, and never unpacked over — so bytes, mtimes, installed packages and settings survive. PRoot `--link2symlink` `.l2s` records and older host-absolute references may still contain the old path, so an upgraded install additionally keeps exactly one relative compatibility alias `alpine-rootfs → Ubuntu-rootfs` (a symlink, never a data copy; all current readers use `Ubuntu-rootfs`). Fresh installs do not create the old name. A pending journal in `filesDir` makes an interruption between the rename and the alias recoverable on the next launch. If both directories contain data, both are left untouched and the conflict is surfaced as a visible error that blocks install and reset. Historical `alpine-rootfs.pre-ubuntu-*` backups and an old `alpine-rootfs.staging` remain discoverable through `RootfsManager.legacyBackupDirs` (the rootfs screen shows the latest preserved path) and are never auto-deleted.

The 1.1 interactive features are unchanged: native math plot drag/hit-testing and continuous sliders, the composer's original 20dp corners with 16dp bottom safe-area clearance, a per-provider custom HTTPS balance URL, and live model-catalog replacement with a visible error on failed refreshes.

Current branch: `minis-next`, repository https://github.com/liyongshun114514/Minis-Next . Continues Kairos 1.0.2 functions without removing features; display name Minis Next, applicationId com.openminis.app.next, namespace com.openminis.app. Launcher icons remain the pinned upstream originals. `docs/KAIROS.md` is historical, not current installation/signing guidance.

## New stable signing identity

Public certificate SHA-256: `f1464cfe2b280348ee1fa611733dc55818bdcdcae659dd4fcbf27421a5a1065f`.

Release variants use explicit NEXT_KEYSTORE and NEXT_STORE_PASSWORD and alias minisnext; CI restores NEXT_KEYSTORE_BASE64 and NEXT_STORE_PASSWORD from encrypted repository secrets. Secrets/key material are never committed. This is NOT the old Next debug certificate or Kairos release certificate. **Cannot directly update either differently signed app.** Back up in the old app before any uninstall; do not claim uninstall is lossless. No automatic migration, uninstall, installation or device Root is performed by the build workflow.

## Intelligent UI

See [INTELLIGENT_UI.md](INTELLIGENT_UI.md) for protocol, native component catalog, upstream attribution, limitations and tests. Existing tools, terminal and web-preview functionality remain; generated Intelligent UI cards do not execute web code or replace approval prompts.

## Provider settings (introduced in 1.1, unchanged in 1.2)

In the provider's Quota section, **Balance URL** accepts a complete HTTPS endpoint; blank restores automatic detection. The configured endpoint receives the instance's API key using Bearer authentication. Redirects are not followed. Common balance response shapes (DeepSeek, Moonshot, SiliconFlow, New API, Sub2API, billing, LiteLLM, Novita and root/data balance fields) are supported; unreadable responses are errors, never invented zero balances. An explicit ISO currency is kept; unknown sites do not get a guessed currency. Room v4→v5 adds one nullable column without deleting existing rows, and older backups default to automatic detection.

Each model refresh bypasses discovery caches. A successful catalog replaces upstream-discovered ids immediately, including an empty catalog; errors keep the previous list with a visible reason. Existing ids keep user overrides/hidden state. Hand-added custom rows and voice templates that are not supplied by the catalog are retained. No public catalog or compiled-in image list tops up a successful live answer. Removed ids are pruned from group and agent-loop references.

## Android guest directory in 1.2

- Canonical live tree: `filesDir/Ubuntu-rootfs`; staging: `filesDir/Ubuntu-rootfs.staging`; preserved previous guest: `filesDir/Ubuntu-rootfs.previous-<stamp>`. Constants live in `sandbox/SandboxDistribution.kt`.
- `sandbox/RootfsDirectoryMigration.kt` performs the one-time rename under a files-directory lock before any reader observes the path. It never copies, merges or traverses the guest; an empty legacy directory is the only thing removed.
- For upgraded installs it then creates exactly one relative symlink `alpine-rootfs → Ubuntu-rootfs`. This is a compatibility alias for PRoot `--link2symlink` `.l2s` records and older host-absolute references — not a second data tree. All current readers use `Ubuntu-rootfs`; a fresh install never creates `alpine-rootfs`.
- A journal at `filesDir/.ubuntu-rootfs-migration.pending` (exact content `Ubuntu-rootfs\n`) records the window between rename and alias creation. The next launch completes the alias if the process died there; an unrecognised journal, a missing target, or two non-empty directories aborts with a visible error and no overwrite.
- On disk, the string `alpine` is not guaranteed to be gone: an upgraded install has the alias, and historical `alpine-rootfs.pre-ubuntu-*` backups or an old `alpine-rootfs.staging` may exist. These are legacy **identifiers**, not the current distro label; `RootfsManager.legacyBackupDirs` reports the real backup trees (the alias is excluded).
- When both `alpine-rootfs` and `Ubuntu-rootfs` are non-empty, nothing is moved or deleted. `RootfsManager.rootfsMigrationError` is shown and both `installIfNeeded()` and `reset()` refuse to proceed.
- The old documentation claim that `alpine-rootfs` was deliberately kept as the live path is obsolete: no current reader uses it as the install destination.
- iOS/iSH remains Alpine; `deps/prepare_alpine_rootfs.sh`, historical measurements and legacy-compatibility fallbacks are intentionally not relabelled as Ubuntu.

## Rebuild and verify

Run `.github/workflows/android-ubuntu.yml` on `minis-next` with variant release. It creates a verified preinstalled Ubuntu rootfs, compiles helpers, executes selected JVM/Compose tests, assembles Release and verifies package, launcher aliases, signature/alignment and the final APK's production rootfs extraction. Missing signing credentials reject the build. `scripts/stage_kairos_release.py` retains its historical filename but stages **MinisNext** artifacts with the new identity and exact certificate check into `release/minis-next`.

Actions artifact: `MinisNext-arm64-<run_number>`. APK protocol: `MinisNext-1.2-build47-arm64-v8a.apk`, plus .sha256, release-metadata.json and mapping. The stable signing identity above is unchanged from 1.1. No Release or tag is created in this iteration, and the workflow does not install the APK or run it on a device. Cloud build/test success is not a device installation or runtime UI validation claim.
