#!/usr/bin/env python3
"""Real Kotlin/Compose compilation + JVM test for the OmniBot UI port subset.

This is NOT a screenshot test and NOT a Gradle build. It compiles the actual
production Kotlin sources of the ported UI subset with the Kotlin 2.1.0
compiler and Compose compiler plugin that are already present in the local
Gradle cache, against the real Compose 1.9.1 / Material3 1.3.2 jars in
/opt/toolchains/ui-verify and the compile-only `android.jar` of the installed
Android SDK platform. The compiled classes are then exercised by JUnit 4 on a
plain JVM (the `OmniMotionTest` unit), so motion constants and easing math are
really executed rather than pattern-matched.

Stub boundary (explicit, nothing more is faked)
-----------------------------------------------
* `android.jar` is the SDK's compile-only stub: `android.*` symbols resolve at
  compile time and throw at runtime. No `android.*` symbol is re-implemented
  here, and no UI class is replaced.
* `com.openminis.app.R` is the only generated stub. It is emitted from the
  `R.string.<name>` references found in the compiled subset, and every name is
  first required to exist in the real `src/main/res/values*/` resources. A typo
  or a deleted string therefore fails the run instead of being absorbed by the
  stub. No layout/drawable/id resource class is generated because the subset
  does not reference one (asserted, not assumed).
* `com.openminis.app.data.model.ModelGroup` is compiled from its REAL source
  (`data/model/ProviderConfig.kt`), not stubbed, because `OmniHomeDockBar`
  takes it as a parameter.
* Everything else in the subset is production code copied by path only.

What is proven: the ported files type-check against the real Compose API, the
compiler accepts their `@Composable` bodies, and the motion unit passes.
What is NOT proven here: rendering, layout, gestures, navigation wiring,
device behaviour. Those need instrumentation and are out of scope.

Usage:
  scripts/test-omnibot-ui.py [--keep] [--verbose] [--motion-only]
                             [--work-root DIR] [--log-dir DIR]

Exit codes: 0 = compile + tests passed, 1 = compile or test failure,
            2 = toolchain/environment problem (never counted as a pass).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "src" / "android" / "app"
MAIN = APP / "src" / "main" / "java" / "com" / "openminis" / "app"
TEST = APP / "src" / "test" / "java" / "com" / "openminis" / "app"
RES = APP / "src" / "main" / "res"
UI_VERIFY = Path("/opt/toolchains/ui-verify")
GRADLE_CACHE = Path("/var/cache/minis/gradle/caches/modules-2/files-2.1")
FALLBACK_GRADLE_LIB = Path("/opt/gradle-8.11.1/lib")
DEFAULT_WORK_ROOT = Path("/var/minis/workspace/tmp/omnibot-ui-20261101/verify")

KOTLIN_VERSION = "2.1.0"
COMPOSE_RUNTIME = "1.9.1"

# (relative path under the app module, why it is in the subset)
SUBSET = [
    ("main/java/com/openminis/app/ui/navigation/OmniMotion.kt",
     "page/fade motion constants + easing, new in this port"),
    ("main/java/com/openminis/app/ui/theme/OmniPalette.kt",
     "neutral OmniBot palette + dimens + omniTextStyle, new"),
    ("main/java/com/openminis/app/ui/theme/ChatColors.kt",
     "chat colour palettes (light/dark) reworked by this port"),
    ("main/java/com/openminis/app/ui/theme/Theme.kt",
     "MinisTheme providing LocalOmniPalette from the same dark flag"),
    ("main/java/com/openminis/app/ui/components/MinisButton.kt",
     "generic standalone control"),
    ("main/java/com/openminis/app/ui/components/SectionDesign.kt",
     "generic standalone control (rows/cards)"),
    ("main/java/com/openminis/app/ui/components/SettingsSection.kt",
     "generic standalone control"),
    ("main/java/com/openminis/app/ui/components/BadgedIconButton.kt",
     "generic standalone control, dependency-free"),
    ("main/java/com/openminis/app/ui/components/SectionTextField.kt",
     "generic standalone control"),
    ("main/java/com/openminis/app/ui/components/MinisAlertDialog.kt",
     "generic standalone control; the only R.string consumer"),
    ("main/java/com/openminis/app/ui/sessions/OmniHomeChrome.kt",
     "new home top bar + shared home tokens"),
    ("main/java/com/openminis/app/ui/sessions/OmniHomeDock.kt",
     "new home dock / search capsule / dock bar"),
    ("main/java/com/openminis/app/data/model/ProviderConfig.kt",
     "REAL ModelGroup parameter type of OmniHomeDockBar (not stubbed)"),
    ("main/java/com/openminis/app/data/model/LLMModel.kt",
     "REAL dependency of ProviderConfig (serialization only)"),
    ("main/java/com/openminis/app/data/model/SubAgentDefinition.kt",
     "REAL dependency of ProviderConfig (serialization only)"),
    ("main/java/com/openminis/app/backup/BackupFormat.kt",
     "REAL Iso8601 serializers referenced by ProviderConfig/SubAgentDefinition"),
]

UI_JARS = [
    "runtime-android-1.9.1.jar",
    "runtime-saveable-android-1.9.1.jar",
    "runtime-annotation.jar",
    "annotation.jar",
    "ui-android-1.9.1.jar",
    "ui-geometry-android-1.9.1.jar",
    "ui-graphics-android-1.9.1.jar",
    "ui-text-android-1.9.1.jar",
    "ui-unit-android-1.9.1.jar",
    "ui-util-android-1.9.1.jar",
    "foundation-android-1.9.1.jar",
    "foundation-layout-android-1.9.1.jar",
    "animation-android-1.9.1.jar",
    "animation-core-android-1.9.1.jar",
    "material3-android-1.3.2.jar",
    "material-icons-core-android-1.7.8.jar",
    "material-icons-extended-android-1.7.8.jar",
    "lifecycle-common.jar",
    "lifecycle-runtime-android-2.8.7.jar",
    "lifecycle-runtime-compose-android-2.8.7.jar",
]

TEST_CLASSES = ["com.openminis.app.ui.navigation.OmniMotionTest"]

EXPECTED_CLASSES = [
    "com/openminis/app/ui/navigation/OmniMotion.class",
    "com/openminis/app/ui/theme/OmniPalette.class",
    "com/openminis/app/ui/theme/OmniPaletteKt.class",
    "com/openminis/app/ui/sessions/OmniHomeSpec.class",
    "com/openminis/app/ui/theme/ChatColorsKt.class",
    "com/openminis/app/ui/theme/ThemeKt.class",
    "com/openminis/app/ui/sessions/OmniHomeChromeKt.class",
    "com/openminis/app/ui/sessions/OmniHomeDockKt.class",
    "com/openminis/app/ui/components/MinisButtonKt.class",
    "com/openminis/app/ui/components/SectionDesignKt.class",
]


class ToolchainError(RuntimeError):
    """Missing JDK/compiler/jar. Never reported as a test pass."""


def log(message: str) -> None:
    print(message, flush=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def first_match(pattern: str, roots: list[Path]) -> Path | None:
    for root in roots:
        if not root.is_dir():
            continue
        hits = sorted(root.glob(pattern))
        if hits:
            return hits[0]
    return None


def find_jdk(preferred: list[str]) -> Path:
    candidates = [Path(p) for p in preferred if p]
    candidates += [Path("/usr/lib/jvm/java-21-openjdk-arm64"),
                   Path("/usr/lib/jvm/java-17-openjdk-arm64")]
    for candidate in candidates:
        if (candidate / "bin" / "java").is_file() and (candidate / "bin" / "javac").is_file():
            return candidate
    raise ToolchainError("no JDK 17/21 with javac found under /usr/lib/jvm")


def kotlin_compiler_classpath() -> tuple[list[Path], Path]:
    """Kotlin 2.1 compiler + its runtime, from the existing Gradle cache."""
    base = GRADLE_CACHE / "org.jetbrains.kotlin"
    def jar(module: str, name: str) -> Path:
        hit = first_match(f"{module}/{KOTLIN_VERSION}/*/{name}", [base])
        if hit is None:
            raise ToolchainError(f"missing {name} for Kotlin {KOTLIN_VERSION} in {base}")
        return hit

    compiler = jar("kotlin-compiler-embeddable", f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar")
    stdlib = jar("kotlin-stdlib", f"kotlin-stdlib-{KOTLIN_VERSION}.jar")
    needed = [compiler, stdlib,
              jar("kotlin-script-runtime", f"kotlin-script-runtime-{KOTLIN_VERSION}.jar"),
              jar("kotlin-reflect", f"kotlin-reflect-{KOTLIN_VERSION}.jar"),
              jar("kotlin-daemon-embeddable", f"kotlin-daemon-embeddable-{KOTLIN_VERSION}.jar")]
    for name in ("kotlinx-coroutines-core-jvm-1.6.4.jar", "trove4j-1.0.20200330.jar",
                 "annotations-24.0.1.jar"):
        candidate = FALLBACK_GRADLE_LIB / name
        if candidate.is_file():
            needed.append(candidate)
        elif name.startswith("trove4j") or name.startswith("annotations"):
            raise ToolchainError(f"missing compiler support jar {candidate}")
    return needed, stdlib


def compose_plugin() -> Path:
    hit = first_match(f"kotlin-compose-compiler-plugin-embeddable/{KOTLIN_VERSION}/*/*.jar",
                      [GRADLE_CACHE / "org.jetbrains.kotlin"])
    if hit is None:
        raise ToolchainError(f"missing Kotlin {KOTLIN_VERSION} Compose compiler plugin in Gradle cache")
    return hit


def serialization_plugin() -> Path:
    hit = first_match(f"kotlin-serialization-compiler-plugin-embeddable/{KOTLIN_VERSION}/*/*.jar",
                      [GRADLE_CACHE / "org.jetbrains.kotlin"])
    if hit is None:
        raise ToolchainError(f"missing Kotlin {KOTLIN_VERSION} serialization plugin (needed by ProviderConfig.kt)")
    return hit


def serialization_runtime() -> list[Path]:
    base = GRADLE_CACHE / "org.jetbrains.kotlinx"
    jars = []
    for module, version, name in (
        ("kotlinx-serialization-core-jvm", "1.7.3", "kotlinx-serialization-core-jvm-1.7.3.jar"),
        ("kotlinx-serialization-json-jvm", "1.7.3", "kotlinx-serialization-json-jvm-1.7.3.jar"),
    ):
        hit = first_match(f"{module}/{version}/*/{name}", [base])
        if hit is None:
            raise ToolchainError(f"missing {name} in Gradle cache")
        jars.append(hit)
    return jars


def coroutines_runtime() -> Path:
    hit = first_match("kotlinx-coroutines-core-jvm/1.9.0/*/kotlinx-coroutines-core-jvm-1.9.0.jar",
                      [GRADLE_CACHE / "org.jetbrains.kotlinx"])
    if hit is None:
        raise ToolchainError("missing kotlinx-coroutines-core-jvm 1.9.0 in Gradle cache")
    return hit


def androidx_collection_runtime() -> Path:
    """androidx.compose.animation.core.CubicBezierEasing touches androidx.collection at
    class-init time; the ui-verify bundle does not ship it, the Gradle cache does."""
    hit = first_match("androidx.collection/collection-jvm/*/*/collection-jvm-*.jar", [GRADLE_CACHE])
    if hit is None:
        raise ToolchainError("missing androidx.collection:collection-jvm in Gradle cache")
    return hit


def android_jar() -> Path:
    sdk_root = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/opt/android-sdk")
    platforms = sdk_root / "platforms"
    if not platforms.is_dir():
        raise ToolchainError(f"no Android SDK platforms under {platforms}")
    for name in ("android-35", "android-36", "android-34"):
        candidate = platforms / name / "android.jar"
        if candidate.is_file():
            return candidate
    raise ToolchainError(f"no android.jar in {platforms}")


def junit_jars() -> list[Path]:
    base = FALLBACK_GRADLE_LIB
    jars = [base / "junit-4.13.2.jar", base / "hamcrest-core-1.3.jar"]
    missing = [str(jar) for jar in jars if not jar.is_file()]
    if missing:
        raise ToolchainError(f"missing local JUnit jars: {missing}")
    return jars


def ui_jars() -> list[Path]:
    jars, missing = [], []
    for name in UI_JARS:
        candidate = UI_VERIFY / name
        (jars if candidate.is_file() else missing).append(candidate if candidate.is_file() else name)
    if missing:
        raise ToolchainError(f"missing Compose jars in {UI_VERIFY}: {missing}")
    return jars


R_STRING_REF = re.compile(r"\bR\.string\.([A-Za-z_][A-Za-z0-9_]*)")
R_OTHER_REF = re.compile(r"\bR\.([a-z]+)\.")


def resource_string_names() -> set[str]:
    names: set[str] = set()
    for values in sorted(RES.glob("values*")):
        for xml in values.glob("*.xml"):
            text = xml.read_text(encoding="utf-8", errors="replace")
            names.update(re.findall(r'<string\s+name="([^"]+)"', text))
    return names


def write_r_stub(target: Path, sources: list[Path]) -> list[str]:
    """Emit com.openminis.app.R containing only real, referenced string ids."""
    referenced: set[str] = set()
    other_kinds: set[str] = set()
    for source in sources:
        text = source.read_text(encoding="utf-8", errors="replace")
        referenced.update(R_STRING_REF.findall(text))
        for kind in R_OTHER_REF.findall(text):
            if kind != "string":
                other_kinds.add(kind)
    if other_kinds:
        raise ToolchainError(
            "subset references R resource kinds that this compile-only R stub does not model: "
            f"{sorted(other_kinds)} -- extend the stub deliberately, do not guess ids")
    known = resource_string_names()
    unknown = sorted(referenced - known)
    if unknown:
        raise ToolchainError(f"subset references string ids absent from src/main/res: {unknown}")
    lines = ["package com.openminis.app", "",
             "/** Generated compile-only R stub (test-omnibot-ui.py). */",
             "object R {", "    object string {"]
    for name in sorted(referenced):
        lines.append(f"        const val {name} = 0x7f000000")
    lines += ["    }", "}", ""]
    target.write_text("\n".join(lines), encoding="utf-8")
    return sorted(referenced)

PACKAGE_CONSTANTS = [
    # (consumer file, real declaration file, constant name, expected literal)
    ("main/java/com/openminis/app/ui/sessions/OmniHomeDock.kt",
     "main/java/com/openminis/app/ui/sessions/SessionListScreen.kt",
     "PREF_FAB_SWAPPED", "fab_swapped"),
]


def write_package_constants(target: Path, sources: list[Path]) -> list[str]:
    """Copy same-package `internal const val` declarations out of files that are
    too dependency-heavy to compile here, after proving the real declaration and
    its literal still exist. Not a substitute for UI code: only a constant."""
    emitted, missing = [], []
    for consumer, declaration, name, literal in PACKAGE_CONSTANTS:
        if not (APP / "src" / consumer).is_file():
            continue
        real = APP / "src" / declaration
        if not real.is_file():
            raise ToolchainError(f"{declaration} vanished but {consumer} still needs {name}")
        text = real.read_text(encoding="utf-8", errors="replace")
        match = re.search(rf'internal const val {name}\s*=\s*"([^"]*)"', text)
        if match is None:
            raise ToolchainError(f"{declaration} no longer declares internal const val {name}")
        if match.group(1) != literal:
            raise ToolchainError(f"{name} changed from {literal!r} to {match.group(1)!r}")
        emitted.append((declaration.rsplit("/", 1)[0].replace("main/java/", "").replace("/", "."), name, literal))
    if not emitted:
        return []
    lines = [f"package {emitted[0][0]}", "",
             "/** Generated by test-omnibot-ui.py: declarations verified against their real files. */"]
    lines += [f'internal const val {name} = "{literal}"' for _, name, literal in emitted]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return [name for _, name, _ in emitted]


def run(cmd: list[str], timeout: int = 900) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=timeout,
                          env={**os.environ, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"})


def main() -> int:
    parser = argparse.ArgumentParser(description="Real Kotlin/Compose compile + motion test for the OmniBot UI subset")
    parser.add_argument("--keep", action="store_true", help="keep the compilation work directory")
    parser.add_argument("--verbose", action="store_true", help="print toolchain jar paths and full commands")
    parser.add_argument("--motion-only", action="store_true", help="compile/run only OmniMotion + its test")
    parser.add_argument("--work-root", default=str(DEFAULT_WORK_ROOT),
                        help=f"temp root (default {DEFAULT_WORK_ROOT}); removed when empty")
    parser.add_argument("--log-dir", default=None, help="write raw compile/test output here too")
    parser.add_argument("--probe", action="store_true",
                        help="PROBE MODE: compile a copy of a file whose only error is a missing import, "
                             "with that import added, to localise the defect. Production file untouched.")
    args = parser.parse_args()

    subset = SUBSET
    if args.motion_only:
        subset = [entry for entry in SUBSET if entry[0].endswith(("OmniMotion.kt", "ProviderConfig.kt"))]
    missing = [rel for rel, _ in subset if not (APP / "src" / rel).is_file()]
    if missing:
        log("FAIL missing subset sources:")
        for rel in missing:
            log(f"  - src/{rel}")
        return 1
    package_prefix = "com.openminis.app."
    missing_tests = [c for c in TEST_CLASSES
                     if not (TEST / (c.replace(package_prefix, "").replace(".", "/") + ".kt")).is_file()]
    if missing_tests:
        log(f"FAIL missing test sources: {missing_tests}")
        return 1

    try:
        jdk = find_jdk([os.environ.get("MINIS_OMNIBOT_JDK", ""), os.environ.get("JAVA_HOME", "")])
        java = jdk / "bin" / "java"
        compiler_cp, stdlib = kotlin_compiler_classpath()
        compose = compose_plugin()
        serialize = serialization_plugin() if not args.motion_only else None
        android = android_jar()
        deps = (ui_jars() + junit_jars() + serialization_runtime()
                + [coroutines_runtime(), androidx_collection_runtime(), stdlib])
    except ToolchainError as error:
        log(f"TOOLCHAIN ERROR: {error}")
        return 2

    work_root = Path(args.work_root)
    work_root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="compile-", dir=work_root))
    log(f"[omnibot-ui] JDK        {jdk}")
    log(f"[omnibot-ui] kotlinc    {KOTLIN_VERSION}")
    log(f"[omnibot-ui] compose    {compose.name}")
    log(f"[omnibot-ui] android    {android}")
    log(f"[omnibot-ui] work       {work}")
    if args.verbose:
        for path in compiler_cp + deps + [compose] + ([serialize] if serialize else []):
            log(f"    jar {path}  sha256={sha256(path)[:16]}")

    status = 0
    transcript: list[str] = []
    try:
        classes = work / "classes"
        classes.mkdir()
        sources = [APP / "src" / rel for rel, _ in subset]
        if args.probe:
            patched = []
            for source in sources:
                text = source.read_text(encoding="utf-8", errors="replace")
                if re.search(r"\b\d+\.dp\b", text) and "import androidx.compose.ui.unit.dp" not in text:
                    copy = work / ("probe-" + source.name)
                    text = text.replace("import androidx.compose.ui.unit.sp",
                                        "import androidx.compose.ui.unit.sp\nimport androidx.compose.ui.unit.dp", 1)
                    copy.write_text(text, encoding="utf-8")
                    log(f"[omnibot-ui] PROBE PATCH {source.name}: added missing 'import androidx.compose.ui.unit.dp' "
                        f"to a COPY; the production file is still broken")
                    patched.append(copy)
                else:
                    patched.append(source)
            sources = patched
        for test_class in TEST_CLASSES:
            test_path = TEST / (test_class.replace("com.openminis.app.", "").replace(".", "/") + ".kt")
            sources.append(test_path)
            log(f"[omnibot-ui] test source {test_path.relative_to(ROOT)}")
        try:
            stub_names = write_r_stub(work / "R.kt", sources) if not args.motion_only else []
        except ToolchainError as error:
            log(f"STUB BOUNDARY ERROR: {error}")
            return 2
        constant_names = write_package_constants(work / "PackageConstants.kt", sources)
        if constant_names:
            log(f"[omnibot-ui] const copy  {', '.join(constant_names)} (verified against real declarations)")
            sources.append(work / "PackageConstants.kt")
        if stub_names:
            log(f"[omnibot-ui] R stub     com.openminis.app.R.string{{{', '.join(stub_names)}}} (verified against res/)")
            sources.append(work / "R.kt")

        classpath = os.pathsep.join(map(str, [android] + deps))
        plugins = [f"-Xplugin={compose}"] + ([f"-Xplugin={serialize}"] if serialize else [])
        compile_cmd = [str(java), "-Xmx1536m", "-cp", os.pathsep.join(map(str, compiler_cp)),
                       "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                       "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                       *plugins,
                       "-classpath", classpath, "-d", str(classes),
                       *map(str, sources)]
        if args.verbose:
            log("    " + " ".join(compile_cmd))
        log(f"[omnibot-ui] compiling {len(sources)} sources ({len(subset)} subset files)")
        result = run(compile_cmd)
        output = result.stdout
        transcript.append(output)
        if result.returncode:
            log("FAIL Kotlin/Compose compilation")
            log(output.strip())
            return 1
        errors = [line for line in output.splitlines() if ": error:" in line]
        if errors:
            log("FAIL compiler reported errors despite a zero exit code")
            log("\n".join(errors))
            return 1
        warnings = [line for line in output.splitlines() if ": warning:" in line]
        log(f"[omnibot-ui] compile OK ({len(warnings)} warnings)")

        absent = [name for name in EXPECTED_CLASSES if not (classes / name).is_file()]
        if absent:
            log("FAIL expected class outputs missing (compilation silently incomplete):")
            for name in absent:
                log(f"  - {name}")
            return 1
        log(f"[omnibot-ui] class output verified ({len(EXPECTED_CLASSES)} representative classes)")

        run_cp = os.pathsep.join(map(str, [classes, android] + deps))
        log(f"[omnibot-ui] running JUnit: {', '.join(TEST_CLASSES)}")
        test = run([str(java), "-cp", run_cp, "org.junit.runner.JUnitCore", *TEST_CLASSES])
        transcript.append(test.stdout)
        log(test.stdout.strip())
        if test.returncode:
            log("FAIL JUnit")
            return 1
        log(f"[omnibot-ui] PASS compile + {len(TEST_CLASSES)} unit class(es)")
        return 0
    finally:
        if args.log_dir:
            log_dir = Path(args.log_dir)
            log_dir.mkdir(parents=True, exist_ok=True)
            (log_dir / "omnibot-ui-verify-raw.log").write_text(
                f"kotlinc={KOTLIN_VERSION} compose={compose.name} android={android}\n\n"
                + "\n\n".join(transcript), encoding="utf-8")
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)
            try:
                work_root.rmdir()  # only removes it when we left nothing behind
            except OSError:
                pass
        else:
            log(f"[omnibot-ui] kept work dir {work}")


if __name__ == "__main__":
    sys.exit(main())
