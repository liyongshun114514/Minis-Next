# Native Composer UI + Soul length port

Status: **source implementation + runnable pure tests complete; not compiled as an APK and not visually verified on a device.**

This document records what was actually ported from the uploaded
`MinisAllInOne` 1.0.2-test module into the OpenMinis Android app, what was
deliberately **not** ported, how the native bindings work, and what remains for
another owner.

## 1. Scope and provenance

| Item | Value |
|---|---|
| Input module | `MinisAllInOne` 1.0.2-test, unpacked at `/var/minis/workspace/tmp/openminis-port/MinisAllInOne` (94 files) |
| Target | `/var/minis/shared/openminis-ubuntu` (Android app, `com.openminis.app`) |
| License | GPL-3.0. `LICENSE` in the input and in this repository are byte-identical: MD5 `1ebbd3e34237af26da5dc08a4e440464` |
| Ported surfaces | Composer UI: merged `+` menu, two-line centred title, particle thinking slider. Soul: configurable application-side body-length gate + `∞` counter |
| Explicitly out of scope | Root Bridge, Ubuntu/execution backend, `RootfsManager` / `PRootKernel` / `RootfsManagementScreen`, Gradle/CMake, Actions, `strings.xml`, `OffloadPermissionDialog.kt` |

The uploaded ZIP was treated as **unverified input**. Nothing in its README,
scripts or Java code was executed. Its Xposed hooks, reflection maps, DEX
scanner and `assets/title.dex` were **not** copied. Only observable UI
behaviour and visual constants informed this native Compose implementation.

## 2. Feature mapping

| Upstream module | Native file / symbol | State |
|---|---|---|
| `PlusMenu` merged `+` menu (camera / photos / files + all slash commands) | `ui/chat/ChatScreen.kt` → `showAttachMenu` menu, plus `ComposerMenuHeader` / `ComposerCommandMenuRows` in `ui/chat/NativeComposerExtras.kt` | Ported |
| `ComposerViews.Toolbar` bottom model + thinking row | Native top-bar model/thinking subtitle already exists and is reused (`showModelPicker`, `ThinkingLevelBadge`); no duplicate bar added | Reused, not duplicated |
| `EffortSlider` custom Android View (22 stars, golden-ratio phases, nebula, sweep, discrete snap) | `ComposerEffortSlider` (`Canvas` + `pointerInput`) and `ComposerPortMath` in `ui/chat/` | Ported |
| Slider inside the host thinking sheet | `ThinkingLevelSheet` now hosts `ComposerEffortSlider` with the same `rows` (`OFF` + supported levels) and `viewModel.setThinkingLevel` | Ported |
| `TitleView` two lines, centred | Native title `Text` now `maxLines = 2`, `textAlign = TextAlign.Center`; top-bar height 68 → 92dp | Ported |
| Title click → native rename | Existing `editingSession = viewModel.loadSessionEntity()` retained on the same `Text` | Reused |
| Native attachments (camera, photo/video picker, document picker) | Unchanged `launchCamera()`, `mediaPickerLauncher`, `filePickerLauncher` inside the merged menu | Reused |
| Native slash commands / Skills / MCP dispatch | `ChatViewModel.executeSlashCommand(cmd, inputText)` from the merged menu; thinking row opens the slider sheet instead of blind toggle | Reused |
| Native send button, mic, voice, edit-mode pill, stop button | Untouched | Reused |
| Soul length gate removal / `∞` counter | `SoulBodyLimitMode`, `SoulBodyLimitSettings`, `SoulBodyLimitPolicy` in `agent/SoulStore.kt`; controls in `ui/settings/SoulLimitUi.kt`; wiring in `SoulSettingsScreen.kt` | Ported |
| Xposed component lifecycle, DEX hash gate, `HostProfile`, `Reflect`, `title.dex`, Root Bridge | — | **Not ported (by design)** |

### Exact native touch points

* `ChatScreen.kt:3070` — two-line centred title, native rename click retained.
* `ChatScreen.kt:3771` — `expandedHeight = 92.dp` for the taller title.
* `ChatScreen.kt:7362` — merged `+` menu: attachment header + three native actions, then `ComposerCommandMenuRows`.
* `ChatScreen.kt:8720` — thinking sheet body replaced by `ComposerEffortSlider`.
* `NativeComposerExtras.kt:79/101/162` — menu header, command rows, slider.
* `SoulStore.kt:215/218/249` — length mode, persisted settings, classifier.
* `SoulStore.kt:413` — legacy `isOverLimit(String)` now consults the cached policy.
* `SoulStore.kt:671` — OS identity string updated to Ubuntu 24.04 / PRoot or Chroot.
* `SoulSettingsScreen.kt:363` — mode selector + custom caps.
* `SoulSettingsScreen.kt:721` — counter renders `/ ∞` in unlimited mode.

## 3. Slider implementation notes

The Canvas port keeps the upstream visual constants:

* 22 stars, golden-ratio phase (`0.6180339887498949`), deterministic Y/brightness/spread.
* Track: 14dp radius, blue `#4D93F8` → violet `#9333EA` → deep `#4C1D95`; flat blue up to the second level.
* Nebula, diagonal sweep and star layer clipped to the filled segment.
* Discrete snap on release; a tap commits directly.
* Labels are drawn with the same `pct()` that positions the ticks, so each label is centred on its own tick.
* `Settings.Global.ANIMATOR_DURATION_SCALE == 0` disables the sweep and slows star travel.

