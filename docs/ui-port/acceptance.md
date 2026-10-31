# Acceptance checklist

Current status: **incomplete UI port; do not merge**.

## Automated checks

- `python3 scripts/test-omnibot-ui.py`: local Kotlin 2.1 + Compose compiler, actual 16 production source files, eight motion tests. SDK `android.jar` is compile-only; generated R.string IDs are verified against the real resources. Not a whole-app build or screenshot test.
- `python3 -m unittest discover -s scripts/tests -p test_omnibot_ui_contract.py -v`: frozen navigation/data/markdown boundary, package/shortcut isolation, empty-home task/terminal access, original drag-swap preference and button target guard.
- `python3 scripts/tests/test_native_composer_port.py`: existing 124 native composer/Soul static contracts.
- Actions `android-ubuntu.yml` with `variant=release`, `ui_preview=true`: entire Android module compile, selected JVM/Robolectric tests, real APK signature/identity/rootfs validation. New `OmniMotionTest` and `OmniPresentationRenderTest` must have nonzero tests and zero skipped/failures/errors.
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

The latter glass, full dock/pager/rail and source-morph items are implementation gaps, not merely missing tests. A successful Actions run does not complete them or authorize calling this a full port.

## Merge gate

Only after implementation gaps and real-device regressions are closed: restore/default the production package `com.openminis.app.next`, build and verify the production artifact, re-check against latest main, then merge the UI branch into `minis-next`. Do not delete the branch or install/replace the running assistant during incomplete acceptance.
