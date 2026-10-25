#!/usr/bin/env python3
"""Launcher-entry contract tests for scripts/verify_ubuntu_apk.py.

Covers the run-40203864705 failure: OpenMinis uses a dynamic app icon, so the
MAIN/LAUNCHER filter lives on an <activity-alias> and aapt2 `dump badging`
prints no `launchable-activity:` line at all. The verifier must read the
compiled manifest (`aapt2 dump xmltree`) and prove the entry is real, not
merely that some text contains MAIN/LAUNCHER.

Pure-function tests build manifest trees directly; the integration tests (when
aapt2 + android.jar are available) compile real manifests and parse the real
aapt2 output.

Run:  python3 -m unittest discover -s scripts/tests -p 'test_verify_android_launcher.py' -v
"""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "verify_ubuntu_apk.py"

spec = importlib.util.spec_from_file_location("verify_ubuntu_apk", SCRIPT)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

PACKAGE = "com.openminis.app.ubuntu"
MAIN = verifier.MAIN_ACTION
LAUNCHER = verifier.LAUNCHER_CATEGORY

# Real `aapt2 dump xmltree` excerpt for a legal alias manifest (verified with
# build-tools 35.0.0); keeps the parser test honest about the tool's format.
REAL_DUMP = '''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: http://schemas.android.com/apk/res/android:package="com.example.alias" (Raw: "com.example.alias")
    A: platformBuildVersionCode=35
      E: application (line=2)
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="x" (Raw: "x")
          E: activity (line=2)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)=".MainActivity" (Raw: ".MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
          E: activity-alias (line=2)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)=".Icon" (Raw: ".Icon")
            A: http://schemas.android.com/apk/res/android:targetActivity(0x01010202)=".MainActivity" (Raw: ".MainActivity")
            A: http://schemas.android.com/apk/res/android:enabled(0x0101000e)=true
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=2)
                  E: action (line=2)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
                  E: category (line=2)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
'''

MANIFEST_TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package}">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="{target_sdk}" />
  <application{app_attrs}>
{body}
  </application>
