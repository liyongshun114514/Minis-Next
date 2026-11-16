# Minis Next project guidance

Read `BUILDING.md` (Android → Tests) before choosing a build or test command.

## Android SDK and selected tests

- This branch uses AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, compileSdk 36 and explicitly pinned **Build Tools 35.0.0**. `.github/workflows/android-ubuntu.yml` installs 35.0.0. Keep the module pin and CI package aligned.
- AGP 8.7.3 defaults to 34.0.0 when the module has no pin. Having 35/36 installed, setting PATH/`ANDROID_BUILD_TOOLS`, or overriding aapt2 does not select the whole SDK package. If 34.0.0 is requested, check the actual checkout/module/config first. Do not repeatedly install 34.0.0, create a fake SDK alias, rewrite shared SDK metadata, or upgrade AGP merely to run a small test.
- On the Minis Ubuntu ARM64 host, source `/opt/toolchains/env.sh`; inspect `/opt/android-sdk` and `/var/cache/minis/gradle` before downloading dependencies. Local 35.0.0 wraps 36.0.0 x86_64 tools through qemu; CI has official 35.0.0. Verify actual tools rather than assuming directory presence proves readiness.
- `--tests` only filters execution. Android Gradle unit tests still configure the app and may require resources, generated code, Build Tools and `rclone.aar`. Use direct Python contracts or focused Kotlin/JUnit for independent logic; use Gradle/Actions when Android integration or packaging matters.
- Offline verification scripts are in `scripts/verification/`. The SOUL/group suite compiles all production sources once; do not repeatedly launch it for a single arithmetic test. Reuse compiled output only when the source snapshot matches; stale reuse is diagnostics-only.
- Preserve real test behavior. Ordinary JVM tests use AGP mockable Android classes, migration tests use SQLite JDBC. Robolectric NATIVE graphics is unsupported on Linux/aarch64: report ENV-GAP and run on x86_64 CI, never count it as a pass. Type checks do not prove APK packaging, installation or device behavior.
- Use isolated temporary output directories. Preserve source/evidence, then clean only this task's finished intermediate outputs. Do not remove shared SDK or Gradle caches.
