"""The cloud UI test gate must fail closed for missing/skipped suites."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ui_gate', ROOT / 'scripts/verify-ui-test-reports.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class UiReportGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='minis-ui-report-gate-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.src = self.root / 'sources'
        self.reports = self.root / 'reports'
        self.src.mkdir()
        self.reports.mkdir()
        (self.src / 'OmniOneTest.kt').write_text('package com.test\nclass OmniOneTest {}\n')

    def xml(self, name='OmniOneTest', tests=2, **bad):
        attrs = {'tests': tests, 'skipped': 0, 'failures': 0, 'errors': 0} | bad
        (self.reports / f'TEST-com.test.{name}.xml').write_text('<testsuite ' + ' '.join(f'{k}="{v}"' for k, v in attrs.items()) + '/>')

    def run_gate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            gate.verify(self.reports, self.src)

    def test_complete_real_reports_pass(self):
        self.xml()
        self.run_gate()

    def test_missing_report_fails(self):
        with self.assertRaisesRegex(ValueError, 'missing XML'):
            self.run_gate()

    def test_zero_tests_fails(self):
        self.xml(tests=0)
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_skips_fail(self):
        self.xml(skipped=1)
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_failures_fail(self):
        self.xml(failures=1)
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_errors_fail(self):
        self.xml(errors=1)
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_new_ui_class_requires_report(self):
        self.xml()
        (self.src / 'OmniTwoTest.kt').write_text('package com.test\nclass OmniTwoTest {}\n')
        with self.assertRaisesRegex(ValueError, 'OmniTwoTest'):
            self.run_gate()

    def test_chat_motion_requires_report(self):
        self.xml()
        (self.src / 'ChatComposerMotionTest.kt').write_text('package com.test\nclass ChatComposerMotionTest {}\n')
        with self.assertRaisesRegex(ValueError, 'ChatComposerMotionTest'):
            self.run_gate()

    def test_bad_xml_fails(self):
        self.xml()
        (self.reports / 'TEST-com.test.OmniOneTest.xml').write_text('not xml')
        with self.assertRaisesRegex(ValueError, 'invalid XML'):
            self.run_gate()


if __name__ == '__main__':
    unittest.main()
