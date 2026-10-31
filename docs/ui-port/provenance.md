# UI presentation port — provenance and acceptance

## Reference and implementation boundary

- Minis Next baseline: `dad91db` on `minis-next`.
- Visual reference: [omnimind-ai/OmniBot](https://github.com/omnimind-ai/OmniBot), commit `c4728736964245509404670ee992d3b658efaecd`.
- Reference tokens: `ui/lib/theme/omni_theme_palette.dart` and `app_theme.dart`.
- Reference geometry: `ui/lib/widgets/settings_ui.dart`, `omni_root_toolbar.dart`, home conversation tiles, chat composer and message bubble widgets.
- FlClash-origin motion and surfaces are identified by OmniBot's `docs/third-party/flclash-NOTICE.md`, pinned upstream at `7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`.

Colors, dimensions, typography and timing are independently expressed in Android Jetpack Compose. The liquid-glass optics and superellipse geometry in `ui/components/OmniGlass*.kt` / `OmniShapes.kt` additionally re-express the separately attributed **FlClash GPL-3.0-only** portions identified above; those files carry source and licence headers and are listed in `THIRD_PARTY_LICENSES.md`. Their Android capture pipeline, API isolation, AGSL/mesh backends and tests are this project's implementation; no upstream shader asset is bundled. No OmniBot business runtime, Dart widget tree, branded assets, font, application state machine or service is incorporated. Existing Minis Next GPLv3 remains in force. OmniBot's root segmented AGPL/commercial notice is **not** assumed to license its entire repository as GPLv3. This document records source boundaries; it does not grant third-party rights.

## Identity

Production remains `com.openminis.app.next`. Only explicit `-PminisUiPreview=true` produces `com.openminis.app.next.ui`, with matching static shortcut targets. The default Actions push path remains production; UI acceptance is a manual opt-in. Preview data is separate; no automatic production migration is performed. Both APKs share the `minis://` scheme, so system deep-link resolution may show a chooser when both are installed.

## Function preservation

No provider, agent, repository, persistence schema, permission gate, tool runtime, root/chroot service, markdown parser, text selection engine or conversation ownership is to change. Presentation reshuffles must retain every old callback and interaction, including long-press group shortcuts and persisted action-order swapping. New dock items are **existing actions**, not invented feature destinations.

## Acceptance levels

1. Source contract and actual Kotlin submodule compilation: useful but not a whole-app build.
2. Actions whole-app compile, selected real JVM tests and strict APK/signature/rootfs checks: not device or visual acceptance.
3. Installed preview: light/dark, system/app theme mismatch, font scale, narrow/wide, keyboard, accessibility and complete feature regression.
4. Compare reference screens and recorded motion on the same device. Only then can layout/motion parity be asserted.

## Current parity gaps — merge blockers

- Background capture, AGSL refraction, API26–32 mesh refraction and true superellipse geometry are implemented, but the native renderer has not been pixel-compared with Flutter on a device; the source's layered atlas/goo contours are not reproduced.
- Four retained Minis destinations (Home/Agents/Usage/Settings), adaptive rail and measured search/settings/menu container transforms are implemented. Their real-device back, keyboard, lifecycle and pixel parity are still unverified.
- Menu expansion interpolates the measured source geometry; it does not replay a bitmap snapshot of the outgoing control. Dock jelly uses a bounded independent spring approximation; pointer-hover magnet/parallax and fling prediction are not implemented.
- Chat visual defaults now use 15sp/21.45sp; only those two constants change inside StreamingMarkdownText. All other parser/selection code is frozen. Device line wrapping and selection alignment need regression.
- Native back uses Navigation 2.8 predictive transitions, not a competing full-screen drag recogniser; its seek/cancel trajectory is not yet compared with the source.
- Real-device visual, motion and full functional regression are required before merging.

A green build cannot waive these gaps. Do not merge into `minis-next` or describe this branch as a complete port until they are resolved and verified.
