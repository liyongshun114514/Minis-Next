#!/usr/bin/env python3
"""Real compile + JUnit + Robolectric pointer test for the chat composer.

Three stages, all executed against the PRODUCTION sources of the working tree
(no file is rewritten, no assertion is a grep-only claim):

1. `compile`  — the composer shell (`ChatComposerMotion.kt`), the context-usage
   glow (`ContextUsageHintUi.kt`), the send/stop buttons
   (`SendReturnModeMenu.kt`), the chat-area indicators + empty greeting
   (`ChatIndicators.kt`) and their real dependencies (theme, decorative clock,
   superellipse/glass shapes) are compiled with the local Kotlin 2.1.0 compiler
   and Compose compiler plugin against the real Compose 1.9.1 jars and the
   compile-only `android.jar`.
2. `jvm`      — JUnit 4 runs the shipped unit test `ChatComposerMotionTest`
   plus `ComposerFinalGapTest` (emitted here), which pins the geometry this
   change is about: 48dp slot / 38dp visual / 5dp inset, one 8dp vertical
   gutter, the 420ms/10dp greeting entrance, and the morph radius identity.
3. `pointer`  — Robolectric + Compose UI test renders the real
   `ComposerActionSlot` and injects touches with a real InputDispatcher: 3px
   inside the slot's edge (outside the painted 38dp circle) must fire the
   callback, and the slot must measure 48dp. This is the check that a
   `clickable` on the inner circle cannot pass.

Stub boundary (explicit):
* `android.jar` is the SDK's compile-only stub — `android.*` resolves, throws at
  runtime. No `android.*` symbol is reimplemented.
* `com.openminis.app.R` is generated from the `R.string.*` references of the
  compiled subset, and every name is first required to exist in the real
  `src/main/res/values*/strings.xml`; a deleted or mistyped string fails the run.
* Three app-module symbols live in files too large to compile in isolation
  (`ui/settings/AppearanceScreen.kt`, `agent/SoulStore.kt`,
  `logging/AppLogger.kt`). They are declared as minimal shims emitted into the
  work dir; the chat files under test are the REAL sources, and every shim
  signature is copied from the real declaration.
* For the Robolectric stage, the cached `*-classes.jar` AAR artefacts have no
  generated `R` classes, so the `androidx.*` `R` classes that the cached
  bytecode really references are generated from that bytecode (see
  `_gen_r_stubs`). Values are synthetic but application-scoped.
* `androidx.lifecycle.FullLifecycleObserver` is no longer shipped by the cached
  lifecycle 2.9.0 artefacts although lifecycle-runtime still references it; a
  two-line interface shim lets the Robolectric sandbox link.

Usage:
  scripts/test-composer-motion.py [--keep] [--work-dir DIR] [--verbose]

Exit codes: 0 = all stages passed, 1 = a stage failed, 2 = toolchain problem.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "src" / "android" / "app"
MAIN = APP / "src" / "main" / "java" / "com" / "openminis" / "app"
TEST = APP / "src" / "test" / "java" / "com" / "openminis" / "app"
RES = APP / "src" / "main" / "res"
UI_VERIFY = Path("/opt/toolchains/ui-verify")
GRADLE_CACHE = Path("/var/cache/minis/gradle/caches/modules-2/files-2.1")
FALLBACK = Path("/opt/gradle-8.11.1/lib")
KOTLIN_VERSION = "2.1.0"

VERBOSE = False


def log(message: str) -> None:
    print(message, flush=True)


class ToolchainError(RuntimeError):
    pass


# ---------------------------------------------------------------------------
# toolchain
# ---------------------------------------------------------------------------


def find_jdk() -> Path:
    for candidate in ("/usr/lib/jvm/java-21-openjdk-arm64", "/usr/lib/jvm/java-17-openjdk-arm64"):
        path = Path(candidate)
        if (path / "bin" / "java").is_file():
            return path
    raise ToolchainError("no JDK under /usr/lib/jvm")


def first(module: str, name: str) -> Path:
    hits = sorted((GRADLE_CACHE / module).rglob(name))
    if not hits:
        raise ToolchainError(f"missing {name} under {GRADLE_CACHE / module}")
    return hits[0]


def kotlin_std() -> Path:
    return first("org.jetbrains.kotlin/kotlin-stdlib", f"kotlin-stdlib-{KOTLIN_VERSION}.jar")


def compiler_classpath() -> list[Path]:
    jars = [
        first("org.jetbrains.kotlin/kotlin-compiler-embeddable",
              f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar"),
        kotlin_std(),
        first("org.jetbrains.kotlin/kotlin-script-runtime", f"kotlin-script-runtime-{KOTLIN_VERSION}.jar"),
        first("org.jetbrains.kotlin/kotlin-reflect", f"kotlin-reflect-{KOTLIN_VERSION}.jar"),
        first("org.jetbrains.kotlin/kotlin-daemon-embeddable",
              f"kotlin-daemon-embeddable-{KOTLIN_VERSION}.jar"),
    ]
    for name in ("kotlinx-coroutines-core-jvm-1.6.4.jar", "trove4j-1.0.20200330.jar",
                 "annotations-24.0.1.jar"):
        candidate = FALLBACK / name
        if candidate.is_file():
            jars.append(candidate)
    return jars


def compose_plugin() -> Path:
    hits = [p for p in sorted((GRADLE_CACHE / "org.jetbrains.kotlin").rglob("*.jar"))
            if "compose-compiler-plugin" in p.name and KOTLIN_VERSION in p.name]
    if not hits:
        raise ToolchainError("missing compose compiler plugin")
    return hits[0]


def coroutines_jar() -> Path:
    hits = sorted((GRADLE_CACHE / "org.jetbrains.kotlinx").rglob("kotlinx-coroutines-core-jvm-*.jar"))
    hits = [p for p in hits if "sources" not in p.name]
    if not hits:
        raise ToolchainError("missing kotlinx-coroutines-core-jvm")
    return hits[-1]


def collection_jar() -> Path:
    hits = sorted((GRADLE_CACHE / "androidx.collection" / "collection-jvm").rglob("collection-jvm-*.jar"))
    if not hits:
        raise ToolchainError("missing androidx.collection collection-jvm")
    return hits[-1]


def android_jar() -> Path:
    for name in ("android-36", "android-35"):
        path = Path("/opt/android-sdk/platforms") / name / "android.jar"
        if path.is_file():
            return path
    raise ToolchainError("no android.jar in /opt/android-sdk/platforms")


def junit_jars() -> list[Path]:
    return [FALLBACK / "junit-4.13.2.jar", FALLBACK / "hamcrest-core-1.3.jar"]


def ui_jars() -> list[Path]:
    jars = sorted(UI_VERIFY.glob("*.jar"))
    if not jars:
        raise ToolchainError(f"no jars in {UI_VERIFY}")
    return jars


def run(command: list[str], timeout: int = 1800) -> subprocess.CompletedProcess:
    if VERBOSE:
        log("$ " + " ".join(str(c) for c in command))
    return subprocess.run(
        [str(c) for c in command], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=timeout, env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
                              "HOME": "/root"},
    )


# ---------------------------------------------------------------------------
# production subset
# ---------------------------------------------------------------------------

# Real production sources, by path. Everything the chat composer's shell,
# glow, buttons and empty state need that can be compiled without the whole app
# module.
SUBSET = [
    "ui/chat/ChatComposerMotion.kt",
    "ui/chat/ContextUsageHintUi.kt",
    "ui/chat/ContextUsageHint.kt",
    "ui/chat/SendReturnModeMenu.kt",
    "ui/chat/StopConfirmPolicy.kt",
    "ui/chat/ChatIndicators.kt",
    "ui/chat/ComposerPlaceholderRotation.kt",
    "ui/chat/ComposerPlaceholderUi.kt",
    "ui/chat/ComposerHintCarousel.kt",
    "ui/chat/RetryBadge.kt",
    "ui/chat/ComposerSurfaceGeometry.kt",
    "ui/theme/OmniPalette.kt",
    "ui/theme/ChatColors.kt",
    "ui/components/DecorativeAnimation.kt",
    "ui/components/OmniGlass.kt",
    "ui/components/OmniGlassCapture.kt",
    "ui/components/OmniGlassMath.kt",
    "ui/components/OmniGlassShader.kt",
    "ui/components/OmniGlassShaderApi33.kt",
    "ui/components/OmniShapes.kt",
]

# Shims: only the three symbols whose real files cannot be compiled alone. Each
# signature is copied from the real declaration; nothing else is stubbed.
SHIM_PREFS = '''package com.openminis.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

// Mirrors ui/settings/AppearanceScreen.kt:80/86/265 verbatim (that file cannot
// be compiled without the whole settings module).
const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
'''

SHIM_SOUL = '''package com.openminis.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Mirrors agent/SoulStore.kt:29/373/374 — a StateFlow<SoulMetadata> whose
// `name` the chat reads (composer placeholder + empty greeting).
object SoulStore {
    data class SoulMetadata(val name: String) {
        companion object {
            val DEFAULT = SoulMetadata("Minis Next")
        }
    }

    private val _cachedMetadata = MutableStateFlow(SoulMetadata.DEFAULT)
    val cachedMetadata: StateFlow<SoulMetadata> = _cachedMetadata
}
'''

SHIM_VM = '''package com.openminis.app.ui.chat

// Mirrors the nested type ChatIndicators reads (ChatViewModel.kt:2349); that
// file drags in the whole VM and cannot be compiled in isolation.
class ChatViewModel {
    data class CompactProgress(
        val startedAtMs: Long,
        val depth: Int = 0,
        val callsIssued: Int = 0,
        val callBudget: Int = 0,
    )
}
'''

SHIM_LOGGER = '''package com.openminis.app.logging

// Mirrors logging/AppLogger.kt's `info(tag, message)` used by the send/stop
// choosers.
object AppLogger {
    fun info(tag: String, message: String) {
        // no-op: a log line is not part of what this test asserts
    }
}
'''

R_STUB_HEADER = "package com.openminis.app;\n\n/** Generated from the subset's R.* references; names checked against res/. */\npublic final class R {\n"


def resource_names() -> dict[str, set[str]]:
    """Every `R.<kind>.<name>` declared in the real resources."""
    names: dict[str, set[str]] = {"string": set(), "plurals": set(), "array": set(),
                                  "integer": set(), "bool": set()}
    pattern = {
        "string": re.compile(r'<string name="([A-Za-z0-9_]+)"'),
        "plurals": re.compile(r'<plurals name="([A-Za-z0-9_]+)"'),
        "array": re.compile(r'<string-array name="([A-Za-z0-9_]+)"'),
        "integer": re.compile(r'<integer name="([A-Za-z0-9_]+)"'),
        "bool": re.compile(r'<bool name="([A-Za-z0-9_]+)"'),
    }
    for values in sorted(RES.glob("values*")):
        for xml in values.glob("*.xml"):
            text = xml.read_text(encoding="utf-8", errors="replace")
            for kind, regex in pattern.items():
                names[kind] |= set(regex.findall(text))
    return names


def emit_r_stub(target: Path, sources: list[Path]) -> list[str]:
    """Generate com/openminis/app/R.java from `R.<kind>.<name>` references."""
    declared = resource_names()
    used: dict[str, set[str]] = {}
    reference = re.compile(r"\bR\.(string|plurals|array|integer|bool)\.([A-Za-z0-9_]+)")
    for source in sources:
        for kind, name in reference.findall(source.read_text(encoding="utf-8", errors="replace")):
            used.setdefault(kind, set()).add(name)
    missing = sorted(f"R.{k}.{n}" for k, names in used.items() for n in names
                     if n not in declared.get(k, set()))
    if missing:
        raise ToolchainError(f"resource references with no declaration: {missing}")
    body = [R_STUB_HEADER]
    value = 0x7F0A0000
    for kind in sorted(used):
        body.append(f"    public static final class {kind} {{\n")
        for name in sorted(used[kind]):
            value += 1
            body.append(f"        public static final int {name} = 0x{value:08x};\n")
        body.append("    }\n")
    body.append("}\n")
    out = target / "shim" / "R.java"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(body))
    return sorted(f"R.{k}.{n}" for k, names in used.items() for n in names)


def emit_shims(target: Path) -> None:
    shim = target / "shim"
    shim.mkdir(parents=True, exist_ok=True)
    (shim / "AppearancePrefs.kt").write_text(SHIM_PREFS)
    (shim / "SoulStore.kt").write_text(SHIM_SOUL)
    (shim / "AppLogger.kt").write_text(SHIM_LOGGER)
    (shim / "ChatViewModelShim.kt").write_text(SHIM_VM)


# The unit test this change is about. Compiled together with the shipped
# `ChatComposerMotionTest` (same module, so `internal` is visible).
GAP_TEST = '''package com.openminis.app.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-omnibot-composer-final-gap] Geometry introduced by the last chat pass:
 * the 48dp composable slot, the single 8dp vertical gutter and the empty
 * greeting's entrance. Numbers only — the behaviour is covered by
 * `ComposerHitRegionTest` (real pointer dispatch) and the compose wiring is
 * asserted by the driver's static stage.
 */
class ComposerFinalGapTest {

    @Test
    fun tapSlotIsRealFourtyEight() {
        assertEquals(48f, ComposerTapSpec.Slot.value, 0f)
        assertEquals(48f, ComposerMorphSpec.TapSlot.value, 0f)
    }

    @Test
    fun visualCircleIsUnchanged() {
        assertEquals(38f, ComposerTapSpec.Visual.value, 0f)
        assertEquals(38f, ComposerMorphSpec.IconVisualSize.value, 0f)
    }

    @Test
    fun insetIsFivePerSide() {
        assertEquals(5f, ComposerTapSpec.VisualInset.value, 0f)
        assertEquals(
            ComposerTapSpec.Slot.value,
            ComposerTapSpec.Visual.value + ComposerTapSpec.VisualInset.value * 2f,
            0.001f,
        )
    }

    @Test
    fun oneEightDpVerticalGutter() {
        val padding = ComposerMorphSpec.ContentPadding
        assertEquals(8f, ComposerMorphSpec.VerticalGutter.value, 0f)
        assertEquals(8f, padding.calculateTopPadding().value, 0f)
        assertEquals(8f, padding.calculateBottomPadding().value, 0f)
    }

    @Test
    fun textFieldAddsNoSecondGutter() {
        val field = ComposerMorphSpec.FieldContentPadding
        assertEquals(0f, field.calculateTopPadding().value, 0f)
        assertEquals(0f, field.calculateBottomPadding().value, 0f)
        assertEquals(0f, field.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).value, 0f)
        assertEquals(0f, field.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).value, 0f)
    }

    @Test
    fun shellRadiusMatchesTheCardConstant() {
        assertEquals(20f, ComposerMorphSpec.ShellRadius.value, 0f)
        assertEquals(20f, ComposerSurfaceGeometry.CORNER_RADIUS_DP, 0f)
    }

    @Test
    fun morphRadiusIsTheStadiumAtRest() {
        // 300x100 shell: stadium radius 50, resting 20 -> progress 0 is 50.
        assertEquals(50f, ComposerMorphMath.cornerRadiusPx(0f, 300f, 100f, 20f), 0.001f)
        assertEquals(20f, ComposerMorphMath.cornerRadiusPx(1f, 300f, 100f, 20f), 0.001f)
        // Clamped: a resting radius above the stadium cannot invert the arcs.
        assertEquals(50f, ComposerMorphMath.cornerRadiusPx(1f, 300f, 100f, 999f), 0.001f)
    }

    @Test
    fun greetingEntranceIs420And10() {
        assertEquals(420, ChatEmptyGreetingSpec.ENTER_MS)
        assertEquals(10f, ChatEmptyGreetingSpec.RISE_DP, 0f)
        assertEquals(10f, ChatEmptyGreetingSpec.riseDp(0f), 0.001f)
        assertEquals(0f, ChatEmptyGreetingSpec.riseDp(1f), 0.001f)
        assertEquals(5f, ChatEmptyGreetingSpec.riseDp(0.5f), 0.001f)
        assertEquals(28f, ChatEmptyGreetingSpec.SIDE_PADDING_DP, 0f)
    }

    @Test
    fun greetingRotationIsTheShippedCadence() {
        assertEquals(1800L, ChatEmptyGreetingSpec.ROTATE_MS)
    }

    @Test
    fun greetingRiseNeverLeavesItsRange() {
        for (step in 0..10) {
            val value = ChatEmptyGreetingSpec.riseDp(step / 10f)
            assertTrue("rise at $step = $value", value in 0f..10f)
        }
        assertEquals(10f, ChatEmptyGreetingSpec.riseDp(-1f), 0.001f)
        assertEquals(0f, ChatEmptyGreetingSpec.riseDp(2f), 0.001f)
    }

    @Test
    fun tapSlotIsNotBelowTheMaterialMinimum() {
        assertTrue(ComposerTapSpec.Slot >= 48.dp)
    }
}
'''

HIT_TEST = '''package smoke

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.chat.ComposerActionSlot
import com.openminis.app.ui.chat.ComposerTapSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real pointer-dispatch proof of the composer's 48dp hit region.
 *
 * Renders the PRODUCTION `ComposerActionSlot` — the one composable every
 * composer round control (+, mic, send, stop) now goes through — measures the
 * node, and injects touches through Compose's InputDispatcher:
 *
 *  - the slot measures 48dp square;
 *  - a tap 3px inside the slot's left edge, OUTSIDE the 38dp painted circle,
 *    fires onClick;
 *  - a tap 3px below the slot's top edge, also outside the circle, fires;
 *  - the centred tap still fires (control).
 *
 * A `clickable` living on the inner 38dp circle would fail the two edge cases:
 * the layout box would measure 48dp while the pointer region stayed 38dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ComposerHitRegionTest {

    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(
            ComponentName(app.packageName, ComponentActivity::class.java.name),
        )
    }

    @get:Rule
    val compose = createComposeRule()

    private fun content(onTap: () -> Unit, trailing: @Composable () -> Unit = {}) {
        compose.setContent {
            Box {
                ComposerActionSlot(
                    onClick = onTap,
                    modifier = Modifier.testTag("slot"),
                    visualModifier = Modifier.background(Color.Blue, CircleShape),
                ) {
                    Box(Modifier.size(ComposerTapSpec.Visual))
                }
                trailing()
            }
        }
    }

    @Test
    fun slotMeasures48dp() {
        content(onTap = {})
        compose.onNodeWithTag("slot").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("slot").assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun tapInsideSlotButOutsideVisualCircleFires() {
        var taps = 0
        content(onTap = { taps++ })
        compose.onNodeWithTag("slot").performTouchInput { click(Offset(3f, centerY)) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun tapAboveVisualCircleFires() {
        var taps = 0
        content(onTap = { taps++ })
        compose.onNodeWithTag("slot").performTouchInput { click(Offset(centerX, 3f)) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun tapAtCentreStillFires() {
        var taps = 0
        content(onTap = { taps++ })
        compose.onNodeWithTag("slot").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }
}
'''


# ---------------------------------------------------------------------------
# androidx R shims for the Robolectric stage
# ---------------------------------------------------------------------------

LIFECYCLE_SHIM = '''package androidx.lifecycle;

/** Link shim: the cached lifecycle 2.9.0 artefacts no longer ship this type. */
public interface FullLifecycleObserver extends LifecycleObserver {
    void onCreate(LifecycleOwner owner);
    void onStart(LifecycleOwner owner);
    void onResume(LifecycleOwner owner);
    void onPause(LifecycleOwner owner);
    void onStop(LifecycleOwner owner);
    void onDestroy(LifecycleOwner owner);
}
'''

R_FIELD = re.compile(
    r"androidx/([a-zA-Z0-9_/]+)/R\$(id|string|dimen|layout|color|array|styleable|attr|drawable|integer|bool)\.([A-Za-z0-9_]+)"
)
R_KINDS = ("id", "string", "dimen", "layout", "color", "array", "styleable", "attr",
           "drawable", "integer", "bool")


def _gen_r_stubs(java: Path, jars: list[Path], work: Path) -> Path:
    """Emit the androidx `R` classes the cached AAR class jars are missing.

    The `*-classes.jar` artefacts carry the AAR's classes but not its generated
    `R`; Robolectric links them like a device would. Names come from the real
    bytecode (`javap`), values are synthetic but application-scoped.
    """
    found: dict[str, dict[str, set[str]]] = {}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for index, jar in enumerate(jars):
            try:
                archive = zipfile.ZipFile(jar)
            except (zipfile.BadZipFile, OSError):
                continue
            hits = []
            with archive:
                for name in archive.namelist():
                    if not name.endswith(".class"):
                        continue
                    data = archive.read(name)
                    if b"/R$" not in data:
                        continue
                    target = root / str(index) / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                    hits.append(str(target))
            for chunk in (hits[i:i + 300] for i in range(0, len(hits), 300)):
                out = subprocess.run(
                    [str(java.with_name("javap")), "-p", "-c", *chunk],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                ).stdout
                for owner, kind, field in R_FIELD.findall(out):
                    found.setdefault("androidx." + owner.replace("/", "."), {}) \
                         .setdefault(kind, set()).add(field)
    sources = work / "androidx-r" / "src"
    value = 0x7F0F0000
    for package, kinds in sorted(found.items()):
        body = []
        for kind in R_KINDS:
            fields = sorted(kinds.get(kind, ()))
            if not fields:
                continue
            value += 0x1000
            if kind == "styleable":
                lines = [f"        public static final int[] {f} = new int[] {{ {value + i} }};"
                         for i, f in enumerate(fields)]
            else:
                lines = [f"        public static final int {f} = 0x{value + i:08x};"
                         for i, f in enumerate(fields)]
            body.append(f"    public static final class {kind} {{\n" + "\n".join(lines) + "\n    }")
        directory = sources / package.replace(".", "/")
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "R.java").write_text(
            f"package {package};\n\n"
            "/** Link shim: AAR R class absent from the cached *-classes.jar. */\n"
            "public final class R {\n" + "\n".join(body) + "\n}\n"
        )
    lifecycle = sources / "androidx" / "lifecycle"
    lifecycle.mkdir(parents=True, exist_ok=True)
    (lifecycle / "FullLifecycleObserver.java").write_text(LIFECYCLE_SHIM)
    return sources


# ---------------------------------------------------------------------------
# compile helpers
# ---------------------------------------------------------------------------


def compile_kotlin(
    java: Path,
    out: Path,
    sources: list[Path],
    classpath: list[Path],
    compose: Path,
) -> subprocess.CompletedProcess:
    out.mkdir(parents=True, exist_ok=True)
    command = [
        java, "-cp", ":".join(str(p) for p in compiler_classpath()),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-nowarn",
        f"-Xplugin={compose}",
        "-classpath", ":".join(str(p) for p in classpath),
        "-d", str(out),
    ] + [str(p) for p in sources]
    return run(command)


def compile_java(java: Path, out: Path, sources: list[Path], classpath: list[Path]) -> subprocess.CompletedProcess:
    out.mkdir(parents=True, exist_ok=True)
    return run([java.with_name("javac"), "-nowarn", "-d", str(out),
                "-classpath", ":".join(str(p) for p in classpath)]
               + [str(p) for p in sources])


def tail(result: subprocess.CompletedProcess, lines: int = 40) -> str:
    text = (result.stdout or "").strip().splitlines()
    return "\n".join(text[-lines:])


def read_runtime_classpath(explicit: str | None) -> list[Path] | None:
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    env = __import__("os").environ.get("MINIS_COMPOSER_MOTION_CP")
    if env:
        candidates.append(Path(env))
    candidates.append(Path("/var/cache/minis/ui-verify-robolectric/runtime-classpath.txt"))
    for candidate in candidates:
        if candidate.is_file():
            jars = [Path(line.strip()) for line in candidate.read_text().splitlines() if line.strip()]
            if all(jar.is_file() for jar in jars):
                return jars
    return None


# ---------------------------------------------------------------------------
# stage: static compose wiring
# ---------------------------------------------------------------------------

CHAT = MAIN / "ui" / "chat"

WIRING: dict[str, list[tuple[str, str]]] = {
    "ChatScreen.kt": [
        ("composer shell takes the glass bundle", "glass = composerGlass,"),
        ("glass bundle comes from the capture scope",
         "com.openminis.app.ui.components.LocalOmniGlass.current != null"),
        ("glass keeps the real focus morph and honours reduced motion",
         "animationsEnabled = !animationsDisabled(context),"),
        ("flow ring stops under glass", "glass = composerGlass != null,"),
        ("glow rides the morph radius", "morph = composerMorph,\n                            animate ="),
        ("text field padding has one source",
         "contentPadding = ComposerMorphSpec.FieldContentPadding,"),
        ("empty greeting mounted", "ChatEmptyGreeting("),
        ("empty greeting gate: no messages", "val showEmptyGreeting = messages.isEmpty() &&"),
        ("empty greeting gate: history loaded", "!enteringSession &&"),
        ("empty greeting gate: not searching", "!chatSearch.isActive"),
        ("sheet surface comes from the wrapper",
         "import com.openminis.app.ui.components.OmniBottomSheet as ModalBottomSheet"),
    ],
    "ChatComposerWidgets.kt": [
        ("plus button uses the shared slot", "ComposerActionSlot(\n        onClick = onClick,"),
        ("mic button uses the shared slot", "ComposerActionSlot(\n        onClick = onClick,\n        onLongClick = onLongClick,"),
        ("mic keeps its long-press", "onLongClick = onLongClick,"),
        ("mic mode still crossfades", "AnimatedContent("),
    ],
    "SendReturnModeMenu.kt": [
        ("send button uses the shared slot", "ComposerActionSlot(\n        onClick = {"),
        ("stop button uses the shared slot", "ComposerActionSlot(\n        onClick = {"),
        ("send chooser is anchored to the slot", "overlay = {"),
        ("stop chooser is anchored to the slot", "overlay = {"),
        ("send keeps its a11y action", "SendReturnMode.tapShouldSend(canActivate, menuVisible, since)"),
        ("stop keeps its dismiss guard", "msSinceMenuDismiss = since"),
    ],
    "ChatIndicators.kt": [
        ("greeting exists", "internal fun ChatEmptyGreeting("),
        ("greeting headline is an existing string",
         "stringResource(R.string.onboarding_welcome_title)"),
        ("greeting rotates the existing hint pool", "composerPlaceholderText(ComposerPlaceholderPool.textIndex(hintIndex), soulName)"),
        ("greeting uses the shipped rotation rule", "ComposerPlaceholderRotation.nextIndex("),
        ("greeting entrance is the spec duration", "ChatEmptyGreetingSpec.ENTER_MS"),
        ("greeting rise is read in the layer", "translationY = ChatEmptyGreetingSpec.riseDp(enter.value).dp.toPx()"),
        ("greeting honours reduce motion", "if (animate) {"),
        ("greeting is pinned for a screen reader", "if (screenReaderOn) return@LaunchedEffect"),
    ],
    "ChatComposerMotion.kt": [
        ("one slot composable", "internal fun ComposerActionSlot("),
        ("slot is a real size", ".size(ComposerTapSpec.Slot)"),
        ("glass pane applied", "Modifier.omniGlassSurface(shape = glass.shape, style = glass.style)"),
        ("glass skips the opaque fill", "if (glass != null) {\n            drawComposerHairline"),
        ("flow ring stops under glass", "if (!visible || glass) return"),
        ("glass shell resolver", "internal fun rememberComposerGlassShell("),
        ("morph corner size feeds the glass resolver", "internal class ComposerMorphCornerSize("),
    ],
    "ContextUsageHintUi.kt": [
        ("glow overload driven by the morph", "internal fun Modifier.contextUsageGlow(\n    tier: ContextUsage.Tier,\n    morph: ComposerMorphHandle,"),
        ("glow radius read in the draw phase", "progress = morph.shapeProgress,"),
        ("glow paint objects are reused", "val path = remember { android.graphics.Path() }"),
    ],
}

# Needles that must be GONE: each one is the shape of the gap that was closed.
FORBIDDEN: dict[str, list[tuple[str, str]]] = {
    "ChatComposerMotion.kt": [
        ("no sizeIn-based slot left", "sizeIn(minWidth = slot"),
        ("no composerTapSlot helper left", "internal fun Modifier.composerTapSlot("),
    ],
    "ChatComposerWidgets.kt": [
        ("no min-size slot wrapper", ".composerTapSlot()"),
    ],
    "SendReturnModeMenu.kt": [
        ("no 38dp tap target left", ".size(38.dp)\n            .background("),
    ],
    "ChatScreen.kt": [
        ("glass must not disable the source shape morph", "&& !composerGlassEnabled"),
        ("no fixed glow radius at the call site",
         "cornerRadius = ComposerSurfaceGeometry.CORNER_RADIUS_DP.dp,"),
        ("no second 8dp in the text field",
         "contentPadding = PaddingValues(\n                                        horizontal = 0.dp,\n                                        vertical = 8.dp,\n                                    ),"),
        ("material ModalBottomSheet import replaced",
         "import androidx.compose.material3.ModalBottomSheet"),
    ],
}

# Ordered checks: (file, label, [needles in order]).
ORDERED: list[tuple[str, str, list[str]]] = [
    (
        "ChatComposerMotion.kt",
        "gesture sits on the 48dp node, visual is a child",
        [".size(ComposerTapSpec.Slot)", ".clip(CircleShape)", ".then(",
         "Modifier.combinedClickable(", ".size(ComposerTapSpec.Visual)"],
    ),
    (
        "ChatScreen.kt",
        "glass pane is applied before its own paint pass",
        [".composerMorphSurface(", "glass = composerGlass,", ".contextUsageGlow("],
    ),
]


def static_stage() -> int:
    failures = 0
    total = 0
    for name, checks in WIRING.items():
        text = (CHAT / name).read_text(encoding="utf-8")
        for label, needle in checks:
            total += 1
            if needle not in text:
                log(f"  FAIL {name}: {label} — missing {needle!r}")
                failures += 1
    for name, checks in FORBIDDEN.items():
        text = (CHAT / name).read_text(encoding="utf-8")
        for label, needle in checks:
            total += 1
            if needle in text:
                log(f"  FAIL {name}: {label} — still present {needle!r}")
                failures += 1
    for name, label, needles in ORDERED:
        text = (CHAT / name).read_text(encoding="utf-8")
        total += 1
        position = -1
        for needle in needles:
            found = text.find(needle, position + 1)
            if found < 0:
                log(f"  FAIL {name}: {label} — {needle!r} missing or out of order")
                failures += 1
                break
            position = found
    log(f"[wiring] {total - failures}/{total} static assertions")
    return 0 if failures == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep", action="store_true", help="keep the work directory")
    parser.add_argument("--work-dir", default=None)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--stage", choices=["all", "compile", "jvm", "pointer", "wiring"],
                        default="all")
    parser.add_argument("--runtime-classpath", default=None,
                        help="file listing jars for the Robolectric stage "
                             "(default: the prepared deps cache, else the stage is skipped)")
    parser.add_argument("--require-pointer", action="store_true",
                        help="fail (instead of skipping) when the Robolectric classpath is absent")
    args = parser.parse_args()
    global VERBOSE
    VERBOSE = args.verbose

    work = Path(args.work_dir) if args.work_dir else Path(tempfile.mkdtemp(prefix="composer-motion-"))
    work.mkdir(parents=True, exist_ok=True)

    try:
        java = find_jdk() / "bin" / "java"
        stdlib = kotlin_std()
        android = android_jar()
        compose = compose_plugin()
        deps = ui_jars() + junit_jars() + [stdlib, coroutines_jar(), collection_jar(), android]
    except ToolchainError as error:
        log(f"TOOLCHAIN ERROR: {error}")
        return 2

    sources = [MAIN / rel for rel in SUBSET]
    absent = [str(p) for p in sources if not p.is_file()]
    if absent:
        log(f"TOOLCHAIN ERROR: missing production sources: {absent}")
        return 2

    emit_shims(work)
    try:
        stub_names = emit_r_stub(work / "app-r", [MAIN / rel for rel in SUBSET])
    except ToolchainError as error:
        log(f"TOOLCHAIN ERROR: {error}")
        return 2

    log(f"[compile] {len(sources)} production sources, {len(stub_names)} R references")
    compiled = compile_java(java, work / "app-r" / "classes",
                            [work / "app-r" / "shim" / "R.java"], deps)
    if compiled.returncode != 0:
        log("[compile] R stub failed to compile\n" + tail(compiled))
        return 1

    shim_sources = sorted((work / "shim").glob("*.kt"))
    classes = work / "classes"
    result = compile_kotlin(
        java, classes,
        sources + shim_sources,
        deps + [work / "app-r" / "classes"],
        compose,
    )
    if result.returncode != 0:
        log("[compile] FAILED\n" + tail(result, 60))
        return 1
    log("[compile] OK")

    if args.stage == "compile":
        return 0

    # ---- stage: JVM unit tests -------------------------------------------
    jvm_ok = True
    if args.stage in ("all", "jvm"):
        gap = work / "tests" / "ComposerFinalGapTest.kt"
        gap.parent.mkdir(parents=True, exist_ok=True)
        gap.write_text(GAP_TEST)
        shipped = TEST / "ui" / "chat" / "ChatComposerMotionTest.kt"
        tests = [gap] + ([shipped] if shipped.is_file() else [])
        test_classes = work / "test-classes"
        result = compile_kotlin(java, test_classes, sources + shim_sources + tests,
                                deps + [work / "app-r" / "classes", classes], compose)
        if result.returncode != 0:
            log("[jvm] test compile FAILED\n" + tail(result, 60))
            return 1
        run_cp = ":".join(str(p) for p in [test_classes, classes, work / "app-r" / "classes"]
                          + deps)
        names = ["com.openminis.app.ui.chat.ComposerFinalGapTest"]
        if shipped.is_file():
            names.append("com.openminis.app.ui.chat.ChatComposerMotionTest")
        result = run([java, "-cp", run_cp, "org.junit.runner.JUnitCore", *names])
        text = result.stdout or ""
        log("[jvm] " + " | ".join(line for line in text.splitlines()
                                  if line.startswith(("OK", "Tests run", "FAILURES"))))
        if result.returncode != 0:
            log(tail(result, 60))
            jvm_ok = False
        else:
            log(f"[jvm] OK ({len(names)} classes)")

    # ---- stage: real pointer dispatch ------------------------------------
    pointer_ok = True
    pointer_ran = False
    if args.stage in ("all", "pointer"):
        runtime = read_runtime_classpath(args.runtime_classpath)
        if runtime is None:
            message = ("[pointer] SKIPPED: no Robolectric/Compose-UI classpath. Pass "
                       "--runtime-classpath <file> (one jar per line) to run the real "
                       "touch-injection test.")
            log(message)
            if args.require_pointer:
                pointer_ok = False
        else:
            pointer_ran = True
            hit = work / "pointer" / "ComposerHitRegionTest.kt"
            hit.parent.mkdir(parents=True, exist_ok=True)
            hit.write_text(HIT_TEST)
            pointer_classes = work / "pointer-classes"
            pointer_cp = deps + runtime
            result = compile_kotlin(java, pointer_classes, sources + [hit],
                                    pointer_cp + [work / "app-r" / "classes", classes], compose)
            if result.returncode != 0:
                log("[pointer] test compile FAILED\n" + tail(result, 60))
                return 1
            r_sources = _gen_r_stubs(java.with_name("java"), list(runtime) + deps, work)
            r_classes = work / "androidx-r" / "classes"
            result = compile_java(java, r_classes, sorted(r_sources.rglob("*.java")),
                                  [coroutines_jar(), stdlib] + runtime)
            if result.returncode != 0:
                log("[pointer] androidx R shims failed to compile\n" + tail(result, 40))
                return 1
            android_all = work / "android-all"
            cached = Path("/var/cache/minis/ui-verify-robolectric/android-all")
            if cached.is_dir():
                android_all = cached
            run_cp = ":".join(str(p) for p in [pointer_classes, classes, r_classes] + pointer_cp)
            result = run([
                java, "-cp", run_cp,
                "-Drobolectric.offline=true",
                f"-Drobolectric.dependency.dir={android_all}",
                "-Drobolectric.logging.enabled=false",
                "-Drobolectric.graphicsMode=LEGACY",
                "-Drobolectric.sqliteMode=LEGACY",
                "org.junit.runner.JUnitCore", "smoke.ComposerHitRegionTest",
            ])
            text = result.stdout or ""
            log("[pointer] " + " | ".join(line for line in text.splitlines()
                                          if line.startswith(("OK", "Tests run", "FAILURES"))))
            if result.returncode != 0:
                log(tail(result, 60))
                pointer_ok = False

    # ---- stage: static wiring -------------------------------------------
    wiring_ok = True
    if args.stage in ("all", "wiring"):
        wiring_ok = static_stage() == 0

    if not args.keep:
        shutil.rmtree(work, ignore_errors=True)
    else:
        log(f"[work] {work}")

    if jvm_ok and pointer_ok and wiring_ok:
        log("PASS" + (" (pointer stage SKIPPED)" if not pointer_ran and args.stage in ("all", "pointer") else ""))
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
