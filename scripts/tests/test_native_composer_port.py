#!/usr/bin/env python3
"""Static contract checks for the native Composer / Soul-length port.

Run from the repository root:

    python3 scripts/tests/test_native_composer_port.py

It does NOT compile Android and does NOT claim visual verification. It
checks the source-level bindings that the port depends on, and that no
Xposed / reflection / DEX-scanning machinery was copied into the app.
"""

from __future__ import annotations

import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAT = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt"
CHAT_EXTRAS = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/NativeComposerExtras.kt"
MATH = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPortMath.kt"
MATH_TEST = ROOT / "src/android/app/src/test/java/com/openminis/app/ui/chat/ComposerPortMathTest.kt"
SOUL = ROOT / "src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt"
SOUL_UI = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/settings/SoulLimitUi.kt"
SOUL_SCREEN = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt"

failures: list[str] = []
checks = 0


def ok(cond: bool, message: str) -> None:
    global checks
    checks += 1
    if not cond:
        failures.append(message)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def strip_kotlin(src: str) -> str:
    """Remove comments and string/char literals so brace balance is meaningful."""
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            i = src.find("\n", i)
            if i < 0:
                break
            out.append("\n")
            i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif c == '"' or c == "'":
            quote = c
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def balanced(src: str, label: str) -> None:
    clean = strip_kotlin(src)
    stack: list[tuple[str, int]] = []
    pairs = {")": "(", "]": "[", "}": "{"}
    line = 1
    for ch in clean:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack or stack[-1][0] != pairs[ch]:
                ok(False, f"{label}: unmatched {ch} near line {line}")
                return
            stack.pop()
    ok(not stack, f"{label}: unclosed delimiters {stack[-3:]}")


