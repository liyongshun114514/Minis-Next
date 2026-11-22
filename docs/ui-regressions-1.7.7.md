# Minis Next 1.7.7 (65): separate status rows and consistent glass

User report: sub-agent/work rows intermittently disappear; surrounding color discontinuities remain. Preserve two rows: sub-agent above, model work below, both using the input's glass blur.

Changes:
- Tool blocks now derive synchronously from the same messages/streaming snapshot as the visibility and scroll reserve. Remove the separately restarted collector that could lag by a frame.
- Compute available height from BoxWithConstraints rather than a size callback initialized to zero, avoiding transient zero-height clipping on entry.
- Render a distinct upper status surface and lower work surface with a 4dp gap. Both call the same modifier used by the input. The child status entry paints content only, avoiding double tint.
- Remove the rectangular wrapper clip so rounded panel blur/shadows are not abruptly cut at the side insets. The wrapper has no paint; each panel controls its own silhouette.
- Keep existing preview thumbnail, tool paging, status filtering, expand sheet, message capsule color and web inset fix.

Validation: local full ToolbarGapTest, shared renderer source contract and 18 glass contracts (23 total), plus 10 Python version/build checks. Cloud Compose coverage exercises both themes, preview off/on/off, upper/lower geometry, fallback colors and a real recorded backdrop against the input renderer. Full APK and native rendering checks require the new Actions run; physical-device acceptance is not implied by source tests.
