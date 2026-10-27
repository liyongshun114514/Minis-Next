# Kairos 1.0.0

Kairos is an independently packaged Android fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis), maintained in [liyongshun114514/OpenMinis](https://github.com/liyongshun114514/OpenMinis/tree/ubuntu-chroot). Existing copyright notices, authorship and GPLv3 licensing are retained.

## Independent application

- Application ID: `com.kairos.app` (source namespace remains `com.openminis.app`; it is not the installation ID).
- Version: `1.0.0`, Android versionCode `1`.
- Android arm64-v8a, minSdk 26 / targetSdk 35 / compileSdk 36.
- Separate app storage from Minis/OpenMinis/Minis Next. No automatic access to or migration of their private app data.
- Launcher: new cyan/violet orbital star-ring, automatic/dark/light adaptive variants and an Android themed-icon silhouette. Source artwork is maintained under `branding/kairos/`.
- Static app shortcuts target `com.kairos.app` while the activity classes retain the source namespace.

## Prompt modes

The two settings are mutually exclusive runtime modes, not merely editor tabs:

- **Global**: every selected model uses the same saved global system-prompt body. Model/group rule text is not added.
- **Custom**: only the configured prompt for the exact provider-instance/model pair is used. A model with no enabled nonempty prompt receives no user-configured system-prompt body. The global body is never a fallback or an addition in this mode.

The application still sends its ordinary tool/runtime/environment instructions separately. API-system channel placement does not supersede model-provider safety policy or device permission gates. Existing child-agent prompt separation is unchanged. A mode is captured for a turn; fallback/model-switch resolution uses that same mode with the new exact model identity.

The custom editor is model-only: choose a model, edit text, save; rows provide enable/edit/delete. Legacy group records can remain stored without being applied. Mode/rules share the backed-up `SOUL_PROMPT_RULES.json` envelope, and the global text remains in `SOUL.md`.

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

Use the `Kairos Android APK` workflow (`.github/workflows/android-ubuntu.yml`) on `ubuntu-chroot` with release variant. It builds the Ubuntu guest toolset, runs the selected JVM/resource/protocol regressions, compiles Android/Compose and checks the final ZIP, package, launcher aliases, ABI, signature and alignment. The bundled guest is extracted with the production Kotlin installer; skipped/failed extraction tests reject delivery. `scripts/stage_kairos_release.py` independently checks the release certificate and stages versioned files with SHA-256 and source-commit metadata.

CI and static validation do **not** establish real-device appearance, IME/gesture behavior, vendor API behavior, or Root/chroot execution. Those require a separate explicit device test. Never label an unverified diagnostic artifact as an accepted release.
