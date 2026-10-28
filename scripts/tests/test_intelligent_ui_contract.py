"""Native UI wiring and no-web/no-release contracts (runtime Compose tests are in Kotlin)."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'src/android/app/src/main/java/com/openminis/app'
class IntelligentUiContractTest(unittest.TestCase):
 def test_renderer_is_native_not_web(self):
  s=(JAVA/'intelligentui/IntelligentUiCard.kt').read_text()
  for token in ('Canvas(', 'OutlinedTextField(', 'Slider(', 'Checkbox(', 'ElevatedCard(', 'rememberSaveable'):
   self.assertIn(token,s)
  for forbidden in ('WebView','AndroidView(', 'evaluateJavascript', 'loadUrl', 'HttpURLConnection'):
   self.assertNotIn(forbidden,s)
 def test_registered_executed_and_rendered(self):
  for path,token in [('tools/AgentTools.kt','IntelligentUiTool.definition()'),('ui/chat/ChatViewModel.kt','IntelligentUiTool.execute(argsJson)'),('ui/chat/ChatViewModel.kt','IntelligentUiTool.PROMPT'),('ui/chat/ChatScreen.kt','IntelligentUiCard('),('ui/chat/ToolFoldPlanner.kt','IntelligentUiTool.NAME -> Input.Barrier')]:
   self.assertIn(token,(JAVA/path).read_text())
 def test_controls_do_not_execute_actions(self):
  s=(JAVA/'intelligentui/IntelligentUiCard.kt').read_text()
  self.assertIn('onFollowUp(IntelligentUiParser.followUp',s)
  self.assertIn('interactive && !invalid',s)
  prompt=(JAVA/'intelligentui/IntelligentUiTool.kt').read_text()
  self.assertIn('Use user_ask for approvals',prompt)
  self.assertIn('does not wait for input',prompt)
 def test_new_tests_cannot_be_silently_skipped(self):
  s=(ROOT/'.github/workflows/android-ubuntu.yml').read_text()
  for name in ('IntelligentUiParserTest','UiExpressionTest','IntelligentUiRenderTest'):
   self.assertIn(name,s)
  self.assertIn("--tests '*IntelligentUi*'",s)
  self.assertIn('"skipped","failures","errors"',s)
 def test_cloud_build_has_no_release_publication(self):
  s=(ROOT/'.github/workflows/android-ubuntu.yml').read_text()
  self.assertNotIn('contents: write',s)
  self.assertNotIn('gh release',s)
  self.assertNotIn('publish_verified_run',s)
 def test_upstream_and_limits_documented(self):
  s=(ROOT/'docs/INTELLIGENT_UI.md').read_text()
  self.assertIn('8391a94c1c3939c679e40ddd7bfc08a9ef690e70',s)
  self.assertIn('WebView',s)
  self.assertIn('示意',s)
if __name__=='__main__':unittest.main()
