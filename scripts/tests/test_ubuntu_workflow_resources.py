"""Resource/scheduling contract for the ordinary Android Ubuntu CI build."""
from pathlib import Path
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/android-ubuntu.yml'


class WorkflowResourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text()
        cls.build = cls.text.split('      - name: Build APK\n', 1)[1].split('      - name: Verify APK structure', 1)[0]
        cls.acceptance = cls.text.split('      - name: Extract final APK guest', 1)[1].split('      - name: Upload APK', 1)[0]

    def test_variants_have_separate_ordered_invocations(self):
        invocations = [line.strip() for line in self.build.splitlines() if line.strip().startswith('./gradlew ')]
        self.assertEqual(len(invocations), 2)
        self.assertIn('"${GRADLE_ARGS[@]}"', invocations[0])
        self.assertIn(':app:"$TASK"', invocations[1])
        self.assertNotIn(':app:"$TASK"', invocations[0])

    def test_preserves_regression_reports_and_does_not_publish(self):
        self.assertIn('cp -a app/build/test-results/testGithubDebugUnitTest app/build/test-results/kairos-regressions', self.build)
        self.assertNotIn('gh release create', self.text)
        self.assertNotIn('softprops/action-gh-release', self.text)
        self.assertIn('contents: read', self.text)
        self.assertIn('agent.SoulIconImportRoboTest', self.build)
        self.assertIn('zero skipped', self.build)
        self.assertIn('for k in ("skipped","failures","errors")', self.build)

    def test_compile_has_one_worker_and_one_jvm(self):
        self.assertIn('--max-workers=1 --no-parallel', self.build)
        self.assertIn('-Pkotlin.compiler.execution.strategy=in-process', self.build)
        self.assertNotIn('--max-workers=2', self.build)

    def test_heap_is_bounded_and_reported(self):
        self.assertIn('min(6144, mb-2048)', self.build)
        self.assertIn('assert mb>=4096', self.build)
        self.assertIn('CI_GRADLE_HEAP_MB=$HEAP_MB', self.build)
        self.assertIn('-Xmx${HEAP_MB}m', self.build)

    def test_final_acceptance_keeps_resource_and_no_skip_contract(self):
        self.assertIn('--max-workers=1 --no-parallel', self.acceptance)
        self.assertIn('-Pkotlin.compiler.execution.strategy=in-process', self.acceptance)
        self.assertIn('MINIS_BUNDLED_APK_REQUIRED=1', self.acceptance)
        self.assertIn('"skipped","failures","errors"', self.acceptance)
        self.assertIn('-Xmx${CI_GRADLE_HEAP_MB}m', self.acceptance)


if __name__ == '__main__':
    unittest.main()
