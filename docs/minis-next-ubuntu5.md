# Minis Next 1.15-ubuntu.5

## Changes

- The chroot backend executes approved commands as real `uid=0`, rather than dropping user commands to the application UID. PRoot keeps its separate emulated-root behavior.
- Native Root and chroot share one persisted permission: **不允许 / 每次询问 / 完全允许**. Availability detection is not permission. A one-off approval does not turn into permanent approval.
- The composer `+` menu has **权限** immediately below **添加文件**. Changes are drafts until **保存**, and the menu uses the same setting as Settings → Permissions → Native Root.
- Non-interactive chroot dispatch uses a fresh command process even when the user selected the warm-shell strategy. A real interactive terminal requests authorization for that session, and commands subsequently typed into that terminal fall within its approved scope.
- The thinking slider has English mode names, shared tick/label/hit-test geometry, a thumb inside the pill, and state-driven settle/particle animation. The redundant drag/release hint is removed.
- Native Root settings match the application appearance and no longer show the old “授权期间直接执行” row.
- System Prompt settings have **全局 / 自定义** tabs. Custom rules append in stable order: global → matching group rules → matching model rules. Model matching uses provider-instance ID plus model ID, not display name. Disabled, missing-target and nonmatching rules do not apply globally.
- Existing `SOUL.md` stays in place. Scoped rules are stored beside it in `minis-global/memory/SOUL_PROMPT_RULES.json`, so memory backup/restore includes them; restore invalidates the runtime rules cache.

## Permission and compatibility notes

Real Root can modify device data. `chroot` and a private mount namespace are **not** a security boundary or a guarantee that Root commands cannot affect the host. The device's superuser manager independently controls actual `su` authorization. The app does not bypass that authorization or change SELinux policy.

Declining, cancelling or timing out an Ask-every-time request does not execute its command and does not change the stored level. Fully allowed is a persistent decision explicitly selected by the user. Existing interactive sessions may retain their approved scope until stopped; stop them before revoking permission.

A chroot-installed package can create files owned by real Root in the shared rootfs. PRoot remains application-UID-backed, so switching between the two is not a promise of identical file-ownership behavior.

The normal chat agent resolves scoped rules for the actual selected model/group at each request. The global prompt and rule list are snapshotted at turn start; settings edits therefore apply on the next turn. Existing helper-agent/title-generation prompt isolation is preserved rather than silently replacing their task-specific prompts.

## Validation scope

Local checks cover JVM decision/geometry tests, source-wiring contracts, resource XML, selected real Android/Compose type probes and native component compilation/ELF checks. These do not establish device `su`, package installation, overlay appearance, interactive job control or APK runtime behavior. The complete application must also pass GitHub Actions compilation, tests, APK verification and the production rootfs extraction check. Installation is a separate user action.
