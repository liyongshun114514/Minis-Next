"""Freeze contracts for the OmniBot UI port: navigation, untouched chat/data logic, preview isolation.

The port is allowed to restyle screens and add new components; it is NOT
allowed to renumber routes, drop NavHost destinations or callback wiring, edit
markdown/selection/provider/data logic, or leak the UI-acceptance build into the
production APK. Those four things are asserted against the frozen baseline
commit `dad91db` and against the real build/shortcut configuration.

Runs offline with the stdlib + git; no Gradle, no network, no device:

    python3 -m unittest discover -s scripts/tests -p test_omnibot_ui_contract.py -v
"""
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src/android/app"
JAVA = APP / "src/main/java/com/openminis/app"
RES = APP / "src/main/res"
PREVIEW_RES = APP / "src/uiPreview/res"
BASELINE = "dad91db"
NAV = "src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt"
GRADLE = APP / "build.gradle.kts"
MANIFEST = APP / "src/main/AndroidManifest.xml"
NAMESPACE = "com.openminis.app"
PRODUCTION_ID = "com.openminis.app.next"
PREVIEW_ID = "com.openminis.app.next.ui"
ANDROID = "{http://schemas.android.com/apk/res/android}"
CONST_PAIR = re.compile(r'const val ([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"')
DESTINATION = re.compile(r'composable\(\s*(?:route\s*=\s*)?Routes\.([A-Z][A-Z0-9_]*)')
ROUTE_REF = re.compile(r'\bRoutes\.([A-Z][A-Za-z0-9_]*)')


def git(*args):
    return subprocess.run(("git",) + args, cwd=str(ROOT), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def baseline(rel):
    return git("show", f"{BASELINE}:{rel}")


def worktree(rel):
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"expected file is missing: {rel}")
    return path.read_text(encoding="utf-8")


def routes_block(text):
    start = text.index("object Routes {")
    return text[start:text.index("\n}", start)]


def const_pairs(text):
    return {name: value for name, value in CONST_PAIR.findall(routes_block(text))}


def destination_names(text):
    return set(DESTINATION.findall(text))


def route_refs(text):
    return Counter(ROUTE_REF.findall(text))


def changed_since(paths):
    out = git("diff", "--name-only", BASELINE, "--", *paths).strip()
    return [line for line in out.splitlines() if line.strip()]


def ancestors_of(lines, index):
    """Text of every block opener that encloses lines[index]."""
    enclosing, depth = [], 0
    for i in range(index - 1, -1, -1):
        depth += lines[i].count("}") - lines[i].count("{")
        if depth < 0:
            enclosing.append(lines[i])
            depth = 0
    return enclosing


