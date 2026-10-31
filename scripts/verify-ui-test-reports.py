#!/usr/bin/env python3
"""Require every UI-port test class to run successfully; never accept skips.

Run after testGithubDebugUnitTest with --reports pointing at the copied XMLs.
The class list is derived from checked-in *Omni*Test.kt files, so adding a
new test cannot silently leave it outside the cloud acceptance gate.
"""
from pathlib import Path
import argparse
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def expected_classes(source_root):
    result = set()
    paths = set(source_root.rglob('*Omni*Test.kt')) | set(source_root.rglob('ChatComposerMotionTest.kt'))
    for path in paths:
        text = path.read_text()
        package = re.search(r'^package\s+([\w.]+)', text, re.M)
        if not package:
            raise ValueError(f'No package in {path}')
        # Top-level test classes only; helper nested classes are not suites.
        for match in re.finditer(r'^(?:public\s+|internal\s+)?class\s+(\w*Omni\w*Test|ChatComposerMotionTest)\b', text, re.M):
            result.add(package.group(1) + '.' + match.group(1))
    if not result:
        raise ValueError('No UI acceptance classes discovered')
    return result


def verify(reports, sources):
    names = expected_classes(sources)
    count = 0
    failures = []
    for name in sorted(names):
        path = reports / ('TEST-' + name + '.xml')
        if not path.is_file():
            failures.append(f'{name}: missing XML')
            continue
        try:
            suite = ET.parse(path).getroot()
            tests = int(suite.get('tests', '0'))
            bad = {k: int(suite.get(k, '0')) for k in ('skipped', 'failures', 'errors')}
            if tests <= 0 or any(bad.values()):
                failures.append(f'{name}: tests={tests}, {bad}')
            else:
                count += tests
                print(f'PASS {name}: {tests} tests, zero skipped')
        except (ET.ParseError, ValueError) as exc:
            failures.append(f'{name}: invalid XML: {exc}')
    if failures:
        raise ValueError('\n'.join(failures))
    print(f'PASS all {len(names)} UI acceptance classes: {count} tests')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reports', type=Path, required=True)
    p.add_argument('--sources', type=Path, default=ROOT / 'src/android/app/src/test/java')
    a = p.parse_args()
    try:
        verify(a.reports, a.sources)
    except (ValueError, OSError) as exc:
        print(f'FAIL UI acceptance: {exc}', file=sys.stderr)
        sys.exit(1)
