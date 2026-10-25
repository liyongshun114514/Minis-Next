# Ubuntu fork validation — 2026-10-25

Source branch: `ubuntu-chroot` in `liyongshun114514/OpenMinis`.
Latest source commit at this report: `be9af7f`.

## Actually verified

- Root Java core: javac succeeds. Disabled, missing-authorizer and explicit-denial normal states return 126 without executing su.
- Offline JUnit: `UbuntuNormalCompatibilityTest` (3) and `NativeRootPolicyTest` (4): **7/7 passed**.
- Composer/Soul static contract checks: **81/81 passed**. Delegated pure UI/math tests: 9 slider + 6 length-policy tests passed; key new Compose files type-checked. Whole-module cloud build remains authoritative.
- Native Root integration: delegated independent Kotlin 2.1.0 + Compose type-check compiled 25 source/support files with **0 errors**; no Android execution.
- Chroot launcher and offload client: C syntax checks pass. Main agent independently cross-compiled Android API26 launcher and ELF-checked all LOAD segments for 16KiB alignment and exact `/system/bin/linker64`.
- Ordinary offload mock-server round trip: original tool path/arguments, output and true exit status verified. Host-built client only, no Root and no Android executable run.
- Ubuntu official 24.04.5 arm64 checksum independently matched `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`. Delegate verified signature/download/cache and normal official-archive extraction.
- Workflow YAML and shell syntax checks pass; `git diff --check` clean at push.
- Cloud run 40198511388 successfully built PRoot/rclone, both Chroot helpers, and verified Ubuntu Base. It failed in Kotlin on an unclosed documentation comment, fixed later.
- Cloud run 40199528742 reached whole Kotlin compilation; its only reported error was a missing `activitySequence` definition, fixed in b59ed16.

## Current cloud status at handoff

- Run 40200136218 (b59ed16): failed in existing unit-test compilation because old callers lacked the new permission-request identity argument; fixed in 7258059.
- Run 40201452769 (be9af7f): cancelled after the same source error was diagnosed, to avoid repeating a known failure.
- Run 40201745266 (7258059): **in progress**. This is the latest authoritative APK acceptance run.
- No final APK is claimed built or installable yet. Signing, ZIP/ABI/payload/alignment checks and artifact upload must succeed in the latest run.

## Not verified

No APK installed, no real device Root/su/Shizuku command executed, no device Chroot/PTY/mount/SELinux acceptance, no visual touch test, and no guest apt installation on a device. Chroot commands drop to app UID; apt/dpkg installs must use PRoot. Ubuntu's first interactive shell installs the default tools; noninteractive early calls display the initialisation/retry hint.

This delivery intentionally omits security-trigger input/reproduction fixtures. Verification is normal functionality, compilation and static review, not exploitability testing.

## Data, signing and cleanup

Independent package `com.openminis.app.ubuntu` preserves the upstream installation. Legacy guest data inside the fork is retained as a sibling backup and shown in its environment page. Test signing is not upstream/production signing.
Local generated Gradle outputs, temporary compiler fixtures, extracted input copies and local dependency AAR are disposable: cloud source-build scripts reproduce them. Original user ZIP, final source and documentation are retained. No global tools, system files, SELinux settings or actual Root permissions were changed.
