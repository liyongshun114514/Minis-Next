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
| `ComposerViews.Toolbar` bottom row: model name, then thinking-level chip, then flexible space | `ComposerModelThinkingBar` (`NativeComposerExtras.kt:157`), called once in the composer (`ChatScreen.kt:7242`); the chip is drawn **inside** the bar, immediately after the model name (`compact = true`, `showLevel = true`) | **Ported (was missing)** |
| Level chip follows the model name instead of sitting at the right edge | bar takes `Modifier.weight(1f)` on the `+` row; name is `weight(1f, fill = false)` and the chip is the non-weighted sibling measured before it, so a short name keeps the chip beside it and a long name expands until the chip meets the send button | **2026-10-27** |
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

The Canvas port is now a **one-to-one transcription of the upstream web
component**, not of the old Xposed `EffortSlider.java`.

### 4.1 Upstream basis (read, not guessed)

* Repository: `https://github.com/Bai-2008/dsh-better-panel`
* Commit: `4f798ffcecf17d52cd15aaa617124c21b9784c12` (2026-10-25)
* File: `lib/client.js`
* Component: `CodexEffortSlider` (l.1913), stylesheet `CSS` (l.470-560),
  pure helpers (l.662-900), pointer maths (l.2253-2315), render tree
  (l.2400-2510)

The values below are copied from that file, not inferred from screenshots:

| Item | Upstream | Native port |
| --- | --- | --- |
| Track height | `TRACK_HEIGHT = 20` (l.195) | `TRACK_HEIGHT_DP = 20f` |
| Knob diameter | `KNOB_SIZE = 28` (l.194) | `KNOB_SIZE_DP = 28f` |
| End inset | `TRACK_RADIUS = 10` (l.204) | `Track.endInset = barHeight / 2` |
| Stop offset | `calc(10px + pct*(100% - 20px))` (l.896) | `Track.tickX` |
| Fill cap | `10px 0 0 10px` (l.501) | rounded left, square right path |
| Fill colours | `#4D93F8 / #9333EA / #4C1D95` (l.456-458) | same ARGB |
| Energy window | `ENERGY_START = 1/3`, `ENERGY_END = 2/3` | same |
| Nebula | `#3B1178 0% / #6D28D9 30% / #9333EA 62% / #C084FC 100%` (l.512) | same stops |
| Sweep | 2.4s linear, `translateX(100% -> -100%)` | same clock |
| Stars | 22, golden-ratio phase, slot-searched Y, ±8% duration spread | `ComposerPortMath.PARTICLES` |
| Star duration | `1.5 * 2 / speed` (l.853) | same |
| Star count | `round(energy * 22)` (l.876) | same |
| Star layer opacity | `0.6 + 0.4 * energy` (l.888) | same |
| Speed | `clamp(3p - 1, 0.35, 2)` (l.821) | same |
| Detent | `floor(u - 1/3) + 1` (l.2288) | `ComposerPortMath.detentIndex` |
| Press | nearest stop, dead-zone only on a nearest/detent conflict | same |
| Slide | `.16s cubic-bezier(.2,.9,.3,1)` / `.34s cubic-bezier(.45,.05,.35,1)` | `tween(160/340, CubicBezierEasing)` |
| Slow/fast rule | gap since last step, 320ms in / 200ms out hysteresis | same |

Every layer (fill width, energy width, star clip, knob x) is driven by a single
`Animatable` in percent space, so they cannot separate — the native equivalent
of the shared `--ces-slide` CSS variable.

### 4.2 Deliberate deviations (and why)

* **Off/Low particles — upstream conflict, reported.** Upstream stars are gated
  by `energyFor(pct)`, which is exactly 0 for every `pct <= 1/3`; with
  off/low/high/max that means **zero stars and no nebula at Off and Low**. The
  previous native port overrode this with a floor of 5 stars (and dropped the
  nebula entirely). A one-to-one replica cannot keep that floor: the two
  requirements are mutually exclusive. Upstream wins here — stars appear at
  6 (pct 0.5), 11 (high, pct 2/3) and 22 (max). If a live field at Off/Low is
  required later, the single hook is `ComposerPortMath.starCountFor`; it would
  be a documented divergence from `4f798ffc`, not part of this replica.
