# Ubuntu fork validation — 2026-10-25

Source branch: `ubuntu-chroot` in `liyongshun114514/OpenMinis`.
Cloud builds verified below: source commits `7258059` and `9cd091a`; subsequent APK-verifier corrections are documented separately.

## 1.15-ubuntu.2 — preinstalled environment and UI revision

See [UBUNTU_PREINSTALLED.md](UBUNTU_PREINSTALLED.md). The user's real phone log
showed apt's terminal restoration (`TCSAFLUSH`/`tcsetattr EIO`) failing after the
tool packages were configured. The previous APK's successful build was not a
runtime acceptance. Version .2 removes first-terminal apt: CI preinstalls all
16 default packages in a disposable native-ARM64 guest and checks their state
and commands before packing the image. The app validates the complete extracted
image locally before marking Installed. Old incomplete guests are preserved as
backups during replacement; no device Root or installation was performed here.

Local main-agent checks for this revision: 26 builder-contract tests, 18 explicit
bootstrap-maintenance tests, 52 APK-payload/toolset tests, 21 launcher tests all
passed. Actual Kotlin compilation plus ordinary JVM tests for toolset status
and distribution compatibility: 9 passed. Workflow YAML, job dependency ordering
and 10 embedded shell blocks passed syntax checks. UI changes are source-level;
whole Android compilation and native ARM64 preinstallation await this revision's
CI run. No successful final .2 APK is claimed at this point.

## Earlier source/build checks (not proof of .2 runtime behavior)

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
- Run 40201745266 (7258059): **completed/failure**, but `Build APK` succeeded (`BUILD SUCCESSFUL in 12m 49s`), and downloaded JUnit XML confirms **7 tests, 0 failures/errors**. Failure was in the subsequent APK verifier: it required only `assets/ubuntu-base-arm64.tar.gz`, whereas AGP's asset merger decompresses `.gz` files and removes the suffix. The runtime already accepts `.tar` as well as `.tar.gz`.
- Independently executed the real AGP 8.7.3 / sdk-common 31.7.3 `AssetItem` + `MergedAssetWriter.AssetWorkAction` against a normal synthetic tar.gz: output was named `ubuntu-base-arm64.tar` and exactly matched the original uncompressed tar bytes. Direct aapt2 packaging retains `.gz`; the transformation is specifically AGP's merge stage.
- APK-verifier correction: accepts exactly one of the runtime-supported `.tar.gz` / `.tar` names, validates Ubuntu 24.04 archive content, and compares its decompressed SHA-256 with the prepared source. CI explicitly requires that source to exist. Main-agent independent ordinary compatibility tests: **20/20 passed**; signature/package/ABI/alignment checks are retained, not bypassed.
- Run 40203864705 (9cd091a): `Build APK` and the 20 payload-verifier compatibility tests succeeded. Downloaded JUnit XML again confirms **7 tests, 0 failures/errors**. The payload content/source-digest and package checks completed, then verification failed at the old `launchable-activity:` badging assertion.
- Launcher correction: the app's dynamic icon uses enabled/exported `activity-alias` pointing to `MainActivity`. Independently compiled an ordinary two-alias manifest with real aapt2; `dump badging` omitted `launchable-activity:` while `dump xmltree` retained the valid structure. The verifier now checks the compiled manifest for enabled/exported MAIN + LAUNCHER in the same intent-filter and a declared, enabled activity target. Main-agent independent tests: **21 launcher tests + 20 payload tests passed**, including real aapt2 packaging fixtures. No app/runtime/permission behavior was changed.
- CI now retains a clearly labelled **UNVERIFIED diagnostic APK** for three days if compilation succeeds but verification fails; this is not an accepted delivery. This avoids losing build bytes needed for further diagnosis. Normal verified artifact upload still requires full verification success.
- No final APK is claimed installable or delivered yet. The failed run uploaded only test XML; signature/alignment checks and verified APK artifact upload remain pending successful end-to-end acceptance.

## Not verified

No APK installed, no real device Root/su/Shizuku command executed, no device Chroot/PTY/mount/SELinux acceptance, no visual touch test, and no guest apt installation on a device. Chroot commands drop to app UID; apt/dpkg installs must use PRoot. Ubuntu's first interactive shell installs the default tools; noninteractive early calls display the initialisation/retry hint.

This delivery intentionally omits security-trigger input/reproduction fixtures. Verification is normal functionality, compilation and static review, not exploitability testing.

## Data, signing and cleanup

Independent package `com.openminis.app.ubuntu` preserves the upstream installation. Legacy guest data inside the fork is retained as a sibling backup and shown in its environment page. Test signing is not upstream/production signing.
Local generated Gradle outputs, temporary compiler fixtures, extracted input copies and local dependency AAR are disposable: cloud source-build scripts reproduce them. Original user ZIP, final source and documentation are retained. No global tools, system files, SELinux settings or actual Root permissions were changed.
