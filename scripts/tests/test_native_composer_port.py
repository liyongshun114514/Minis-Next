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
    for path in (CHAT, CHAT_EXTRAS, MATH, MATH_TEST, SOUL, SOUL_UI, SOUL_SCREEN):
        ok(path.is_file(), f"missing {path.relative_to(ROOT)}")
        if path.is_file():
            balanced(text(path), str(path.relative_to(ROOT)))

    chat = text(CHAT)
    extras = text(CHAT_EXTRAS)
    soul = text(SOUL)
    soul_ui = text(SOUL_UI)
    soul_screen = text(SOUL_SCREEN)
    math = text(MATH)

    # --- Composer: title + subtitle --------------------------------------
    ok("textAlign = TextAlign.Center" in chat, "title is not centre-aligned")
    title_block = re.search(
        r"Text\(\s*text = displayTitle,.*?maxLines = 2", chat, re.S
    )
    ok(title_block is not None, "title does not reserve two lines")
    ok("expandedHeight = 80.dp" in chat, "top bar height does not match the two-row title card")
    ok("editingSession = viewModel.loadSessionEntity()" in chat,
       "native rename click lost")
    ok("val subtitleText = buildString" in chat and "topBarSoul.name" in chat,
       "top-bar subtitle is not the Soul/category line")
    ok('"$providerName · $modelName"' not in chat,
       "model is still rendered in the top bar (duplicate with the bottom bar)")
    ok("ThinkingLevelBadge(" not in chat.replace("private fun ThinkingLevelBadge(", ""),
       "thinking badge is still called from the top bar (duplicate with the bottom bar)")

    # --- Composer: bottom model + thinking toolbar ------------------------
    # Definition first...
    ok("internal fun ComposerModelThinkingBar(" in extras,
       "bottom model/thinking toolbar is not defined")
    # ...then REAL invocation evidence: exactly one call, inside the composer,
    # between the input area and the native +/mic/send row. A definition alone
    # must never satisfy this contract again.
    ok(chat.count("ComposerModelThinkingBar(") == 1,
       "bottom model/thinking toolbar is not called exactly once in ChatScreen")
    if "ComposerModelThinkingBar(" in chat:
        call_at = chat.index("ComposerModelThinkingBar(")
        ok(chat.index("// ─── Input area") < call_at,
           "toolbar call is outside the composer input area")
        ok(call_at < chat.index("// Button row below text field"),
           "toolbar call is not in the composer's bottom region")
        ok("viewModel.thinkingLevel.collectAsState()" in chat[call_at - 1600:call_at],
           "toolbar does not observe the live thinking level")
        ok("viewModel.availableThinkingLevels" in chat[call_at:call_at + 900],
           "toolbar does not read the live supported levels")
        ok("viewModel.currentModelSupportsReasoning" in chat[call_at:call_at + 900],
           "toolbar does not read the live reasoning capability")
        ok("onModelClick = { showModelPicker = true }" in chat[call_at:call_at + 900],
           "toolbar model chip does not open the native model picker")
        ok("onLevelClick = { showThinkingLevelSheet = true }" in chat[call_at:call_at + 900],
           "toolbar level chip does not open the native thinking sheet")
        ok("modelInteractionSource = modelPickerInteraction" in chat[call_at:call_at + 1500],
           "toolbar lost the native model-picker interaction source")
        ok("groupLabel = bottomGroupLabel?.takeIf" in chat[call_at:call_at + 1800],
           "group binding is not passed to the bottom toolbar")
        ok("selectedGroupName.ifEmpty" in chat[call_at - 1200:call_at]
           and "selectedGroupId != null" in chat[call_at - 1200:call_at],
           "group binding fallback/resolve visibility was dropped")
    ok("groupLabel: String? = null" in extras,
       "bottom toolbar cannot show the bound group")
    ok("Icons.Default.Layers" in extras and "contentDescription = \"当前分组 $groupLabel\"" in extras,
       "group label is not drawn with the native Layers icon / a11y label")
    ok("widthIn(max = 56.dp)" in extras,
       "group label is not capped for narrow screens")
    # Role status that used to live on the top bar must survive the move.
    ok("SpeedTierNavBadge(" in chat[chat.index("ComposerModelThinkingBar("):]
       if "ComposerModelThinkingBar(" in chat else False,
       "speed-tier badge was dropped instead of moving to the bottom bar")
    ok("com.openminis.app.ui.quota.ModelQuotaStatusDot(" in chat[
        chat.index("ComposerModelThinkingBar("):] if "ComposerModelThinkingBar(" in chat else False,
       "quota dot was dropped instead of moving to the bottom bar")

    # Layout contract that keeps the bar usable on narrow phones.
    for token in (
        "weight(1f, fill = false)",
        "widthIn(min = 64.dp)",
        "TextOverflow.Ellipsis",
        "enabled = levelEnabled",
        "alpha(if (levelEnabled) 1f else 0.45f)",
        "rows.indexOf(level)",
        "ComposerPortMath.levelColorArgb(",
    ):
        ok(token in extras, f"bottom toolbar layout/state contract missing: {token}")
    ok("localizedName(context)" in extras,
       "toolbar level chip is not localized")

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
    ok("enum class SoulBodyLimitMode { NATIVE, UNLIMITED, CUSTOM }" in soul,
       "soul limit modes missing")
    ok("object SoulBodyLimitPolicy" in soul, "soul limit policy missing")
    ok("if (settings.mode == SoulBodyLimitMode.UNLIMITED) return SoulBodyLimitCheck.Ok" in soul,
       "unlimited mode does not bypass only the length gate")
    ok("fun isOverLimit(body: String): SoulBodyLimitCheck" in soul,
       "legacy context-free isOverLimit signature lost")
    ok("SoulBodyLimitPolicy.ensureLoaded(context)" in soul,
       "policy is not primed from the native read paths")
    ok("SoulBodyLimitPolicy.save(context, next)" in soul_screen,
       "settings screen does not persist the mode")
    ok("soulBodyCountTextAndroid(body, limitSettings)" in soul_screen,
       "counter is not driven by the selected mode")
    ok('"$chars / ∞ chars"' in soul_screen and '"$words / ∞ words"' in soul_screen,
       "counter does not render infinity")
    ok("SoulBodyLimitControls(" in soul_screen, "limit controls not rendered")
    ok("chineseCharLimit" in soul_ui and "englishWordLimit" in soul_ui,
       "custom cap fields missing")

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
    ok("Personality (from SOUL.md" in soul,
       "native personality prompt block changed/missing")

    # The injection block must be byte-identical to HEAD (only length +
    # identity are allowed to change in SoulStore.kt).
    try:
        head = subprocess.check_output(
            ["git", "show", "HEAD:src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt"],
            cwd=ROOT, text=True,
        )
        for marker in (
            "val INJECTION_PATTERNS: List<Regex> = listOf(",
            "private fun scrubInjections(s: String): String =",
        ):
            before = head[head.index(marker): head.index(marker) + 800]
            after = soul[soul.index(marker): soul.index(marker) + 800]
            ok(before == after, f"security-adjacent block changed in SoulStore: {marker}")
    except (subprocess.CalledProcessError, ValueError):
        ok(False, "could not compare SoulStore against HEAD")

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
