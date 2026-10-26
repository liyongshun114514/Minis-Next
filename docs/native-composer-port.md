# Native Composer UI port — reverted

Status: **reverted.** The chat Composer UI ported from `MinisAllInOne` has been
removed from the `ubuntu-chroot` branch; `ChatScreen.kt` is back to the native
baseline commit `6eda552`, and the port-only sources were deleted. Static guard:
`python3 scripts/tests/test_native_composer_port.py` → **46/46 PASS**. No Kotlin
compile, APK build or device run was performed for this revert (see §7).

## 1. Baseline and history

| Item | Value |
|---|---|
| Repository / branch | `/var/minis/shared/openminis-ubuntu`, `ubuntu-chroot` |
| **Original (pre-port) commit** | **`6eda552`** — `Merge pull request #491 from OpenMinis/v1.15` |
| First port commit | `0dfd03d` — `feat(android): Ubuntu guest, selectable chroot, native Root and Composer port` |
| Follow-up port commit | `54fc58f` — `fix(android): accept bundled tar root and persist native composer UI` |
| Intermediate commit | `289221d` — test-only (`test_ubuntu_bootstrap.py`); it **already contains** the ported UI, so it is *not* an original reference |
| Provenance of the UI | `MinisAllInOne` 1.0.2-test (GPL-3.0), reference copy at `/var/minis/shared/MinisAllInOne` |

`git log --follow` for `ui/chat/ChatScreen.kt` shows exactly two commits after
the baseline: `0dfd03d` and `54fc58f`. Both are the Composer port, so restoring
the file to `6eda552` is a precise revert of the ported UI rather than a
whole-commit rollback — `0dfd03d` also contains Ubuntu/chroot/Root/Soul work,
none of which is touched.

## 2. Reverted surfaces (port → original)

| Surface | Ported behaviour (removed) | Original behaviour (restored) |
|---|---|---|
| Session title | `maxLines = 2`, `lineHeight = 20.sp`, `textAlign = TextAlign.Center` | `maxLines = 1`, `lineHeight = 19.sp`, start-aligned; rename tap unchanged (`editingSession = viewModel.loadSessionEntity()`) |
| Top-bar model / group subtitle | Deleted, with a comment claiming it moved to the composer | Two-line Column: group/model + chevron row, `provider · model` row, quota dot, speed-tier badge, thinking-level badge (all native callbacks kept) |
| Top-bar height | `expandedHeight = 80.dp` | `expandedHeight = 68.dp` |
| Bottom toolbar | `ComposerModelThinkingBar(...)` (model chip, group label, thinking chip, quota dot, speed badge) injected under the input field | Absent; model/thinking live only in the top bar |
| `+` menu | `ComposerMenuHeader("附件")`, `MinisMenuDivider()`, `ComposerMenuHeader("命令")`, `ComposerCommandMenuRows(...)` | Native attachment items only (Take Photo / Choose Photos & Videos / Add File); slash commands stay on the native `/` entry |
| Thinking sheet | Particle `ComposerEffortSlider` + hint text + explicit OK button; selecting did not dismiss | Native row list (`Icons.Default.Lightbulb`, check mark on the active level); `onSelect` calls `viewModel.setThinkingLevel(level)` **and** closes the sheet |
| Slider math | `ComposerPortMath` (22 stars, phases, ARGB mixing) | Deleted with the slider |

Deleted files (port-only, no remaining callers):

* `src/android/app/src/main/java/com/openminis/app/ui/chat/NativeComposerExtras.kt`
* `src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPortMath.kt`
* `src/android/app/src/test/java/com/openminis/app/ui/chat/ComposerPortMathTest.kt` (unit test of the deleted math; would not compile otherwise)

## 3. Preserved (deliberately untouched)

* Native send / stop / queue state machine (`ComposerSendButton`, `ComposerStopButton`, `val showStop = sessionBusy && !hasContent`, `canActivate`), attachments, slash dispatch (`viewModel.executeSlashCommand`, `showSlashMenuOverInput`), model picker, session rename, search, sharing.
* Ubuntu/chroot/PRoot guest, terminal, Root Bridge, offload permissions (`OffloadPermissionDialog` root-confirm layout), rootfs management.
* SOUL.md and its length policy (`SoulStore`, `SoulLimitUi`, `SoulSettingsScreen`) — not part of this revert and not modified here.
* Gradle, CI workflows, version numbers, shared build configuration — no edits.

