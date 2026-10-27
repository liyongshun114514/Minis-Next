#!/usr/bin/env python3
"""Restore the exact upstream launcher assets from pinned Git history.

Requires a full Git checkout containing SOURCE. Does not alter application names,
manifest aliases, package identity, or signing. No image service is involved.
"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = '6eda5523a9c14fa8edc4fd9fb72e643fe47f23be'
PREFIX = 'src/android/app/src/main/res/'

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])

def main():
    tree = git('ls-tree', '-r', '--name-only', SOURCE, PREFIX).decode().splitlines()
    names = [p for p in tree if Path(p).name.startswith('ic_launcher')]
    assert len(names) == 40, len(names)
    # Load and validate all source bytes before changing any resource.
    blobs = {p: git('show', SOURCE + ':' + p) for p in names}
    for name, data in blobs.items():
        (ROOT / name).write_bytes(data)
    # This generated hourglass-only resource is no longer referenced.
    (ROOT / PREFIX / 'drawable-nodpi/ic_launcher_monochrome_mask.png').unlink(missing_ok=True)
    manifest = {'sourceCommit': SOURCE, 'upstream': 'OpenMinis/OpenMinis',
                'files': {p: hashlib.sha256(b).hexdigest() for p, b in blobs.items()}}
    (ROOT / 'branding/kairos/upstream-icons.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Restored 40 exact upstream launcher resources; application name unchanged')

if __name__ == '__main__':
    main()