</manifest>
'''

ACTIVITY = '''
    <activity android:name=".MainActivity"{attrs} />'''

ALIAS = '''
    <activity-alias android:name=".MainActivityIconAuto"
        android:targetActivity=".MainActivity"{attrs}>
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity-alias>'''


def make_manifest(body, target_sdk=35, app_attrs=""):
    return MANIFEST_TEMPLATE.format(
        package=PACKAGE, target_sdk=target_sdk, app_attrs=app_attrs, body=body)


# --- pure tree builders -----------------------------------------------------

def node(tag, attrs=None, children=()):
    item = verifier.ManifestNode(tag, 1)
    item.attrs = dict(attrs or {})
    item.children = list(children)
    return item


def intent_filter(*, main=True, launcher=True):
    children = []
    if main:
        children.append(node("action", {"name": MAIN}))
    if launcher:
        children.append(node("category", {"name": LAUNCHER}))
    return node("intent-filter", children=children)


def activity(name=".MainActivity", **attrs):
    return node("activity", {"name": name, **{k: str(v).lower() for k, v in attrs.items()}})


def alias(name=".Icon", target=".MainActivity", filters=(), **attrs):
    attrs = {k: str(v).lower() for k, v in attrs.items()}
    if target is not None:
        attrs["targetActivity"] = target
    return node("activity-alias", {"name": name, **attrs}, filters)


def tree(*components, app_enabled=True, target_sdk=35, package=PACKAGE):
    app = node("application", {} if app_enabled else {"enabled": "false"}, components)
    return node("manifest", {"package": package}, [
        node("uses-sdk", {"targetSdkVersion": str(target_sdk)}), app])


def launcher_attrs(**extra):
    return {"enabled": "true", "exported": "true", **extra}


class PureLauncherTests(unittest.TestCase):
    def assert_rejected(self, manifest, needle):
        with self.assertRaises(AssertionError) as ctx:
            verifier.validate_launcher_manifest(manifest, PACKAGE)
        self.assertIn(needle, str(ctx.exception))

    def test_legal_alias_is_accepted(self):
        manifest = tree(activity(), alias(filters=[intent_filter()], **launcher_attrs()))
        self.assertEqual(verifier.validate_launcher_manifest(manifest, PACKAGE),
                         PACKAGE + ".Icon")

    def test_legal_direct_activity_is_accepted(self):
        manifest = tree(node("activity",
                             {"name": ".MainActivity", "exported": "true"},
                             [intent_filter()]))
        self.assertEqual(verifier.validate_launcher_manifest(manifest, PACKAGE),
                         PACKAGE + ".MainActivity")

    def test_enabled_defaults_true(self):
        # No android:enabled anywhere; exported explicit (targetSdk 35).
        manifest = tree(activity(), alias(filters=[intent_filter()], exported="true"))
        self.assertEqual(verifier.validate_launcher_manifest(manifest, PACKAGE),
                         PACKAGE + ".Icon")

    def test_exported_default_depends_on_target_sdk(self):
        legacy = tree(activity(), alias(filters=[intent_filter()]), target_sdk=30)
        self.assertEqual(verifier.validate_launcher_manifest(legacy, PACKAGE),
                         PACKAGE + ".Icon")
        modern = tree(activity(), alias(filters=[intent_filter()]), target_sdk=35)
        self.assert_rejected(modern, "not exported")

    def test_application_disabled_rejected(self):
        manifest = tree(activity(), alias(filters=[intent_filter()], **launcher_attrs()),
                        app_enabled=False)
        self.assert_rejected(manifest, "<application> is disabled")

    def test_disabled_alias_rejected(self):
        manifest = tree(activity(), alias(filters=[intent_filter()],
                                          enabled=False, exported=True))
        self.assert_rejected(manifest, "is disabled")

    def test_alias_not_exported_rejected(self):
        manifest = tree(activity(), alias(filters=[intent_filter()],
                                          enabled=True, exported=False))
        self.assert_rejected(manifest, "not exported")

    def test_missing_target_rejected(self):
        manifest = tree(activity(), alias(target=None, filters=[intent_filter()],
                                          **launcher_attrs()))
        self.assert_rejected(manifest, "no targetActivity")

    def test_undeclared_target_rejected(self):
        manifest = tree(alias(target=".Ghost", filters=[intent_filter()],
                              **launcher_attrs()))
        self.assert_rejected(manifest, "not a declared <activity>")

    def test_disabled_target_rejected(self):
        manifest = tree(activity(enabled=False),
                        alias(filters=[intent_filter()], **launcher_attrs()))
        self.assert_rejected(manifest, "targetActivity com.openminis.app.ubuntu.MainActivity is disabled")

    def test_alias_target_must_be_activity_not_alias(self):
        manifest = tree(
            alias(name=".Second", target=".Icon", filters=[intent_filter()],
                  **launcher_attrs()),
            alias(filters=[intent_filter()], **launcher_attrs()))
        self.assert_rejected(manifest, "not a declared <activity>")

    def test_main_and_launcher_must_share_one_filter(self):
        split = alias(filters=[intent_filter(launcher=False)],
                      **launcher_attrs())
        split.children.append(intent_filter(main=False))
        manifest = tree(activity(), split)
        self.assert_rejected(manifest, "no launchable")

    def test_text_mentions_do_not_count(self):
        # A data URI/comment containing the strings must not satisfy the check.
        component = alias(**launcher_attrs())
        component.children.append(node("intent-filter", children=[
            node("action", {"name": "android.intent.action.VIEW"}),
            node("category", {"name": "android.intent.category.DEFAULT"}),
            node("data", {"mimeType": "text/x;name=MAIN;category=LAUNCHER"}),
        ]))
        manifest = tree(activity(), component)
        self.assert_rejected(manifest, "no launchable")

    def test_wrong_package_rejected(self):
        manifest = tree(activity(), alias(filters=[intent_filter()], **launcher_attrs()))
        with self.assertRaises(AssertionError) as ctx:
            verifier.validate_launcher_manifest(manifest, "com.example.other")
        self.assertIn("!= expected", str(ctx.exception))

    def test_legacy_aapt2_typed_booleans(self):
        legacy = REAL_DUMP.replace(
            'enabled(0x0101000e)=true', 'enabled(0x0101000e)=(type 0x12)0xffffffff'
        ).replace('exported(0x01010010)=true', 'exported(0x01010010)=(type 0x12)0xffffffff')
        self.assertEqual(
            verifier.validate_launcher_manifest(legacy),
            'com.example.alias.Icon')
        disabled = REAL_DUMP.replace(
            'enabled(0x0101000e)=true', 'enabled(0x0101000e)=(type 0x12)0x0')
        with self.assertRaises(AssertionError) as ctx:
            verifier.validate_launcher_manifest(disabled)
        self.assertIn('is disabled', str(ctx.exception))

    def test_validate_accepts_xmltree_text(self):
        self.assertEqual(verifier.validate_launcher_manifest(REAL_DUMP),
                         'com.example.alias.Icon')

    def test_parse_real_xmltree(self):
        parsed = verifier.parse_xmltree(REAL_DUMP)
        self.assertEqual(parsed.attr("package"), "com.example.alias")
        self.assertEqual(verifier.validate_launcher_manifest(parsed), "com.example.alias.Icon")

    def test_parse_rejects_non_manifest_root(self):
        with self.assertRaises(AssertionError):
            verifier.parse_xmltree("  E: resources (line=1)\n")


def _find_aapt2():
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            candidates.append(Path(os.environ[env]) / "build-tools" / "35.0.0" / "aapt2")
    candidates.append(Path("/opt/android-sdk/build-tools/35.0.0/aapt2"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    found = shutil.which("aapt2")
    return Path(found) if found else None


def _find_android_jar():
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             "/opt/android-sdk"]
    for root in filter(None, roots):
        jars = sorted((Path(root) / "platforms").glob("android-*/android.jar"), reverse=True)
        if jars:
            return jars[0]
    return None


class RealAapt2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.aapt2 = _find_aapt2()
        cls.android_jar = _find_android_jar()
        if not cls.aapt2 or not cls.android_jar:
            raise unittest.SkipTest("aapt2/android.jar unavailable")

    def compile_and_dump(self, manifest_text):
        with tempfile.TemporaryDirectory(prefix="launcher-manifest-") as tmp:
            tmp = Path(tmp)
            (tmp / "AndroidManifest.xml").write_text(manifest_text)
            apk = tmp / "out.apk"
            subprocess.run([str(self.aapt2), "link", "-o", str(apk),
                            "--manifest", str(tmp / "AndroidManifest.xml"),
                            "-I", str(self.android_jar)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            badging = subprocess.check_output(
                [str(self.aapt2), "dump", "badging", str(apk)], text=True)
            xmltree = subprocess.check_output(
                [str(self.aapt2), "dump", "xmltree", str(apk),
                 "--file", "AndroidManifest.xml"], text=True)
            return badging, xmltree

    def test_real_alias_apk_passes_and_badging_has_no_launchable_line(self):
        badging, xmltree = self.compile_and_dump(
            make_manifest(ACTIVITY.format(attrs=' android:exported="true"') + ALIAS.format(
                attrs=' android:enabled="true" android:exported="true"')))
        # Root cause evidence: aapt2 badging does not emit the alias.
        self.assertNotIn("launchable-activity:", badging)
        self.assertEqual(
            verifier.validate_launcher_manifest(verifier.parse_xmltree(xmltree), PACKAGE),
            PACKAGE + ".MainActivityIconAuto")

    def test_real_disabled_alias_apk_is_rejected(self):
        _, xmltree = self.compile_and_dump(
            make_manifest(ACTIVITY.format(attrs=' android:exported="true"') + ALIAS.format(
                attrs=' android:enabled="false" android:exported="true"')))
        with self.assertRaises(AssertionError):
            verifier.validate_launcher_manifest(verifier.parse_xmltree(xmltree), PACKAGE)

    def test_real_alias_with_missing_target_is_rejected(self):
        body = ACTIVITY.format(attrs=' android:exported="true"') + '''
    <activity-alias android:name=".Icon" android:enabled="true" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity-alias>'''
        _, xmltree = self.compile_and_dump(make_manifest(body))
        with self.assertRaises(AssertionError):
            verifier.validate_launcher_manifest(verifier.parse_xmltree(xmltree), PACKAGE)


if __name__ == "__main__":
    unittest.main()
