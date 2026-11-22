# Minis Next 1.7.5 (63): original tool colors and skills preview

Base: `2772e93d0bbb18fc7cbea46be97ed79a618f8c68` (`minis-next`).

## Visual changes

- Transcript tool-call pills use the original Minis neutral capsule colors: light `#F2F2F7`, dark `#28282C`, not the user-bubble/Miuix accent surface. Tool glyph accents (shell green, file-read cyan, file-write blue, edit orange) are unchanged.
- Tool thumbnails use the original Minis dark preview surface `#1F1F24` for every tool, including file reading/writing. File titles use white at 45% opacity; output retains its original tool accent at 85%.
- Both floating work rows use the composer's `glassPanel`, `puredThinGlass`, theme-specific small edge strokes, regular shadow, no shading and neutral input fallback. Removed the separate 20-radius blur/72%-opaque tint and duplicate borders/shadows. Existing dimensions, selection, paging, stopping and sub-agent status behavior are retained.
- System notification and background overlay lifecycle are not changed; these are separate from the transcript tool-call capsule shown with a stop button.

## Skills link preview

See `SkillLinkPreviewTest`: file resolution and unresolved sandbox links are tested independently of a running shell. Missing local resources must never be loaded as web pages.

## Build and signing

- Application ID remains `com.openminis.app.next`.
- Release signing continues using the existing private Actions secrets. No signing identity is replaced and no credential is added to source.
- Version advances from 1.7.4 (62) to 1.7.5 (63).
- The existing `android-ubuntu.yml` workflow builds and verifies the APK. `OriginalMinisToolColorsTest`, `SkillLinkPreviewTest` and `MinisPathCandidatesTest` are included in selected cloud regressions alongside existing glass/layout tests.
- Local Android compilation is deliberately not attempted: this device's installed SDK/NDK do not match the project's SDK 37.2 / NDK 28.2 requirements. No persistent toolchain modifications are required.

## CI dependency-download fix

Run `44995548918` compiled the Debug sources and test sources, then failed inside `Build APK`: all ten failing Robolectric classes received HTTP 429 when downloading Android runtime jars from Maven Central. Release packaging did not run. No behavioral test failures were observed in that run; the affected tests had not started.

CI now prepares the required API 32/34/35 instrumented SDKs before the test worker starts. `scripts/robolectric-sdks.json` pins the versions and SHA-512 digests, checked against both Maven Central and its Google-hosted mirror. The downloader validates cached/downloaded files, retries and falls back across mirrors, and fails closed if no verified copy is available. Every Gradle `Test` task receives the same offline SDK directory, including final APK extraction verification. Test selection and acceptance assertions remain intact.

## Device acceptance

After installing the signed CI APK, check light and dark themes, file_read/file_write/shell thumbnails, both work rows over scrolling text, and tapping a skills result. Test both an existing percent-encoded skills file and a missing file. Existing files should open natively; missing ones should show an error rather than an empty browser. Visual/device acceptance is separate from compilation and unit-test success.
