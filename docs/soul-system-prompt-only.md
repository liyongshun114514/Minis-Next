# Settings → System Prompt (native, no Xposed)

The Soul page is the app's system-prompt setting. It is implemented natively in
this fork, so the Minis All-in-One module is no longer needed for it.

## What the page contains

`ui/settings/SoulSettingsScreen.kt` renders exactly three editable things:

| Control | Behaviour |
| --- | --- |
| Avatar | Tap opens the existing menu: emoji / image / use default. Images are stored **uncropped** (aspect preserved, longest edge ≤ 256 px) and drawn with `ContentScale.Crop` inside the rounded clip, so the frame is filled edge to edge. |
| Name | Single-line `OutlinedTextField`, written to SOUL.md `name`. |
| System prompt | The Markdown body of SOUL.md, previously labelled “Personality Prompt”. |

Plus app-bar **Save** (enabled when dirty), the back arrow, the unsaved-changes
dialog and the save-error dialog. Titles: `soul_settings_title` /
`settings_soul` are “System Prompt / 系统提示词” in all 23 resource sets.

Removed from the page: preview card, `Style` field, `Language` picker, the
length-mode editor, `Restore Default` and the always-on character counter.
`style`, `lang` and `emoji` are still read and written back unchanged, so an
older SOUL.md round-trips without losing metadata.

## Length

`SoulBodyLimitSettings.mode` now defaults to `UNLIMITED`, and a stored `NATIVE`
from an older build is folded into `UNLIMITED`. Only an explicit `CUSTOM` still
counts characters, and the page no longer offers that editor. Nothing else was
relaxed: native format validation, `containsInjectionPattern` and
`scrubInjections` still run on the body.

## Priority

`SystemPromptBuilder.applicationPrompt()` produces “supreme preamble + user
body”. The result travels in `LLMProvider.applicationSystemPrompt`, which every
provider places **first** in its own native system channel:

| Provider call | Field |
| --- | --- |
| OpenAI Chat Completions | `messages[0]` with `role: "system"`, ahead of the app-generated system message and every turn |
| OpenAI Responses | start of `instructions` (Codex image path too) |
| Anthropic | first `system` text block, after the mandatory OAuth identity block |
| Gemini | start of `systemInstruction.parts[0].text` |

It is a separate field rather than a prefix concatenated into `systemPrompt`
because the callers replace `systemPrompt` wholesale on the OAuth path and on a
model switch; folding the two would silently reorder or drop the user's prompt.
A sub agent (`helperConfig != null`) intentionally does not inherit the parent's
application prompt: it has its own `HelperRunner.identitySection` and no
settings surface.

## Verification

Local (no compilation, per project rule): `soul_static_check.py` bracket/symbol
audit, `soul_wire_sim.py` reproduces the four assembled bodies, and
`scripts/tests/test_native_composer_port.py` passes 98/98. Kotlin compilation,
JUnit and the real wire format are verified by CI.
