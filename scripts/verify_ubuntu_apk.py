#!/usr/bin/env python3
"""Fail closed on missing guest/assets/native libraries; verify actual APK signing."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def verify(apk: Path):
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None, f'corrupt ZIP: {apk}'
        names = set(z.namelist())
        required = {'AndroidManifest.xml', 'classes.dex', 'assets/ubuntu-base-arm64.tar.gz',
                    'lib/arm64-v8a/libproot.so', 'lib/arm64-v8a/libproot-loader.so',
                    'lib/arm64-v8a/libproot-loader32.so', 'lib/arm64-v8a/libgojni.so'}
        assert required <= names, f'missing payload: {required - names}'
        assert 'assets/alpine-minirootfs.tar.gz' not in names, 'old Alpine payload still bundled'
        assert not any(n.startswith('lib/') and n.split('/')[1] != 'arm64-v8a' for n in names), 'unexpected ABI'
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    tools = sdk / 'build-tools' / '35.0.0'
    subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
    print(f'PASS {apk} {apk.stat().st_size} bytes SHA256={digest}; not installed or run')


if __name__ == '__main__':
    paths = sorted(Path(sys.argv[1]).rglob('*.apk'))
    if not paths:
        raise SystemExit('No APK found; build is not a success')
    for path in paths:
        verify(path)
