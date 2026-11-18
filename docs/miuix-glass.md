# Android glass rendering

The Android UI uses Miuix `0.9.5-beta01`. Preserve these sampling boundaries when changing a toolbar or menu.

## Sampling graph

- Chat records an **opaque, full-width message viewport**, before message-column centering and gutters. The composer, toolbar title and toolbar controls are not part of this recording.
- `MinisGlassTopBar` renders the native progressive band from that viewport. A second recording combines the raw background and rendered band, without foreground title or buttons.
- `MinisGlassIconButton` samples the second recording, with an explicitly floating button material. Do not replace it with the raw message backdrop or tie material visibility to a zero scroll offset.
- `MinisGlassScene` records the rendered page, including its bars. `MinisMenu` is hosted **outside** that recording in the root Miuix `PopupLayout`; recording a menu inside its own backdrop creates a feedback loop.

The band extends beyond the toolbar's layout bounds using `GlassTopAppBarDefaults.BandOverhang`. Only the foreground toolbar consumes toolbar taps; the fading overhang must not block messages.

## Menus

- Discrete menus use native `GlassPopup`; chat More, composer +, home Terminal, scheduled More and skill menus use `GlassTransformPopup` with the actual glass-button anchor. Opening and closing share the same origin.
- Independent icon actions use glass. Only function-menu triggers opt into `menuMotion = true` (one shared press/rebound spring). Direct navigation, add-provider/editor screens, Search, New Chat and Back do not use the spring; do not infer menu motion from an Add/More icon alone.
- `MinisPageScaffold` records only its body for top-right actions; `SettingsScaffold` supplies its existing scroll recording. Never pass the global scene to a button already inside that scene.
- Miuix owns the glass surface, shadow, stroke, spring, blur and content transition. `MinisMenuPosition` only translates existing physical left/right alignment, gaps and finger anchors into window coordinates.
- The host is first composed closed so native transitions animate on first open. Keep the caller composed through exit. The final-content disposal reads **current** visibility and fires `onDismissFinished` once; the chat speed submenu opens from that callback.
- Composer Send/Stop choosers share this implementation, prefer placement above their button, avoid the IME, and do not take keyboard focus. Their existing preference writers and dismiss-tap guards remain unchanged.
- Preserve caller composition locals when moving menu content into the root overlay (session context, theme and interaction state).

## Floating composer and settings

The composer follows Eta's thin-glass proportions: a 24dp glass shape, 10dp side gutters, 12dp bottom clearance plus the live navigation inset, and a 36dp minimum text slot (up to six text lines). It samples the full-height **message-only** backdrop, with `puredThinGlass`, a small edge stroke and native regular shadow; old APIs use the same shape with an opaque fill.

The message list continues behind the input overlay instead of ending at a Column sibling boundary. Its bottom content padding includes the measured composer height and visible tool/status reserve exactly once. Tool/scroll controls are lifted by that same height. User bubbles use the neutral `surfaceContainerHigh`, not a primary-blue mix.

Section/dialog fields use neutral inset fills with rounded corners. Skill-import tabs use native `TabRowWithContour` (plain `TabRow` paints a rectangular bed). Choice rows clip their press indication to a 16dp shape. Sub-agent concurrency uses the same `OverlayDialog` arrangement as the wait-time selector. The System Prompt directory contains only SOUL and workspace instructions; runtime prompt assembly and stored catalog overrides are not deleted.

The thinking-level selector intentionally restores the original native-Canvas particle slider, including detents, cancel-without-commit, shared fill/thumb geometry and accessible tier actions; it is no longer the generic Miuix slider. A reduced-motion setting stops the particle clock.

## Fallbacks

On systems without runtime-shader support, use Miuix's opaque surface with its configured stroke/shadow and the toolbar's theme gradient. An independently hosted window dialog must not send its menu underneath itself in the activity overlay: it uses a non-focusable popup and does not sample a different window's scene.

## Regression checks

From the repository root:

```sh
python3 scripts/tests/test_native_composer_port.py
```

The source gate pins the sampling graph as well as the existing composer contracts. Android regression coverage lives in `MinisMenuPositionTest` and `MiuixMigrationUiTest` (scrolling menus, outside dismissal, repeated open/exit/submenu handoff, focused unsent draft, and toolbar fallback controls). They run through the Android CI regression task.

Source checks and JVM interaction tests are not a visual acceptance test. Inspect native glass, scrolling fade continuity, light/dark materials and button-to-menu morphing on a supported device.
