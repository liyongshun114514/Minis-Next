# Acceptance checklist

Current status: **incomplete UI port; do not merge**.

## Automated checks

- `python3 scripts/test-omnibot-ui.py`: automatically discovers all Omni presentation modules and their real dependencies. Latest local run compiled 34 production + 12 test files and passed 110 JVM/Robolectric tests. It links an actual test APK and AndroidX resource tables with aapt2 (no invented R IDs). Local Robolectric uses LEGACY graphics because NATIVE is unsupported on this aarch64 host; no GPU/pixel parity is claimed.
- `python3 scripts/test-composer-motion.py --stage all --require-pointer`: real composer compile, 28 JVM checks, four real pointer-hit tests; static wiring forbids disabling glass shape animation. Runtime bundle is a local cache, not part of the source tree.
- The whole production set was separately type-compiled with Kotlin/Compose (852 sources, 101 resolved jars, generated compile-only R/BuildConfig and an rclone interface stub): zero errors. This is NOT an APK build or native-library validation.
- `python3 -m unittest discover -s scripts/tests -p test_omnibot_ui_contract.py -v`: frozen navigation/data/markdown boundary, package/shortcut isolation, empty-home task/terminal access, original drag-swap preference and button target guard.
- `python3 scripts/tests/test_native_composer_port.py`: existing 124 native composer/Soul static contracts.
- Actions `android-ubuntu.yml` with `variant=release`, `ui_preview=true`: entire Android module compile, selected JVM/Robolectric tests, real APK signature/identity/rootfs validation. Every checked-in `*Omni*Test` class and `ChatComposerMotionTest` must have nonzero tests and zero skipped/failures/errors; `verify-ui-test-reports.py` discovers the expected classes from source and fails closed for missing XML.
- The Actions default remains production identity and does not publish a GitHub Release.

## Device functional regression — not yet executed

- Separate package can coexist without modifying production preferences/history/rootfs.
- Empty/onboarding homepage retains settings, scheduled task and Shell/terminal/rootfs/browser access.
- New chat; long-press group choice; search including zero results; horizontal drag swaps primary actions and persists across restart.
- Existing conversations, folders, selection, export, deletion confirmation, model/group switching.
- Send/cancel/queue/attachments/camera/voice, tool approval/denial, streaming scroll/selection/copy/markdown.
- File browsing, previews, terminal, settings save/cancel, system share, shortcut and deep-link resolution.
- App theme override contrary to system theme; font scaling; dark/light contrast; permission denial.
- Narrow phone, keyboard show/hide, gesture/three-button system navigation, landscape, foldable and existing tablet split view.

## Visual/motion parity — not yet executed

Record reference and preview on the same device/density and compare both light/dark:

- 72dp header; grouped settings surfaces and 22/26dp geometry; neutral accents and exact palette.
- Chat bubble shape, input shell, typography and line wrapping; tool/status surfaces.
- 450ms quarter-width page movement and fade phases; interrupted pushes/back/veto/results.
- Dock glass/refraction, spring/lens movement, retained tab pages, adaptive rail.
- Search/header source morph, popup source snapshots, measured settings row expand/contract.

Implementation now includes background capture + API33 AGSL / older-API refractive mesh, actual superellipse paths, four retained destination trees with active-page lifecycle isolation, 600dp rail, dock lens/drag/jelly, measured search/menu/settings container transforms, composer focus/attachment morph, 220ms theme blending and sheet rubber-band motion. These are implemented, not proof of reference parity. Remaining source differences include approximate spring/jelly integration, missing pointer-hover magnet/parallax and fling prediction, no outgoing-control bitmap snapshot, and no source atlas/goo contour implementation. Predictive back relies on Navigation 2.8 rather than a new competing full-screen gesture. Reference-equivalent GPU output, performance and interrupted/back-cancel motion still require device verification. A successful Actions run cannot waive those limits or authorize calling this a visually complete port.

## Merge gate

Only after implementation gaps and real-device regressions are closed: restore/default the production package `com.openminis.app.next`, build and verify the production artifact, re-check against latest main, then merge the UI branch into `minis-next`. Do not delete the branch or install/replace the running assistant during incomplete acceptance.
