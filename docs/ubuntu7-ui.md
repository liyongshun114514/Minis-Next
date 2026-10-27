# Minis Next 1.15-ubuntu.7

## Final scope

The character-card proposal was explicitly cancelled before delivery. All character-card storage, migration, import/export, UI, config commands, navigation, backup integrations and tests from `51ca144` / `070e3a6` are removed from the final source tree. No device files were migrated and neither cancelled build was installed by this task.

Ordinary system-prompt configuration remains the existing `SOUL.md` global body plus `SOUL_PROMPT_RULES.json` group/model additions, with the same matching, instruction scrubbing and user-approved configuration writes. No model safety controls or device permission gates are bypassed. The supplied text travels in each provider's supported native system channel, not a fabricated user/assistant message.

The generated identity section no longer repeats the global body: user text is carried once in `applicationSystemPrompt`, including after model changes/fallbacks. The UI offers Global / Custom scope switching while retaining the draft and the native editor.

## Retained UI fixes

- Thinking slider body: source-based Compose port of `Bai-2008/dsh-better-panel` at `4f798ffcecf17d52cd15aaa617124c21b9784c12`; details and platform rendering differences in `native-composer-port.md`. The surrounding sheet and actual thinking-level parameters remain native.
- Sub-agent concurrent limit: native scrollable bottom sheet, full-width choice rows (1–8), selected checkmark, standard settings spacing. Registry/queue behavior is unchanged from ubuntu.6.
- Roster text receives the full row width; reorder controls sit on a separate row. Names, descriptions and model labels can occupy two lines.

## Verification

- Pure JVM: ordinary prompt construction and exact model matching, provider placement, editor contracts, slider golden values/geometry, concurrency UI contracts.
- Static: existing composer/prompt checks and workflow/APK verifier tests.
- Cloud: complete Android/Compose compilation, selected JVM regressions, APK structure/signature checks and production Kotlin extraction of the bundled Ubuntu rootfs.

Pure/static checks do not establish device behavior. Installation, actual IME/layout/gestures, file IO and device Root remain untested here. No Root, installation or actual user-profile migration is performed by this development task.
