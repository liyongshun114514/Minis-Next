#!/usr/bin/env python3
"""Run actual inline parser functions on the JVM with real Compose text classes.

Extracts production functions verbatim by named source anchors (no substitute
parser). Only the palette and parseInlineForTest wrapper are supplied here.
Does not exercise Android text layout, pixel rendering or the full application.
Requires the local Gradle/compiler and Compose UI jars; no downloads/install.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('jvm', ROOT / 'scripts/test-tool-arguments.py')
jvm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jvm)
BASE = ROOT / 'src/android/app/src'
SOURCE = BASE / 'main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt'
UI = Path('/opt/toolchains/ui-verify')


def extracted(raw):
    start = raw.index('private const val INCR_TAIL_MARGIN')
    end = raw.index('/**\n * T208-4 part 4: estimate the on-screen', start)
    math_start = raw.index('private fun findInlineMathClose(')
    math_end = raw.index('/**\n * [T-android-usage-capsule-blank-tap-margin]', math_start)
    # The bodies below (parser, recursive styles, safe split, math helpers) are
    # byte-for-byte source slices. No Android composables are needed to inspect
    # AnnotatedString output and exact span styles.
    return '''package com.openminis.app.ui.chat
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
private data class MdColors(val link: Color = Color.Unspecified, val inlineCodeText: Color = Color.Unspecified)
internal fun parseInlineForTest(text: String): AnnotatedString = parseInline(text, MdColors())
''' + raw[start:end] + raw[math_start:math_end]


def main():
    options = argparse.ArgumentParser(description=__doc__)
    options.add_argument('--baseline', help='test production parser from this git revision instead of the working tree')
    args = options.parse_args()
    java, _ = jvm.jdk_tools(jvm.DEFAULT_JDK)
    comp, std = jvm.compiler_classpath()
    dependencies = [UI / f for f in (
        'ui-text-android-1.9.1.jar', 'ui-unit-android-1.9.1.jar',
        'ui-graphics-android-1.9.1.jar', 'ui-util-android-1.9.1.jar',
        'ui-geometry-android-1.9.1.jar', 'foundation-android-1.9.1.jar',
        'runtime-android-1.9.1.jar', 'runtime-saveable-android-1.9.1.jar', 'annotation.jar')]
    dependencies += [jvm.GRADLE_LIB / 'junit-4.13.2.jar', jvm.GRADLE_LIB / 'hamcrest-core-1.3.jar', std]
    for p in dependencies:
        if not p.is_file():
            raise SystemExit(f'Missing dependency: {p}')
    temp_root = Path(os.environ.get('MINIS_MARKDOWN_TEST_TMP', '/var/minis/workspace/tmp/markdown-italics-20261101'))
    temp_root.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix='jvm-', dir=temp_root) as work:
            work = Path(work)
            parser = work / 'InlineParser.kt'
            raw = subprocess.check_output(['git', 'show', args.baseline + ':' + str(SOURCE.relative_to(ROOT))], cwd=ROOT, text=True) if args.baseline else SOURCE.read_text()
            parser.write_text(extracted(raw))
            classes = work / 'classes'
            classes.mkdir()
            source_dir = BASE / 'main/java/com/openminis/app/ui/markdown'
            test_dir = BASE / 'test/java/com/openminis/app/ui/chat'
            sources = [parser, source_dir / 'UnderscoreEmphasis.kt', source_dir / 'InlineHtmlTags.kt']
            tests = ['MarkdownUnderscoreTest', 'SafeInlineSplitOffsetTest']
            sources += [test_dir / f'{test}.kt' for test in tests]
            cp = os.pathsep.join(map(str, dependencies))
            compile_cmd = [str(java), '-cp', os.pathsep.join(comp),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, sources)]
            result = subprocess.run(compile_cmd)
            if result.returncode:
                return result.returncode
            return subprocess.run([str(java), '-cp', str(classes) + os.pathsep + cp,
                'org.junit.runner.JUnitCore', *['com.openminis.app.ui.chat.' + test for test in tests]]).returncode
    finally:
        # Remove only our empty parent, never another task's temporary content.
        try:
            temp_root.rmdir()
        except OSError:
            pass


if __name__ == '__main__':
    raise SystemExit(main())
