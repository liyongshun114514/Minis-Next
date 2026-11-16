#!/usr/bin/env python3
"""Offline full-source TYPE CHECK for the Minis-Next Android app.

This is NOT an APK build and must never be reported as one. It compiles every
production Kotlin + Java source file under ``src/android/app/src/main/java``
with the real, already-downloaded Gradle dependency cache (AndroidX jars/AARs)
plus a platform ``android.jar``.

What is real
------------
* every production ``.kt`` / ``.java`` file in ``src/main/java``
* every AndroidX / third-party artifact selected from
  ``$GRADLE_USER_HOME/caches/modules-2/files-2.1`` (classes.jar extracted from
  each AAR, exactly the bytes Gradle would put on the compile classpath)
* the Kotlin compiler + Compose compiler plugin + kotlinx.serialization
  compiler plugin from the same cache (same versions the build uses)
* ``android.jar`` of the ``compileSdk`` declared in app/build.gradle.kts
* (when present) the real gomobile Java API jar AGP extracted from
  ``app/libs/rclone.aar``; otherwise a signature-only stub is generated and
  the run is marked as such.

What is a stub (compile-only, explicitly NOT an APK)
----------------------------------------------------
* ``com.openminis.app.R`` — generated from ``src/main/res`` (names only, no
  aapt2). Resource ids are synthetic.
* ``com.openminis.app.BuildConfig`` — field NAMES/TYPES parsed from
  ``buildConfigField`` declarations; values are placeholders. Configured
  secrets (provider-customization.properties) are never read into the stub or
  printed.
No resource compilation (aapt2), no KSP/Room code generation, no dexing, no
packaging, no signing, no native (CMake) build.

Network / secrets
-----------------
This script never invokes Gradle, curl, wget or any network client; it only
reads local caches and the repository. It does not read local.properties or
provider-customization.properties. Everything written to the report is passed
through ``redact()`` so token-shaped strings never land in logs.

Output directory
----------------
All state is written under an EXPLICIT ``--out`` directory (default: this
task's tmp dir). Nothing is written to a shared/common temp directory, and the
script refuses to delete anything that resolves outside ``--out``.

Usage
-----
  python3 scripts/verification/typecheck_sources.py \\
      --out /var/minis/workspace/tmp/minis-soul-20261116/validation

  # dependency/toolchain readiness audit only (seconds, read-only, no compile)
  python3 scripts/verification/typecheck_sources.py --out <dir> --check-only

Layout::

  <out>/typecheck/<label>/logs/{00..07}.log|json|md
  <out>/typecheck/<label>/work/{src,deps,stubs,out,tmpjava}

Exit codes: 0 = all production sources type-check; 1 = compile/dep failure;
2 = environment/preflight failure; 3 = source tree or work dir disappeared
(typically a concurrent checkout/cleanup — rerun, do not treat as a verdict).
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

# ---------------------------------------------------------------- configuration

REPO_DEFAULT = Path("/var/minis/shared/Minis-Next")
TASK_OUT_DEFAULT = Path("/var/minis/workspace/tmp/minis-soul-20261116/validation")
GRADLE_HOME = Path(os.environ.get("GRADLE_USER_HOME", "/var/cache/minis/gradle"))
SDK_DEFAULT = Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk"))
JAVA_HOME_CANDIDATES = (
    Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None,
    Path("/usr/lib/jvm/java-21-openjdk-arm64"),
    Path("/usr/lib/jvm/java-17-openjdk-arm64"),
    Path("/usr/lib/jvm/default-java"),
)
KOTLIN_PIN = "2.1.0"

# Dependency group allow-list. Only groups the app (or its real runtime graph)
# needs; build-tool groups (com.android.tools.*, org.ow2.asm, ...) are
# deliberately excluded so a tooling jar can never mask a missing app dep.
GROUP_PREFIXES = (
    "androidx.",
    "com.mikepenz",
    "com.squareup.okhttp3",
    "com.squareup.okio",
    "io.coil-kt",
    "dev.rikka.shizuku",
    "sh.calvin.reorderable",
    "com.github.helloooideeeeea",
    "org.jetbrains.kotlinx",
    "ch.acra",
)
# Exact extra coordinates (KMP markdown parser + annotations used by Compose).
GROUP_EXACT = {"org.jetbrains"}
GROUP_EXACT_ARTIFACTS = {"annotations", "markdown", "markdown-jvm"}

# Version pins: the version the project resolves, when several are cached.
# Pins are applied per group so a group never mixes two versions.
VERSION_PINS = {
    "androidx.lifecycle": "2.8.7",
    "androidx.activity": "1.9.3",
    "org.jetbrains.kotlinx": "1.9.0",      # coroutines 1.9.0 / serialization 1.7.3
    "org.jetbrains.kotlin": KOTLIN_PIN,
}
ARTIFACT_PINS = {
    "kotlinx-serialization-core": "1.7.3",
    "kotlinx-serialization-core-jvm": "1.7.3",
    "kotlinx-serialization-json": "1.7.3",
    "kotlinx-serialization-json-jvm": "1.7.3",
    "kotlin-stdlib": KOTLIN_PIN,
    "kotlin-stdlib-jdk7": KOTLIN_PIN,
    "kotlin-stdlib-jdk8": KOTLIN_PIN,
    "kotlin-reflect": KOTLIN_PIN,
    "kotlinx-coroutines-android": "1.9.0",
    "kotlinx-coroutines-core": "1.9.0",
    "kotlinx-coroutines-core-jvm": "1.9.0",
}

KOTLINC_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

SECRET_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9_\-]{12,}"),
    re.compile(r"AIza[0-9A-Za-z_\-]{20,}"),
    re.compile(r"(?i)\b(api[_-]?key|secret|password|passwd|token|bearer)\b\s*[:=]\s*\S+"),
    re.compile(r"(?i)\bauthorization:\s*\S+"),
)


def log(msg=""):
    print(msg, flush=True)


def redact(text):
    """Strip token-shaped strings from anything written to disk or stdout."""
    out = str(text)
    for pat in SECRET_PATTERNS:
        out = pat.sub(lambda m: m.group(1) + "=<redacted>" if m.groups() else "<redacted>", out)
    return out


def run(cmd, logfile, cwd=None, env=None, timeout=3600, label=""):
    """Run a command, tee stdout+stderr to logfile, return (rc, text)."""
    log(f"\n$ {label or ' '.join(str(c) for c in cmd[:4])}")
    started = time.time()
    try:
        proc = subprocess.run(
            [str(c) for c in cmd], cwd=str(cwd) if cwd else None, env=env,
            timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, errors="replace",
        )
        rc, out = proc.returncode, proc.stdout
    except subprocess.TimeoutExpired as exc:
        rc = 124
        chunk = exc.stdout or ""
        if isinstance(chunk, bytes):
            chunk = chunk.decode("utf-8", "replace")
        out = chunk + f"\n[typecheck] TIMEOUT after {timeout}s\n"
    with open(logfile, "w", encoding="utf-8", errors="replace") as fh:
        fh.write(f"# cmd: {cmd}\n# rc: {rc}  elapsed: {time.time() - started:.1f}s\n\n")
        fh.write(redact(out))
    log(f"  rc={rc}  elapsed={time.time() - started:.1f}s  log={logfile}")
    return rc, out


def resolve_java_home():
    for cand in JAVA_HOME_CANDIDATES:
        if cand and (cand / "bin" / "java").is_file():
            return cand
    return Path("/usr/lib/jvm/default-java")


def module_cache():
    return GRADLE_HOME / "caches" / "modules-2" / "files-2.1"


# --------------------------------------------------------------- dependencies


def version_key(v):
    """Sortable key: numeric parts first, pre-releases sort below releases."""
    main, _, pre = v.partition("-")
    nums = []
    for part in main.split("."):
        m = re.match(r"(\d+)", part)
        nums.append(int(m.group(1)) if m else 0)
    while len(nums) < 4:
        nums.append(0)
    return (tuple(nums), 1 if not pre else 0, pre)


def allowed_group(group):
    return group.startswith(GROUP_PREFIXES) or group in GROUP_EXACT


def artifact_files(vdir):
    """Return (aar, jar) candidates in a version directory, ignoring aux files."""
    aars, jars = [], []
    for f in sorted(vdir.rglob("*")):
        if not f.is_file():
            continue
        n = f.name.lower()
        if n.endswith("-sources.jar") or n.endswith("-javadoc.jar"):
            continue
        if n.endswith(".aar"):
            aars.append(f)
        elif n.endswith(".jar"):
            jars.append(f)
    return aars, jars


def resolve_deps(work, logfile, warnings):
    """Scan the Gradle module cache and materialise a compile classpath."""
    modules = module_cache()
    if not modules.is_dir():
        raise SystemExit(f"FATAL: gradle module cache missing: {modules}")
    deps_dir = work / "deps"
    deps_dir.mkdir(parents=True, exist_ok=True)

    selected = []
    for gdir in sorted(modules.iterdir()):
        if not gdir.is_dir() or not allowed_group(gdir.name):
            continue
        for adir in sorted(gdir.iterdir()):
            if not adir.is_dir():
                continue
            versions = []
            for vdir in adir.iterdir():
                if not vdir.is_dir():
                    continue
                aars, jars = artifact_files(vdir)
                if aars or jars:
                    versions.append((vdir, aars, jars))
            if not versions:
                continue
            by_name = {v.name: (v, a, j) for v, a, j in versions}
            pin = ARTIFACT_PINS.get(adir.name) or VERSION_PINS.get(gdir.name)
            if pin and pin in by_name:
                vdir, aars, jars = by_name[pin]
            else:
                vdir, aars, jars = max(versions, key=lambda t: version_key(t[0].name))
            src = aars[0] if aars else jars[0]
            selected.append({
                "group": gdir.name, "artifact": adir.name,
                "version": vdir.name, "src": src,
                "kind": "aar" if aars else "jar",
            })

    lines, jars_out, skipped = [], [], []
    for dep in selected:
        src = dep["src"]
        stem = f"{dep['group']}__{dep['artifact']}__{dep['version']}"
        dest = deps_dir / (stem + ".jar")
        if dep["kind"] == "jar":
            if not dest.exists():
                shutil.copyfile(src, dest)
        else:
            if not dest.exists():
                try:
                    with zipfile.ZipFile(src) as zf:
                        names = zf.namelist()
                        member = "classes.jar" if "classes.jar" in names else None
                        if member is None:
                            cand = [n for n in names if n.endswith("/classes.jar")]
                            member = cand[0] if cand else None
                        if member is None:
                            skipped.append(str(src))
                            continue
                        with zf.open(member) as fsrc, open(dest, "wb") as fdst:
                            shutil.copyfileobj(fsrc, fdst)
                except zipfile.BadZipFile:
                    skipped.append(str(src) + " (bad zip)")
                    continue
        if dest.exists() and dest not in jars_out:
            jars_out.append(dest)
            lines.append(f"{dep['group']}:{dep['artifact']}:{dep['version']}\t"
                         f"{dep['kind']}\t{src.name}")

    with open(logfile, "w", encoding="utf-8") as fh:
        fh.write("# resolved compile classpath (from gradle module cache)\n")
        fh.write(f"# cache: {modules}\n")
        fh.write(f"# artifacts: {len(selected)}  jars on classpath: {len(jars_out)}\n")
        fh.write("\n".join(lines) + "\n")
        if skipped:
            fh.write("\n# AARs without classes.jar (no compile types):\n")
            fh.write("\n".join(skipped) + "\n")
    log(f"  resolved {len(selected)} artifacts -> {len(jars_out)} classpath jars"
        f" ({len(skipped)} AARs had no classes.jar)")
    if skipped:
        warnings.append(f"{len(skipped)} AAR(s) carried no classes.jar")
    return jars_out, selected


DECL_RE = re.compile(
    r'(?P<scope>implementation|api|compileOnly|ksp|kapt|testImplementation|'
    r'androidTestImplementation|testCompileOnly)\s*\(\s*'
    r'(?:platform\s*\(\s*)?"(?P<group>[\w.\-]+):(?P<artifact>[\w.\-]+)'
    r'(?::(?P<version>[\w.\-]+))?"')


def declared_coordinates(build_file):
    """Extract declared coordinates (scope, group, artifact, version|None)."""
    text = build_file.read_text(encoding="utf-8", errors="replace")
    rows, seen = [], set()
    for m in DECL_RE.finditer(text):
        key = (m.group("scope"), m.group("group"), m.group("artifact"), m.group("version") or "")
        if key in seen:
            continue
        seen.add(key)
        rows.append({"scope": key[0], "group": key[1], "artifact": key[2],
                     "version": key[3] or None})
    return rows


def cached_versions(group, artifact):
    adir = module_cache() / group / artifact
    if not adir.is_dir():
        return []
    return sorted(d.name for d in adir.iterdir() if d.is_dir())


def readiness_audit(repo, work, java_home, warnings):
    """Read-only dependency/toolchain readiness report (no compile)."""
    app_dir = repo / "src" / "android" / "app"
    build_file = app_dir / "build.gradle.kts"
    rows, missing_blocking, missing_test = [], [], []
    blocking_scopes = ("implementation", "api", "compileOnly")
    for dep in declared_coordinates(build_file):
        versions = cached_versions(dep["group"], dep["artifact"])
        if dep["version"]:
            ok = dep["version"] in versions
        else:
            ok = bool(versions)
        entry = dict(dep, cached=versions, ok=ok)
        rows.append(entry)
        if not ok and dep["scope"] in blocking_scopes:
            missing_blocking.append(dep)
        elif not ok and dep["scope"] in ("testImplementation", "testCompileOnly",
                                         "androidTestImplementation"):
            missing_test.append(dep)

    tools = find_tool_jars()
    needed_tools = ("kotlin-compiler-embeddable", "kotlin-stdlib",
                    "kotlin-compose-compiler-plugin-embeddable",
                    "kotlin-serialization-compiler-plugin-embeddable")
    missing_tools = [t for t in needed_tools if t not in tools]
    build_text = build_file.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"compileSdk\s*=\s*(\d+)", build_text)
    compile_sdk = m.group(1) if m else "36"
    android_jar = SDK_DEFAULT / "platforms" / f"android-{compile_sdk}" / "android.jar"

    go_jar = find_gomobile_jar()
    bundle = Path(os.environ.get("MINIS_OFFLINE_TEST_BUNDLE",
                                 "/var/cache/minis/ui-verify-robolectric"))
    bundle_jars = bundle / "jars"
    junit_jar = bundle_jars / "junit-4.13.2.jar"
    robolectric_jar = bundle_jars / "robolectric-4.17.jar"
    report = {
        "kind": "offline dependency readiness (read-only, no network)",
        "gradle_module_cache": str(module_cache()),
        "declared": rows,
        "missing_compile_scope": missing_blocking,
        "missing_test_scope": missing_test,
        "offline_test_bundle": {
            "path": str(bundle),
            "present": bundle.is_dir(),
            "junit_4_13_2": junit_jar.is_file(),
            "robolectric_4_17": robolectric_jar.is_file(),
            "android_all": sorted(p.name for p in (bundle / "android-all").glob("*.jar"))
                           if (bundle / "android-all").is_dir() else [],
            "note": "test-only jars Gradle would download come from this "
                    "pre-existing offline bundle; org.json is extracted from "
                    "android-all at test time",
        },
        "kotlin_tools": {k: str(v) for k, v in tools.items()},
        "missing_kotlin_tools": missing_tools,
        "android_jar": str(android_jar),
        "android_jar_present": android_jar.is_file(),
        "java_home": str(java_home),
        "java_present": (java_home / "bin" / "java").is_file(),
        "javac_present": (java_home / "bin" / "javac").is_file(),
        "gomobile_api_jar": str(go_jar) if go_jar else None,
        "gomobile_from_cache": bool(go_jar),
        "rclone_aar_present": (app_dir / "libs" / "rclone.aar").is_file(),
        "rclone_aar_path": str(app_dir / "libs" / "rclone.aar"),
    }
    out = work / "readiness.json"
    work.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(redact(json.dumps(report, indent=2)) + "\n")
    log(f"  declared deps: {len(rows)}  "
        f"missing compile-scope: {len(missing_blocking)}  "
        f"missing test-scope: {len(missing_test)}")
    for dep in missing_blocking:
        log(f"    MISSING(compile) {dep['group']}:{dep['artifact']}:{dep['version'] or '<bom>'}")
    for dep in missing_test:
        log(f"    missing(test)    {dep['group']}:{dep['artifact']}:{dep['version'] or '<bom>'}")
    if missing_tools:
        log(f"    MISSING Kotlin tool jars: {missing_tools}")
    if not android_jar.is_file():
        log(f"    MISSING android.jar: {android_jar}")
    if not go_jar:
        log("    gomobile API jar: absent -> signature-only stub will be generated")
    if missing_blocking or missing_tools or not android_jar.is_file():
        warnings.append("dependency readiness is incomplete (see readiness.json)")
    return report


# ------------------------------------------------------------- compile stubs

RES_TYPE_BY_DIR = {
    "drawable": "drawable", "mipmap": "mipmap", "font": "font", "raw": "raw",
    "layout": "layout", "anim": "anim", "animator": "animator", "xml": "xml",
    "menu": "menu", "color": "color", "navigation": "navigation",
    "transition": "transition", "interpolator": "interpolator",
}
RES_TYPE_BY_TAG = {
    "string": "string", "color": "color", "dimen": "dimen", "integer": "integer",
    "bool": "bool", "style": "style", "plurals": "plurals",
    "fraction": "fraction", "string-array": "array", "integer-array": "array",
    "array": "array", "attr": "attr", "drawable": "drawable", "id": "id",
}


def sanitize_res_name(name):
    return re.sub(r"[.\-: ]", "_", name)


def parse_resources(res_dir, warnings):
    """Collect R.<type>.<name> from res/ without aapt2 (names only)."""
    import xml.etree.ElementTree as ET
    types = {}
    if not res_dir.is_dir():
        warnings.append(f"resource dir missing: {res_dir}")
        return types
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir():
            continue
        base = d.name.split("-")[0]
        if base == "values":
            for f in sorted(d.glob("*.xml")):
                try:
                    root = ET.parse(f).getroot()
                except ET.ParseError as exc:
                    warnings.append(f"unparsable resource xml {f.name}: {exc}")
                    continue
                for child in root:
                    tag = child.tag
                    nm = child.get("name")
                    if tag == "declare-styleable":
                        for attr in child:
                            if attr.tag == "attr" and attr.get("name"):
                                types.setdefault("attr", set()).add(
                                    sanitize_res_name(attr.get("name")))
                        if nm:
                            types.setdefault("styleable", set()).add(
                                sanitize_res_name(nm))
                        continue
                    if tag == "item":
                        tag = child.get("type") or ""
                    rtype = RES_TYPE_BY_TAG.get(tag)
                    if rtype and nm:
                        types.setdefault(rtype, set()).add(sanitize_res_name(nm))
        else:
            rtype = RES_TYPE_BY_DIR.get(base)
            if not rtype:
                continue
            for f in d.iterdir():
                if f.is_file():
                    stem = f.name
                    if stem.endswith(".9.png"):
                        stem = stem[:-6]
                    else:
                        stem = stem.split(".")[0]
                    types.setdefault(rtype, set()).add(sanitize_res_name(stem))
    return types


def scan_source_res_refs(src_root):
    """R.<type>.<name> symbols referenced by source (belt and braces)."""
    refs = {}
    pat = re.compile(r"\bR\.([a-z][a-zA-Z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)")
    for f in src_root.rglob("*"):
        if f.suffix not in (".kt", ".java"):
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for m in pat.finditer(text):
            refs.setdefault(m.group(1), set()).add(m.group(2))
    return refs


def write_r_java(ns, res_types, dest):
    lines = [f"package {ns};", "",
             "/* COMPILE-ONLY STUB generated by typecheck_sources.py — NOT an APK build.",
             " * Resource ids are synthetic (aapt2 never ran). */",
             "public final class R {"]
    rid = 0x7F010000
    total = 0
    for rtype in sorted(res_types):
        names = sorted(n for n in res_types[rtype]
                       if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", n))
        if not names:
            continue
        lines.append(f"    public static final class {rtype} {{")
        for n in names:
            rid += 1
            total += 1
            lines.append(f"        public static final int {n} = 0x{rid:08x};")
        lines.append("    }")
    lines.append("}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return total


def parse_build_config_fields(build_file):
    """Parse buildConfigField("<type>", "<name>", <value>) — names/types only."""
    text = build_file.read_text(encoding="utf-8", errors="replace")
    fields = {}
    for m in re.finditer(r"buildConfigField\s*\(", text):
        i = m.end()
        depth, args, cur, in_str, esc = 1, [], "", False, False
        while i < len(text) and depth:
            ch = text[i]
            if in_str:
                cur += ch
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                    cur += ch
                elif ch == "(":
                    depth += 1
                    cur += ch
                elif ch == ")":
                    depth -= 1
                    if depth:
                        cur += ch
                elif ch == "," and depth == 1:
                    args.append(cur.strip())
                    cur = ""
                else:
                    cur += ch
            i += 1
        if cur.strip():
            args.append(cur.strip())
        if len(args) >= 2:
            jtype = args[0].strip().strip('"')
            jname = args[1].strip().strip('"')
            fields[jname] = jtype
    return fields


def write_build_config_java(ns, fields, dest):
    defaults = {
        "boolean": "false", "int": "0", "long": "0L", "String": '""',
        "double": "0.0", "float": "0.0f",
    }
    standard = {
        "DEBUG": "boolean", "APPLICATION_ID": "String", "BUILD_TYPE": "String",
        "FLAVOR": "String", "VERSION_CODE": "int", "VERSION_NAME": "String",
        "LIBRARY_PACKAGE_NAME": "String",
    }
    merged = dict(fields)
    for k, v in standard.items():
        merged.setdefault(k, v)
    lines = [f"package {ns};", "",
             "/* COMPILE-ONLY STUB generated by typecheck_sources.py — NOT an APK build.",
             " * Placeholder values; only field names/types matter to the type check.",
             " * Configured customization values are never read. */",
             "public final class BuildConfig {"]
    for name in sorted(merged):
        jtype = merged[name] if merged[name] in defaults else "String"
        lines.append(f"    public static final {jtype} {name} = {defaults[jtype]};")
    lines.append("}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(merged)


# ------------------------------------------------------- gomobile (rclone.aar)

GO_PACKAGE_DIR = "com/openminis/rclone/gomobile"
GO_REQUIRED_METHODS = (b"rcloneInitialize", b"rcloneSetSocks", b"rcloneRPC",
                       b"tsSetLogger", b"tsStatus", b"netDiagnose")

GO_STUB = """package com.openminis.rclone.gomobile;

