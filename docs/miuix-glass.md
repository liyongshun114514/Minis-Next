# Android glass rendering

The Android UI uses Miuix `0.9.5-beta01`. Preserve these sampling boundaries when changing a toolbar or menu. This pass follows the pinned official Demo/source `6d2554a8`, the `limczhh/miuix-skill` rules, and Eta `939cae8` as a composition reference (opaque page base, scene/page sampling separation and native glass/press APIs; no dependency or navigation-runtime migration).

## Backdrop ownership

- Chat records an **opaque, full-width message viewport** before message-column centering and gutters. The toolbar and composer are separate from the recording. Shared scrolling settings record their body for the same top-bar effect.
- `MinisGlassTopBar` draws one Miuix `textureBlur` background. `BandOverhang` extends the fade beneath the measured foreground. The `DstIn` mask fades both blur and tint to zero, and the overhang draws without intercepting taps.
- `MinisGlassIconButton` samples `LocalMinisActionBackdrop`, which records only the opaque page body. Buttons remain siblings of the band; neither samples itself. Use the native default fill only without a supported backdrop, and the same `GlassShadows.Regular` at the button and morph endpoint.
- The home session list and model-group management list draw directly. The home accordion keeps stable item keys and placement motion. Items removed by a toggle are disposed without a fade-out drawing layer. This reduces exposure to the `RenderThread` crash reported during group toggles. Confirm stability on the affected device.
- `MinisOverlayHost` supplies the root Miuix `PopupLayout`. `MinisSceneBackdrop` records the opaque scene only while a same-window menu owns a lease, including exit. Root popups and dimming are outside this recording. The lease is released on disposal; idle lists/transitions are not permanently recorded. Window-hosted menus keep the readable native fallback rather than sampling another window.

`MinisSmallTopAppBar` deliberately shares `SettingsGlassTopBar`'s three-slot measure/place policy. Miuix 0.9.5-beta01's `SmallTopAppBarLayout` measures `actionIconsPlaceable` but never places it, hiding Mount Add / detail Save actions. Do not regress to that layout or its shadow-clipping `clipToBounds`. Reserve full action widths before ellipsizing the title; keep folder/permissions pages on the same `surface` background as the glass band.

## Menus

- Discrete/selection/context menus use native `GlassDropdownPopup` for the spring/arc opening and exit. Chat More, composer +, home Terminal, scheduled More, model-member More and skill menus use `GlassTransformPopup` with the actual glass-button anchor. Mark the icon with `glassPopupAnchorContent`, so its copied content does not expand from the entire button bounds. Opening and closing share the same origin.
- Independent icon actions use glass. Function-menu/sheet triggers opt into `menuMotion = true`; controls with long-press menus also use it. `minisJellyPress` observes without consuming clicks and delegates motion to Miuix `glassPress` (no custom spring or painted dim). Direct navigation/Search/Back stay still. `MinisDropdownPreference` binds native `BasicComponent` slots and `GlassPopupItem` to the shared exit/IME/scene contract; no library measurement/press code is copied.
- Miuix owns the glass surface, shadow, stroke and transitions. `MinisMenuPosition` translates existing physical left/right alignment, gaps and finger anchors into window coordinates.
- The host is first composed closed so native transitions animate on first open. Keep the caller composed through exit. Final-content disposal reads **current** visibility, sets the root `hostVisible` false and releases `mounted` before firing `onDismissFinished` once. Do not unconditionally restore `hostVisible = true` on a closing SideEffect: that can retain a collapsed surface/shadow over the restored button. The chat speed submenu opens from the finished callback.
- Composer Send/Stop choosers share this implementation, prefer placement above their button, avoid the IME, and do not take keyboard focus. Their existing preference writers and dismiss-tap guards remain unchanged.
- Preserve caller composition locals when moving menu content into the root overlay (session context, theme and interaction state).

## Floating composer and settings

The composer uses a 24dp glass shape, 10dp side gutters, 12dp bottom clearance plus the live navigation inset, and a 56dp minimum editor region that supports up to six text lines. Opening or closing the + menu leaves the composer at its measured size and position. The menu measures itself within the available safe area. Send uses neutral `onSurface` with a 10%-alpha idle fill.

The application uses the platform's unmodified Compose density and system font converter (the earlier 92% override has been removed). Typography no longer forces global left alignment, so native button/title alignment survives. The model picker uses the same platform density in the activity overlay. It uses a `surface` sheet and `surfaceContainer` cards, with a readable accent and radio-button semantics for selection. Its native glass host stays mounted through Miuix's exit animation so a management route opens after dismissal.

