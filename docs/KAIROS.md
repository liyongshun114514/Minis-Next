# Kairos 1.0.2

Kairos is an independently packaged Android fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis), maintained in [liyongshun114514/OpenMinis](https://github.com/liyongshun114514/OpenMinis/tree/ubuntu-chroot). Existing copyright notices, authorship and GPLv3 licensing are retained.

## Independent application

- Application ID: `com.kairos.app` (source namespace remains `com.openminis.app`; it is not the installation ID).
- Version: `1.0.2`, Android versionCode `3`; same package and signing identity as 1.0.0 and 1.0.1.
- Android arm64-v8a, minSdk 26 / targetSdk 35 / compileSdk 36.
- Separate app storage from Minis/OpenMinis/Minis Next. No automatic access to or migration of their private app data.
- Launcher: original upstream OpenMinis icon, including automatic/light/dark/night and themed-icon variants. All 40 resources match pinned upstream bytes; provenance is in `branding/kairos/upstream-icons.json`. Application and launcher labels remain Kairos.
- Built-in general subagent description: “使用工具独立完成委派任务。” / “Handles delegated tasks independently using tools.” Names, IDs, instructions and execution behavior are unchanged.
- Static app shortcuts target `com.kairos.app` while the activity classes retain the source namespace.

## Global system prompt

Only the global editor and saved `SOUL.md` body are active. Per-model/per-group custom prompts and mode selection are removed. Legacy rule files remain preserved for backup compatibility but are not applied; a previously saved CUSTOM mode cannot suppress the global body.

The application imposes no body character/word limit and does not truncate saved text. Real model context windows, API payload limits and provider fees still apply. The global body is frozen for a turn and sent once through each provider's native system channel (OpenAI system/instructions, Anthropic system, Gemini systemInstruction where supported). Native channel placement is not a promise to override provider rules. Ordinary tool/runtime/environment instructions and device permission gates remain unchanged.

## Avatar and PRoot maintenance

Avatar images fill the circle with center-crop in settings, chat, export and overlays. Import decodes image bounds first and samples pixel data before resizing; local file-size/pixel-count rejection is removed without allocating full camera-resolution bitmaps. Unreadable formats and I/O errors are still reported.

On PRoot boot, the current process's supplementary IDs from `/proc/self/status` are named in the guest's `etc/group` only when missing. Existing rows and permissions are preserved. This fixes `groups: cannot find name for group ID ...` without changing the host groups, invoking Root or suppressing terminal errors. Chroot setup is unchanged.

## Distribution, updates and contact

- Releases: <https://github.com/liyongshun114514/OpenMinis/releases>
- Feedback: <https://github.com/liyongshun114514/OpenMinis/issues>
- Email: <liyongshun114514@gmail.com>
- Telegram: <https://t.me/LYS114514>
- Original project: <https://github.com/OpenMinis/OpenMinis>

Update assets use `Kairos-<version>-build<code>-arm64-v8a.apk`. The updater ignores differently branded APKs in the same repository; package identity and newer versionCode are checked before installation.

Release builds use an explicit private Kairos signing identity, not the Android Debug key or an evictable build cache. Missing signing secrets fail the release workflow. The public certificate SHA-256 is:

`1DEDA3DB350D32849D53212D29F210B64CF5F0401BA36BA7C3C3791994B3C628`

Signing credentials are stored outside the public repository and passed via encrypted GitHub Actions secrets. They must be backed up privately by the maintainer and never attached to a release. No app-installation or device-Root action is required to build or publish.

## Build and acceptance

For 1.0.2, the maintainer requested a public GitHub Release after successful cloud compilation and independent artifact verification. Publish tag `v1.0.2` at the accepted source commit with the signed APK, SHA-256 and public release metadata. Version 1.0.1 was CI-only; the earlier 1.0.0 Release remains deleted (its source/tag/local APK remain).

Use the `Kairos Android APK` workflow (`.github/workflows/android-ubuntu.yml`) on `ubuntu-chroot` with release variant. It builds the Ubuntu guest toolset, runs the selected JVM/resource/protocol regressions, compiles Android/Compose and checks the final ZIP, package, launcher aliases, ABI, signature and alignment. The bundled guest is extracted with the production Kotlin installer; skipped/failed extraction tests reject delivery. `scripts/stage_kairos_release.py` independently checks the release certificate and stages versioned files with SHA-256 and source-commit metadata.

CI and static validation do **not** establish real-device appearance, IME/gesture behavior, vendor API behavior, or Root/chroot execution. Those require a separate explicit device test. Never label an unverified diagnostic artifact as an accepted release.