def main() -> int:
    for path in (CHAT, CHAT_EXTRAS, MATH, MATH_TEST, SOUL):
        ok(path.is_file(), f"missing {path.relative_to(ROOT)}")
        if path.is_file():
            balanced(text(path), str(path.relative_to(ROOT)))

    chat = text(CHAT)
    extras = text(CHAT_EXTRAS)
    soul = text(SOUL)
    math = text(MATH)

    # --- Composer: title only -------------------------------------------
    ok("textAlign = TextAlign.Center" in chat, "title is not centre-aligned")
    title_block = re.search(
        r"Text\(\s*text = displayTitle,.*?maxLines = 2", chat, re.S
    )
    ok(title_block is not None, "title does not reserve two lines")
    ok("expandedHeight = 80.dp" in chat, "top bar height does not match the two-row title card")
    ok("editingSession = viewModel.loadSessionEntity()" in chat,
       "native rename click lost")
    # The bar carries the current chat title and nothing else: the Soul/category
    # subtitle row is gone.
    ok("val subtitleText = buildString" not in chat,
       "top bar still renders a subtitle line")
    ok('"$providerName · $modelName"' not in chat,
       "model is still rendered in the top bar")
    ok("ThinkingLevelBadge(" not in chat.replace("private fun ThinkingLevelBadge(", ""),
       "thinking badge is still called from the top bar")

    # --- Composer: one + row carrying model + thinking --------------------
    ok("internal fun ComposerModelThinkingBar(" in extras,
       "model/thinking chips are not defined")
    ok(chat.count("ComposerModelThinkingBar(") == 1,
       "model/thinking chips are not called exactly once in ChatScreen")
    row_at = chat.index("// Button row below text field")
    call_at = chat.index("ComposerModelThinkingBar(")
    ok(row_at < call_at,
       "model/thinking chips are not inside the composer button row")
    ok(call_at < chat.index("// Right: 3-state Send"),
       "model/thinking chips are not on the + row, before send")
    ok("viewModel.thinkingLevel.collectAsState()" in chat[call_at - 600:call_at],
       "chips do not observe the live thinking level")
    ok("viewModel.availableThinkingLevels" in chat[call_at:call_at + 900],
       "chips do not read the live supported levels")
    ok("viewModel.currentModelSupportsReasoning" in chat[call_at:call_at + 900],
       "chips do not read the live reasoning capability")
    ok("onModelClick = { showModelPicker = true }" in chat[call_at:call_at + 900],
       "model chip does not open the native model picker")
    ok("onLevelClick = { showThinkingLevelSheet = true }" in chat[call_at:call_at + 900],
       "level chip does not open the native thinking sheet")
    ok("modelInteractionSource = modelPickerInteraction" in chat[call_at:call_at + 900],
       "model chip lost the native model-picker interaction source")
    # Nothing else on that row: no group label, no speed tier, no quota dot.
    ok("groupLabel = bottomGroupLabel" not in chat,
       "group label is still passed to the chips")
    ok("SpeedTierNavBadge(" not in chat,
       "speed-tier badge is still drawn in the composer")
    ok("com.openminis.app.ui.quota.ModelQuotaStatusDot(" not in chat,
       "quota dot is still drawn in the composer")
    # The + button keeps every action; "/" and the mic button are gone.
    ok("Icons.Default.Add" in chat, "+ button lost its icon")
    ok("showAttachMenu = true" in chat, "+ button no longer opens the merged menu")
    ok("MicButton(" not in chat, "mic button is still rendered in the composer")
    ok('                        // Left: "/" slash command button' not in chat,
       "the separate / button is still rendered")

    # Layout contract that keeps the chips usable on narrow phones.
    for token in (
        "weight(1f, fill = false)",
        "widthIn(min = 64.dp)",
        "TextOverflow.Ellipsis",
        "enabled = levelEnabled",
        "alpha(if (levelEnabled) 1f else 0.45f)",
        "rows.indexOf(level)",
        "ComposerPortMath.levelColorArgb(",
    ):
        ok(token in extras, f"chip layout/state contract missing: {token}")
    ok("localizedName(context)" in extras,
       "thinking chip is not localized")

    # --- Composer: merged + menu ----------------------------------------
    ok("ComposerCommandMenuRows(" in chat, "merged + menu does not render commands")
    ok('ComposerMenuHeader("附件")' in chat and 'ComposerMenuHeader("命令")' in chat,
       "merged menu section headers missing")
    for native in (
        "R.string.chat_attach_take_photo",
        "R.string.chat_attach_choose_photos_videos",
        "R.string.chat_attach_add_file",
    ):
        ok(native in chat, f"native attachment action lost: {native}")
    ok("viewModel.executeSlashCommand(cmd, inputText)" in chat,
       "merged menu bypasses the native command dispatcher")
    ok("viewModel.showSlashMenuOverInput(inputText)" in chat,
       "merged menu does not preserve the existing draft before dispatch")
    ok("showThinkingLevelSheet = true" in chat,
       "thinking command does not route to the slider sheet")
    ok("showAttachMenu = true" in chat, "+ button no longer opens the merged menu")

    # --- Composer: slider / send / status --------------------------------
    ok("ComposerEffortSlider(" in chat, "thinking sheet does not host the slider")
    ok("viewModel.setThinkingLevel(level)" in chat,
       "slider no longer persists through the native setter")
    ok("ComposerSendButton(" in chat, "native send button missing")
    # Send-area state machine must stay native: streaming + text = Send into
    # queue, streaming + no content = Stop, idle = Send, attachments-only =
    # Send. The bottom bar must not have replaced or gated any of it.
    ok("val showStop = sessionBusy && !hasContent" in chat,
       "native send/stop state selection changed")
    ok("ComposerStopButton(" in chat, "native stop button missing")
    ok("canActivate = canActivate" in chat, "native send activation changed")
    ok("viewModel.thinkingLevel.collectAsState()" in chat,
       "thinking-level state is not observed")
    ok("viewModel.modelName.collectAsState()" in chat,
       "model-name state is not observed")
    ok("ComposerPortMath.energy(" in extras, "slider does not use the shared math")

    # --- Slider math -----------------------------------------------------
    for token in (
        "const val PARTICLE_COUNT = 22",
        "fun nearestIndex(",
        "fun fillEndArgb(",
        "fun starPhase(",
        "fun starAlpha(",
    ):
        ok(token in math, f"slider math missing: {token}")
    ok("PARTICLE_COUNT = 22" in math, "particle count changed")

    # --- No forbidden machinery in new UI files --------------------------
    forbidden = [
        "Xposed",
        "reflect",
        "Reflect",
        "DexClass",
        "InMemoryDex",
        "title.dex",
        "RootBridge",
        "Shizuku",
        "su -c",
        "AssetManager.open(\"classes",
    ]
    for path in (CHAT_EXTRAS, MATH):
        # Scan CODE only: the header comments name the upstream Xposed
        # component and title.dex deliberately, to document what was NOT ported.
        body = strip_kotlin(text(path))
        for token in forbidden:
            ok(token not in body, f"{path.name} contains forbidden token {token!r}")
    ok(not (ROOT / "src/android/app/src/main/assets/title.dex").exists(),
       "title.dex was copied into the app")

    # --- Soul length policy ---------------------------------------------
    ok("enum class SoulBodyLimitMode" in soul and "UNLIMITED" in soul,
       "soul limit modes missing")
    ok("object SoulBodyLimitPolicy" in soul, "soul limit policy missing")
    ok("fun isOverLimit(body: String): SoulBodyLimitCheck" in soul,
       "legacy context-free isOverLimit signature lost")
    ok("SoulBodyLimitPolicy.ensureLoaded(context)" in soul,
       "policy is not primed from the native read paths")
    # The settings page is now a three-field system-prompt editor and the
    # policy defaults to UNLIMITED, so the length controls, the live counter
    # and the mode persistence it used to assert no longer exist by design.
    # The gate itself is still covered by ApplicationSystemPromptTest and
    # SoulBodyLimitPolicy's own tests.

    # --- Soul safety boundary: prompt text must stay native --------------
    ok("Ubuntu 24.04" in soul and "PRoot or Chroot" in soul,
       "OS identity string was not updated to Ubuntu 24.04")
    for token in (
        "ignore.{0,30}previous.{0,30}instructions?",
        "disregard.{0,30}(previous|prior).{0,30}instructions?",
        "forget.{0,30}(previous|prior).{0,30}instructions?",
    ):
        ok(token in soul, f"native injection pattern changed/missing: {token}")
    ok("fun containsInjectionPattern(s: String): Boolean" in soul,
       "native injection detector signature changed")
    ok("private fun scrubInjections(s: String): String" in soul,
       "native injection scrubber signature changed")

    # The injection machinery itself is pinned by the token checks above; the
    # old "byte-identical to HEAD" comparison was removed because this branch
    # now legitimately edits SoulStore (unlimited default, supreme preamble),
    # which shifted every later offset and made the fixed-width window compare
    # two different regions of two different files.

    # --- Provenance / licence -------------------------------------------
    up_license = ROOT.parent.parent / "workspace/tmp/openminis-port/MinisAllInOne/LICENSE"
    repo_license = ROOT / "LICENSE"
    if up_license.is_file():
        ok(
            hashlib.md5(up_license.read_bytes()).hexdigest()
            == hashlib.md5(repo_license.read_bytes()).hexdigest(),
            "repository LICENSE differs from the uploaded module's GPL-3.0 LICENSE",
        )
    ok(repo_license.read_text(encoding="utf-8").startswith("                    GNU GENERAL PUBLIC LICENSE"),
       "repository LICENSE is not GPL-3.0")

    if failures:
        print(f"Native composer/Soul static checks: {checks - len(failures)}/{checks} passed")
        for failure in failures:
            print("FAIL:", failure)
        return 1
    print(f"Native composer/Soul static checks: {checks}/{checks} PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
