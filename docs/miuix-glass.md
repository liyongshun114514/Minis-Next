# Android glass rendering

The Android UI uses Miuix `0.9.5-beta01`. Preserve these sampling boundaries when changing a toolbar or menu.

## Sampling graph

- Chat records an **opaque, full-width message viewport**, before message-column centering and gutters. The composer, toolbar title and toolbar controls are not part of this recording.
- `MinisGlassTopBar` renders the native progressive band from that viewport. A second recording combines the raw background and rendered band, without foreground title or buttons.
- `MinisGlassIconButton` samples the second recording, with an explicitly floating button material. Do not replace it with the raw message backdrop or tie material visibility to a zero scroll offset.
- `MinisGlassScene` records the rendered page, including its bars. `MinisMenu` is hosted **outside** that recording in the root Miuix `PopupLayout`; recording a menu inside its own backdrop creates a feedback loop.

The band extends beyond the toolbar's layout bounds using `GlassTopAppBarDefaults.BandOverhang`. Only the foreground toolbar consumes toolbar taps; the fading overhang must not block messages.

## Menus

- Discrete menus use native `GlassPopup`; chat More uses `GlassTransformPopup` with the real glass-button anchor and icon content bounds.
- Miuix owns the glass surface, shadow, stroke, spring, blur and content transition. `MinisMenuPosition` only translates existing physical left/right alignment, gaps and finger anchors into window coordinates.
- The host is first composed closed so native transitions animate on first open. Keep the caller composed through exit. The final-content disposal reads **current** visibility and fires `onDismissFinished` once; the chat speed submenu opens from that callback.
- Composer Send/Stop choosers share this implementation, prefer placement above their button, avoid the IME, and do not take keyboard focus. Their existing preference writers and dismiss-tap guards remain unchanged.
- Preserve caller composition locals when moving menu content into the root overlay (session context, theme and interaction state).

## Fallbacks

On systems without runtime-shader support, use Miuix's opaque surface with its configured stroke/shadow and the toolbar's theme gradient. An independently hosted window dialog must not send its menu underneath itself in the activity overlay: it uses a non-focusable popup and does not sample a different window's scene.

## Regression checks

From the repository root:

```sh
python3 scripts/tests/test_native_composer_port.py
```

The source gate pins the sampling graph as well as the existing composer contracts. Android regression coverage lives in `MinisMenuPositionTest` and `MiuixMigrationUiTest` (scrolling menus, outside dismissal, repeated open/exit/submenu handoff, focused unsent draft, and toolbar fallback controls). They run through the Android CI regression task.

Source checks and JVM interaction tests are not a visual acceptance test. Inspect native glass, scrolling fade continuity, light/dark materials and button-to-menu morphing on a supported device.
