#!/usr/bin/env python3
"""Static guards for the reverted native Composer port.

Run from the repository root:

    python3 scripts/tests/test_native_composer_port.py

The chat Composer UI that was ported from `MinisAllInOne` was reverted: the
native `ChatScreen.kt` of baseline commit `6eda552` (the parent of the port
commit `0dfd03d`) is back in place, and the port-only files were deleted.
This script therefore asserts the ABSENCE of the port and the presence of the
original native chat UI, instead of the port's bindings.

It does NOT compile Android and does NOT claim visual verification. It only
checks source-level facts and that no Xposed / reflection / DEX-scanning
machinery was copied into the app.

Soul (SOUL.md) and permission surfaces are deliberately out of scope here:
they are owned by separate changes, and their tests live with them
(`SoulBodyLimitPolicyTest`, the offload/root tests).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHAT = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt"
CHAT_REL = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt"
# Baseline that the port (0dfd03d) modified; ChatScreen.kt is restored to it.
BASELINE = "6eda552"
REMOVED = (
    ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/NativeComposerExtras.kt",
    ROOT / "src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPortMath.kt",
    ROOT / "src/android/app/src/test/java/com/openminis/app/ui/chat/ComposerPortMathTest.kt",
)
REMOVED_NAMES = {p.name for p in REMOVED}

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
    """Remove comments and string/char literals so token scans are code-only."""
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
    pairs = {"(": ")", "[": "]", "{": "}"}
    closers = {v: k for k, v in pairs.items()}
    stack: list[str] = []
    for ch in strip_kotlin(src):
        if ch in pairs:
            stack.append(ch)
        elif ch in closers:
            if not stack or stack[-1] != closers[ch]:
                ok(False, f"{label}: unbalanced {ch!r}")
                return
            stack.pop()
    ok(not stack, f"{label}: unclosed delimiters {stack[-3:]}")


def main() -> int:
    # --- Port-only files must be gone ------------------------------------
    for path in REMOVED:
        ok(not path.exists(), f"port-only file still present: {path.relative_to(ROOT)}")

    # --- ChatScreen is the pre-port baseline ------------------------------
    ok(CHAT.is_file(), "missing ui/chat/ChatScreen.kt")
    chat = text(CHAT)
    balanced(chat, "ChatScreen.kt")
    try:
        baseline = subprocess.check_output(
            ["git", "show", f"{BASELINE}:{CHAT_REL}"], cwd=ROOT, text=True
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        print(f"NOTE: {BASELINE} unavailable (shallow clone?); "
              "skipping byte-identical baseline comparison")
    else:
        ok(chat == baseline,
           f"ChatScreen.kt is not byte-identical to the {BASELINE} baseline")

    # --- No residue of the ported Composer UI ----------------------------
    port_tokens = (
        "ComposerModelThinkingBar",
        "ComposerEffortSlider",
        "ComposerCommandMenuRows",
        "ComposerMenuHeader",
        "ComposerPortMath",
        "NativeComposerExtras",
        "T-native-composer",
        "groupLabel = bottomGroupLabel",
    )
    for token in port_tokens:
        ok(token not in chat, f"ported Composer UI residue in ChatScreen.kt: {token}")
    app_src = ROOT / "src/android/app/src"
    for path in app_src.rglob("*.kt"):
        if path.name in REMOVED_NAMES:
            continue
        body = text(path)
        for token in ("ComposerModelThinkingBar", "ComposerEffortSlider",
                      "ComposerCommandMenuRows", "ComposerPortMath"):
            if token in body:
                ok(False, f"{path.relative_to(ROOT)} still calls removed {token}")

    # --- Original native top bar is back --------------------------------
    title_at = chat.index("text = displayTitle,")
    title_block = chat[title_at: title_at + 700]
    ok("maxLines = 1," in title_block, "title no longer reserves a single line")
    ok("lineHeight = 19.sp" in title_block, "title line height is not the native 19sp")
    ok("textAlign = TextAlign.Center" not in title_block,
       "ported centred title alignment is still present")
    ok("expandedHeight = 68.dp" in chat, "top bar height is not the native 68dp")
    ok('"$providerName · $modelName"' in chat,
       "native top-bar model / provider subtitle is missing")
    ok("ThinkingLevelBadge(" in chat.replace("private fun ThinkingLevelBadge(", ""),
       "native top-bar thinking badge call is missing")
    ok("selectedGroupName.ifEmpty" in chat,
       "native top-bar group fallback is missing")
    ok("editingSession = viewModel.loadSessionEntity()" in chat,
       "native rename click lost")

    # --- Native attachment / slash menu (no merged composer menu) -------
    for native in (
        "R.string.chat_attach_take_photo",
        "R.string.chat_attach_choose_photos_videos",
        "R.string.chat_attach_add_file",
    ):
        ok(native in chat, f"native attachment action lost: {native}")
    ok("showAttachMenu = true" in chat, "+ button no longer opens the native menu")
    ok("viewModel.executeSlashCommand(" in chat,
       "native slash command dispatcher call is missing")
    ok("viewModel.showSlashMenuOverInput(" in chat,
       "native draft-preserving slash entry is missing")

    # --- Native thinking sheet (row list, dismiss on select) -------------
    ok("viewModel.setThinkingLevel(level)" in chat,
       "native thinking setter call is missing")
    ok("Icons.Default.Lightbulb" in chat,
       "native thinking-sheet row list is missing")
    ok("showThinkingLevelSheet = false" in chat,
       "native thinking sheet no longer has a dismiss path")

    # --- Native send / stop / queue state untouched ----------------------
    ok("ComposerSendButton(" in chat, "native send button missing")
    ok("ComposerStopButton(" in chat, "native stop button missing")
    ok("val showStop = sessionBusy && !hasContent" in chat,
       "native send/stop state selection changed")
    ok("canActivate = canActivate" in chat, "native send activation changed")

    # --- No forbidden machinery in ChatScreen ----------------------------
    forbidden = (
        "Xposed",
        "Reflect",
        "DexClass",
        "InMemoryDex",
        "title.dex",
        "RootBridge",
        "Shizuku",
        "su -c",
    )
    chat_code = strip_kotlin(chat)
    for token in forbidden:
        ok(token not in chat_code, f"ChatScreen.kt contains forbidden token {token!r}")
    ok(not (ROOT / "src/android/app/src/main/assets/title.dex").exists(),
       "title.dex was copied into the app")

    # --- Provenance / licence -------------------------------------------
    repo_license = ROOT / "LICENSE"
    ok(repo_license.is_file(), "repository LICENSE missing")
    if repo_license.is_file():
        ok(repo_license.read_text(encoding="utf-8").startswith(
            "                    GNU GENERAL PUBLIC LICENSE"),
           "repository LICENSE is not GPL-3.0")

    if failures:
        print(f"Native composer revert static checks: "
              f"{checks - len(failures)}/{checks} passed")
        for failure in failures:
            print("FAIL:", failure)
        return 1
    print(f"Native composer revert static checks: {checks}/{checks} PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