* **Labels stay.** The upstream slider has no mode names — the host row shows
  only the current value. The native sheet keeps its eight names under the
  stops (peripheral layout preserved), centred on `track.tickX`, so the name of
  every stop still lines up with its dot.
* **Knob overhang + canvas gutter.** Upstream puts the 28px knob on a 20px
  track; at the end stops it deliberately overhangs the pill by 4dp. Compose
  canvases clip, so the canvas reserves a gutter of at least 4dp (plus the
  label gutter): the knob still overhangs the pill by 4dp but never leaves the
  composable, including at 90dp width.
* **Vertical slot unchanged.** The 20dp pill is centred in the previous 30dp
  band (top 5dp), so the label band, gap and the sheet's total height do not
  move; the knob (28dp) exactly fits the slot.
* **Release-only commit.** The sheet persists through `setThinkingLevel` once
  per completed gesture, as before. Upstream additionally debounces a write
  after 260ms of stillness while dragging; the native control keeps the
  release commit the task preserves (same final `reasoningEffort`).
* **Menu-level chrome not ported.** The upstream row glow, seat-button colour,
  provider badge and API note live in the host menu, not in the slider body;
  the native sheet has no equivalent row, so none of those DOM decorations
  apply.
* **No press scale.** Upstream has no thumb growth; the old 1.10 scale was
  removed rather than kept as decoration.
* Single level: `pct(0, 1) = 0` (upstream `pctFromIndex`), so the disabled
  single-stop control rests on the left cap instead of the old right edge.

### 4.3 Verification

* `python3 scripts/tests/test_native_composer_port.py` — 123/123 PASS
  (contract unchanged).
* `ComposerPortMath.kt` and `ThinkingSliderGeometry.kt` compiled with the
  cached Kotlin 2.0.21 compiler (`/opt/gradle-8.13/lib`, no Gradle run).
* `ComposerPortMathTest` + `ThinkingSliderGeometryTest`: **27 tests, OK**
  (JUnit 4.13.2 from the cached distribution). Golden numbers were produced by
  running the upstream functions from `lib/client.js` in Node, e.g. stars at
  pct 0.5/2/3/1 = 6/11/22, `fillEnd(0.5) = #7063F1`, particle Y table
  `52,84,64,16,...`, `starDelay(0, 3s) = -1.87579`.
* SVG preview rebuilt from the **Kotlin** dump of the same six positions:
  `/var/minis/workspace/tmp/ubuntu7-slider/preview.html` (visual aid only, not
  device acceptance).
* Not verified: on-device rendering/gesture feel, and
  `:app:compileGithubDebugKotlin` (needs the full Compose/Android classpath;
  the sandbox does not build Gradle).

`ComposerPortMath` stays pure Kotlin (no Compose/Android imports), covered by
`ComposerPortMathTest`; `ThinkingSliderGeometry` stays pure as well, covered by
`ThinkingSliderGeometryTest`.

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
# Native composer/Soul static checks: 116/116 PASS
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
* narrow-screen contract: `weight(1f, fill = false)` on the name, chip as the
  non-weighted sibling, `widthIn(min = 0.dp)` when compact, ellipsis, disabled
  alpha;
* flow contract (2026-10-27): the composer row has exactly ONE `Modifier.weight(1f)`
  (the model bar) and no `Spacer(modifier = Modifier.weight(1f))`; the bar has no
  trailing weight spacer and no `widthIn(max = 150.dp)` cap; `showLevel = true`
  in the call and no standalone `ComposerThinkingChip` anywhere;
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

2026-10-27 follow-up: the user builds on GitHub Actions, so no Gradle/APK run
was attempted locally for this layout change. Instead both edited Kotlin files
were parsed with the Kotlin 2.2.21 compiler front end already present in the
Gradle cache (`K2JVMCompiler -no-stdlib`, no dependency install):

```sh
java -cp <kotlin-compiler-embeddable + stdlib/reflect/trove/coroutines> \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -d /tmp/kc-out \
  .../ChatScreen.kt .../NativeComposerExtras.kt
# exit=1 — only "unresolved reference"/"cannot access built-in declaration"
# (missing Compose/Android classpath). Zero "expecting", "unexpected token"
# or other parse errors: both files parse cleanly.
```

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