/* COMPILE-ONLY STUB (gomobile API) generated by typecheck_sources.py.
 * app/libs/rclone.aar was not built, so no real gomobile Java API exists in
 * this checkout. Signatures mirror deps/rclone-mobile/gomobile usage in
 * src/main/java; there is NO Go runtime behind them. */
public final class Gomobile {
    public static final class Result {
        public long status;
        public String output;
        public Result(long status, String output) {
            this.status = status; this.output = output;
        }
    }
    public static void rcloneInitialize() {}
    public static String rcloneSetSocks(String s) { return "{\\"ok\\":true}"; }
    public static Result rcloneRPC(String m, String i) { return new Result(0, ""); }
    public static void tsSetLogger(LogSink sink) {}
    public static String tsStart(String a, String h, String d) { return "{\\"ok\\":true}"; }
    public static String tsSetAcceptRoutes(boolean on) { return "{\\"ok\\":true}"; }
    public static String tsSetExitNode(String ip) { return "{\\"ok\\":true}"; }
    public static String tsStop() { return "{\\"ok\\":true}"; }
    public static String tsLogout() { return "{\\"ok\\":true}"; }
    public static String tsStatus() { return "{}"; }
    public static String tsRoutes() { return "{}"; }
    public static String tsDialTest(String a, long t) { return "{\\"ms\\":-1}"; }
    public static String tsSetInterfaces(String j) { return "{\\"ok\\":true}"; }
    public static void tsSetDefaultRouteInterface(String n) {}
    public static void tsNetworkChanged() {}
    public static String netConfigure(String j) { return "{\\"port\\":0}"; }
    public static String netStop() { return "{}"; }
    public static String netHookRoutes(String t, String j) { return "{}"; }
    public static String netHookRoutes2(String t, String a, String b) { return "{}"; }
    public static String netXparentStart(String d, String t) { return "{}"; }
    public static String netShellProbe(String r, String n, long t) { return "{}"; }
    public static String netXparentStop() { return "{}"; }
    public static String netXparentStatus() { return "{}"; }
    public static String netStatus() { return "{}"; }
    public static String netHealth() { return "{}"; }
    public static String netSelfTest(long t) { return "{\\"ms\\":-1}"; }
    public static String netDiagnose(long t) { return "{}"; }
    public static String netDecide(String a, String b) { return "{}"; }
    public static String netProbe(String a, String b, long t) { return "{}"; }
    public static String netTestUpstream(String a, String b, long t) { return "{\\"ms\\":-1}"; }
}
"""


def valid_go_jar(path):
    try:
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
            needed = {f"{GO_PACKAGE_DIR}/Gomobile.class",
                      f"{GO_PACKAGE_DIR}/LogSink.class",
                      f"{GO_PACKAGE_DIR}/Gomobile$Result.class"}
            if not needed.issubset(names):
                return False
            blob = zf.read(f"{GO_PACKAGE_DIR}/Gomobile.class")
            return all(m in blob for m in GO_REQUIRED_METHODS)
    except (zipfile.BadZipFile, KeyError, OSError):
        return False


def find_gomobile_jar():
    tc = GRADLE_HOME / "caches" / "8.11.1" / "transforms"
    if not tc.is_dir():
        return None
    for cand in sorted(tc.glob("*/transformed/rclone-api.jar"),
                       key=lambda p: p.stat().st_mtime, reverse=True):
        if valid_go_jar(cand):
            return cand
    return None


def resolve_go_api(work, logfile, warnings):
    """Locate the compiled gomobile Java API, else generate a labelled stub."""
    deps_dir = work / "deps"
    deps_dir.mkdir(parents=True, exist_ok=True)
    real = find_gomobile_jar()
    if real:
        dest = deps_dir / "go-gomobile-api.jar"
        shutil.copyfile(real, dest)
        with open(logfile, "w", encoding="utf-8") as fh:
            fh.write(f"# source: real AGP transform of app/libs/rclone.aar\n")
            fh.write(f"# jar: {real}\n# kind: REAL gomobile Java API (not a stub)\n")
        log(f"  Go API: real rclone-api.jar from transform cache")
        return [dest], [], True

    warnings.append("rclone.aar / rclone-api.jar absent -> gomobile stub (compile-only)")
    stub_dir = work / "stubs-go" / "com" / "openminis" / "rclone" / "gomobile"
    stub_dir.mkdir(parents=True, exist_ok=True)
    stub = stub_dir / "Gomobile.java"
    stub.write_text(GO_STUB, encoding="utf-8")
    sink = stub_dir / "LogSink.java"
    sink.write_text(
        "package com.openminis.rclone.gomobile;\n\n"
        "/* COMPILE-ONLY STUB (gomobile API), see Gomobile.java. */\n"
        "public interface LogSink { void line(String line); }\n", encoding="utf-8")
    with open(logfile, "w", encoding="utf-8") as fh:
        fh.write("# kind: GENERATED STUB (compile-only); no real rclone.aar\n")
        fh.write(f"# stub: {stub}\n# stub: {sink}\n")
    log("  Go API: generated compile-only stub (app/libs/rclone.aar absent)")
    return [], [stub, sink], False


def check_gomobile_usage(src_root, warnings):
    """Fail loudly if source calls a Gomobile method the stub does not declare."""
    stub_methods = set(re.findall(r"public static [\w.$<>\[\]]+ (\w+)\(", GO_STUB))
    used = set()
    pat = re.compile(r"\bGomobile\.(\w+)\s*\(")
    for f in src_root.rglob("*.kt"):
        try:
            used.update(pat.findall(f.read_text(encoding="utf-8", errors="replace")))
        except OSError:
            continue
    missing = sorted(used - stub_methods)
    if missing:
        warnings.append("gomobile stub lacks methods used by source: " + ", ".join(missing))
    return missing# ------------------------------------------------------------------- driver


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def snapshot_sources(app_dir, work, logfile, main_src=None):
    """Copy main sources into the work dir so concurrent edits cannot tear a run."""
    src_main = Path(main_src) if main_src else (app_dir / "src" / "main")
    dst = work / "src" / "main"
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    for rel in ("java", "res", "resources"):
        s = src_main / rel
        if s.is_dir():
            shutil.copytree(s, dst / rel, symlinks=False)
    manifest = src_main / "AndroidManifest.xml"
    if manifest.is_file():
        shutil.copyfile(manifest, dst / "AndroidManifest.xml")
    rows = []
    for f in sorted(dst.rglob("*")):
        if f.is_file():
            rows.append(f"{sha256_file(f)}  {f.relative_to(dst)}")
    with open(logfile, "w", encoding="utf-8") as fh:
        fh.write(f"# source snapshot: {dst}\n# files: {len(rows)}\n")
        fh.write("\n".join(rows) + "\n")
    return dst, rows


def find_tool_jars():
    mods = module_cache() / "org.jetbrains.kotlin"
    want = ("kotlin-compiler-embeddable", "kotlin-compose-compiler-plugin-embeddable",
            "kotlin-serialization-compiler-plugin-embeddable", "kotlin-stdlib",
            "kotlin-reflect", "kotlin-daemon-embeddable", "kotlin-script-runtime")
    found = {}
    for art in want:
        adir = mods / art / KOTLIN_PIN
        jars = sorted(adir.rglob("*.jar")) if adir.is_dir() else []
        jars = [j for j in jars if "-sources" not in j.name and "-javadoc" not in j.name]
        if jars:
            found[art] = jars[0]
    return found


def kotlinc_runtime_classpath(tools, warnings):
    """Jars the embeddable Kotlin compiler itself needs at run time."""
    rcp = [tools["kotlin-compiler-embeddable"], tools["kotlin-stdlib"]]
    for key in ("kotlin-reflect", "kotlin-script-runtime", "kotlin-daemon-embeddable"):
        if key in tools:
            rcp.append(tools[key])
    mods = module_cache()
    extras = (
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "kotlinx-coroutines-core-jvm not found for compiler runtime"),
        ("org.jetbrains.intellij.deps", "trove4j", "trove4j not found for compiler runtime"),
    )
    for group, artifact, warn in extras:
        d = mods / group / artifact
        jars = [j for j in sorted(d.rglob("*.jar")) if "-sources" not in j.name] if d.is_dir() else []
        if jars:
            rcp.append(jars[-1])
        else:
            warnings.append(warn)
    # The JVM backend loads org.jetbrains.annotations.Nullable from its OWN
    # classpath when emitting nullability annotations.
    ann = [p for p in sorted((mods / "org.jetbrains" / "annotations").rglob("*.jar"))
           if "-sources" not in p.name]
    if ann:
        rcp.append(max(ann, key=lambda p: version_key(p.parent.parent.name)))
    else:
        warnings.append("org.jetbrains:annotations not found for compiler runtime")
    return rcp


def extract_errors(logtext, limit=60):
    errs = []
    for line in logtext.splitlines():
        if re.search(r"(^e: |error:|Exception in thread|FAILURE:)", line):
            errs.append(redact(line.strip()))
    return errs[:limit], len(errs)


def work_vanished(work, logtext=""):
    if not work.exists():
        return True
    return bool(logtext) and "No such file or directory" in logtext and str(work) in logtext


def ensure_inside(path, root, what):
    """Refuse to delete/write outside the explicit output directory."""
    p = Path(path).resolve()
    r = Path(root).resolve()
    if p != r and r not in p.parents:
        raise SystemExit(f"REFUSING: {what} {p} is outside --out {r}")
    return p


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", type=Path, default=REPO_DEFAULT)
    ap.add_argument("--out", type=Path,
                    default=Path(os.environ.get("MINIS_SOUL_VALIDATION_OUT",
                                                str(TASK_OUT_DEFAULT))),
                    help="explicit output root (all state is written below it)")
    ap.add_argument("--label", default="current")
    ap.add_argument("--main-src", type=Path, default=None,
                    help="override src/android/app/src/main (e.g. a HEAD baseline)")
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--check-only", action="store_true",
                    help="dependency/toolchain readiness audit only; no compile")
    args = ap.parse_args()

    started = time.time()
    repo = args.repo.resolve()
    out = args.out.expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    run_dir = ensure_inside(out / "typecheck" / args.label, out, "run dir")
    logs = run_dir / "logs"
    work = run_dir / "work"
    logs.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    warnings, failures = [], []
    stamp = time.strftime("%Y%m%dT%H%M%S")
    java_home = resolve_java_home()
    build_file = repo / "src" / "android" / "app" / "build.gradle.kts"

    log("=== Minis-Next offline full-source TYPE CHECK (not an APK build) ===")
    log(f"repo : {repo}")
    log(f"out  : {out}")
    log(f"run  : {run_dir}")

    if not build_file.is_file():
        log(f"FATAL: {build_file} not found")
        return 2
    if args.main_src is not None and not Path(args.main_src).is_dir():
        log(f"FATAL: --main-src disappeared: {args.main_src}")
        return 3

    if args.check_only:
        log("\n[readiness] dependency/toolchain audit (read-only, no compile)")
        report = readiness_audit(repo, logs, java_home, warnings)
        log(f"  readiness: {logs / 'readiness.json'}")
        return 0 if report["android_jar_present"] and not report["missing_kotlin_tools"] else 2

    # ---- preflight
    build_text = build_file.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"compileSdk\s*=\s*(\d+)", build_text)
    compile_sdk = m.group(1) if m else "36"
    ns_m = re.search(r'namespace\s*=\s*"([\w.]+)"', build_text)
    namespace = ns_m.group(1) if ns_m else "com.openminis.app"
    android_jar = SDK_DEFAULT / "platforms" / f"android-{compile_sdk}" / "android.jar"
    pre = [
        f"java_home={java_home}",
        f"compileSdk={compile_sdk} namespace={namespace}",
        f"android_jar={android_jar} exists={android_jar.is_file()}",
        f"gradle_cache={module_cache()} exists={module_cache().is_dir()}",
    ]
    tools = find_tool_jars()
    for k, v in tools.items():
        pre.append(f"kotlin-tool {k}={v}")
    java_ver = subprocess.run([str(java_home / "bin" / "java"), "-version"],
                              capture_output=True, text=True)
    pre.append("java -version: " + " ".join((java_ver.stderr or java_ver.stdout).split()[:3]))
    (logs / "00-preflight.log").write_text(redact("\n".join(pre)) + "\n", encoding="utf-8")
    log("\n".join(pre))

    if not android_jar.is_file():
        log("FATAL: android.jar missing (SDK platform not installed offline)")
        return 2
    missing_tools = [k for k in ("kotlin-compiler-embeddable", "kotlin-stdlib")
                     if k not in tools]
    if missing_tools:
        log(f"FATAL: missing Kotlin compiler jars in cache: {missing_tools}")
        return 2

    # ---- readiness (audit is cheap; always record it next to the compile)
    log("\n[0/5] dependency readiness audit (read-only)")
    readiness = readiness_audit(repo, logs, java_home, warnings)
    (logs / "00b-readiness.txt").write_text(
        redact("\n".join(
            [f"{d['scope']:22} {d['group']}:{d['artifact']}:{d['version'] or '<bom>'} "
             f"cached={','.join(d['cached']) or '-'} ok={d['ok']}"
             for d in readiness["declared"]] +
            ["", f"gomobile_api_jar={readiness['gomobile_api_jar']}",
             f"rclone_aar_present={readiness['rclone_aar_present']}"])),
        encoding="utf-8")

    # ---- work dir: always rebuilt under --out (rerun is self-healing)
    for sub in ("src", "deps", "stubs", "stubs-go", "out", "tmpjava"):
        p = work / sub
        if p.exists():
            ensure_inside(p, out, "work subdir")
            shutil.rmtree(p)
    (work / "tmpjava").mkdir(exist_ok=True)

    # ---- S1 snapshot + repo identity
    log("\n[1/5] snapshot of production sources")
    git = shutil.which("git") or "/usr/bin/git"
    head = subprocess.run([git, "-C", str(repo), "rev-parse", "--short=10", "HEAD"],
                          capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run([git, "-C", str(repo), "status", "--porcelain", "--",
                            "src/android"], capture_output=True, text=True).stdout
    dirty_lines = [l for l in dirty.splitlines() if l.strip()]
    try:
        snap, rows = snapshot_sources(repo / "src" / "android" / "app", work,
                                      logs / "01-source-snapshot.sha256", args.main_src)
    except OSError as exc:
        log(f"  snapshot failed (source tree removed mid-run?): {exc}")
        return 3
    (logs / "01-git-state.log").write_text(
        redact(f"HEAD={head}\nmodified/untracked under src/android: {len(dirty_lines)}\n\n" + dirty),
        encoding="utf-8")
    java_files = sorted(p for p in snap.rglob("*.java"))
    kt_files = sorted(p for p in snap.rglob("*.kt"))
    log(f"  HEAD={head}  dirty_paths={len(dirty_lines)}")
    log(f"  Kotlin files: {len(kt_files)}   Java files: {len(java_files)}")
    if not kt_files:
        log("FATAL: no Kotlin sources found")
        return 3 if args.main_src else 2

    # ---- S2 dependencies
    log("\n[2/5] resolving real cached dependencies")
    dep_jars, dep_sel = resolve_deps(work, logs / "02-deps-resolved.tsv", warnings)
    go_jars, go_stub_srcs, go_real = resolve_go_api(work, logs / "02b-go-api.log", warnings)
    if not go_real:
        missing_go = check_gomobile_usage(snap / "java", warnings)
        if missing_go:
            log(f"  WARNING: gomobile stub lacks used methods: {missing_go}")
    dep_jars = go_jars + dep_jars
    decl = declared_coordinates(build_file)
    missing_decl = sorted(
        f"{d['group']}:{d['artifact']}:{d['version']}"
        for d in decl
        if d["version"] and d["scope"] not in ("testImplementation", "testCompileOnly")
        and not any(s["group"] == d["group"] and s["artifact"] == d["artifact"]
                    and s["version"] == d["version"] for s in dep_sel))
    if missing_decl:
        warnings.append("declared compile coordinates not matched exactly in cache: "
                        + ", ".join(missing_decl))

    # ---- S3 stubs
    log("\n[3/5] generating compile-only stubs (R / BuildConfig)")
    res_types = parse_resources(snap / "res", warnings)
    src_refs = scan_source_res_refs(snap / "java")
    added = 0
    for rtype, names in src_refs.items():
        have = res_types.setdefault(rtype, set())
        for n in names:
            if n not in have:
                have.add(n)
                added += 1
    r_java = work / "stubs" / "com" / "openminis" / "app" / "R.java"
    bc_java = work / "stubs" / "com" / "openminis" / "app" / "BuildConfig.java"
    r_count = write_r_java(namespace, res_types, r_java)
    bc_fields = parse_build_config_fields(build_file)
    bc_count = write_build_config_java(namespace, bc_fields, bc_java)
    (logs / "03-stubs.log").write_text(
        "COMPILE-ONLY STUBS (not an APK build; aapt2/KSP never ran)\n"
        f"R.java: {r_count} fields across {len(res_types)} types "
        f"({added} names referenced by source but absent from res)\n"
        f"BuildConfig.java: {bc_count} fields {sorted(bc_fields)}\n"
        f"paths: {r_java}\n       {bc_java}\n", encoding="utf-8")
    log(f"  R: {r_count} fields / {len(res_types)} types (+{added} source-only names)")
    log(f"  BuildConfig: {bc_count} fields")

    # ---- S4 compile
    log("\n[4/5] compiling (javac stubs -> kotlinc -> javac)")
    out_dir = work / "out"
    out_java1, out_kotlin, out_java2 = out_dir / "java1", out_dir / "kotlin", out_dir / "java2"
    for d in (out_java1, out_kotlin, out_java2):
        d.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(SDK_DEFAULT),
               TMPDIR=str(work / "tmpjava"))
    base_cp = [str(android_jar)] + [str(j) for j in dep_jars] + [str(tools["kotlin-stdlib"])]

    # 4a javac: stubs + Java sources
    java_srcs = [str(p) for p in java_files] + [str(r_java), str(bc_java)] + \
        [str(p) for p in go_stub_srcs]
    cmd = [java_home / "bin" / "javac", "-encoding", "UTF-8", "-source", "17",
           "-target", "17", "-proc:none", "-nowarn", "-Xlint:none",
           "-d", out_java1, "-classpath", os.pathsep.join(base_cp)] + java_srcs
    rc_javac1, txt_javac1 = run(cmd, logs / "04-javac-pass1.log", env=env,
                                timeout=args.timeout,
                                label="javac pass1 (stubs+java)")
    if work_vanished(work, txt_javac1):
        log("  work dir disappeared during javac pass1 (external cleanup)")
        return 3

    # 4b kotlinc: all Kotlin sources against deps + java1
    kt_cp = base_cp + [str(out_java1)]
    argfile = work / "kotlinc.args"
    kt_args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17",
               "-classpath", os.pathsep.join(kt_cp),
               "-d", str(out_kotlin),
               "-Xsuppress-version-warnings",
               "-module-name", "app"]
    if "kotlin-compose-compiler-plugin-embeddable" in tools:
        kt_args.append("-Xplugin=" + str(tools["kotlin-compose-compiler-plugin-embeddable"]))
    else:
        warnings.append("Compose compiler plugin jar not in cache")
    if "kotlin-serialization-compiler-plugin-embeddable" in tools:
        kt_args.append("-Xplugin=" +
                       str(tools["kotlin-serialization-compiler-plugin-embeddable"]))
    else:
        warnings.append("kotlinx.serialization compiler plugin jar not in cache")
    argfile.write_text("\n".join(kt_args + [str(p) for p in kt_files]) + "\n",
                       encoding="utf-8")
    cmd = [java_home / "bin" / "java", "-Xmx3g", "-XX:+UseSerialGC",
           "-cp", os.pathsep.join(str(p) for p in kotlinc_runtime_classpath(tools, warnings)),
           KOTLINC_MAIN, "@" + str(argfile)]
    rc_kotlin, txt_kotlin = run(cmd, logs / "05-kotlinc.log", env=env,
                                timeout=args.timeout,
                                label="kotlinc (all Kotlin sources)")
    if rc_kotlin != 0 and work_vanished(work, txt_kotlin):
        log("  work dir disappeared during kotlinc (external tmp cleanup)")
        return 3

    # 4c javac: Java sources again, now with Kotlin output visible
    if out_kotlin.exists() and any(out_kotlin.rglob("*.class")):
        kt_cp2 = base_cp + [str(out_java1), str(out_kotlin)]
        cmd = [java_home / "bin" / "javac", "-encoding", "UTF-8", "-source", "17",
               "-target", "17", "-proc:none", "-nowarn", "-Xlint:none",
               "-d", out_java2, "-classpath", os.pathsep.join(kt_cp2),
               "-sourcepath", str(snap / "java")] + [str(p) for p in java_files]
        rc_javac2, txt_javac2 = run(cmd, logs / "06-javac-pass2.log", env=env,
                                    timeout=args.timeout,
                                    label="javac pass2 (java + kotlin)")
        if work_vanished(work, txt_javac2):
            log("  work dir disappeared during javac pass2 (external cleanup)")
            return 3
    else:
        rc_javac2, txt_javac2 = 1, "skipped: no Kotlin classes produced\n"
        (logs / "06-javac-pass2.log").write_text(txt_javac2, encoding="utf-8")
        log("  javac pass2 skipped (kotlinc produced no classes)")

    # ---- S5 report
    log("\n[5/5] writing report")
    classes = len(list(out_kotlin.rglob("*.class"))) + len(list(out_java1.rglob("*.class")))
    kt_errs, kt_err_count = extract_errors(txt_kotlin)
    j1_errs, j1_err_count = extract_errors(txt_javac1)
    j2_errs, j2_err_count = extract_errors(txt_javac2)
    ok = rc_kotlin == 0 and rc_javac2 == 0
    summary = {
        "generated_at": stamp,
        "kind": "offline full-source TYPE CHECK (NOT an APK build)",
        "repo": str(repo),
        "out": str(out),
        "run_dir": str(run_dir),
        "label": args.label,
        "main_src": str(args.main_src) if args.main_src else
                    str(repo / "src" / "android" / "app" / "src" / "main"),
        "git_head": head,
        "dirty_paths_under_src_android": len(dirty_lines),
        "kotlin_sources": len(kt_files),
        "java_sources": len(java_files),
        "kotlin_lines": sum(len(p.read_text(encoding="utf-8", errors="replace").splitlines())
                            for p in kt_files),
        "java_lines": sum(len(p.read_text(encoding="utf-8", errors="replace").splitlines())
                          for p in java_files),
        "resource_files": len(list((snap / "res").rglob("*")))
                          if (snap / "res").is_dir() else 0,
        "deps_artifacts": len(dep_sel),
        "deps_classpath_jars": len(dep_jars),
        "deps_aar_extracted": sum(1 for d in dep_sel if d["kind"] == "aar"),
        "gomobile_api": "real" if go_real else "stub",
        "android_jar": str(android_jar),
        "kotlin_compiler": str(tools.get("kotlin-compiler-embeddable", "")),
        "compose_plugin": str(tools.get("kotlin-compose-compiler-plugin-embeddable", "")),
        "serialization_plugin": str(
            tools.get("kotlin-serialization-compiler-plugin-embeddable", "")),
        "stubs": {"R_fields": r_count, "R_types": len(res_types),
                  "R_source_only_names": added, "BuildConfig_fields": bc_count,
                  "paths": [str(r_java), str(bc_java)]},
        "exit_codes": {"javac_pass1": rc_javac1, "kotlinc": rc_kotlin,
                       "javac_pass2": rc_javac2},
        "class_files": classes,
        "error_counts": {"kotlinc": kt_err_count, "javac_pass1": j1_err_count,
                         "javac_pass2": j2_err_count},
        "result": "PASS" if ok else "FAIL",
        "warnings": [redact(w) for w in warnings],
    }
    (logs / "07-summary.json").write_text(
        redact(json.dumps(summary, indent=2)) + "\n", encoding="utf-8")

    md = [f"# Offline full-source type check — {summary['result']} [{args.label}]", "",
          f"- when: {stamp}    elapsed: {time.time() - started:.0f}s",
          f"- sources: `{summary['main_src']}`",
          f"- repo: `{repo}` @ `{head}` (dirty paths under src/android: {len(dirty_lines)})",
          f"- Kotlin sources: **{summary['kotlin_sources']}** files / "
          f"{summary['kotlin_lines']} lines",
          f"- Java sources: **{summary['java_sources']}** files / "
          f"{summary['java_lines']} lines",
          f"- real deps: {summary['deps_artifacts']} cached artifacts "
          f"({summary['deps_aar_extracted']} AAR -> classes.jar), "
          f"{summary['deps_classpath_jars']} jars on classpath",
          f"- gomobile API: {summary['gomobile_api']}",
          f"- stubs: R {r_count} fields / BuildConfig {bc_count} fields (compile-only)",
          f"- exit codes: javac#1={rc_javac1} kotlinc={rc_kotlin} javac#2={rc_javac2}",
          f"- classes emitted: {classes}",
          "",
          "This is a type check, not an APK: no aapt2, KSP/Room codegen, dex, "
          "packaging, signing or native build ran. If the worktree was being "
          "edited concurrently, rerun before drawing a conclusion.", ""]
    if warnings:
        md += ["## Warnings", ""] + [f"- {w}" for w in warnings] + [""]
    for name, errs, count in (("kotlinc", kt_errs, kt_err_count),
                              ("javac pass1", j1_errs, j1_err_count),
                              ("javac pass2", j2_errs, j2_err_count)):
        if errs:
            md += [f"## {name}: {count} error lines (first {len(errs)})", ""]
            md += ["```"] + errs + ["```", ""]
    (logs / "07-summary.md").write_text(redact("\n".join(md)) + "\n", encoding="utf-8")

    log(f"\nRESULT: {summary['result']}  (kotlinc rc={rc_kotlin}, javac#2 rc={rc_javac2})")
    log(f"  Kotlin: {len(kt_files)} files / {summary['kotlin_lines']} lines; "
        f"Java: {len(java_files)} files; deps: {len(dep_sel)} artifacts; "
        f"classes: {classes}")
    log(f"  summary: {logs / '07-summary.md'}")
    if not ok:
        for e in (kt_errs or j2_errs)[:15]:
            log("  " + e)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())