class NavigationFreezeTest(unittest.TestCase):
    def test_route_constants_are_unchanged(self):
        before, after = const_pairs(baseline(NAV)), const_pairs(worktree(NAV))
        self.assertGreater(len(before), 40, "baseline route table looks truncated")
        changed = {name: (value, after.get(name)) for name, value in before.items()
                   if after.get(name) != value}
        self.assertEqual({}, changed, f"route constants were renumbered or removed: {changed}")

    def test_navhost_destinations_are_unchanged(self):
        before = destination_names(baseline(NAV))
        after = destination_names(worktree(NAV))
        self.assertGreater(len(before), 20, "baseline NavHost looks truncated")
        self.assertEqual(set(), before - after, f"NavHost destinations were dropped: {sorted(before - after)}")
        before_text, after_text = baseline(NAV), worktree(NAV)
        self.assertGreaterEqual(after_text.count("composable("), before_text.count("composable("),
                                "the NavHost lost destination registrations")

    def test_route_references_and_callbacks_are_unchanged(self):
        before, after = route_refs(baseline(NAV)), route_refs(worktree(NAV))
        missing = {name: (count, after.get(name, 0)) for name, count in before.items()
                   if after.get(name, 0) < count}
        self.assertEqual({}, missing, f"navigation calls lost route references: {missing}")
        before_text, after_text = baseline(NAV), worktree(worktree(NAV) and NAV)
        for call in ("navController.safeNavigate(", "navController.navigate(", "popUpTo("):
            self.assertGreaterEqual(after_text.count(call), before_text.count(call),
                                    f"{call} wiring was removed from the graph")
        callbacks = re.compile(r"\bon[A-Z][A-Za-z0-9]*(?=\s*=)")
        lost = set(callbacks.findall(before_text)) - set(callbacks.findall(after_text))
        self.assertEqual(set(), lost, f"screen callbacks were disconnected: {sorted(lost)}")

    def test_page_transitions_consume_the_ported_motion_parameters(self):
        text = worktree(NAV)
        self.assertIn("OmniMotion.PageDuration", text)
        self.assertIn("OmniMotion.PageEasing", text)
        self.assertIn("OmniMotion.FadeInDuration", text)
        self.assertIn("OmniMotion.FadeOutDuration", text)
        # The old hand-rolled Material3 curves must not survive next to the port.
        self.assertNotIn("EmphasizedDecelerate", text)

    def test_route_helper_functions_still_build_their_patterns(self):
        text = worktree(NAV)
        for helper in ('fun chat(sessionId: String) = "chat/$sessionId"',
                       'fun providerDetail(instanceId: String)',
                       'fun terminal(initCommand: String? = null, sessionId: String? = null)',
                       'fun skillFile(skillId: String, relativePath: String = "SKILL.md")'):
            self.assertTrue(helper in text, f"route helper was changed or removed: {helper}")


class UntouchedLogicTest(unittest.TestCase):
    MARKDOWN_AND_SELECTION = [
        "src/android/app/src/main/java/com/openminis/app/ui/markdown",
        "src/android/app/src/main/java/com/openminis/app/ui/chat/MinisTextKitSelection.kt",
        "src/android/app/src/main/java/com/openminis/app/ui/chat/SelectionDocumentOrder.kt",
        "src/android/app/src/main/java/com/openminis/app/ui/chat/share",
    ]
    MODEL_AND_BUSINESS = [
        "src/android/app/src/main/java/com/openminis/app/data",
        "src/android/app/src/main/java/com/openminis/app/provider",
        "src/android/app/src/main/java/com/openminis/app/agent",
        "src/android/app/src/main/java/com/openminis/app/tools",
        "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt",
    ]

    def test_markdown_and_selection_sources_are_byte_identical(self):
        changed = changed_since(self.MARKDOWN_AND_SELECTION)
        self.assertEqual([], changed, f"markdown/selection logic was edited by the UI port: {changed}")

    def test_model_provider_and_business_sources_are_byte_identical(self):
        changed = changed_since(self.MODEL_AND_BUSINESS)
        self.assertEqual([], changed, f"model/data/business logic was edited by the UI port: {changed}")

    def test_chat_ui_edits_are_presentation_only(self):
        changed = [p for p in changed_since(["src/android/app/src/main/java/com/openminis/app/ui/chat"])
                   if p.endswith(".kt")]
        self.assertIsNotNone(changed)
        for rel in changed:
            diff = git("diff", "-U0", BASELINE, "--", rel)
            removed_functions = [line for line in diff.splitlines()
                                 if line.startswith("-") and not line.startswith("---")
                                 and "fun " in line]
            self.assertEqual([], removed_functions,
                             f"{rel}: the port removed/renamed a function signature: {removed_functions}")


