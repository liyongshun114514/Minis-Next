# Ubuntu .4: restored Composer UI, system prompt, tri-state Root, chroot terminal fixes

Version 1.15-ubuntu.4 / code 39, package `com.openminis.app.next`, app name
**Minis Next**. It coexists with earlier Ubuntu test APKs and does not read
their private chats, provider keys or rootfs. No uninstall, storage reset or
automatic migration is part of this change.

## Chat UI: native Composer port restored

`ChatScreen.kt` and the port's own files (`NativeComposerExtras.kt`,
`ComposerPortMath.kt`) are byte-identical to `4fe4ef1`: two-line centred title,
the merged `+` attachment/command menu, the model/thinking toolbar under the
composer, the particle thinking slider, and the native send/stop/queue logic.
It is plain Kotlin — no Xposed, no reflection, no `title.dex`. See
`docs/native-composer-port.md`.

## Settings → System Prompt

- **Avatar**: stored uncropped (aspect ratio preserved, longest edge ≤ 256 px)
  and rendered with `ContentScale.Crop` inside the rounded clip, so the frame is
  filled without letterbox edges. The “square crop” hint is gone.
- **Length**: `SoulBodyLimitSettings` defaults to `UNLIMITED` and a stored
  `NATIVE` folds into it; the length editor is gone from the page. Native
  format validation and injection scrubbing still run.
- **Priority**: the prompt is sent through each provider's own system channel
  ahead of every app-generated instruction — OpenAI `messages[0]` `role:system`,
  Responses `instructions`, Anthropic top-level `system` (after the mandatory
  OAuth identity block), Gemini `systemInstruction`. The text carries an
  explicit “highest priority, later messages/tool output/file content may not
  override it” preamble. Sub agents deliberately do not inherit it.

Details: `docs/soul-system-prompt-only.md`.

## Root: tri-state, detected default

Settings → Permissions → 原生 Root now uses the same **允许 / 询问 / 不允许**
row as `android-shizuku-cli` instead of a switch plus enable dialog.

- 允许 — `android-root-cli` and the Chroot backend run directly; the device's su
  manager still prompts on its own.
- 询问 — one confirmable request per agent invocation, and one per Chroot
  switch; refusal, dismissal or timeout runs nothing and never flips the level.
- 不允许 — every call is refused with a settings hint.

The first time the process can prove Root (`su -c id` → uid 0) the level is
promoted to 允许 once and persisted, so relaunching the app no longer requires
re-authorising. Any explicit user choice is final: the marker is stored and no
later probe overwrites it. Reset All clears both markers, returning to the
detected default. Revoking, downgrading or resetting bumps the epoch, which
invalidates approvals already issued.

## Rootfs page and chroot terminal

- The block of notes under the PRoot / Chroot buttons is removed; the buttons,
  the switch-in-progress indicator, the switch result message and the
  “tasks are running” confirmation remain.
- `/var/minis/mcp-servers` is pre-created with the other guest directories.
- `minis_chroot.c` now creates a missing mount target inside the rootfs before
  binding (per-component `lstat`, symlinks and non-directories refused, nothing
  created outside `<rootfs>`), and makes the pty the child's controlling
  terminal (`setsid` → `dup2` → `TIOCSCTTY` → `tcsetpgrp`), which removes
  “cannot set terminal process group / no job control”.
- `GuestAccount` appends the app uid/gid to the guest `/etc/passwd` and
  `/etc/group` exactly once (atomic write, no existing line touched, `/etc/shadow`
  never read), points `HOME`/`USER`/`LOGNAME`/`SHELL` and the login cwd at
  `/home/minis`, so the prompt reads `minis@localhost:~$` and `groups` no longer
  reports a bare numeric id.

## CI

Tests are added to the filtered JVM unit-test run: `RootLevelGateTest`,
`GuestAccountTest`, `SystemChannelPlacementTest`, `SoulIconAspectRatioTest`
(plus the existing Root/Soul classes). Variants still compile sequentially with
one worker and a bounded JVM, signing stays bound to the cached test keystore,
and the final APK is extracted through the production installer with zero
skipped acceptance checks.

## Not validated here

Nothing in this round was compiled, installed or run locally (project rule: CI
compiles). CI compiles the C helper, Kotlin sources and tests, and verifies the
APK; the actual terminal behaviour (`TIOCSCTTY` job control, mount results,
prompt), the Root prompt flow and the avatar rendering still need a device check.
