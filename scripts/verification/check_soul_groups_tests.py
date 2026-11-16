#!/usr/bin/env python3
"""Focused OFFLINE JUnit run for SOUL / system-prompt / group-avatar tests.

This is a TEST run on a plain JVM, not an APK build and not a device test.
It reuses the dependency resolution and compile-only stubs of
``scripts/verification/typecheck_sources.py`` (imported as a module) so the
production classes under test are compiled from the real worktree with the
real cached AndroidX/Compose/Kotlin jars, and then compiles ONLY the selected
test sources plus the test helpers they import, and runs them with JUnit 4.

What is real
------------
* every production Kotlin source under ``src/android/app/src/main/java``
  (snapshot copy, so concurrent edits cannot tear a class set mid-run)
* the jars Gradle would put on the compile classpath (gradle module cache)
* ``android.jar`` of the declared compileSdk
* JUnit 4.13.2 + Robolectric 4.17 from the pre-existing offline bundle
  ``/var/cache/minis/ui-verify-robolectric`` (test-only jars are NOT in the
  Gradle cache; the bundle is read-only and never modified)
* ``org.json`` extracted from the bundle's ``android-all`` jar, because unit
  tests run against a mockable android.jar that has no org.json bodies
* the **unit-test android runtime Gradle would use**: a plain (non-Robolectric)
  JUnit class runs against a *mockable* ``android.jar`` built by AGP's own
  ``com.android.builder.testing.MockableJarGenerator`` (the class Gradle 8.7.3
  runs for ``testOptions.unitTests.returnDefaultValues = true``), executed
  offline from the Gradle module cache. Every ``android.*`` method then
  returns its default value (0 / false / null) exactly like Gradle's unit test
  JVM, instead of throwing ``RuntimeException: Stub!`` from the real stub
  ``android.jar``. Production and test *bytecode* is never rewritten and no
  business method is stubbed: only the framework-only ``android.jar`` is
  swapped for the same variant Gradle uses. Robolectric classes keep the real
  ``android.jar`` (Robolectric instruments ``android-all`` itself).
* declared ``testImplementation`` jars that exist in the Gradle module cache
  (e.g. ``org.xerial:sqlite-jdbc:3.41.2.2`` for the Room migration test) are
  put on the test runtime classpath, as Gradle would. A missing one is
  reported, never faked.

What is NOT real
----------------
* no Gradle, no aapt2, no KSP/Room codegen, no dex, no packaging
* no device: Robolectric tests run in Robolectric's JVM sandbox. On Linux
  aarch64 Robolectric's NATIVE graphics runtime refuses to load
  ("The Robolectric native runtime is not supported on Linux (aarch64)"), so
  a test that demands ``@GraphicsMode(NATIVE)`` is reported as ENV-GAP: not a
  pass, not a source failure.
* an ENV-GAP never counts as a pass. Three kinds are recognised and recorded
  with their own reason: ``robolectric-native`` (native runtime absent),
  ``android-stub`` (a real stub ``android.jar`` body was reached because the
  mockable jar could not be produced) and ``missing-test-dep`` (a declared
  test-only jar such as sqlite-jdbc is absent from the offline cache).

Usage
-----
  python3 scripts/verification/check_soul_groups_tests.py \\
      --out /tmp/minis-resume-20261116-verify --label soul-groups

  # discover every SOUL/group test present right now (incl. brand-new files)
  python3 scripts/verification/check_soul_groups_tests.py --list
  # compile production + tests only, run nothing
  python3 scripts/verification/check_soul_groups_tests.py --only-compile
  # reuse another run's already-compiled production classes (no second compile)
  python3 scripts/verification/check_soul_groups_tests.py \\
      --reuse-main /var/minis/workspace/tmp/minis-resume-20261116/soul/val/typecheck/current

Exit codes: 0 = every selected test passed; 1 = at least one test failure or
ENV-GAP; 2 = environment/compile failure; 3 = source tree vanished mid-run.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import typecheck_sources as tc  # noqa: E402  (same-directory helper, no network)

REPO_DEFAULT = Path("/var/minis/shared/Minis-Next")
OUT_DEFAULT = Path("/tmp/minis-resume-20261116-verify")
BUNDLE_DEFAULT = Path(os.environ.get("MINIS_OFFLINE_TEST_BUNDLE",
                                     "/var/cache/minis/ui-verify-robolectric"))
MAIN_PKG = "com/openminis/app"
TEST_PKG = "com.openminis.app"

# Contract tests for this task. A missing one is reported, never skipped
# silently: a rename must show up as a gap in the report.
PINNED_TESTS = [
    "com.openminis.app.agent.prompts.PromptCatalogScopeTest",
    "com.openminis.app.ui.settings.UnifiedPromptSettingsTest",
    "com.openminis.app.agent.SystemPromptInjectionWiringTest",
    "com.openminis.app.agent.ApplicationSystemPromptTest",
    "com.openminis.app.provider.SystemChannelPlacementTest",
    "com.openminis.app.agent.SoulBodyLimitPolicyTest",
    "com.openminis.app.agent.SoulPromptRulesTest",
    "com.openminis.app.agent.SoulIconTest",
    "com.openminis.app.agent.SoulIconAspectRatioTest",
    "com.openminis.app.agent.SoulIconConfigEdgeTest",
    "com.openminis.app.agent.SoulIconImportPlanTest",
    "com.openminis.app.agent.SoulIconSidecarTest",
    "com.openminis.app.agent.SoulIconImportRoboTest",
    "com.openminis.app.ui.settings.SoulSettingsPageContractTest",
    "com.openminis.app.ui.settings.SoulPromptModeUiContractTest",
    "com.openminis.app.ui.sessions.GroupMemberTileTest",
]

# Files matched in addition to PINNED_TESTS so newly added tests (group avatar
# migration/backup, SOUL profiles) are picked up without editing this script.
DEFAULT_PATTERNS = (
    r".*/Soul[A-Za-z0-9_]*Test\.kt$",
    r".*/[A-Za-z0-9_]*SoulProfiles[A-Za-z0-9_]*Test\.kt$",
    r".*/SystemPromptInjectionWiringTest\.kt$",
    r".*/ApplicationSystemPromptTest\.kt$",
    r".*/UnifiedPromptSettingsTest\.kt$",
    r".*/PromptCatalogScopeTest\.kt$",
    r".*/KairosPromptModeTest\.kt$",
    r".*/GroupMemberTileTest\.kt$",
    r".*/GroupRunningRingTest\.kt$",
    r".*/[A-Za-z0-9_]*GroupAvatar[A-Za-z0-9_]*Test\.kt$",
    r".*/[A-Za-z0-9_]*Avatar[A-Za-z0-9_]*Test\.kt$",
    r".*/[A-Za-z0-9_]*GroupTile[A-Za-z0-9_]*Test\.kt$",
)

# Bundle jars that must NOT shadow the Gradle-cache versions of the same libs.
BUNDLE_BLOCK_PREFIXES = (
    "kotlin-stdlib", "kotlin-stdlib-jdk", "kotlin-reflect", "kotlinx-coroutines-core",
    "kotlinx-coroutines-android", "kotlinx-coroutines-core-jvm",
    "kotlinx-serialization-core", "kotlinx-serialization-json",
    "annotation-1.9.1.jar", "annotations-23.0.0.jar",
)

ROBOLECTRIC_JVM_ARGS = (
    "-Drobolectric.offline=true",
    "-Drobolectric.logging.enabled=false",
    "-Drobolectric.graphicsMode=LEGACY",
    "-Drobolectric.sqliteMode=LEGACY",
)

# AGP's own mockable-jar generator, used the way Gradle's unit test task uses it
# (testOptions.unitTests.returnDefaultValues = true). Written into the shim cache,
# never into the repo. Compiled by the JDK's single-file source launcher.
MOCKABLE_LAUNCHER_SRC = """import com.android.builder.testing.MockableJarGenerator;

