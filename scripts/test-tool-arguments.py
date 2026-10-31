#!/usr/bin/env python3
"""Focused native-JVM tests for tool-argument handling (no Gradle, no Android).

Compiles the REAL Kotlin sources (not copies) with the Kotlin 2.0.20 compiler
embedded in the local Gradle 8.11.1 distribution, then runs JUnit 4.13.2 with a
plain `java` command on the local JDK 21.

Test selection is exact: only these fully-qualified test classes are handed to
JUnitCore:

  * com.openminis.app.provider.openai.ToolArgumentBufferTest          (required)
  * com.openminis.app.provider.openai.ResponsesToolCallAssemblerTest  (optional)
  * com.openminis.app.provider.openai.ChatToolCallIndexTest           (optional)

A unit whose production source or test source does not exist yet is reported as
SKIPPED and is explicitly NOT counted as a pass. Nothing else is discovered or
executed.

Dependencies (junit 4.13.2, hamcrest-core 1.3, org.json 20231013) are taken from
the local Gradle distribution when present, otherwise downloaded from Maven
Central (or a trusted mirror) into a cache directory. SHA-256 of every jar is
printed and recorded; this is content pinning for reproducibility only -- no
PGP/GPG signature verification is performed or claimed.

Usage:
  scripts/test-tool-arguments.py [--work-dir DIR] [--keep] [--dep-cache DIR]
                                [--log-dir DIR] [--require-all] [--verbose]

Exit codes: 0 = every runnable unit passed (skips allowed unless --require-all),
1 = test or compile failure, 2 = toolchain/infrastructure error,
3 = --require-all and at least one unit was skipped.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MAIN_PKG = REPO_ROOT / "src/android/app/src/main/java/com/openminis/app/provider/openai"
TEST_PKG = REPO_ROOT / "src/android/app/src/test/java/com/openminis/app/provider/openai"

DEFAULT_JDK = Path("/usr/lib/jvm/java-21-openjdk-arm64")
GRADLE_LIB = Path("/opt/gradle-8.11.1/lib")
DEFAULT_DEP_CACHE = Path.home() / ".cache" / "minis-tool-arguments"

KOTLIN_VERSION = "2.0.20"
JUNIT_VERSION = "4.13.2"
HAMCREST_VERSION = "1.3"
ORG_JSON_VERSION = "20231013"

PKG = "com.openminis.app.provider.openai"

# (unit id, main source, test source, exact test class, required)
UNITS = [
    ("buffer", "ToolArgumentBuffer.kt", "ToolArgumentBufferTest.kt",
     f"{PKG}.ToolArgumentBufferTest", True),
    ("assembler", "ResponsesToolCallAssembler.kt", "ResponsesToolCallAssemblerTest.kt",
     f"{PKG}.ResponsesToolCallAssemblerTest", False),
    ("index", "ChatToolCallIndex.kt", "ChatToolCallIndexTest.kt",
     f"{PKG}.ChatToolCallIndexTest", False),
]

# Sibling production sources are added to a unit's compilation ONLY when they are
# dependency-free apart from these prefixes; this keeps Android/OkHttp/coroutines
# code out of the native JVM build while still letting, e.g., the assembler see
# ToolArgumentBuffer and the safeOptString helper.
ALLOWED_IMPORT_PREFIXES = (
    "org.json",
    "kotlin.",
    "java.",
    "javax.",
    "com.openminis.app.provider.safeOptString",
)

MAVEN_BASES = [
    ("maven-central", "https://repo1.maven.org/maven2"),
    ("maven-central-apache", "https://repo.maven.apache.org/maven2"),
    ("google-maven-central-mirror", "https://maven-central.storage-download.googleapis.com/maven2"),
]

# Content hashes observed for these exact artifacts. Recorded, not trusted.
KNOWN_SHA256 = {
    "org.json-20231013.jar": "0f18192df289114e17aa1a0d0a7f8372cc9f5c7e4f7e39adcf8906fe714fa7d3",
    "junit-4.13.2.jar": "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3",
    "hamcrest-core-1.3.jar": "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9",
}


class ToolchainError(RuntimeError):
    """Missing JDK/compiler/dependency; not a test failure."""


def run(cmd: list[str], cwd: Path | None = None, timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        env={**os.environ, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"},
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_local_jar(names: list[str], roots: list[Path]) -> Path | None:
    for root in roots:
        for name in names:
            candidate = root / name
            if candidate.is_file():
                return candidate
    return None


def download_jar(relative: str, dest: Path, verbose: bool) -> tuple[Path, str]:
    """Download one Maven artifact, trying each trusted base. Returns (path, base)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    errors: list[str] = []
    for base_name, base in MAVEN_BASES:
        url = f"{base}/{relative}"
        part = dest.with_suffix(dest.suffix + ".part")
        try:
            if verbose:
                print(f"    GET {url}")
            request = urllib.request.Request(url, headers={"User-Agent": "minis-toolargs-runner/1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != 200:
                    raise RuntimeError(f"HTTP {response.status}")
                with part.open("wb") as out:
                    shutil.copyfileobj(response, out)
            if part.stat().st_size < 1024:
                raise RuntimeError(f"implausibly small artifact ({part.stat().st_size} bytes)")
            os.replace(part, dest)
            return dest, base_name
        except Exception as exc:  # noqa: BLE001 - try the next mirror
            errors.append(f"{base_name}: {exc}")
            part.unlink(missing_ok=True)
    raise ToolchainError(
        f"could not download {relative} from any trusted Maven base:\n      " + "\n      ".join(errors)
    )


def resolve_dep(filename: str, relative: str, cache: Path, verbose: bool,
                local_roots: list[Path]) -> Path:
    cached = cache / filename
    if cached.is_file():
        return cached
    local = find_local_jar([filename], local_roots)
    if local is not None:
        shutil.copy2(local, cached)
        return cached
    print(f"  downloading {filename} from Maven (cache: {cache})")
    path, base = download_jar(relative, cached, verbose)
    print(f"    source: {base}")
    return path


def resolve_deps(cache: Path, verbose: bool) -> dict[str, Path]:
    cache.mkdir(parents=True, exist_ok=True)
    lib = GRADLE_LIB
    deps = {
        "junit": resolve_dep(
            f"junit-{JUNIT_VERSION}.jar", f"junit/junit/{JUNIT_VERSION}/junit-{JUNIT_VERSION}.jar",
            cache, verbose, [lib]),
        "hamcrest": resolve_dep(
            f"hamcrest-core-{HAMCREST_VERSION}.jar",
            f"org/hamcrest/hamcrest-core/{HAMCREST_VERSION}/hamcrest-core-{HAMCREST_VERSION}.jar",
            cache, verbose, [lib]),
        "orgjson": resolve_dep(
            f"org.json-{ORG_JSON_VERSION}.jar",
            f"org/json/json/{ORG_JSON_VERSION}/json-{ORG_JSON_VERSION}.jar",
            cache, verbose, [lib]),
    }
    if deps["orgjson"].stat().st_size < 10_000:
        raise ToolchainError(f"org.json jar looks truncated: {deps['orgjson']}")
    return deps


def compiler_classpath() -> tuple[list[str], Path]:
    """Kotlin embeddable compiler needs its own runtime jars, incl. annotations."""
    lib = GRADLE_LIB
    needed = [
        f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar",
        f"kotlin-stdlib-{KOTLIN_VERSION}.jar",
        f"kotlin-script-runtime-{KOTLIN_VERSION}.jar",
        f"kotlin-reflect-{KOTLIN_VERSION}.jar",
        f"kotlin-daemon-embeddable-{KOTLIN_VERSION}.jar",
        "kotlinx-coroutines-core-jvm-1.6.4.jar",
        "trove4j-1.0.20200330.jar",
        "annotations-24.0.1.jar",
    ]
    missing = [name for name in needed if not (lib / name).is_file()]
    if missing:
        raise ToolchainError(f"missing Kotlin compiler jars in {lib}: {missing}")
    stdlib = lib / f"kotlin-stdlib-{KOTLIN_VERSION}.jar"
    return [str(lib / name) for name in needed], stdlib


def pure_support_sources(unit_main_name: str) -> tuple[list[Path], list[str]]:
    """Sibling sources in the same package that are safe to compile natively."""
    support: list[Path] = []
    excluded: list[str] = []
    for candidate in sorted(MAIN_PKG.glob("*.kt")):
        if candidate.name == unit_main_name:
            continue
        text = candidate.read_text(encoding="utf-8", errors="replace")
        imports = re.findall(r"^import\s+([^\s;]+)", text, re.M)
        if all(imp.startswith(ALLOWED_IMPORT_PREFIXES) for imp in imports):
            support.append(candidate)
        else:
            excluded.append(candidate.name)
    helper = REPO_ROOT / "src/android/app/src/main/java/com/openminis/app/provider/JsonExt.kt"
    if helper.is_file():
        imports = re.findall(r"^import\s+([^\s;]+)", helper.read_text(encoding="utf-8", errors="replace"), re.M)
        if all(imp.startswith(ALLOWED_IMPORT_PREFIXES) for imp in imports):
            support.append(helper)
    return support, excluded


def jdk_tools(jdk: Path) -> tuple[Path, Path]:
    java, javac = jdk / "bin/java", jdk / "bin/javac"
    if not java.is_file():
        raise ToolchainError(f"JDK java not found at {java}")
    if not javac.is_file():
        raise ToolchainError(f"JDK javac not found at {javac}")
    return java, javac


def parse_junit_output(output: str) -> tuple[int | None, int | None]:
    """Return (tests_run, failures) parsed from JUnitCore console output."""
    ok = re.search(r"^OK \((\d+) tests?\)", output, re.M)
    if ok:
        count = int(ok.group(1))
        return count, 0
    fail = re.search(r"^Tests run: (\d+),\s+Failures: (\d+)", output, re.M)
    if fail:
        return int(fail.group(1)), int(fail.group(2))
    return None, None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compile the real tool-argument Kotlin sources and run the three exact JUnit test classes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--work-dir", type=Path, default=None,
                        help="keep classes/deps here instead of an auto-deleted temp dir")
    parser.add_argument("--keep", action="store_true",
                        help="do not delete the auto-created temp work dir")
    parser.add_argument("--dep-cache", type=Path, default=DEFAULT_DEP_CACHE,
                        help=f"persistent jar cache (default: {DEFAULT_DEP_CACHE})")
    parser.add_argument("--log-dir", type=Path, default=None,
                        help="write compile/test logs here in addition to stdout")
    parser.add_argument("--jdk", type=Path, default=DEFAULT_JDK, help=f"JDK home (default: {DEFAULT_JDK})")
    parser.add_argument("--require-all", action="store_true",
                        help="exit 3 if any optional unit is still missing")
    parser.add_argument("--verbose", action="store_true", help="echo each compiler/download command")
    args = parser.parse_args()

    java, javac = jdk_tools(args.jdk)
    comp_cp, stdlib = compiler_classpath()

    version = run([str(java), "-version"])
    java_version = (version.stdout or "").strip().splitlines()[0] if version.stdout else "unknown"

    print("== tool-argument JVM tests ==")
    print(f"repo:      {REPO_ROOT}")
    print(f"jdk:       {args.jdk} ({java_version})")
    print(f"compiler:  Kotlin {KOTLIN_VERSION} (embeddable, {GRADLE_LIB})")
    print(f"junit:     {JUNIT_VERSION} + hamcrest {HAMCREST_VERSION}")
    print()

    try:
        deps = resolve_deps(args.dep_cache, args.verbose)
    except ToolchainError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    print("dependencies:")
    for name, path in deps.items():
        digest = sha256(path)
        expected = KNOWN_SHA256.get(path.name)
        tag = "matches recorded hash" if expected == digest else (
            "no recorded hash" if expected is None else "HASH DIFFERS FROM RECORDED")
        print(f"  {name:9s} {path}  sha256={digest} ({tag})")
    print("  note: hashes pin content only; no PGP/signature authentication is claimed.")
    print()

    work_dir_given = args.work_dir is not None
    work_dir = args.work_dir if work_dir_given else Path(tempfile.mkdtemp(prefix="minis-toolargs-"))
    work_dir.mkdir(parents=True, exist_ok=True)
    classes_root = work_dir / "classes"
    deps_dir = work_dir / "deps"
    deps_dir.mkdir(parents=True, exist_ok=True)

    # Stage dependency jars inside the work dir so it is self-contained.
    staged: dict[str, Path] = {}
    for name, path in deps.items():
        target = deps_dir / path.name
        if not target.exists():
            shutil.copy2(path, target)
        staged[name] = target
    dep_cp = [str(staged["junit"]), str(staged["hamcrest"]), str(staged["orgjson"])]
    runtime_cp = [str(stdlib)] + dep_cp

    log_dir = args.log_dir
    if log_dir:
        log_dir.mkdir(parents=True, exist_ok=True)
    logs: list[str] = []

    print(f"work dir:  {work_dir}")
    print(f"log dir:   {log_dir if log_dir else '(stdout only)'}")
    print()

    results: list[dict] = []
    try:
        for unit_id, main_name, test_name, test_class, required in UNITS:
            main_src = MAIN_PKG / main_name
            test_src = TEST_PKG / test_name
            entry = {
                "id": unit_id, "main": main_src, "test": test_src, "class": test_class,
                "required": required, "status": "SKIPPED", "tests": None, "failures": None,
                "detail": "", "compile_cmd": None, "run_cmd": None,
            }
            missing = []
            if not main_src.is_file():
                missing.append(f"main {main_name}")
            if not test_src.is_file():
                missing.append(f"test {test_name}")
            if missing:
                entry["detail"] = "not present yet: " + ", ".join(missing) + " (NOT counted as pass)"
                results.append(entry)
                print(f"[{unit_id}] SKIPPED — {entry['detail']}")
                print()
                continue

            out_dir = classes_root / unit_id
            out_dir.mkdir(parents=True, exist_ok=True)
            support, excluded = pure_support_sources(main_name)
            base_sources = [str(main_src), str(test_src)]
            attempts: list[tuple[str, list[str]]] = [("unit-only", base_sources)]
            if support:
                attempts.append(("with-support", base_sources + [str(path) for path in support]))

            compile_cmd: list[str] = []
            compiled = None
            compile_mode = "unit-only"
            for compile_mode, sources in attempts:
                compile_cmd = [
                    str(java), "-cp", os.pathsep.join(comp_cp),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17",
                    "-classpath", os.pathsep.join([str(stdlib)] + dep_cp),
                    "-d", str(out_dir),
                ] + sources
                if args.verbose:
                    print("  $ " + shlex.join(compile_cmd))
                compiled = run(compile_cmd, timeout=300)
                if compiled.returncode == 0:
                    break

            entry["compile_cmd"] = shlex.join(compile_cmd)
            entry["compile_mode"] = compile_mode
            entry["support"] = [] if compile_mode == "unit-only" else [str(p) for p in support]
            if compile_mode == "with-support":
                print(f"[{unit_id}] compiled with support sources: "
                      + ", ".join(Path(path).name for path in entry["support"]))
            if excluded and not required:
                print(f"[{unit_id}] non-native siblings left out: " + ", ".join(excluded))
            if log_dir:
                (log_dir / f"{unit_id}.compile.log").write_text(
                    "$ " + entry["compile_cmd"] + "\n\n" + compiled.stdout, encoding="utf-8")
            if compiled.returncode != 0:
                entry["status"] = "COMPILE-FAIL"
                entry["detail"] = f"kotlinc exit {compiled.returncode}"
                results.append(entry)
                print(f"[{unit_id}] COMPILE-FAIL (kotlinc exit {compiled.returncode})")
                print(compiled.stdout.rstrip())
                print()
                continue

            run_cp = os.pathsep.join([str(out_dir)] + runtime_cp)
            run_cmd = [str(java), "-cp", run_cp, "org.junit.runner.JUnitCore", test_class]
            entry["run_cmd"] = shlex.join(run_cmd)
            if args.verbose:
                print("  $ " + entry["run_cmd"])
            tested = run(run_cmd, timeout=180)
            if log_dir:
                (log_dir / f"{unit_id}.test.log").write_text(
                    "$ " + entry["run_cmd"] + "\n\n" + tested.stdout, encoding="utf-8")
            count, failures = parse_junit_output(tested.stdout)
            entry["tests"], entry["failures"] = count, failures
            if tested.returncode == 0 and failures == 0:
                entry["status"] = "PASS"
                entry["detail"] = f"{count} tests"
                print(f"[{unit_id}] PASS — {count} tests, 0 failures")
                print(f"  $ {entry['run_cmd']}")
            else:
                entry["status"] = "FAIL"
                entry["detail"] = f"exit {tested.returncode}, tests={count}, failures={failures}"
                print(f"[{unit_id}] FAIL — {entry['detail']}")
                print(f"  $ {entry['run_cmd']}")
                print(tested.stdout.rstrip())
            results.append(entry)
            print()

        print("== summary ==")
        header = f"{'unit':10s} {'status':12s} {'tests':>5s} {'fail':>4s}  detail"
        print(header)
        print("-" * len(header))
        for entry in results:
            tests = "-" if entry["tests"] is None else str(entry["tests"])
            fails = "-" if entry["failures"] is None else str(entry["failures"])
            print(f"{entry['id']:10s} {entry['status']:12s} {tests:>5s} {fails:>4s}  {entry['detail']}")
        passed = [e for e in results if e["status"] == "PASS"]
        failed = [e for e in results if e["status"] in ("FAIL", "COMPILE-FAIL")]
        skipped = [e for e in results if e["status"] == "SKIPPED"]
        total_tests = sum(e["tests"] or 0 for e in passed)
        print(f"\npassed units: {len(passed)}/{len(results)}  "
              f"(tests run: {total_tests})  failed: {len(failed)}  skipped: {len(skipped)}")
        if skipped:
            print("skipped units are NOT passes: " + ", ".join(e["id"] for e in skipped))

        logs.append("== summary ==")
        logs.append(header)
        for entry in results:
            tests = "-" if entry["tests"] is None else str(entry["tests"])
            fails = "-" if entry["failures"] is None else str(entry["failures"])
            logs.append(f"{entry['id']:10s} {entry['status']:12s} {tests:>5s} {fails:>4s}  {entry['detail']}")
            if entry["compile_cmd"]:
                logs.append("  compile: " + entry["compile_cmd"])
            if entry["run_cmd"]:
                logs.append("  run:     " + entry["run_cmd"])

        if failed:
            return 1
        if args.require_all and skipped:
            return 3
        return 0
    finally:
        if log_dir:
            (log_dir / "summary.log").write_text("\n".join(logs) + "\n", encoding="utf-8")
        if not work_dir_given and not args.keep:
            shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ToolchainError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
