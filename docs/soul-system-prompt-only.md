# Soul page is now the system-prompt setting

Status: UI change only. The agent layer (`SoulStore.kt` — including the new
`render(file)` and the Application's system-prompt label — `SoulMDParser`,
length policy, prompt assembly) is untouched by this change. Nothing here
changes prompt assembly, layering or validation.

## What the page contains now

`ui/settings/SoulSettingsScreen.kt` renders exactly three editable things:

| Control | Behaviour |
| --- | --- |
| Avatar | Tap opens the existing menu: emoji / image / use default. Same `SoulIcon` encode path, same rejection dialogs. |
| Name | Single-line `OutlinedTextField`, written to SOUL.md `name`. |
| System prompt | The `DocumentEditorField` previously labelled "Personality Prompt" / 人格提示词, now labelled "System Prompt" / 系统提示词. Still the Markdown body of SOUL.md. |

Plus the controls needed to leave the screen safely: app-bar **Save**
(disabled until dirty and not over the stored length cap), the back arrow,
the unsaved-changes discard dialog, and the save-error dialog.

Titles: the page's app-bar title (`soul_settings_title`) and the Settings row
(`settings_soul`) are now "System Prompt" / 系统提示词 (localized in all 23
base+locale resource sets).

## Removed from the page (not from the data model)

- The preview card that repeated the name plus the `style` subtitle.
- The `Style` text field and the `Language` (Auto / 中文 / English) picker.
- The "Soul body length" section (`SoulBodyLimitControls`: mode chips and the
  custom `chineseCharLimit` / `englishWordLimit` fields).
- "Restore Default" and its confirmation dialog.
- The always-on `N / cap` counter. Its content is now shown **only** while the
  body is over the stored cap — the same message that explains why Save is
  disabled.

## Data compatibility

Load → edit → save still goes through `SoulStore.load` / `SoulStore.save` with
a complete `SoulFile`, so nothing invisible is dropped:

- `name` — edited.
- `icon` — edited (emoji literal or `data:image/png;base64,…`).
- `body` — edited.
- `emoji` — **preserved verbatim** in `preservedEmoji` and written back; it is
  never displayed (identity glyph is locked to ✨) and never reset.
- `style`, `lang` — **preserved verbatim**: read into state on load, used in
  both the dirty-check baseline and the saved file, just no longer editable.
  A value set by an older build or another device therefore round-trips.

The dirty check is still a value comparison against the loaded `SoulFile`, so
opening the page with old metadata does not mark it dirty, and Save cannot
rewrite the file with defaults for fields the page no longer shows.

Length validation is unchanged: `SoulStore.isOverLimit(body, limitSettings)` is
still loaded from the persisted `SoulBodyLimitPolicy` (never written from this
page) and still gates the Save button. Only the mode/cap *editor* is gone.

## Strings

`res/values*/strings.xml` (23 files):

- `settings_soul`, `soul_settings_title`: → "System Prompt" (localized).
- `soul_section_personality`: "Personality Prompt" → "System Prompt"
  (localized).
- `settings_soul_subtitle`: → "System prompt and identity (SOUL.md)"
  (localized).
- `soul_body_placeholder`, `soul_personality_footer`: reworded to describe the
  system prompt; the footer no longer advertises the removed length controls.

Keys kept but no longer referenced by the screen (harmless, still translated):
`soul_field_style`, `soul_field_lang`, `soul_lang_*`, `soul_length_indicator`,
`soul_count_*`, `soul_restore_*`.

## Tests

- `src/android/app/src/test/.../ui/settings/SoulLangButtonWrapTest.kt` asserted
  the removed language row — deleted, replaced by
  `SoulSettingsPageContractTest.kt` (three-item page contract: one prompt
  editor, name field, avatar menu; the removed controls stay removed;
  style/lang/emoji round-trip; the length gate still guards Save).
- `scripts/tests/test_native_composer_port.py` no longer asserts the Soul
  length UI (it now covers the native-composer revert only); it passes after
  this change.

## Known follow-up (outside this change's file boundary)

`ui/settings/SoulLimitUi.kt` has no production or test reference any more. It is
kept in this change because `docs/UBUNTU_FORK.md` (line 27) and
`docs/native-composer-port.md` (line 48) still describe `SoulLimitUi` as part of
the Soul stack; delete the file and those two doc lines together.