class PaletteParityTest(unittest.TestCase):
    def test_reference_light_and_dark_tokens_are_exact(self):
        text = (JAVA / 'ui/theme/OmniPalette.kt').read_text()
        fields = 'pageBackground surfacePrimary surfaceSecondary surfaceElevated borderSubtle borderStrong textPrimary textSecondary textTertiary accentPrimary brandPrimary brandSurface segmentTrack segmentThumb overlayScrim previewFallback shadowColor'.split()
        expected = {
            'Light': 'FFF6F6F6 FFFFFFFF FFF0F0F0 FFE7E7E7 FFE4E4E4 FFCECECE FF161616 FF777777 FF929292 FF161616 FF2C7FEB FFE6EDFF FFF0F0F0 FFFFFFFF 4D000000 FFF6F6F6 0D000000',
            'Dark': 'FF151515 FF202020 FF292929 FF333333 FF383838 FF4A4A4A FFF3F3F3 FFB0B0B0 FF8E8E8E FFF3F3F3 FF98AD90 FF2E382D FF292929 FF3A3A3A A6000000 FF1B1B1B 26000000',
        }
        for mode, values in expected.items():
            body = text.split('val Omni' + mode + 'Palette = OmniPalette(', 1)[1].split('\n)', 1)[0]
            actual = dict(re.findall(r'(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', body))
            self.assertEqual(dict(zip(fields, values.split())), {field: actual.get(field) for field in fields})

    def test_primary_roles_and_user_bubbles_are_not_old_accent_colors(self):
        text = (JAVA / 'ui/theme/Theme.kt').read_text()
        for mode in ('Light', 'Dark'):
            self.assertIn('primary = Omni' + mode + 'Palette.accentPrimary', text)
        self.assertNotIn('TealPrimary', text)
        chat = (JAVA / 'ui/theme/ChatColors.kt').read_text()
        self.assertEqual(2, chat.count('userBubble = p.surfaceElevated'))

    def test_ci_executes_render_tests_without_skipping(self):
        workflow = (ROOT / '.github/workflows/android-ubuntu.yml').read_text()
        self.assertIn("--tests '*OmniPresentationRenderTest'", workflow)
        self.assertIn('Missing or skipped UI acceptance tests', workflow)
        self.assertIn('test_omnibot_ui_contract.py', workflow)


class HomeFunctionPreservationTest(unittest.TestCase):
    def test_empty_home_still_has_task_and_shell_entries(self):
        text = (JAVA / 'ui/sessions/SessionListScreen.kt').read_text()
        toolbar = text[text.index('    Scaffold('):text.index('// No default FAB')]
        self.assertIn('onClick = onScheduledTasksClick', toolbar)
        self.assertIn('IconButton(onClick = { showOverflowMenu = true })', toolbar)
        for callback in ('onTerminalClick()', 'onRootfsClick()', 'showBrowserSheet = true', 'showBrowserSettings = true'):
            self.assertIn(callback, toolbar)

    def test_drag_swap_still_writes_the_same_preference(self):
        text = (JAVA / 'ui/sessions/OmniHomeDock.kt').read_text()
        self.assertIn('detectHorizontalDragGestures(', text)
        self.assertIn('100.dp.toPx()', text)
        self.assertIn('putBoolean(PREF_FAB_SWAPPED, swapped)', text)
        self.assertEqual(2, text.count('onSwap = swapActions'))

    def test_input_targets_are_not_reduced_by_the_port(self):
        text = (JAVA / 'ui/chat/ChatComposerWidgets.kt').read_text()
        for name in ('InputCircleButton', 'MicButton'):
            start = text.index('fun ' + name + '(')
            next_fun = re.search(r'\n(?:internal |private )?fun ', text[start + 4:])
            body = text[start:start + 4 + next_fun.start()] if next_fun else text[start:]
            self.assertIn('.size(38.dp)', body)
            self.assertNotIn('.size(34.dp)', body)


