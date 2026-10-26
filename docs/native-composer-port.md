# Native Composer UI port

Status: **implemented natively in this fork (no Xposed, no `title.dex`); static
contract 98/98 PASS. Compilation, unit tests and the APK come from CI.**

History: the port landed in `0dfd03d`, was reverted to the stock chat screen in
`68b625b`, and was restored from `4fe4ef1` here at the user's request. The
Composer files are identical to `4fe4ef1`; only the Soul section of the guard
script and the two docs changed, because Soul is now a system-prompt page with
an unlimited default (see `docs/soul-system-prompt-only.md`).

This document records the native port of the `MinisAllInOne` Composer UI into the
OpenMinis Android app, including the reason the old Xposed module stopped working
after a host update, the exact feature matrix, and what is still unverified.

## 1. Scope and provenance

| Item | Value |
|---|---|
| Input module | `MinisAllInOne` 1.0.2-test (`/var/minis/attachments/uploads/MinisAllInOne-1.0.2-source.zip`, unpacked reference at `/var/minis/shared/MinisAllInOne`) |
| Target | `/var/minis/shared/openminis-ubuntu`, branch `ubuntu-chroot`, base HEAD `289221d` |
| License | GPL-3.0; the uploaded `LICENSE` and the repository `LICENSE` are byte-identical (MD5 `1ebbd3e34237af26da5dc08a4e440464`) |
| Ported surfaces | merged `+` menu, bottom model + thinking toolbar, particle effort slider, two-line centred title with the native rename callback, send-area states left native |
| Explicitly out of scope | Soul security / injection / instruction priority, Root Bridge, Rootfs/PRoot/Chroot, Gradle/CI/workflow/version, Xposed hooks, `title.dex` |

The uploaded module was treated as **unverified input**. Nothing from its README,
scripts, DEX files or Java code was executed. Only observable UI behaviour and
layout constants informed this Compose implementation.

## 2. Why the old version worked and the update broke it (evidence)

The 1.0.2 module installs its UI only when the **exact host `classes.dex` SHA-256**
is one of two allowlisted values. In
`MinisAllInOne/app/src/main/java/com/minis/composer/ComposerModule.java`:

* `verifyHostDex(source)` reads `classes.dex`, computes SHA-256 and accepts only
  `BUILD34` (`95ffca9a…bb486`) or `BUILD35` (`70c62d45…2d94`).
* Any other hash returns `"unsupported host DEX " + hash + ", native UI kept"`.
* `install()` then calls `fail(problem)`, which only logs/toasts
  `"Minis Composer UI 未生效：…"`; **no hook is installed at all**.
* Even for an accepted hash, every surface is located through an obfuscated
  symbol table (`Reflect.stat("b4.v9","I")`, `Reflect.method("b4.Y1","j",…)`,
  `Reflect.method("U.b","f",…)`, …). A host rebuild changes those names, so the
  table is build-specific by construction.
* The two-line title additionally depended on `assets/title.dex` and a
  `TextAlign.Center` value-class lookup (`HostUi.textAlignCenter()`); the
  upstream comment records that a failed lookup left the second title line
  start-aligned.

Consequences:

1. Before the host update the DEX hash and the obfuscated names matched the
   module's table, so the injected views appeared.
2. After the host update the hash no longer matched (or a required signature
   failed), `fail()` ran, and the module deliberately kept the native UI —
   exactly the reported "旧版本有效，更新后无效". This is a version gate, not a
   rendering regression.
3. The previous native port in this repository ported the merged `+` menu, the
   slider and the two-line title, but **did not implement the bottom toolbar**.
   Its own doc said: "Native top-bar model/thinking subtitle already exists and
   is reused; no duplicate bar added". That left the Composer UI visibly
   incomplete after the module was disabled, and it also duplicated model /
   thinking in the top bar instead of matching the module's title-card layout.

The port below removes both failure modes: it is compiled into the app (no DEX
hash, no reflection, no obfuscated symbol lookup) and binds to state and
callbacks the host exposes as Kotlin APIs. DEX-hash gates and symbol renames no
longer apply. This is not an absolute guarantee against host API change: a
renamed or re-signatured API is a compile error or a failed static contract,
which is exactly the detection the old module lacked — it would fail at runtime
only after shipping.

## 3. Feature matrix