## 2026-10-27 简化（本仓库）

- 顶栏只保留当前对话标题，Soul/分类副标题行删除。
- 模型名 + 思考等级并入 `+` 这一行：`+`（附件 + 全部斜杠命令）→ 模型名（点开模型选择）→ 思考等级（点开档位面板）→ 发送。
- 独立的 `/` 按钮删除（命令已在 `+` 菜单里）；语音按钮删除；速度徽标、配额点、分组标签不再显示。

## 2026-10-27 模型名 + 思考等级流动布局（本仓库）

用户要求：思考等级必须紧跟模型名右侧；短模型名时不能把等级固定在最右；长模型名尽量完整显示并
把等级往右挤，右边界到原语音输入键位置（发送键左侧），再长才省略模型名并保留等级与发送。

实现（只改 Compose 布局，无交互/状态变更）：

- `ChatScreen.kt` 的 `+` 行给 `ComposerModelThinkingBar` 传
  `modifier = Modifier.weight(1f)`，`compact = true`、`showLevel = true`。该行**只有这一个**
  加权槽位：编辑退出 pill、语言 pill、朗读 pill、发送键都是非加权子项，Row 先测量它们，条本身
  只拿到剩余宽度。
- `NativeComposerExtras.kt` 的 `ComposerModelThinkingBar` 内部：模型名 `Text` 用
  `weight(1f, fill = false)`，等级 chip 是非加权兄弟且排在名称之后 —— Row 先按固有宽度测量
  chip（宽度被预留，永不被名字挤出），再给名字分配余量；`fill = false` 让短名保持固有宽度，
  chip 紧贴其后（Row 默认 `Arrangement.Start`），多余空间留在 chip 右侧而不是把 chip 推到最右。
  长名则一路扩展到 chip 左侧边界后才出现省略号。
- 删除了原先把 chip 固定到语音键槽位的独立 `ComposerThinkingChip` 组件与调用；同时删除条内
  末尾的 `Spacer(weight(1f))` 和 compact 模式的 `widthIn(max = 150.dp)` 硬上限（长名会被截在
  150dp，等级无法被挤到右边界）。
- 等级文案：compact 模式直接显示 `level.displayName`（Off/Low/Medium/High/
  XHigh/Max/Ultra），与思考等级选择器统一首字母大写；请求里的 reasoning effort 仍用原有小写值。无障碍描述仍是中文
  `当前思考等级 <本地化名>，点击选择`，点击仍打开 `showThinkingLevelSheet`。
- 动态控件：编辑退出 pill 只在 `editingMessageId != null` 时出现，朗读 pill 只在语音面板且非
  编辑态出现（二者互斥），语言 pill 只在录音/启动识别时出现；它们都在 bar 之后、以固定间距
  排列，先于 bar 被测量，因此不会与等级 chip 重叠，只会压缩模型名。

静态契约（`scripts/tests/test_native_composer_port.py`，116 项全过）：

- `+` 行中 `Modifier.weight(1f)` 恰好出现一次，且不存在
  `Spacer(modifier = Modifier.weight(1f))`；
- 调用参数 `showLevel = true`、`modifier = Modifier.weight(1f).padding(start = 2.dp)`；
- 全文不再出现 `ComposerThinkingChip`（ChatScreen 与 NativeComposerExtras 都没有）；
- bar 内不再有 `Spacer(modifier = Modifier.weight(1f))` 与 `widthIn(max = 150.dp)`；
- bar 内 `.weight(1f, fill = false)` 恰好一次，等级 chip 代码块不含 `Modifier.weight(`；
- bar 内不出现 `horizontalArrangement` / `Arrangement.SpaceBetween` / `Arrangement.End`
  （否则短名场景会把 chip 重新推到最右）；
- 英文档位表达式与 `widthIn(min = 0.dp)` 仍在。

未做：本地 Gradle/APK（用户走 GitHub Actions 云端构建）；无真机/模拟器视觉验证。真机需重点看
360dp 宽 + 编辑 pill 或语音朗读 pill + 长模型名（如 `claude-sonnet-4-5-20250929`）组合下等级
chip 是否停在发送键左侧而不是被裁切，以及 TalkBack 读取顺序（模型名 → 等级）。