public final class MockableJarMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: MockableJarMain <returnDefaultValues> <in.jar> <out.jar>");
            System.exit(64);
        }
        new MockableJarGenerator(Boolean.parseBoolean(args[0]))
                .createMockableJar(new java.io.File(args[1]), new java.io.File(args[2]));
        System.out.println("mockable jar written: " + args[2]);
    }
}
"""

# Class dirs a previous run may have produced. ``typecheck_sources.py`` writes
# ``work/out/{kotlin,java1,java2}``; older focused runs of this script wrote
# ``work/classes/{main,java1}`` and ``work/out/classes/main``.
MAIN_CLASS_CANDIDATES = (
    "work/out/kotlin",
    "work/classes/main",
    "work/out/classes/main",
    "work/classes/kotlin",
    "work/out/classes/kotlin",
)
JAVA_CLASS_CANDIDATES = (
    "work/out/java1",
    "work/classes/java1",
    "work/out/java",
    "work/classes/java",
)
JAVA_CLASS_EXTRA_CANDIDATES = (
    "work/out/java2",
    "work/classes/java2",
)

# Why a failing class is an environment gap instead of a source verdict.
# (kind, marker substring of the failure's exception line, note, extra keywords)
ENV_GAP_MARKERS = (
    ("robolectric-native", "Robolectric native runtime is not supported",
     "Robolectric NATIVE graphics runtime unavailable on Linux/aarch64"),
    ("android-stub", "RuntimeException: Stub!",
     "a real stub android.jar body was reached: the mockable unit-test "
     "android.jar could not be produced on this host"),
    ("missing-test-dep", "No suitable driver found for jdbc:sqlite",
     "declared testImplementation org.xerial:sqlite-jdbc is absent from the "
     "offline Gradle module cache"),
)


def log(msg=""):
    print(msg, flush=True)


class Runner:
    def __init__(self, args):
        self.args = args
        self.repo = args.repo.resolve()
        self.out = args.out.expanduser().resolve()
        self.run_dir = self.out / "focused-tests" / args.label
        self.logs = self.run_dir / "logs"
        self.work = self.run_dir / "work"
        self.events = []
        self.warnings = []
        self.java_home = tc.resolve_java_home()
        self.bundle = args.bundle.expanduser().resolve()
        self.tmp = self.work / "tmp"
        self.mockable_info = None
        self.test_dep_info = {"resolved": [], "absent_from_module_cache": []}

    # ---------------------------------------------------------------- helpers
    def prepare(self):
        for d in (self.logs, self.work, self.tmp):
            tc.ensure_inside(d, self.out, "run dir")
            d.mkdir(parents=True, exist_ok=True)
        (self.tmp / "java").mkdir(exist_ok=True)

    def env(self):
        e = dict(os.environ)
        e.update({
            "JAVA_HOME": str(self.java_home),
            "ANDROID_HOME": str(tc.SDK_DEFAULT),
            "TMPDIR": str(self.tmp),
            "KOTLIN_DAEMON_OPTS": "-Xmx512m",
            "GRADLE_USER_HOME": str(tc.GRADLE_HOME),
        })
        e.pop("CLASSPATH", None)
        return e

    def run(self, cmd, logfile, timeout=3600, phase="", cwd=None, extra_env=None):
        """Run a command, record pid/timing, tee merged output into logfile."""
        env = self.env()
        if extra_env:
            env.update(extra_env)
        log(f"\n$ [{phase}] {' '.join(str(c)[:120] for c in cmd[:3])}")
        started = time.time()
        p = subprocess.Popen([str(c) for c in cmd], cwd=str(cwd) if cwd else None,
                             env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, errors="replace")
        out, _ = p.communicate(timeout=timeout)
        rc, elapsed = p.returncode, time.time() - started
        Path(logfile).parent.mkdir(parents=True, exist_ok=True)
        with open(logfile, "w", encoding="utf-8", errors="replace") as fh:
            fh.write(f"# phase: {phase}\n# pid: {p.pid}\n# cwd: {cwd or os.getcwd()}\n"
                     f"# cmd: {cmd}\n# rc: {rc}  elapsed: {elapsed:.1f}s\n"
                     f"# TMPDIR: {env.get('TMPDIR')}\n\n")
            fh.write(tc.redact(out))
        self.events.append({
            "phase": phase, "pid": p.pid, "rc": rc, "seconds": round(elapsed, 1),
            "started": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(started)),
            "log": str(logfile), "argv": [str(c)[:160] for c in cmd][:8],
        })
        log(f"  rc={rc}  elapsed={elapsed:.1f}s  log={logfile}")
        return rc, out

    # ------------------------------------------------------------- production
    def snapshot(self):
        app = self.repo / "src" / "android" / "app"
        snap, rows = tc.snapshot_sources(app, self.work,
                                         self.logs / "01-source-snapshot.sha256")
        return snap, rows, app

    def namespace(self, app):
        text = (app / "build.gradle.kts").read_text(encoding="utf-8", errors="replace")
        m = re.search(r'namespace\s*=\s*"([\w.]+)"', text)
        return m.group(1) if m else "com.openminis.app"

    def compile_sdk(self, app):
        text = (app / "build.gradle.kts").read_text(encoding="utf-8", errors="replace")
        m = re.search(r"compileSdk\s*=\s*(\d+)", text)
        return m.group(1) if m else "36"

    def stubs(self, snap, app):
        res_types = tc.parse_resources(snap / "res", self.warnings)
        refs = tc.scan_source_res_refs(snap / "java")
        added = 0
        for rtype, names in refs.items():
            have = res_types.setdefault(rtype, set())
            for n in names:
                if n not in have:
                    have.add(n)
                    added += 1
        ns = self.namespace(app)
        r_java = self.work / "stubs" / "com" / "openminis" / "app" / "R.java"
        bc_java = self.work / "stubs" / "com" / "openminis" / "app" / "BuildConfig.java"
        r_count = tc.write_r_java(ns, res_types, r_java)
        fields = tc.parse_build_config_fields(app / "build.gradle.kts")
        bc_count = tc.write_build_config_java(ns, fields, bc_java)
        with open(self.logs / "02-stubs.log", "w", encoding="utf-8") as fh:
            fh.write("COMPILE-ONLY STUBS (aapt2/KSP never ran)\n"
                     f"R.java fields={r_count} types={len(res_types)} "
                     f"source_only_names={added}\n"
                     f"BuildConfig fields={bc_count} {sorted(fields)}\n")
        log(f"  stubs: R {r_count} fields, BuildConfig {bc_count} fields")
        return r_java, bc_java, res_types

    # ------------------------------------------------------- reuse a prior run
    def reuse_main(self, run_dir):
        """Adopt another run's compiled production classes if the source snapshot
        hash matches exactly. Returns (classes, java_dirs, match, note, rows) or
        None.

        Accepts both layouts this repo produces: a ``typecheck_sources.py`` run
        (``work/out/kotlin`` + ``work/out/java1|java2``) and an older focused
        run of this script (``work/classes/main`` + ``work/classes/java1``).
        `classes` is a classpath string because a stale reuse may need an
        overlay dir in front of the reused classes.
        """
        run_dir = run_dir.expanduser().resolve()
        classes_dir = None
        for rel in MAIN_CLASS_CANDIDATES:
            cand = run_dir / rel
            if cand.is_dir() and any(cand.rglob("*.class")):
                classes_dir = cand
                break
        if classes_dir is None:
            return None
        java_dirs = [run_dir / rel for rel in
                     (JAVA_CLASS_CANDIDATES + JAVA_CLASS_EXTRA_CANDIDATES)
                     if (run_dir / rel).is_dir()]
        their_log = run_dir / "logs" / "01-source-snapshot.sha256"
        their_rows = []
        if their_log.is_file():
            their_rows = [l for l in their_log.read_text(errors="replace").splitlines()
                          if l.strip() and not l.startswith("#")]
        mine = []
        base = self.work / "src" / "main"
        for f in sorted(base.rglob("*")):
            if f.is_file():
                mine.append(f"{tc.sha256_file(f)}  {f.relative_to(base)}")
        match = bool(their_rows) and their_rows == mine
        return classes_dir, java_dirs, match, {
            "reused_from": str(run_dir),
            "reused_classes_dir": str(classes_dir),
            "reused_java_dirs": [str(p) for p in java_dirs],
            "their_snapshot_rows": len(their_rows),
            "our_snapshot_rows": len(mine),
            "snapshot_match": match,
        }, their_rows

    def changed_sources(self, their_rows):
        """Source files that differ from the reused run's snapshot (code only)."""
        their = {}
        for line in their_rows:
            if "  " in line:
                h, rel = line.split("  ", 1)
                their[rel] = h
        base = self.work / "src" / "main"
        changed = []
        for f in sorted(base.rglob("*")):
            if not f.is_file() or f.suffix not in (".kt", ".java"):
                continue
            rel = str(f.relative_to(base))
            if their.get(rel) != tc.sha256_file(f):
                changed.append(rel)
        return changed

    def incremental_compile(self, changed, r_classes, r_java_dirs, base_cp, tools):
        """Recompile only the changed production files on top of reused classes."""
        overlay = self.work / "classes" / "overlay"
        if overlay.exists():
            shutil.rmtree(overlay)
        overlay.mkdir(parents=True, exist_ok=True)
        base = self.work / "src" / "main"
        kt = [base / r for r in changed if r.endswith(".kt")]
        jv = [base / r for r in changed if r.endswith(".java")]
        cp = [str(overlay), str(r_classes)]
        for d in r_java_dirs:
            cp.append(str(d))
        cp += [str(p) for p in base_cp]
        if kt:
            argfile = self.work / "kotlinc-overlay.args"
            args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17",
                    "-classpath", os.pathsep.join(cp), "-d", str(overlay),
                    "-Xfriend-paths=" + os.pathsep.join([str(overlay), str(r_classes)]),
                    "-Xsuppress-version-warnings", "-module-name", "app"]
            for key in ("kotlin-compose-compiler-plugin-embeddable",
                        "kotlin-serialization-compiler-plugin-embeddable"):
                if key in tools:
                    args.append("-Xplugin=" + str(tools[key]))
            argfile.write_text("\n".join(args + [str(p) for p in kt]) + "\n",
                               encoding="utf-8")
            cmd = [self.java_home / "bin" / "java", "-Xmx2g", "-XX:+UseSerialGC",
                   "-Djava.io.tmpdir=" + str(self.tmp / "java"),
                   "-cp", os.pathsep.join(str(p) for p in
                                          tc.kotlinc_runtime_classpath(tools,
                                                                       self.warnings)),
                   tc.KOTLINC_MAIN, "@" + str(argfile)]
            rc, _ = self.run(cmd, self.logs / "05b-kotlinc-overlay.log",
                             timeout=self.args.timeout, phase="kotlinc-overlay")
            if rc != 0:
                log("  overlay compile FAILED. Two possible causes: (a) the changed "
                    "source really does not compile, (b) Kotlin's `internal` "
                    "visibility is per module, so a file recompiled into an overlay "
                    "cannot see `internal` declarations of the reused class dirs "
                    "(`cannot access ... it is internal in file`). Read "
                    "05b-kotlinc-overlay.log; for (b) do ONE full compile instead of "
                    "an overlay.")
                return None
        if jv:
            rc, _ = self.run(
                [self.java_home / "bin" / "javac", "-encoding", "UTF-8", "-source", "17",
                 "-target", "17", "-proc:none", "-nowarn", "-d", overlay,
                 "-classpath", os.pathsep.join(cp)] + [str(p) for p in jv],
                self.logs / "05c-javac-overlay.log", timeout=self.args.timeout,
                phase="javac-overlay")
            if rc != 0:
                return None
        return overlay

    # ----------------------------------------------------------------- compile
    def compile_main(self, snap, app, r_java, bc_java):
        dep_jars, dep_sel = tc.resolve_deps(self.work, self.logs / "03-deps.tsv",
                                            self.warnings)
        go_jars, go_srcs, go_real = tc.resolve_go_api(self.work, self.logs / "03b-go.log",
                                                      self.warnings)
        tools = tc.find_tool_jars()
        missing = [k for k in ("kotlin-compiler-embeddable", "kotlin-stdlib")
                   if k not in tools]
        if missing:
            raise SystemExit(f"FATAL: missing Kotlin tool jars: {missing}")
        kotlinc_cp = tc.kotlinc_runtime_classpath(tools, self.warnings)

        kt_files = sorted(p for p in snap.rglob("*.kt"))
        java_files = sorted(p for p in snap.rglob("*.java"))
        if self.args.main_include:
            import fnmatch
            pats = [p.strip() for p in self.args.main_include.split(",") if p.strip()]
            base = snap / "java" / "com" / "openminis" / "app"

            def keep(p):
                rel = str(p.relative_to(base)) if base in p.parents else str(p)
                return any(fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch(str(p), pat)
                           for pat in pats)

            kept_kt = [p for p in kt_files if keep(p)]
            kept_java = [p for p in java_files if keep(p)]
            if not kept_kt:
                raise SystemExit(f"FATAL: --main-include matched no Kotlin file: {pats}")
            log(f"  SUBSET main compile: {len(kept_kt)} Kotlin / {len(kept_java)} Java "
                f"files (full tree is {len(kt_files)}/{len(java_files)})")
            kt_files, java_files = kept_kt, kept_java

        compile_sdk = self.compile_sdk(app)
        android_jar = tc.SDK_DEFAULT / "platforms" / f"android-{compile_sdk}" / "android.jar"
        if not android_jar.is_file():
            raise SystemExit(f"FATAL: android.jar missing: {android_jar}")

        base_cp = [str(android_jar)] + [str(j) for j in (go_jars + dep_jars)] + \
                  [str(tools["kotlin-stdlib"])]
        java1 = self.work / "classes" / "java1"
        classes = self.work / "classes" / "main"
        for d in (java1, classes):
            d.mkdir(parents=True, exist_ok=True)

        common = {
            "main_include": self.args.main_include or "",
            "deps_artifacts": len(dep_sel), "deps_jars": len(dep_jars),
            "gomobile_api": "real" if go_real else "stub",
            "android_jar": str(android_jar), "kotlinc_cp_jars": len(kotlinc_cp),
            "kotlin_sources_in_tree": len(list(snap.rglob("*.kt"))),
            "java_sources_in_tree": len(list(snap.rglob("*.java"))),
        }

        if self.args.no_main_compile:
            log("  NO production compile (--no-main-compile): stubs + deps only")
            info = dict(common, mode="none", kotlin_sources=0, java_sources=0,
                        rc_javac=0, rc_kotlinc=0, kotlinc_errors=0,
                        kotlinc_error_lines=[], classes=0)
            return info, base_cp, [java1], str(classes), tools, android_jar

        if self.args.reuse_main and not self.args.main_include:
            reused = self.reuse_main(self.args.reuse_main)
            if reused:
                r_classes, r_java_dirs, match, note, their_rows = reused
                overlay_info = None
                classes_cp = str(r_classes)
                if not match:
                    changed = self.changed_sources(their_rows)
                    if self.args.incremental_overlay and changed:
                        overlay = self.incremental_compile(
                            changed, r_classes, r_java_dirs, base_cp, tools)
                        if overlay is None:
                            raise SystemExit(2)
                        classes_cp = os.pathsep.join([str(overlay), str(r_classes)])
                        overlay_info = {"changed": changed,
                                        "overlay_classes":
                                            len(list(overlay.rglob("*.class")))}
                        log(f"  INCREMENTAL overlay: {len(changed)} changed source "
                            f"file(s) recompiled on top of {r_classes}")
                        for c in changed:
                            log(f"    ~ {c}")
                    elif not self.args.allow_stale_reuse:
                        log("  REUSE REFUSED: source snapshot differs from the reused run")
                        log(f"    {note}")
                        log("    pass --incremental-overlay (recompile changed files) "
                            "or --allow-stale-reuse")
                        raise SystemExit(2)
                    else:
                        self.warnings.append(
                            "reused classes are from a DIFFERENT source snapshot "
                            "(--allow-stale-reuse); changed: " + ", ".join(changed))
                        log(f"  WARNING: stale reuse over {len(changed)} changed file(s)")
                if r_java_dirs:
                    java_dirs = r_java_dirs
                log(f"  production classes REUSED from {note['reused_from']} "
                    f"({note['reused_classes_dir']}, snapshot_match={match}, classes="
                    f"{len(list(r_classes.rglob('*.class')))})")
                info = dict(common, mode=("reused+overlay" if overlay_info
                                          else "reused"),
                            kotlin_sources=len(kt_files),
                            java_sources=len(java_files), rc_javac=0, rc_kotlinc=0,
                            kotlinc_errors=0, kotlinc_error_lines=[],
                            classes=len(list(r_classes.rglob("*.class"))),
                            reuse=note, incremental=overlay_info,
                            changed_since_reuse=(self.changed_sources(their_rows)
                                                 if not match else []))
                return info, base_cp, java_dirs, classes_cp, tools, android_jar
            log("  reuse requested but the other run's classes are gone -> compiling here")
            self.warnings.append("reuse-main had no usable classes; compiled locally")

        # pass 1: java + stubs only
        rc1, txt1 = self.run(
            [self.java_home / "bin" / "javac", "-encoding", "UTF-8", "-source", "17",
             "-target", "17", "-proc:none", "-nowarn", "-Xlint:none",
             "-d", java1, "-classpath", os.pathsep.join(base_cp)]
            + [str(p) for p in java_files] + [str(r_java), str(bc_java)]
            + [str(p) for p in go_srcs],
            self.logs / "04-javac.log", timeout=self.args.timeout, phase="javac-java")

        # pass 2: kotlinc over the production Kotlin files
        argfile = self.work / "kotlinc-main.args"
        kt_cp = base_cp + [str(java1)]
        kt_args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17",
                   "-classpath", os.pathsep.join(kt_cp), "-d", str(classes),
                   "-Xsuppress-version-warnings", "-module-name", "app",
                   "-Xjvm-default=all-compatibility"]
        if "kotlin-compose-compiler-plugin-embeddable" in tools:
            kt_args.append("-Xplugin=" +
                           str(tools["kotlin-compose-compiler-plugin-embeddable"]))
        if "kotlin-serialization-compiler-plugin-embeddable" in tools:
            kt_args.append("-Xplugin=" +
                           str(tools["kotlin-serialization-compiler-plugin-embeddable"]))
        argfile.write_text("\n".join(kt_args + [str(p) for p in kt_files]) + "\n",
                           encoding="utf-8")
        cmd = [self.java_home / "bin" / "java", "-Xmx3g", "-XX:+UseSerialGC",
               "-Djava.io.tmpdir=" + str(self.tmp / "java"),
               "-cp", os.pathsep.join(str(p) for p in kotlinc_cp),
               tc.KOTLINC_MAIN, "@" + str(argfile)]
        rc2, txt2 = self.run(cmd, self.logs / "05-kotlinc-main.log",
                             timeout=self.args.timeout, phase="kotlinc-main")
        errs = tc.extract_errors(txt2)[0]
        info = dict(common, mode=("subset" if self.args.main_include else "full"),
                    kotlin_sources=len(kt_files), java_sources=len(java_files),
                    rc_javac=rc1, rc_kotlinc=rc2,
                    kotlinc_errors=len(errs), kotlinc_error_lines=errs[:25],
                    classes=len(list(classes.rglob("*.class"))))
        return info, base_cp, [java1], str(classes), tools, android_jar

    # ---------------------------------------------------------------- tests
    def discover(self):
        test_root = self.repo / "src" / "android" / "app" / "src" / "test" / "java"
        if not test_root.is_dir():
            raise SystemExit(f"FATAL: test root missing: {test_root}")
        present, absent = [], []
        for cls in PINNED_TESTS:
            f = test_root / (cls.replace(".", "/") + ".kt")
            (present if f.is_file() else absent).append(cls)
        pats = [re.compile(p) for p in DEFAULT_PATTERNS]
        extra = []
        for f in test_root.rglob("*Test.kt"):
            rel = str(f)
            if any(p.match(rel) for p in pats):
                extra.append(str(f.relative_to(test_root))[:-3].replace("/", "."))
        return test_root, sorted(set(present) | set(extra)), sorted(absent)

    def test_files(self, test_root, classes):
        files, extra_classes = [], set(self.extra_test_classes())
        for cls in classes:
            f = test_root / (cls.replace(".", "/") + ".kt")
            if f.is_file():
                files.append(f)
                continue
            j = f.with_suffix(".java")
            if j.is_file():
                files.append(j)
            elif cls not in extra_classes:
                self.warnings.append(f"selected test source vanished: {cls}")
        for raw in (self.args.extra_test_source or "").split(","):
            p = Path(raw.strip())
            if not raw.strip():
                continue
            if p.is_file():
                files.append(p)
            else:
                self.warnings.append(f"extra test source missing: {p}")
        return files

    def extra_test_classes(self):
        """Class names of --extra-test-source files (package + file stem)."""
        out = []
        for raw in (self.args.extra_test_source or "").split(","):
            p = Path(raw.strip())
            if not p.is_file() or p.suffix != ".kt":
                continue
            text = p.read_text(encoding="utf-8", errors="replace")
            m = re.search(r"^package\s+([\w.]+)", text, flags=re.M)
            out.append((m.group(1) + "." if m else "") + p.stem)
        return out

    def helper_closure(self, test_root, test_files):
        """Add same-module test sources the selected tests import (ProductionSources,
        CtxKit, fixtures ...), transitively, so kotlinc resolves them."""
        index = {}
        for f in test_root.rglob("*.kt"):
            index.setdefault(f.stem, []).append(f)
        included = {p.resolve() for p in test_files}
        changed = True
        while changed:
            changed = False
            for f in list(included):
                try:
                    text = f.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                for imp in re.findall(r"^import\s+(com\.openminis\.app\.[\w.]+)",
                                      text, flags=re.M):
                    stem = imp.split(".")[-1]
                    cands = []
                    if imp.startswith(TEST_PKG + "."):
                        rel = imp[len(TEST_PKG) + 1:]
                        cands.append(test_root / (rel.replace(".", "/") + ".kt"))
                    # a top-level object under the package root (ProductionSources)
                    # or a class in a subpackage: fall back to a file-stem lookup.
                    cands.extend(index.get(stem, []))
                    for cand in cands:
                        if cand.is_file() and cand.resolve() not in included:
                            included.add(cand.resolve())
                            changed = True
        return sorted(included)

    def bundle_jars(self):
        out, seen = [], set()
        rt = self.bundle / "runtime-classpath.txt"
        rows = []
        if rt.is_file():
            rows = [Path(l.strip()) for l in rt.read_text().splitlines() if l.strip()]
        extra = self.bundle / "extra-jars" / "classpath.txt"
        if extra.is_file():
            rows += [self.bundle / "extra-jars" / l.strip()
                     for l in extra.read_text().splitlines() if l.strip()]
        for p in rows:
            if not p.is_file():
                self.warnings.append(f"bundle jar missing: {p}")
                continue
            if p.name.startswith(BUNDLE_BLOCK_PREFIXES) or p.name in BUNDLE_BLOCK_PREFIXES:
                continue
            if p.name in seen:
                continue
            seen.add(p.name)
            out.append(p)
        return out

    def extract_json(self, dest):
        src = sorted((self.bundle / "android-all").glob("*.jar"))
        dest.mkdir(parents=True, exist_ok=True)
        if not src:
            self.warnings.append("android-all jar missing: org.json shim not built")
            return
        with zipfile.ZipFile(src[0]) as zf:
            names = [n for n in zf.namelist()
                     if n.startswith("org/json/") and n.endswith(".class")]
            for n in names:
                target = dest / n
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(zf.read(n))
        log(f"  org.json shim: {len(names)} classes from {src[0].name}")

    # ------------------------------------------- Gradle's unit-test android jar
    def agp_tool_jars(self):
        """Jars needed to run AGP's own MockableJarGenerator on a plain JVM.

        Read-only lookups in the Gradle module cache; nothing is downloaded.
        """
        mods = tc.module_cache()
        want = {
            "builder": ("com.android.tools.build", "builder"),
            "guava": ("com.google.guava", "guava"),
            "asm": ("org.ow2.asm", "asm"),
            "asm-tree": ("org.ow2.asm", "asm-tree"),
        }
        found, missing = {}, []
        for key, (group, artifact) in want.items():
            versions = tc.cached_versions(group, artifact)
            picked = None
            for v in sorted(versions, key=tc.version_key, reverse=True):
                aars, jars = tc.artifact_files(mods / group / artifact / v)
                if jars:
                    picked = jars[0]
                    break
            if picked is None:
                missing.append(f"{group}:{artifact}")
            else:
                found[key] = picked
        return found, missing

    def mockable_android(self, android_jar):
        """Build (or reuse) the mockable android.jar Gradle's unit test task uses.

        This is ``testOptions.unitTests.returnDefaultValues = true``: AGP's
        MockableJarGenerator rewrites the *framework* stub jar so every
        ``android.*`` method returns 0/false/null instead of throwing
        ``RuntimeException: Stub!``. Nothing from the app, the tests or any
        business class is touched. Cached by android.jar content hash so
        repeated runs do not regenerate 15k classes.
        """
        if self.args.no_mockable_android:
            self.warnings.append(
                "mockable android.jar disabled (--no-mockable-android): plain JUnit "
                "classes run against the real stub android.jar, so android.* bodies "
                "throw RuntimeException: Stub!")
            return None
        jars, missing = self.agp_tool_jars()
        if missing:
            self.warnings.append(
                "mockable android.jar unavailable (missing from the Gradle module "
                "cache: " + ", ".join(missing) + "); plain JUnit classes fall back to "
                "the real stub android.jar")
            return None
        digest = tc.sha256_file(android_jar)
        cache = self.out / "shim" / "mockable"
        cache.mkdir(parents=True, exist_ok=True)
        dest = cache / f"mockable-{android_jar.stem}-{digest[:12]}.jar"
        launcher = cache / "MockableJarMain.java"
        if not launcher.is_file():
            launcher.write_text(MOCKABLE_LAUNCHER_SRC, encoding="utf-8")
        cached = dest.is_file() and dest.stat().st_size > 4096
        if not cached:
            cmd = [self.java_home / "bin" / "java", "-cp",
                   os.pathsep.join(str(p) for p in jars.values()),
                   str(launcher), "true", str(android_jar), str(dest)]
            rc, _ = self.run(cmd, self.logs / "02b-mockable-android.log",
                             timeout=self.args.timeout, phase="mockable-android")
            if rc != 0 or not dest.is_file():
                self.warnings.append(
                    "mockable android.jar generation failed (rc=%d); plain JUnit "
                    "classes fall back to the real stub android.jar" % rc)
                return None
        try:
            with zipfile.ZipFile(dest) as zf:
                nclasses = sum(1 for n in zf.namelist() if n.endswith(".class"))
        except zipfile.BadZipFile:
            self.warnings.append("mockable android.jar is not a valid zip: " + str(dest))
            return None
        self.mockable_info = {
            "path": str(dest),
            "android_jar": str(android_jar),
            "android_jar_sha256_12": digest[:12],
            "generator": "com.android.builder.testing.MockableJarGenerator "
                         "(AGP %s builder jar, offline)" % jars["builder"].parent.parent.name,
            "return_default_values": True,
            "cache_hit": cached,
            "classes": nclasses,
            "scope": "plain JUnit classes only; Robolectric classes keep the real "
                     "android.jar",
        }
        log(f"  mockable android.jar: {nclasses} classes "
            f"({'cached' if cached else 'generated'}) {dest}")
        return dest

    # ------------------------------------------- declared test-only dependencies
    def test_only_jars(self, app):
        """Real ``testImplementation`` jars Gradle puts on the unit-test classpath
        and that exist in the Gradle module cache (e.g. sqlite-jdbc, which the
        Room migration test needs). Missing ones are reported, never faked."""
        mods = tc.module_cache()
        rows = tc.declared_coordinates(app / "build.gradle.kts")
        bundle_provided = {"junit", "robolectric"}
        found, missing = [], []
        for row in rows:
            if row["scope"] not in ("testImplementation", "testRuntimeOnly"):
                continue
            group, artifact = row["group"], row["artifact"]
            if artifact in bundle_provided:
                continue  # supplied by the offline JUnit/Robolectric bundle
            versions = tc.cached_versions(group, artifact)
            if row["version"] and row["version"] in versions:
                versions = [row["version"]]
            picked = None
            for v in sorted(versions, key=tc.version_key, reverse=True):
                aars, jars = tc.artifact_files(mods / group / artifact / v)
                if jars:
                    picked = jars[0]
                    break
            if picked is None:
                missing.append(f"{group}:{artifact}" + (":" + row["version"]
                                                        if row["version"] else ""))
            else:
                found.append(picked)
        self.test_dep_info = {
            "resolved": [{"path": str(p), "size": p.stat().st_size} for p in found],
            "absent_from_module_cache": missing,
        }
        with open(self.logs / "03c-test-deps.tsv", "w", encoding="utf-8") as fh:
            fh.write("# declared testImplementation/testRuntimeOnly jars\n")
            fh.write("# resolved from the Gradle module cache (no network)\n")
            for p in found:
                fh.write(f"PRESENT\t{p}\t{p.stat().st_size}\n")
            for m in missing:
                fh.write(f"ABSENT\t{m}\n")
        log(f"  test-only jars: {len(found)} resolved, {len(missing)} absent"
            + (" (" + ", ".join(missing) + ")" if missing else ""))
        return found

    def test_classpath(self, main_classes, java_dirs, base_cp, android_jar,
                       mockable_jar=None, extra_jars=()):
        """Test runtime classpath.

        ``mockable_jar`` (plain JUnit only) is placed immediately before
        ``android.jar`` so ``android.*`` resolves to AGP's default-returning
        mockable classes exactly as in Gradle's unit test JVM; Robolectric
        classes pass ``None`` and keep the real android.jar (Robolectric
        instruments its own ``android-all``).
        """
        json_dir = self.work / "shim" / "json"
        if not json_dir.exists():
            self.extract_json(json_dir)
        cps = [p for p in str(main_classes).split(os.pathsep) if p]
        cps += [str(d) for d in java_dirs]
        cps += [str(json_dir),
                str(self.bundle / "jars" / "junit-4.13.2.jar"),
                str(self.bundle / "jars" / "hamcrest-core-1.3.jar")]
        cps += [str(p) for p in self.bundle_jars()]
        # declared testImplementation jars resolved from the offline module cache
        cps += [str(p) for p in extra_jars]
        # production deps last so Robolectric's instrumented runtime and the real
        # AndroidX jars resolve first
        base = [str(p) for p in base_cp]
        if mockable_jar is not None:
            # ``base_cp`` starts with the real android.jar; drop it here or it
            # would shadow the mockable classes (first match wins on a JVM
            # classpath). android.jar stays at the very end as a fallback for the
            # entries the mockable jar deliberately skips (java.*, javax.*,
            # org.xml.*, org.w3c.*, junit.*).
            base = [p for p in base
                    if os.path.abspath(p) != os.path.abspath(str(android_jar))]
            cps += base
            cps.append(str(mockable_jar))
        else:
            cps += base
        cps.append(str(android_jar))
        return cps

    def compile_tests(self, test_root, test_files, cp, main_classes, tools):
        tclasses = self.work / "classes" / "test"
        if tclasses.exists():
            shutil.rmtree(tclasses)
        tclasses.mkdir(parents=True)
        files = self.helper_closure(test_root, test_files)
        (self.logs / "06-test-sources.txt").write_text(
            f"# test root: {test_root}\n# compiled/attempted {len(files)} "
            f"helper+test files\n" + "\n".join(str(p) for p in files) + "\n",
            encoding="utf-8")
        if not test_files:
            self.warnings.append("no test sources selected")
            (self.logs / "06-kotlinc-tests.log").write_text(
                "# no test sources selected - nothing compiled\n", encoding="utf-8")
            return 0, ""
        kt_files = [p for p in files if p.suffix == ".kt"]
        java_files = [p for p in files if p.suffix == ".java"]
        argfile = self.work / "kotlinc-test.args"
        ct_cp = cp + [p for p in str(main_classes).split(os.pathsep) if p]
        args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-classpath", os.pathsep.join(ct_cp), "-d", str(tclasses),
                "-Xfriend-paths=" + str(main_classes),
                "-Xsuppress-version-warnings", "-module-name", "app_test"]
        if "kotlin-compose-compiler-plugin-embeddable" in tools:
            args.append("-Xplugin=" +
                        str(tools["kotlin-compose-compiler-plugin-embeddable"]))
        if "kotlin-serialization-compiler-plugin-embeddable" in tools:
            args.append("-Xplugin=" +
                        str(tools["kotlin-serialization-compiler-plugin-embeddable"]))
        argfile.write_text("\n".join(args + [str(p) for p in kt_files]) + "\n",
                           encoding="utf-8")
        cmd = [self.java_home / "bin" / "java", "-Xmx2g", "-XX:+UseSerialGC",
               "-Djava.io.tmpdir=" + str(self.tmp / "java"),
               "-cp", os.pathsep.join(str(p) for p in
                                      tc.kotlinc_runtime_classpath(tools, self.warnings)),
               tc.KOTLINC_MAIN, "@" + str(argfile)]
        rc, txt = self.run(cmd, self.logs / "06-kotlinc-tests.log",
                           timeout=self.args.timeout, phase="kotlinc-tests")
        if java_files and rc == 0:
            jargs = [self.java_home / "bin" / "javac", "-encoding", "UTF-8",
                     "-source", "17", "-target", "17", "-proc:none", "-nowarn",
                     "-d", tclasses, "-classpath", os.pathsep.join(ct_cp),
                     "-sourcepath", str(test_root)] + [str(p) for p in java_files]
            rc, txt = self.run(jargs, self.logs / "06b-javac-tests.log",
                               timeout=self.args.timeout, phase="javac-tests")
        return rc, txt

    @staticmethod
    def failure_details(text, limit=40):
        """Per-test failure headers from JUnitCore output, with the exception's
        first line and the first app frame, so evidence names the real cause."""
        out, cur = [], None
        for line in text.splitlines():
            m = re.match(r"^(\d+)\)\s+(.+)$", line)
            if m:
                if cur is not None:
                    out.append(cur)
                cur = {"test": m.group(2).strip(), "error": "", "frame": ""}
                continue
            if cur is None:
                continue
            if line.startswith("Tests run:") or line.startswith("FAILURES!!!") \
                    or line.startswith("OK ("):
                out.append(cur)
                cur = None
                continue
            stripped = line.strip()
            if not stripped:
                continue
            if not cur["error"] and not line.startswith("\t"):
                cur["error"] = stripped[:400]
            elif not cur["frame"] and line.startswith("\t") and (
                    "at com.openminis" in line or "at app.openminis" in line):
                cur["frame"] = stripped[:400]
        if cur is not None:
            out.append(cur)
        return out[:limit]

    @staticmethod
    def classify(rc, failures, details):
        """FAIL vs ENV-GAP. An ENV-GAP requires *every* parsed failure to carry a
        recognised environment marker; a single real assertion keeps it FAIL, and
        no test is ever moved into the pass count."""
        if rc == 0 and failures == 0:
            return "PASS", None, None
        kinds = []
        for d in details:
            err = d.get("error") or ""
            kind = next((k for k, marker, _ in ENV_GAP_MARKERS if marker in err), None)
            kinds.append(kind or "real")
        notes = {k: n for k, _, n in ENV_GAP_MARKERS}
        if kinds and "real" not in kinds:
            kind = kinds[0]
            return "ENV-GAP", notes[kind], kind
        return "FAIL", None, None

    def run_class(self, cls, cp, robolectric, has_mockable):
        logfile = self.logs / f"run-{cls.rsplit('.', 1)[-1]}.log"
        cmd = [self.java_home / "bin" / "java", "-Xmx2g", "-XX:+UseSerialGC",
               "-Djava.io.tmpdir=" + str(self.tmp / "java"),
               "-Dfile.encoding=UTF-8"]
        if robolectric:
            cmd += list(ROBOLECTRIC_JVM_ARGS)
            cmd.append("-Drobolectric.dependency.dir=" + str(self.bundle / "android-all"))
        cmd += ["-cp", os.pathsep.join(cp), "org.junit.runner.JUnitCore", cls]
        rc, txt = self.run(cmd, logfile, timeout=self.args.timeout, phase="junit:" + cls)
        m = re.search(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", txt)
        tests = int(m.group(1)) if m else 0
        failures = int(m.group(2)) if m else (0 if rc == 0 else 1)
        ok_line = re.search(r"^OK \((\d+) tests?\)", txt, flags=re.M)
        if ok_line:
            tests, failures = int(ok_line.group(1)), 0
        details = self.failure_details(txt)
        result, note, kind = self.classify(rc, failures, details)
        if result == "ENV-GAP":
            self.warnings.append(f"{cls}: ENV-GAP ({kind}) {note}")
        return {"class": cls, "robolectric": robolectric,
                "android_runtime": ("robolectric/android.jar" if robolectric
                                    else ("mockable-android.jar" if has_mockable
                                          else "android.jar (stub bodies)")),
                "rc": rc, "tests": tests, "failures": failures, "result": result,
                "env_gap_kind": kind, "note": note,
                "failed_tests": details, "log": str(logfile)}

    # ------------------------------------------------------------------ main
    def execute(self):
        started = time.time()
        self.prepare()
        log("=== Minis-Next focused offline JUnit (SOUL / prompt / group avatar) ===")
        log(f"repo : {self.repo}")
        log(f"run  : {self.run_dir}")
        snap, rows, app = self.snapshot()
        log(f"  snapshot: {len(rows)} files under {snap}")
        r_java, bc_java, _ = self.stubs(snap, app)
        try:
            info, base_cp, java_dirs, main_classes, tools, android_jar = \
                self.compile_main(snap, app, r_java, bc_java)
        except SystemExit as exc:
            log(f"  ABORT: {exc}")
            self.finish({"result": "ABORT", "reason": str(exc)}, {"mode": "none"},
                        [], [], [], set(), started)
            return 2 if exc.code in (None, 0) else int(exc.code)
        log(f"  production classes: mode={info.get('mode')} "
            f"kotlinc rc={info.get('rc_kotlinc')} classes={info.get('classes')}")

        test_root, selected, missing_pins = self.discover()
        if self.args.no_default_tests:
            selected, missing_pins = [], []
        selected = sorted(set(selected) |
                          {t.strip() for t in (self.args.tests or "").split(",") if t.strip()}
                          | set(self.extra_test_classes()))
        test_files = self.test_files(test_root, selected)

        robo_classes = set()
        for f in test_files:
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if "org.robolectric" not in text:
                continue
            try:
                cls = str(f.relative_to(test_root))[:-3].replace("/", ".")
            except ValueError:
                m = re.search(r"^package\s+([\w.]+)", text, flags=re.M)
                cls = (m.group(1) + "." if m else "") + f.stem
            robo_classes.add(cls)
        log(f"  selected tests: {len(test_files)} files "
            f"({len(robo_classes)} Robolectric), pinned missing: {len(missing_pins)}")

        if info.get("rc_kotlinc", 0) != 0:
            log("  production Kotlin did not compile -> tests NOT run")
            self.finish({"result": "COMPILE-FAIL",
                         "reason": "production kotlinc rc != 0",
                         "error_lines": info.get("kotlinc_error_lines", [])},
                        info, test_files, [], missing_pins, robo_classes, started)
            return 2

        extra_test_jars = self.test_only_jars(app)
        mockable = self.mockable_android(android_jar)

        # compile tests against the real android.jar (Gradle compiles against it
        # too); only the *runtime* classpath differs per class kind
        cp_compile = [str(self.work / "classes" / "test")] + self.test_classpath(
            main_classes, java_dirs, base_cp, android_jar, None, extra_test_jars)
        rc_test, txt_test = self.compile_tests(test_root, test_files, cp_compile,
                                               main_classes, tools)
        if rc_test != 0:
            log("  test sources did not compile -> tests NOT run")
            errs = tc.extract_errors(txt_test)[0]
            self.finish({"result": "TEST-COMPILE-FAIL", "errors": errs},
                        info, test_files, [], missing_pins, robo_classes, started)
            return 2

        cp_plain = [str(self.work / "classes" / "test")] + self.test_classpath(
            main_classes, java_dirs, base_cp, android_jar, mockable, extra_test_jars)
        cp_robo = cp_compile

        results = []
        if not self.args.only_compile:
            for cls in selected:
                is_robo = cls in robo_classes
                log(f"\n[test] {cls}{' (robolectric)' if is_robo else ''}")
                results.append(self.run_class(cls, cp_robo if is_robo else cp_plain,
                                              is_robo, mockable is not None))
                log(f"  -> {results[-1]['result']} "
                    f"({results[-1]['tests']} tests, {results[-1]['failures']} failures)")

        code = self.finish(None, info, test_files, results, missing_pins, robo_classes,
                           started, compile_rc=rc_test)
        if self.args.only_compile:
            return 0
        return code

    def finish(self, failure, info, test_files, results, missing_pins, robo_classes,
               started, compile_rc=0):
        declared = {}
        for f in test_files:
            try:
                declared[f.stem] = len(re.findall(r"^\s*@Test\b",
                                                  f.read_text(encoding="utf-8",
                                                              errors="replace"),
                                                  flags=re.M))
            except OSError:
                pass
        payload = {
            "generated_at": time.strftime("%Y%m%dT%H%M%S"),
            "kind": "focused offline JUnit run (NOT an APK, NOT a device test)",
            "repo": str(self.repo),
            "run_dir": str(self.run_dir),
            "test_compile_rc": compile_rc,
            "production_compile": info,
            "selected_sources": [(str(p.relative_to(self.repo))
                                  if self.repo in p.parents else str(p))
                                 for p in test_files],
            "declared_tests_per_file": declared,
            "robolectric_classes": sorted(robo_classes),
            "pinned_missing": missing_pins,
            "android_unit_test_runtime": self.mockable_info,
            "test_runtime_jars": self.test_dep_info,
            "results": results,
            "failure": failure,
            "elapsed_seconds": round(time.time() - started, 1),
            "warnings": self.warnings,
            "processes": self.events,
        }
        total = sum(r["tests"] for r in results)
        failures = sum(r["failures"] for r in results)
        env_gaps = [r["class"] for r in results if r["result"] == "ENV-GAP"]
        passed = sum(1 for r in results if r["result"] == "PASS")
        if failure and failure.get("result") in ("COMPILE-FAIL", "TEST-COMPILE-FAIL",
                                                 "ABORT"):
            overall = failure["result"]
        elif not results:
            overall = "NO-TESTS"
        elif failures == 0 and not env_gaps:
            overall = "PASS"
        else:
            overall = "FAIL" if failures else "ENV-GAP"
        payload["overall"] = overall
        gap_kinds = {}
        for r in results:
            if r["result"] == "ENV-GAP":
                gap_kinds[r.get("env_gap_kind") or "unknown"] = \
                    gap_kinds.get(r.get("env_gap_kind") or "unknown", 0) + 1
        real_fail_classes = [r["class"] for r in results if r["result"] == "FAIL"]
        payload["totals"] = {"classes": len(results), "classes_passed": passed,
                             "tests": total, "failures": failures,
                             "env_gaps": len(env_gaps),
                             "env_gap_kinds": gap_kinds,
                             "classes_failed": len(real_fail_classes),
                             "real_failure_classes": real_fail_classes}
        with open(self.logs / "07-summary.json", "w", encoding="utf-8") as fh:
            fh.write(tc.redact(json.dumps(payload, indent=2)) + "\n")
        with open(self.logs / "07-processes.jsonl", "w", encoding="utf-8") as fh:
            for ev in self.events:
                fh.write(json.dumps(ev) + "\n")
        md = [f"# Focused offline JUnit - {overall}", "",
              f"- when: {payload['generated_at']}  elapsed: "
              f"{payload['elapsed_seconds']}s",
              f"- repo: `{self.repo}`",
              f"- production classes: mode={info.get('mode')} "
              f"kotlinc rc={info.get('rc_kotlinc')} "
              f"({info.get('kotlin_sources', 0)} Kotlin files compiled, "
              f"{info.get('classes', 0)} classes)",
              f"- test compile rc={compile_rc}; selected {len(test_files)} test sources",
              f"- JUnit: {len(results)} classes, {total} tests, {failures} failures",
              ""]
        if missing_pins:
            md += ["## Pinned tests not present", ""] + \
                  [f"- {c}" for c in missing_pins] + [""]
        runtime = self.mockable_info or {}
        if runtime:
            runtime_line = ("mockable android.jar (AGP MockableJarGenerator, "
                            "returnDefaultValues=true), "
                            f"{runtime.get('classes')} classes, "
                            f"sha256[:12]={runtime.get('android_jar_sha256_12')}")
        else:
            runtime_line = "real stub android.jar (mockable jar unavailable)"
        absent = self.test_dep_info.get("absent_from_module_cache", [])
        dep_line = (f"{len(self.test_dep_info.get('resolved', []))} resolved offline"
                    + (", absent: " + ", ".join(absent) if absent else ""))
        md += [f"- android unit-test runtime: {runtime_line}",
               f"- declared test-only jars: {dep_line}",
               ""]
        md += ["| class | runtime | tests | failures | result | env-gap kind |",
               "|---|---|---|---|---|---|"]
        for r in results:
            md.append(f"| `{r['class']}` | {r['android_runtime']} | "
                      f"{r['tests']} | {r['failures']} | {r['result']} | "
                      f"{r.get('env_gap_kind') or ''} |")
        if env_gaps:
            md += ["", "ENV-GAP is not a pass. Causes on this host:"]
            for kind, note in {r.get("env_gap_kind"): r.get("note") for r in results
                               if r["result"] == "ENV-GAP"}.items():
                md.append(f"- `{kind}`: {note}")
            md.append("")
        failing = [r for r in results if r["result"] == "FAIL"]
        if failing:
            md += ["", "## Real failures (per test)", ""]
            for r in failing:
                md.append(f"### `{r['class']}` ({r['failures']} failures)")
                for d in r.get("failed_tests", []):
                    md.append(f"- `{d['test']}`: {d['error'] or 'no header parsed'}")
                    if d.get("frame"):
                        md.append(f"  - {d['frame']}")
                md.append("")
        if failure:
            md += ["", "## Not run / failed before tests", ""] + \
                  [f"- {json.dumps(failure)[:800]}"]
        if self.warnings:
            md += ["", "## Warnings", ""] + [f"- {w}" for w in self.warnings]
        (self.logs / "07-summary.md").write_text(tc.redact("\n".join(md)) + "\n",
                                                encoding="utf-8")
        log(f"\nRESULT: {overall}  classes={len(results)} tests={total} "
            f"failures={failures}")
        log(f"  summary: {self.logs / '07-summary.md'}")
        if self.args.clean_work:
            tc.ensure_inside(self.work, self.out, "work dir")
            shutil.rmtree(self.work, ignore_errors=True)
            log("  work dir removed (--clean-work); logs kept")
        if self.args.only_compile:
            return 0
        return 0 if overall == "PASS" else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, default=REPO_DEFAULT)
    ap.add_argument("--out", type=Path, default=OUT_DEFAULT)
    ap.add_argument("--bundle", type=Path, default=BUNDLE_DEFAULT)
    ap.add_argument("--label", default="soul-groups")
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--list", action="store_true", help="list selected tests and exit")
    ap.add_argument("--only-compile", action="store_true", help="compile only, run nothing")
    ap.add_argument("--no-mockable-android", action="store_true",
                    help="do NOT build AGP's mockable android.jar: plain JUnit classes "
                         "then run against the real stub android.jar (Stub! bodies)")
    ap.add_argument("--clean-work", action="store_true",
                    help="delete the work dir after writing logs")
    ap.add_argument("--tests", default="", help="extra comma-separated test classes")
    ap.add_argument("--no-main-compile", action="store_true",
                    help="skip production compilation entirely; only source-grep / "
                         "stub-only tests can compile and run (diagnostic mode)")
    ap.add_argument("--no-default-tests", action="store_true",
                    help="ignore pinned/pattern discovery; use only --tests")
    ap.add_argument("--main-include", default="",
                    help="SUBSET mode: comma-separated globs (relative to "
                         "src/main/java/com/openminis/app) of production files to "
                         "compile; empty = full source tree (the canonical run)")
    ap.add_argument("--extra-test-source", default="",
                    help="comma-separated extra .kt test files outside the repo "
                         "(diagnostic probes; never written into the repo)")
    ap.add_argument("--reuse-main", type=Path, default=None,
                    help="a typecheck run dir whose work/out classes are reused "
                         "instead of compiling production again")
    ap.add_argument("--incremental-overlay", action="store_true",
                    help="with --reuse-main: recompile only the production files that "
                         "changed since the reused run's snapshot, into an overlay "
                         "class dir placed before the reused classes")
    ap.add_argument("--allow-stale-reuse", action="store_true",
                    help="allow --reuse-main even when the source snapshot differs "
                         "(recorded loudly; use only for diagnostics)")
    args = ap.parse_args()
    runner = Runner(args)
    test_root, selected, missing = runner.discover()
    if args.no_default_tests:
        selected, missing = [], []
    if args.tests:
        selected = sorted(set(selected) |
                          {t.strip() for t in args.tests.split(",") if t.strip()})
    if args.list:
        for c in selected:
            log(("PRESENT " if (test_root / (c.replace(".", "/") + ".kt")).is_file()
                 else "MISSING ") + c)
        if missing:
            log(f"\npinned but absent: {missing}")
        return 0
    return runner.execute()


if __name__ == "__main__":
    sys.exit(main())