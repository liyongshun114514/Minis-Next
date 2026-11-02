"""Prompt catalog checks work before Gradle generates any Android assets."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('prompt_catalog', ROOT / 'scripts/check-prompt-catalog.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
RULES = 'src/shared/bashism/bashism_rules.json'
GENERATED = 'src/android/app/src/main/assets/bashism/bashism_rules.json'


class PromptCatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='prompt-catalog-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.write(RULES, json.dumps({'rules': [
            {'name': 'sample', 'behaviorNote': 'Behavior', 'fixHint': 'Fix'}
        ]}, indent=2))
        self.write(CHECK.MAIN + '/Sample.kt', 'PromptOverrides.render("sample", "Hello")\n')
        self.write(CHECK.MAIN + '/com/openminis/app/agent/shell/BashismReminder.kt',
                   '"bashism." + h.ruleName + ".behavior"\n'
                   '"bashism." + h.ruleName + ".fix"\n')
        self.write(CHECK.CATALOG, json.dumps([
            {'id': 'sample', 'group': 'test', 'defaultTemplate': 'Hello',
             'source': CHECK.MAIN + '/Sample.kt:1'},
            {'id': 'bashism.sample.behavior', 'group': 'shell',
             'defaultTemplate': 'Behavior', 'source': RULES + ':5'},
            {'id': 'bashism.sample.fix', 'group': 'shell',
             'defaultTemplate': 'Fix', 'source': RULES + ':6'}
        ]))

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def test_fresh_checkout_without_generated_assets_passes(self):
        self.assertFalse((self.root / GENERATED).exists())
        result = CHECK.validate(self.root)
        self.assertTrue(result['ok'], result['errors'])
        self.assertEqual(3, result['catalog_entries'])
        self.assertEqual(2, result['dynamic_shell_rules'])
        self.assertFalse((self.root / GENERATED).exists())

    def test_stale_generated_copy_cannot_override_shared_source(self):
        self.write(GENERATED, 'not json; ignored build output')
        result = CHECK.validate(self.root)
        self.assertTrue(result['ok'], result['errors'])

    def test_shared_rule_drift_still_fails_closed(self):
        rules = json.loads((self.root / RULES).read_text())
        rules['rules'][0]['fixHint'] = 'Changed fix'
        self.write(RULES, json.dumps(rules))
        result = CHECK.validate(self.root)
        self.assertFalse(result['ok'])
        self.assertIn('shell rule default differs: bashism.sample.fix', result['errors'])

    def test_refresh_points_to_tracked_source_not_generated_copy(self):
        result = CHECK.validate(self.root, refresh=True)
        self.assertTrue(result['ok'], result['errors'])
        entries = json.loads((self.root / CHECK.CATALOG).read_text())
        for entry in entries:
            if entry['id'].startswith('bashism.'):
                relative, number = entry['source'].rsplit(':', 1)
                self.assertEqual(RULES, relative)
                field = 'fixHint' if entry['id'].endswith('.fix') else 'behaviorNote'
                self.assertIn('"' + field + '"', (self.root / relative).read_text().splitlines()[int(number)-1])


if __name__ == '__main__':
    unittest.main()