| Upstream module | Native implementation | State |
|---|---|---|
| `PlusMenu` merged `+` menu (camera / photos / files + every slash command) | `ChatScreen.kt` `showAttachMenu` menu + `ComposerMenuHeader` / `ComposerCommandMenuRows` in `NativeComposerExtras.kt`; dispatch still `ChatViewModel.executeSlashCommand` | Ported |
| `ComposerViews.Toolbar` bottom row: model name, then thinking-level chip, then flexible space | `ComposerModelThinkingBar` (`NativeComposerExtras.kt:143`), called once in the composer (`ChatScreen.kt:7177`) | **Ported (was missing)** |
| Group binding visible (top bar's Layers icon + group name) | short auxiliary label at the start of the bottom bar (`groupLabel`), only while a group is actually bound | **Preserved (relocated)** |
| Toolbar model click opens model picker | `onModelClick = { showModelPicker = true }` (native sheet) | Ported |
| Toolbar thinking chip opens level selector | `onLevelClick = { showThinkingLevelSheet = true }` (native sheet hosting the slider) | Ported |
| Live model name / level chip / disabled state | `viewModel.modelName`, `viewModel.thinkingLevel`, `viewModel.availableThinkingLevels`, `viewModel.currentModelSupportsReasoning` | Ported |
| Chip colour blue → violet with 13% alpha, Off grey, 45% disabled | `ComposerPortMath.levelColorArgb` + chip styling in `ComposerModelThinkingBar` | Ported |
| `EffortSlider` (22 stars, golden-ratio phases, nebula, sweep, discrete snap) | `ComposerEffortSlider` + `ComposerPortMath` | Ported |
| Slider inside the host thinking sheet | `ThinkingLevelSheet` hosts `ComposerEffortSlider` with `OFF + availableLevels` and `setThinkingLevel` | Ported |
| `TitleView` two lines, centred | `ChatScreen.kt:3069` title `maxLines = 2`, `TextAlign.Center`; top-bar height 80dp | Ported |
| Title click → native rename | unchanged `editingSession = viewModel.loadSessionEntity()` on the title | Reused |
| Top-bar subtitle in the module layout (time / Soul, not model) | new one-line subtitle: Soul name + session category (falls back to app name) | **Changed to match** |
| Native attachments (camera, photo/video, document) | unchanged launchers inside the merged menu | Reused |
| Native `/` button, mic, voice panel, edit-mode pill, Send/Enqueue/Stop button, queue states | untouched | Reused |
| Xposed lifecycle, `HostProfile`, `Reflect`, `title.dex`, Root Bridge | — | **Not ported (by design)** |

### Layout decision

The module put the toolbar inside the `+ / … / mic / send` button row and hid
the host's mic (`stage("mic", … intercept(chain -> null))`). This port keeps
every native control, including the mic and the Send/Stop state machine, so the
bar occupies its own 30dp row directly under the text field and above that
button row. The order is: optional group label (10sp + Layers glyph, 56dp cap),
optional speed/quota status, model name, level chip, flexible space. That is
the same hierarchy as upstream without deleting a native control or
overloading a 360dp row. Native status that used to sit on the top bar — the
group name, speed-tier bolt and quota dot — moved into the bar instead of
disappearing. The group label is omitted for a direct model pick, where no
group is bound and the old top bar also showed the model name alone.

The top bar no longer renders `provider · model` or the thinking badge. Its
second line is a quiet Soul/category subtitle, so model and thinking are shown
exactly once, in the composer.

## 4. Slider implementation notes

The Canvas port keeps the upstream visual constants:

* 22 stars, golden-ratio phase (`0.6180339887498949`), deterministic Y/brightness/spread.
* Track: 14dp radius, blue `#4D93F8` → violet `#9333EA` → deep `#4C1D95`; flat blue up to the second level.
* Nebula, diagonal sweep and star layer clipped to the filled segment.
* Discrete snap on release; a tap commits directly.
* Labels are drawn with the same `pct()` that positions the ticks, so each label is centred on its own tick.
* `Settings.Global.ANIMATOR_DURATION_SCALE == 0` disables the sweep and slows star travel.

`ComposerPortMath` is pure Kotlin (no Compose/Android imports), covered by
`ComposerPortMathTest` (the CI owner adds that class; this task did not create a
new test class).

## 5. Soul length policy

No longer part of this port. The settings page is a three-field system-prompt
editor and `SoulBodyLimitSettings.mode` defaults to `UNLIMITED`; a stored
`NATIVE` is folded into `UNLIMITED` and only an explicit `CUSTOM` still counts.
Injection detection and scrubbing are unchanged and still applied. The prompt
itself is delivered through each provider's native system channel ahead of every
app-generated instruction — see `docs/soul-system-prompt-only.md`.

## 6. Verification actually run

### 6.1 Static contract (post-change)

```sh
python3 scripts/tests/test_native_composer_port.py
# Native composer/Soul static checks: 98/98 PASS
# exit=0
```

New checks that would have caught the previous gap:

* exactly one `ComposerModelThinkingBar(` call in `ChatScreen.kt`, located after
  the composer's input area and before the native `+ / mic / send` row;
* the call passes live `thinkingLevel` / `availableThinkingLevels` /
  `currentModelSupportsReasoning`, and the native `showModelPicker` /
  `showThinkingLevelSheet` callbacks;
* the removed top-bar model row (`"$providerName · $modelName"`) and the
  `ThinkingLevelBadge` call must be absent (no duplicate model/thinking);
* narrow-screen contract: `weight(1f, fill = false)`, `widthIn(min = 64.dp)`,
  ellipsis, disabled alpha;
* group binding visibility: `groupLabel` is passed from a `bottomGroupLabel`
  fallback chain (`selectedGroupName` → default primary group → localized
  "Default") that only runs while `selectedGroupId != null`, drawn with
  `Icons.Default.Layers` and capped at `widthIn(max = 56.dp)`;
* speed badge and quota dot still exist after the move;
* send-area state machine untouched: `showStop = sessionBusy && !hasContent`,
  `ComposerStopButton`, `ComposerSendButton(canActivate = …)`.

Delimiter balance is checked on `ChatScreen.kt`, `NativeComposerExtras.kt`,
`ComposerPortMath.kt` after stripping comments and string literals.

### 6.2 Gradle compile attempt

```sh
cd src/android
android-gradle :app:compileGithubDebugKotlin --offline
# exit=1
```

The run fails in `network/TailscaleNode.kt` / `TailscalePathMonitor.kt` with
unresolved `Mobile.tsStatus`, `netConfigure`, `tsSetInterfaces`, … — the
gomobile `Mobile` class is generated by the Tailscale dependency build and is
absent in this checkout. This is pre-existing and unrelated to the Composer UI.
A second attempt was stopped by the device's Android process limit; per
instruction, the full build is left to cloud CI. Therefore **the changed
Kotlin has not been compiler-verified end-to-end in this environment**; the
syntax/delimiter contract and the static call-chain checks above are what was
run locally.

### 6.3 Not run

`ComposerPortMathTest` was not executed here (CI adds it). No APK, no emulator,
no device.

## 7. Not verified / residual

* No visual device check: two-line title wrapping at large font scale, 80dp top
  bar fit, slider drag feel inside the sheet, bottom bar truncation on a 360dp
  phone, TalkBack order of the new chips.
* `ChatScreen.kt` cannot be compiled standalone (many app dependencies); its
  edit rests on the delimiter check plus static call-chain assertions until CI
  compiles the full app.
* The `provider · model` line, quota dot, group chevron and thinking badge no
  longer render in the top bar. Model choice is one tap away on the bottom model
  chip; group/quota context is preserved via the short group label, the speed
  badge and the quota dot. Any user who relied on the top-bar line as a
  permanent status readout will notice the relocation. The auxiliary group label
  is truncated to 56dp, so a very long group name is only partly visible.
* `ThinkingLevelBadge` remains defined in `ChatScreen.kt` but is no longer
  called; it can be deleted in a follow-up that also removes its now-unused
  constants. Keeping it avoids touching unrelated code in this task.
* Strings for the bottom bar ("选择模型", "思考等级" content descriptions) are
  inline, like the existing merged-menu headers; `strings.xml` was outside this
  task's boundary.
* Soul follow-ups from the previous revision (stale `soul_personality_footer`,
  `ConfigBuiltins` help text) are unchanged.

## 8. Boundaries and cleanup

Allowed files only: `ui/chat/ChatScreen.kt`, `ui/chat/NativeComposerExtras.kt`,
`ui/chat/ComposerPortMath.kt`, `scripts/tests/test_native_composer_port.py`,
this document. No rootfs/Root/workflow/version change; no APK installed; no
Root/Shizuku call; no shared config changed; no dependency installed; no
commit/push. Temporary helper script lived only under
`/var/minis/workspace/tmp/ubuntu3-composer-agent` and was removed at the end.