class PreviewBuildIsolationTest(unittest.TestCase):
    def gradle_lines(self):
        return worktree(GRADLE).splitlines()

    def test_application_id_defaults_to_production(self):
        lines = self.gradle_lines()
        default_index = [i for i, line in enumerate(lines)
                         if line.strip() == f'applicationId = "{PRODUCTION_ID}"']
        preview_index = [i for i, line in enumerate(lines)
                         if line.strip() == f'applicationId = "{PREVIEW_ID}"']
        self.assertEqual(1, len(default_index), "the production applicationId must be declared exactly once")
        self.assertEqual(1, len(preview_index), "the preview applicationId must be declared exactly once")
        self.assertLess(default_index[0], preview_index[0],
                        "the preview id must only override an existing production default")
        enclosing = ancestors_of(lines, preview_index[0])
        self.assertTrue(any("minisUiPreview" in line for line in enclosing),
                        "the preview applicationId is not gated behind -PminisUiPreview=true")
        self.assertTrue(any('== "true"' in line for line in enclosing),
                        "the preview gate must be an explicit opt-in comparison")

    def test_preview_resources_are_not_part_of_the_default_build(self):
        lines = self.gradle_lines()
        src_dir_lines = [i for i, line in enumerate(lines) if 'srcDir("src/uiPreview/res")' in line]
        self.assertEqual(1, len(src_dir_lines), "uiPreview res dir must be wired exactly once")
        enclosing = ancestors_of(lines, src_dir_lines[0])
        self.assertTrue(any("minisUiPreview" in line for line in enclosing),
                        "uiPreview resources are added without the -PminisUiPreview=true gate")
        self.assertTrue(any('"debug"' in line or "listOf(" in line for line in enclosing),
                        "uiPreview resources must be added to the build-type source sets")
        # The preview res dir must never be added to main/global source sets.
        self.assertNotIn('main.res.srcDir("src/uiPreview/res")', worktree(GRADLE))

    def test_preview_tree_is_isolated_from_main_resources(self):
        files = [p.relative_to(ROOT).as_posix() for p in PREVIEW_RES.rglob("*") if p.is_file()]
        self.assertEqual(["src/android/app/src/uiPreview/res/xml/shortcuts.xml"], files,
                         f"unexpected files in the preview resource overlay: {files}")
        main_shortcuts = (RES / "xml/shortcuts.xml").read_text(encoding="utf-8")
        self.assertNotIn(PREVIEW_ID, main_shortcuts,
                         "the production shortcut file must not reference the preview package")