## 4. What the static guard asserts

`scripts/tests/test_native_composer_port.py` (rewritten from a port-presence
contract into a revert guard):

1. The three port-only files do not exist.
2. `ChatScreen.kt` is byte-identical to `git show 6eda552:…/ChatScreen.kt` (skipped with a `NOTE` only when the commit is unavailable, e.g. a depth-1 clone).
3. No port symbol survives in `ChatScreen.kt`: `ComposerModelThinkingBar`, `ComposerEffortSlider`, `ComposerCommandMenuRows`, `ComposerMenuHeader`, `ComposerPortMath`, `NativeComposerExtras`, `T-native-composer`, `groupLabel = bottomGroupLabel`.
4. No `*.kt` under `src/android/app/src` calls the removed composables/math.
5. Original top bar is back: single-line 19sp title without centre alignment, `expandedHeight = 68.dp`, `provider · model` subtitle, `ThinkingLevelBadge(` call, group fallback, rename callback.
6. Native attachment items and slash dispatch are present; `showAttachMenu = true` still opens the native menu.
7. Native thinking sheet: setter, row list, dismiss path.
8. Native send/stop/queue state intact.
9. No Xposed / reflection / DEX-scanning / `title.dex` / RootBridge / Shizuku machinery in `ChatScreen.kt`.
10. Repository `LICENSE` is still GPL-3.0.

Soul assertions that used to be bundled into this script were removed: Soul is
owned by a separate change (and was being edited concurrently in the same
working tree), so asserting its UI here produced false failures unrelated to
the Composer revert. Soul safety coverage remains in
`SoulBodyLimitPolicyTest` and the Soul change itself.

## 5. Revert evidence (commands and results)

```
$ git checkout 6eda552 -- src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt
$ rm -f .../NativeComposerExtras.kt .../ComposerPortMath.kt .../ui/chat/ComposerPortMathTest.kt

$ git diff 6eda552 -- src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt | wc -l
0
```

```
$ rg -n 'ComposerModelThinkingBar|ComposerEffortSlider|ComposerCommandMenuRows|ComposerMenuHeader|ComposerPortMath|NativeComposerExtras|T-native-composer' src/android
(no matches)
```

```
$ python3 scripts/tests/test_native_composer_port.py
Native composer revert static checks: 46/46 PASS
```

`git status --short` for this task:

```
 M src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt
 D src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPortMath.kt
 D src/android/app/src/main/java/com/openminis/app/ui/chat/NativeComposerExtras.kt
 D src/android/app/src/test/java/com/openminis/app/ui/chat/ComposerPortMathTest.kt
 M scripts/tests/test_native_composer_port.py
 M docs/native-composer-port.md
```

## 6. Scope notes

* No commit, no push (`HEAD` remains `4fe4ef1`).
* No dependency installation, no `su`/Shizuku, no APK install, no further delegation.
* Only the five files listed above were edited/deleted; because `0dfd03d`
  mixed the Composer port with unrelated Ubuntu/Root/Soul work, no
  whole-commit `git revert` was used.
* Because the baseline `ChatScreen.kt` uses `OffloadPermissionManager.respondToAndroidPermission(Boolean)`
  and `PermissionRequest(...)` (the latter gained a defaulted `singleUse`
  field later), the restored file stays source-compatible with the current
  permission layer. `ChatViewModel.kt` changed only prompt text in `0dfd03d`,
  so no signature is affected.

## 7. Not verified here

* No Kotlin compilation, no Gradle task, no APK build: the sandbox has no
  Android SDK/Gradle cache for this module, and the task forbids installing
  dependencies. Source compatibility was checked by reading the only changed
  dependency (`OffloadPermissionManager`); a CI `assembleDebug` remains the
  authoritative compile check.
* No emulator/device run: the restored layout (one-line title, top-bar
  subtitle, 68dp bar) was **not** visually compared against the upstream
  native app.
* The concurrent, out-of-scope edit to `ui/settings/SoulSettingsScreen.kt`
  visible in `git status` belongs to another task and is not part of this
  revert.
* `docs/UBUNTU_3_COMPATIBILITY.md` still says "See `native-composer-port.md`
  for the current source-level feature matrix"; that file is outside this
  task's edit set. This document now states the port was reverted, which
  supersedes the old matrix.
