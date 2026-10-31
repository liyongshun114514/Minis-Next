#!/usr/bin/env python3
"""Stage exactly one already-verified MinisNext APK; never generate a success claim."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.openminis.app.next'
if os.environ.get('MINIS_UI_PREVIEW') == 'true':
    PACKAGE += '.ui'
CERT_SHA256 = 'f1464cfe2b280348ee1fa611733dc55818bdcdcae659dd4fcbf27421a5a1065f'

def stage():
    apks = list((ROOT / 'src/android/app/build/outputs/apk/github').rglob('*.apk'))
    assert len(apks) == 1, f'Expected exactly one accepted APK, got {apks}'
    apk = apks[0]
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/android-sdk')) / 'build-tools/35.0.0'
    aapt = sdk / 'aapt2'
    signer = sdk / 'apksigner'
    text = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    assert match and match[1] == PACKAGE, 'Not a MinisNext installation package'
    assert re.search(r"application-label(?:-[^:]+)?:'Minis Next'", text), 'Wrong application label'
    version, code = match[3], int(match[2])
    assert re.fullmatch(r'\d+\.\d+(?:\.\d+)?(?:-[\w.]+)?', version), 'Invalid version'
    output = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', output)
    assert certs == [CERT_SHA256], f'Unexpected public signing certificate: {certs}'
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    sidecar = apk.with_suffix('.apk.sha256')
    assert sidecar.is_file() and sidecar.read_text().split()[0] == digest, 'Missing prior production verification digest'
    out = ROOT / 'release/minis-next'
    out.mkdir(parents=True, exist_ok=True)
    prefix = 'MinisNext-UI' if PACKAGE.endswith('.ui') else 'MinisNext'
    name = f'{prefix}-{version}-build{code}-arm64-v8a.apk'
    target = out / name
    shutil.copy2(apk, target)
    target.with_suffix('.apk.sha256').write_text(f'{digest}  {name}\n')
    metadata = dict(name='Minis Next', package=PACKAGE, versionName=version, versionCode=code,
                    abi='arm64-v8a', apk=name, bytes=target.stat().st_size, sha256=digest,
                    certificateSha256=CERT_SHA256,
                    sourceCommit=os.environ.get('GITHUB_SHA') or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                    installedOrRun=False)
    (out / 'release-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))
    return metadata

if __name__ == '__main__':
    stage()
