#!/usr/bin/env python3
"""Validate the editable Android prompt catalog and its runtime call sites.

Offline static contract check; --refresh-sources only refreshes source metadata.
Does not execute the application or read runtime user configuration.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CATALOG = 'src/android/app/src/main/assets/prompt-catalog.json'
MAIN = 'src/android/app/src/main/java'
CALL = re.compile(r'PromptOverrides\.render\(\s*("(?:\\.|[^"\\])*")\s*,\s*("(?:\\.|[^"\\])*")')
SLOT = re.compile(r'\{\{(slot\d+)\}\}')


def decode(value):
    # Generated Kotlin normal strings use JSON escapes plus Kotlin's escaped $.
    return json.loads(value.replace('\\$', '$'))


def validate(root, refresh=False):
    path = root / CATALOG
    entries = json.loads(path.read_text())
    by_id = {entry['id']: entry for entry in entries}
    errors = []
    if len(entries) != len(by_id):
        errors.append('duplicate catalog ids')
    sources = subprocess.check_output(['rg', '--files', str(root / MAIN), '-g', '*.kt'], text=True).splitlines()
    referenced = set()
    calls = 0
    for name in sources:
        file = Path(name)
        text = file.read_text()
        for match in CALL.finditer(text):
            key, default = map(decode, match.groups())
            line = text.count('\n', 0, match.start()) + 1
            where = str(file.relative_to(root)) + ':' + str(line)
            calls += 1
            referenced.add(key)
            entry = by_id.get(key)
            if entry is None:
                errors.append('unknown id: ' + key + ' at ' + where)
                continue
            if entry['defaultTemplate'] != default:
                errors.append('default differs: ' + key + ' at ' + where)
            required = set(SLOT.findall(default))
            # Generated calls are single-line. Arguments may themselves include
            # literals, but dynamic slot keys are always explicit mapOf entries.
            tail = text[match.end():text.find('\n', match.end()) if '\n' in text[match.end():] else len(text)]
            tail = tail.split('PromptOverrides.render(', 1)[0]
            bound = set(re.findall(r'"(slot\d+)"\s+to\b', tail))
            if required != bound:
                errors.append('slot binding differs: ' + key + ' required=' + str(sorted(required)) + ' bound=' + str(sorted(bound)))
            if refresh:
                entry['source'] = where
            else:
                src = entry['source'].rsplit(':', 1)
                if len(src) != 2 or not src[1].isdigit():
                    errors.append('source lacks line: ' + key)
                else:
                    source = root / src[0]
                    lines = source.read_text().splitlines() if source.is_file() else []
                    if not (0 < int(src[1]) <= len(lines)) or key not in lines[int(src[1])-1]:
                        errors.append('source metadata outdated: ' + key)

    # Shell rules are rendered by a data-driven id. Their detection patterns
    # stay immutable; only the behavior/fix text is user-editable.
    rule_path = root / 'src/android/app/src/main/assets/bashism/bashism_rules.json'
    rule_source = rule_path.read_text()
    for rule in json.loads(rule_source)['rules']:
        for field, suffix in [('behaviorNote', '.behavior'), ('fixHint', '.fix')]:
            key = 'bashism.' + rule['name'] + suffix
            if key not in by_id:
                errors.append('missing shell rule: ' + key)
                continue
            referenced.add(key)
            entry = by_id[key]
            if entry['defaultTemplate'] != rule[field]:
                errors.append('shell rule default differs: ' + key)
            if refresh:
                index = rule_source.index('"name": "' + rule['name'] + '"')
                index = rule_source.index('"' + field + '"', index)
                entry['source'] = str(rule_path.relative_to(root)) + ':' + str(rule_source.count('\n', 0, index) + 1)
    reminder = (root / MAIN / 'com/openminis/app/agent/shell/BashismReminder.kt').read_text()
    for suffix in ['.behavior', '.fix']:
        if '"bashism." + h.ruleName + "' + suffix + '"' not in reminder:
            errors.append('missing runtime shell-rule renderer ' + suffix)
    errors.extend('orphan id: ' + key for key in sorted(set(by_id) - referenced))
    if refresh:
        path.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n')
    return {'catalog_entries': len(entries), 'render_call_sites': calls,
            'dynamic_shell_rules': len(json.loads(rule_source)['rules']) * 2,
            'groups': dict(Counter(entry['group'] for entry in entries)),
            'errors': errors, 'ok': not errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=ROOT)
    parser.add_argument('--refresh-sources', action='store_true')
    args = parser.parse_args()
    result = validate(args.repo, args.refresh_sources)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