`ComposerPortMath` is pure Kotlin (no Compose/Android imports), so it is
covered by a normal JVM JUnit test.

## 4. Soul length policy

Three modes, persisted in the app's `soul_body_limit` SharedPreferences file:

| Mode | Effect |
|---|---|
| `NATIVE` (default) | 1600 CJK code points / 1000 Latin words, chosen by the existing 30% CJK-ratio rule |
| `UNLIMITED` | `SoulStore.isOverLimit` always returns `Ok`; the editor counter shows `N / ∞`; no application-side rejection |
| `CUSTOM` | Explicit Chinese-char and English-word caps (1…1,000,000), same ratio rule |

The policy is cached in-process and primed from `SoulStore.ensureExists`,
`load`, `save` and `refreshCache`, so the existing **context-free**
`SoulStore.isOverLimit(body)` callers obey it without signature changes:

* Settings Save button.
* `minis-config`'s `soul.body` writer (`ConfigBuiltins.kt`, not edited).
* `SystemPromptBuilder.identitySection` over-limit fallback.

### Safety boundary (unchanged)

* `INJECTION_PATTERNS` and `containsInjectionPattern` are byte-identical to HEAD.
* `scrubInjections` is byte-identical to HEAD.
* Personality block text, instruction-priority wording, and prompt assembly
  order are untouched.
* The gate is **length only**; `UNLIMITED` does not bypass injection checks.
* The OS identity sentence is the one additional allowed change:
  `Ubuntu 24.04 Linux environment (PRoot or Chroot, aarch64)`.

## 5. Verification actually run

### 5.1 Static contract test

```sh
python3 scripts/tests/test_native_composer_port.py
# Native composer/Soul static checks: 81/81 PASS
```

Covers: file presence, delimiter balance, two-line title/centre/92dp, native
rename, merged menu + native attachment strings + command dispatcher and
draft preservation, slider
presence and setter, send button and observed state, no Xposed/reflection/DEX
tokens in new UI code, no `title.dex` asset, Soul modes/∞/custom fields, native
injection patterns and scrubber byte-identical to HEAD, GPL-3.0 licence match.

### 5.2 Kotlin compilation of the new Compose UI

`NativeComposerExtras.kt` + `ComposerPortMath.kt` were compiled with Kotlin
2.2.21 + the Compose compiler plugin against the project's Compose 1.9.1 /
Material3 1.4.0 AARs and `android-36/android.jar`, with small stubs for
`ThinkingLevel`, `localizedName`, `SlashCommand` and `ChatColors`.

```
K2JVMCompiler ... NativeComposerExtras.kt -d out
exit=0   (no warnings after replacing deprecated quadraticBezierTo)
```

This validates the Compose API usage (Canvas, pointer input, semantics,
Material3 menu rows, `animate`/`spring`) without building the whole app.

`SoulLimitUi.kt` + the real `SoulStore.kt` also compiled `exit=0` against the
same Compose classpath plus Android `Context` / `SharedPreferences` /
`AppLogger` / `SoulIcon` stubs.

### 5.3 JUnit tests

`ComposerPortMathTest` (9 tests):

```sh
java -cp out:... org.junit.runner.JUnitCore com.openminis.app.ui.chat.ComposerPortMathTest
# OK (9 tests)
```

`SoulBodyLimitPolicyTest` (6 tests), run against the real `SoulStore.kt`
compiled with the stubs above:

```sh
# OK (6 tests)
```

Cases: native 1600/1000 boundaries, unlimited accepts 50k CJK / 20k words,
custom caps per majority language, empty body in every mode, settings
normalisation.

## 6. What is NOT verified / not done

* No Gradle `assembleDebug` and no Android/emulator run in this task.
* No visual verification: title wrapping at large font scales, slider touch
  feel, popup position with many Skills/MCP rows, and dark/light colours are
  **not** device-tested.
* `ChatScreen.kt` and `SoulSettingsScreen.kt` were not compiled standalone
  (too many app dependencies); their edits are small and are covered by the
  static checks. The new files themselves do compile.
* `ComposerPortMathTest` and `SoulBodyLimitPolicyTest` were executed by
  compiling them manually with cached Kotlin/JUnit; they have not been run
  through the project's Gradle test task.

## 7. External follow-ups (other owners)

1. `res/values/strings.xml`: move the four inline English labels from
   `SoulLimitUi.kt` ("Native caps", "Unlimited ∞", "Custom", field labels) and
   the sheet hint ("Drag to choose…") into resources. The file was outside this
   port's boundary.
2. `soul_personality_footer` still says over-limit bodies are rejected. It is
   accurate for NATIVE/CUSTOM but stale for UNLIMITED; update when strings.xml
   is next edited.
3. `ConfigBuiltins.kt`'s `soul.body` description still quotes the native caps.
   Behaviour is correct (it calls `isOverLimit`), only the help text is stale.
4. `ChatViewModel.buildSystemPrompt` / `AgentTools` / `HelperRunner` /
   `OnDemandBash` environment wording is owned by the main agent; this port
   does not touch it.
5. Device check: title two-line truncation, slider drag/snap, sheet height,
   merged menu scrolling, and the `∞` counter in all three modes.

## 8. Cleanup

All compilation fixtures and extracted AARs were kept under `/tmp` only and
removed after the runs. No host APK/DEX was installed, executed or scanned; no
Git commit/push was made; no Root/Shizuku call was made. The only repository
additions from this task are the files listed in §2, the two JVM tests, the
static test script and this document.
