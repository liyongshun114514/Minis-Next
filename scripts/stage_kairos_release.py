#!/usr/bin/env python3
"""Stage exactly one already-verified Kairos APK; never generate a success claim."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.kairos.app'
CERT_SHA256 = '1deda3db350d32849d53212d29f210b64cf5f0401ba36ba7c3c3791994b3c628'

def stage():
    apks = list((ROOT / 'src/android/app/build/outputs/apk/github').rglob('*.apk'))
    assert len(apks) == 1, f'Expected exactly one accepted APK, got {apks}'
    apk = apks[0]
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/android-sdk')) / 'build-tools/35.0.0'
    aapt = sdk / 'aapt2'
    signer = sdk / 'apksigner'
    text = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    assert match and match[1] == PACKAGE, 'Not a Kairos installation package'
    assert re.search(r"application-label(?:-[^:]+)?:'Kairos'", text), 'Wrong application label'
    version, code = match[3], int(match[2])
    assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[\w.]+)?', version), 'Invalid version'
    output = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', output)
    assert certs == [CERT_SHA256], f'Unexpected public signing certificate: {certs}'
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    sidecar = apk.with_suffix('.apk.sha256')
    assert sidecar.is_file() and sidecar.read_text().split()[0] == digest, 'Missing prior production verification digest'
    out = ROOT / 'release/kairos'
    out.mkdir(parents=True, exist_ok=True)
    name = f'Kairos-{version}-build{code}-arm64-v8a.apk'
    target = out / name
    shutil.copy2(apk, target)
    target.with_suffix('.apk.sha256').write_text(f'{digest}  {name}\n')
    metadata = dict(name='Kairos', package=PACKAGE, versionName=version, versionCode=code,
                    abi='arm64-v8a', apk=name, bytes=target.stat().st_size, sha256=digest,
                    certificateSha256=CERT_SHA256,
                    sourceCommit=os.environ.get('GITHUB_SHA') or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                    installedOrRun=False)
    (out / 'release-metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))
    return metadata

if __name__ == '__main__':
    stage()
