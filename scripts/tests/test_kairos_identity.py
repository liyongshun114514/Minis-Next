"""Kairos independent package, brand, signing and release-source contracts."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[2]
APP=ROOT/'src/android/app'
RES=APP/'src/main/res'
A='{http://schemas.android.com/apk/res/android}'

class KairosIdentityTest(unittest.TestCase):
    def test_independent_install_identity_and_reset_version(self):
        s=(APP/'build.gradle.kts').read_text()
        self.assertIn('applicationId = "com.kairos.app"',s)
        self.assertRegex(s,r'versionCode\s*=\s*2\b')
        self.assertIn('versionName = "1.0.1"',s)
        self.assertIn('namespace = "com.openminis.app"',s)
        self.assertIn('"RELEASE_REPO", "\\"liyongshun114514/OpenMinis\\""',s)
    def test_launcher_brand_and_shortcuts(self):
        strings=E.parse(RES/'values/fork_branding.xml').getroot()
        self.assertEqual('Kairos',strings.find("string[@name='fork_app_name']").text)
        manifest=E.parse(APP/'src/main/AndroidManifest.xml').getroot()
        self.assertEqual('@string/fork_app_name',manifest.find('application').get(A+'label'))
        for intent in E.parse(RES/'xml/shortcuts.xml').getroot().iter('intent'):
            self.assertEqual('com.kairos.app',intent.get(A+'targetPackage'))
            self.assertEqual('com.openminis.app.MainActivity',intent.get(A+'targetClass'))
    def test_new_adaptive_and_monochrome_icons(self):
        for qualifier in ('mipmap-anydpi-v26','mipmap-night-anydpi-v26'):
            for name in ('ic_launcher','ic_launcher_classic_light','ic_launcher_classic_dark'):
                x=E.parse(RES/qualifier/(name+'.xml')).getroot()
                self.assertEqual('adaptive-icon',x.tag)
                self.assertEqual('@drawable/ic_launcher_monochrome',x.find('monochrome').get(A+'drawable'))
                self.assertIsNotNone(x.find('foreground').get(A+'drawable'))
        monochrome=(RES/'drawable/ic_launcher_monochrome.xml').read_text()
        self.assertNotIn('M 36,70 L 36,38',monochrome)
        self.assertIn('@drawable/ic_launcher_monochrome_mask',monochrome)
        self.assertTrue((RES/'drawable-nodpi/ic_launcher_monochrome_mask.png').is_file())
        self.assertTrue((ROOT/'branding/kairos/hourglass-source.png').is_file())
    def test_release_uses_private_stable_identity_not_debug_or_cache(self):
        s=(APP/'build.gradle.kts').read_text().split('        release {',1)[1].split('    // [T15-channels]',1)[0]
        self.assertIn('signingConfigs.getByName("kairosRelease")',s)
        self.assertNotIn('signingConfigs.getByName("debug")',s)
        workflow=(ROOT/'.github/workflows/android-ubuntu.yml').read_text()
        self.assertIn('secrets.KAIROS_KEYSTORE_BASE64',workflow)
        self.assertIn('secrets.KAIROS_STORE_PASSWORD',workflow)
        self.assertNotIn('ubuntu-next-test-signing-v1',workflow)
        self.assertIn('python3 scripts/stage_kairos_release.py',workflow)
    def test_verifier_and_asset_protocol_belong_to_kairos(self):
        verifier=(ROOT/'scripts/verify_ubuntu_apk.py').read_text()
        self.assertIn("package = 'com.kairos.app'",verifier)
        policy=(APP/'src/main/java/com/openminis/app/data/UpdatePolicy.kt').read_text()
        self.assertIn('^Kairos-',policy)
        self.assertIn('it.name.startsWith("Kairos-")',policy)
        self.assertNotIn('Regex("^MinisApp-',policy)

if __name__=='__main__':unittest.main()
