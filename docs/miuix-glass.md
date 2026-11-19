# Android glass rendering

The Android UI uses Miuix `0.9.5-beta01`. Preserve these sampling boundaries when changing a toolbar or menu.

## Backdrop ownership

- Chat records an **opaque, full-width message viewport** before message-column centering and gutters. The toolbar and composer are separate from the recording. Shared scrolling settings record their body for the same top-bar effect.
- `MinisGlassTopBar` draws one Miuix `textureBlur` background. `BandOverhang` extends the fade beneath the measured foreground. The `DstIn` mask fades both blur and tint to zero, and the overhang draws without intercepting taps.
- `MinisGlassIconButton` uses a uniform theme fill with Miuix's edge stroke and soft shadow. Each button draws its complete circular surface independently of the bar and its safe-area boundaries.
- The home session list and model-group management list draw directly. The home accordion keeps stable item keys and placement motion. Items removed by a toggle are disposed without a fade-out drawing layer. This reduces exposure to the `RenderThread` crash reported during group toggles. Confirm stability on the affected device.
- `MinisOverlayHost` supplies the root Miuix `PopupLayout`. Its menus use the library's themed fill and draw above the page without recording it.

`MinisSmallTopAppBar` deliberately shares `SettingsGlassTopBar`'s three-slot measure/place policy. Miuix 0.9.5-beta01's `SmallTopAppBarLayout` measures `actionIconsPlaceable` but never places it, hiding Mount Add / detail Save actions. Do not regress to that layout or its shadow-clipping `clipToBounds`. Reserve full action widths before ellipsizing the title; keep folder/permissions pages on the same `surface` background as the glass band.

## Menus

- Discrete menus use native `GlassPopup`; chat More, composer +, home Terminal, scheduled More and skill menus use `GlassTransformPopup` with the actual glass-button anchor. Opening and closing share the same origin.
- Independent icon actions use glass. Only function-menu triggers opt into `menuMotion = true` (one shared press/rebound spring). Direct navigation, add-provider/editor screens, Search, New Chat and Back do not use the spring; do not infer menu motion from an Add/More icon alone.
- Miuix owns the glass surface, shadow, stroke and transitions. `MinisMenuPosition` translates existing physical left/right alignment, gaps and finger anchors into window coordinates.
- The host is first composed closed so native transitions animate on first open. Keep the caller composed through exit. The final-content disposal reads **current** visibility and fires `onDismissFinished` once; the chat speed submenu opens from that callback.
- Composer Send/Stop choosers share this implementation, prefer placement above their button, avoid the IME, and do not take keyboard focus. Their existing preference writers and dismiss-tap guards remain unchanged.
- Preserve caller composition locals when moving menu content into the root overlay (session context, theme and interaction state).

## Floating composer and settings

The composer uses a 24dp glass shape, 10dp side gutters, 12dp bottom clearance plus the live navigation inset, and a 56dp minimum editor region that supports up to six text lines. Opening or closing the + menu leaves the composer at its measured size and position. The menu measures itself within the available safe area. Send uses neutral `onSurface` with a 10%-alpha idle fill.

The application theme uses 92% of the device's Compose density and preserves the system font-scale preference and font converter. The model picker applies the same density to its Android dialog content. It uses a `surface` sheet and `surfaceContainer` cards, with a readable accent and radio-button semantics for selection. Its window stays mounted through Miuix's exit animation so a management route opens after dismissal.

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
