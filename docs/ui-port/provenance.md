# UI presentation port — provenance and acceptance

## Reference and implementation boundary

- Minis Next baseline: `dad91db` on `minis-next`.
- Visual reference: [omnimind-ai/OmniBot](https://github.com/omnimind-ai/OmniBot), commit `c4728736964245509404670ee992d3b658efaecd`.
- Reference tokens: `ui/lib/theme/omni_theme_palette.dart` and `app_theme.dart`.
- Reference geometry: `ui/lib/widgets/settings_ui.dart`, `omni_root_toolbar.dart`, home conversation tiles, chat composer and message bubble widgets.
- FlClash-origin motion and surfaces are identified by OmniBot's `docs/third-party/flclash-NOTICE.md`, pinned upstream at `7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`.

This branch independently expresses measured colors, dimensions and timing in Android Jetpack Compose. No OmniBot business runtime, Dart widget tree, branded assets, font, shader, state machine or service is incorporated. Existing Minis Next GPLv3 license remains in force. OmniBot's root segmented AGPL/commercial notice is **not** assumed to license the entire repository as GPLv3. Its separately attributed FlClash portions retain GPLv3. Any future source translation must be reviewed and attributed separately; this document does not grant third-party rights.

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

- No reference-equivalent background capture/refraction/liquid-glass shader.
- Native capsule corners/shadows are not Flutter superellipse/shader equivalence.
- The dock hosts Minis actions; it is not OmniBot's four retained tabs or adaptive navigation rail.
- Search capsule is not yet the same source-control expansion/return morph.
- No measured settings-row container expansion, popup source-snapshot morph, or full gesture physics parity.
- Chat typography and retained list layout have not been rewritten wholesale: text selection/streaming behavior must remain intact.
- Real-device visual, motion and full functional regression are required before merging.

A green build cannot waive these gaps. Do not merge into `minis-next` or describe this branch as a complete port until they are resolved and verified.