class PreviewShortcutsTest(unittest.TestCase):
    def parse(self, path):
        root = ET.parse(path).getroot()
        return [element for element in root.iter("shortcut")]

    def resolve(self, reference):
        kind, _, name = reference.lstrip("@").partition("/")
        if kind == "string":
            for values in RES.glob("values*"):
                for xml in values.glob("*.xml"):
                    if re.search(rf'<string\s+name="{re.escape(name)}"', xml.read_text(encoding="utf-8")):
                        return True
            return False
        if kind in ("mipmap", "drawable"):
            return any(RES.glob(f"{kind}*/{name}.*"))
        if kind == "xml":
            return (RES / "xml" / f"{name}.xml").is_file()
        return False

    def test_preview_shortcuts_target_the_preview_package_only(self):
        shortcuts = self.parse(PREVIEW_RES / "xml/shortcuts.xml")
        self.assertEqual(3, len(shortcuts))
        ids, datas = [], []
        for shortcut in shortcuts:
            shortcut_id = shortcut.get(f"{ANDROID}shortcutId")
            ids.append(shortcut_id)
            intents = list(shortcut.iter("intent"))
            self.assertEqual(1, len(intents), f"{shortcut_id}: exactly one intent expected")
            intent = intents[0]
            self.assertEqual(PREVIEW_ID, intent.get(f"{ANDROID}targetPackage"),
                             f"{shortcut_id}: must dispatch into the preview package")
            self.assertNotEqual(PRODUCTION_ID, intent.get(f"{ANDROID}targetPackage"),
                                f"{shortcut_id}: must never dispatch into production")
            self.assertEqual(f"{NAMESPACE}.MainActivity", intent.get(f"{ANDROID}targetClass"),
                             f"{shortcut_id}: MainActivity must be addressed by its real class name")
            data = intent.get(f"{ANDROID}data")
            self.assertTrue(data.startswith("minis://action/"), f"{shortcut_id}: unexpected deep link {data}")
            datas.append(data)
        self.assertEqual(len(ids), len(set(ids)), f"duplicate shortcut ids: {ids}")
        self.assertEqual(len(datas), len(set(datas)), f"duplicate shortcut deep links: {datas}")

    def test_preview_shortcut_resources_resolve(self):
        for shortcut in self.parse(PREVIEW_RES / "xml/shortcuts.xml"):
            for attribute in ("icon", "shortcutShortLabel", "shortcutLongLabel"):
                reference = shortcut.get(ANDROID + attribute)
                self.assertIsNotNone(reference, f"{shortcut.get(ANDROID + 'shortcutId')}: missing {attribute}")
                self.assertTrue(self.resolve(reference),
                                f"{reference} (from {shortcut.get(ANDROID + 'shortcutId')}) does not resolve to a real resource")

    def test_shortcut_target_activity_exists_in_the_manifest(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertRegex(manifest, r'android:name="\.MainActivity"',
                         "MainActivity must be declared so shortcut intents can resolve")
        self.assertIn(f'namespace = "{NAMESPACE}"', worktree(GRADLE),
                      "the shortcut targetClass assumes the manifest namespace")

    def test_shortcut_actions_are_handled_by_the_app(self):
        sources = "\n".join(p.read_text(encoding="utf-8", errors="replace")
                            for p in JAVA.rglob("*.kt"))
        self.assertIn("minis://action/", sources,
                      "shortcut deep links use minis://action/<id>; nothing in the app parses that scheme")


class CompileHarnessContractTest(unittest.TestCase):
    SCRIPT = ROOT / "scripts/test-omnibot-ui.py"

    def test_harness_compiles_the_real_ported_sources(self):
        text = self.SCRIPT.read_text(encoding="utf-8")
        for rel in ("ui/navigation/OmniMotion.kt", "ui/theme/OmniPalette.kt", "ui/theme/ChatColors.kt",
                    "ui/theme/Theme.kt", "ui/sessions/OmniHomeChrome.kt", "ui/sessions/OmniHomeDock.kt",
                    "ui/components/MinisButton.kt", "ui/components/SectionDesign.kt"):
            self.assertIn(rel, text, f"the compile subset dropped {rel}")
            self.assertTrue((JAVA / rel).is_file(), f"{rel} does not exist")

    def test_harness_is_offline_and_uses_local_toolchain_only(self):
        text = self.SCRIPT.read_text(encoding="utf-8")
        for forbidden in ("urllib", "requests", "wget", "curl ", "pip install", "apt-get"):
            self.assertNotIn(forbidden, text, f"the harness must not fetch or install anything ({forbidden})")
        self.assertIn("/opt/toolchains/ui-verify", text)
        self.assertIn("kotlin-compiler-embeddable", text)
        self.assertIn("kotlin-compose-compiler-plugin-embeddable", text)

    def test_harness_stub_boundary_is_declared_and_verified(self):
        text = self.SCRIPT.read_text(encoding="utf-8")
        self.assertIn("android.jar", text, "android.jar must be the documented compile-only stub")
        self.assertIn("R.string", text)
        self.assertIn("verified against", text.lower().replace("REAL", "real").lower())
        self.assertIn("resource_string_names", text,
                      "the R stub must be derived from real string resources, not invented ids")

    def test_harness_can_be_invoked_without_a_device(self):
        result = subprocess.run(["python3", str(self.SCRIPT), "--help"], cwd=str(ROOT),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("--probe", result.stdout)
        self.assertIn("Exit codes", Path(self.SCRIPT).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
