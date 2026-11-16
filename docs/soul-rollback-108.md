# SOUL rollback to commit 108

Requested baseline: `d1110b83c66fd4db78637d1332f14dc2a24108e5` (the 108th commit in `git rev-list --reverse HEAD` at original head `6270bd9`). The user explicitly chose **complete SOUL behavior rollback**, not a whole-repository reset.

## SOUL scope

- Restore baseline `SoulStore.kt`, `SoulPromptRules.kt`, `SoulIcon.kt`, `SoulLimitUi.kt`, `SoulPromptRuleEditor.kt`.
- Restore the baseline single-global-file `SoulSettingsScreen.kt`, including Global / Custom group-and-model rules. Apply only the immediately-following `5d5de46` compilation corrections: missing `collectAsState` / `TextOverflow` imports and the missing unnamed-rule label.
- Restore SOUL setting strings and direct `soul` route. Remove multi-profile SOUL selection, profile library, prompt hub and built-in prompt editor navigation.
- Restore SOUL loading, validation, scoped-rule assembly and config writes. No workspace AGENTS injection/import introduced after the baseline. Non-SOUL runtime prompt template infrastructure remains for unrelated features.
- No device SOUL.md, custom profile files, selections or user memory are deleted. Previously created `memory/souls/` files stay on disk but are not read/injected by this build. No application downgrade or database reset.

## Features deliberately retained

- Model-group custom avatars, catalog icons, manual selection priority, persistence and configuration/backup fields.
- Transparent brand icons; Gemini uses the colorful mark.
- Chat identity uses model-group name/avatar rather than SOUL metadata. Message ownership must survive group switching, streaming completion and reload; a historical message must not be relabeled using the current picker selection.
- The missing optional Claude OAuth identity build configuration must not fail ordinary sends.
- All unrelated post-baseline capabilities, including compaction, browser/tool handling, native Root permission gates and provider/balance changes.

## Verification

Final local verification for version **1.7.1 / code 59** is saved in `docs/evidence/soul-rollback-108/`:

- All 820 production Kotlin and 4 Java sources typecheck with the cached dependency classpath; the final source snapshot still matches the working tree. This runner uses compile-only stubs for generated Android/Room and gomobile interfaces; it does not run KSP, dex or APK packaging.
- Focused JUnit: **244 passed**, no source/test failure classes. Another **7** native-graphics tests are blocked because Robolectric NATIVE does not support this Linux/aarch64 host. The runner correctly exits nonzero for this gap.
- The passing tests include scoped SOUL rules, message ownership codecs/resolution, real SQLite column migration/re-upgrade and backup field round trips. The new v15 schema JSON is a provisional schema description, not a KSP export verified locally; CI must generate/validate the Room implementation. SQL tests do not prove that an older released binary can open a downgraded database.
- Affected Python checks: **30 passed**. The full 227-test Python suite also exposes an existing unrelated assertion in `test_prepare_android_ubuntu` expecting the old bare-image delegation; the current script requires a preinstalled toolset. This task leaves that behavior unchanged.
- Prompt catalog validation and XML parsing pass. No APK build, installation, real API send or device UI test was performed locally.

No device SOUL or user data was modified during verification.