The message list explicitly fills the complete viewport, including short conversations, and continues behind the input overlay instead of ending at a Column sibling boundary. `AlwaysStretchOverscrollBox` owns the only overscroll visual node; the inner LazyColumn gets `withoutVisualEffect()` for events only. Attaching the same visual node twice can bind its RenderNode to the short list and cut a pulled-down reply in half. Its bottom content padding includes the measured composer height and visible tool/status reserve exactly once. Tool/scroll controls are lifted by that same height. User bubbles use the neutral `surfaceContainerHigh`, not a primary-blue mix.

Section/dialog fields use neutral inset fills with rounded corners and a 16dp **inner** text margin; outer Card/block padding cannot replace it. Shared section captions use native `SmallTitle` (28dp text inset), Cards use a 12dp external gutter, and model-group detail uses `surface` behind `surfaceContainer` cards. Model members stay separate stable lazy items. Environment editor content/identity/prefill is retained until `onDismissFinished`, with a new draft key on the next session. The Root choice dialog uses native `RadioButtonPreference`, a secondary semantic Card and a 20dp-gap, equal-weight Cancel/Save row. The risk text scrolls while actions remain reachable. Skill-import tabs use native `TabRowWithContour` (plain `TabRow` paints a rectangular bed). The provider API-format pickers use `MinisSegmentedTabs` instead: a static squircle track, one animated selection pill, and native `MiuixIndication` rectangles that stay square at every interior seam — dragging the bar no longer slides the track or the pill. Native Cards own the outside corners of grouped choice rows; interior press edges stay square and flush with their divider. The row and its divider are one layout child, with the divider outside the input plane. Outer card padding is zero vertically; Preference content owns its breathing room. Sub-agent concurrency uses the same `OverlayDialog` arrangement as the wait-time selector. The System Prompt directory contains only SOUL and workspace instructions; runtime prompt assembly and stored catalog overrides are not deleted.

The thinking-level selector is the original discrete particle slider again (Canvas over `ThinkingSliderGeometry`, one Animatable in percent space, detent press/drag, release-only commit, cancellation without commit, accessible stepping and a disabled/reduced-motion clock stop). Its sheet keeps the native Miuix glass surface and samples the recorded chat page, so the blur behind it is real rather than an opaque container fill.

## Native glass sheets and press boundaries

All app bottom-sheet entry points use `MinisBottomSheet`, a lifecycle adapter around official `GlassBottomSheet`. A sheet samples its page recording (`LocalMinisActionBackdrop`, supplied by `MinisPageScaffold`/`SettingsScaffold` bodies) before the shared scene, so it never reads its own surface back into the blur; the thinking-level sheet passes `chatTopBackdrop` explicitly. Miuix owns the layout, handle, scrim, blur material, surface clipping, drag/nested scroll, IME and predictive Back. Root scene sampling excludes all root overlays. A content-scoped lease survives native exit and releases on disposal; caller composition locals and `LocalDismissState` are preserved. Controlled `show` flows keep their finished callback; legacy `show=true` flows defer dismissal notification until native exit. Internal close buttons use that native dismiss state. Independent Android windows keep native `WindowBottomSheet` without unsafe cross-window sampling.

A page bar draws one native progressive blur (80 dp `ProgressiveBlur.Top`) tinted with 30% surface, exactly like the official `GlassTopAppBar`. The earlier 80% wash hid the texture it was meant to blur and read as a plain gradient. Bars fall back to `LocalMinisActionBackdrop`, and even short (non-scrollable) settings pages record their body, so the blur never silently degrades to the band brush.

Glass icon-button surfaces, shadows and anchor bounds stay in the resting coordinate plane; only glyphs receive `glassPress` while the native menu morph owns surface motion. `RichTitleAppBar` paints its background without a rectangular Surface clip, so glass shadows do not end abruptly at the bar edge. Grouped settings press planes do not scale away from dividers; whole-card actions use native Card/Surface click APIs inside their clipping, not external clickable modifiers. Capsule controls clip before the native indication. Custom expand headers use MiuixIndication, never an app-painted or Material press overlay.

## Fallbacks

On systems without runtime-shader support, use Miuix's opaque surface with its configured stroke/shadow and the toolbar's theme gradient. An independently hosted window dialog must not send its menu underneath itself in the activity overlay: it uses a non-focusable popup and does not sample a different window's scene.

## Regression checks

From the repository root:

```sh
python3 scripts/tests/test_native_composer_port.py
```

The source gate pins the sampling graph as well as the existing composer contracts. Android regression coverage lives in `MinisMenuPositionTest` and `MiuixMigrationUiTest` (scrolling menus, outside dismissal, repeated open/exit/submenu handoff, focused unsent draft, and toolbar fallback controls). They run through the Android CI regression task.

Source checks and JVM interaction tests are not a visual acceptance test. Inspect native glass, scrolling fade continuity, light/dark materials and button-to-menu morphing on a supported device.
