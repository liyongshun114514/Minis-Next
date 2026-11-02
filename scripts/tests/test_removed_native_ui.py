"""Guard the removal of the optional native-card feature without deleting history."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src/android/app/src"
MAIN = APP / "main/java/com/openminis/app"
TOKEN = "intelligent" + "ui"
TOOL = "intelligent" + "_ui"


class RemovedNativeUiTest(unittest.TestCase):
    def test_implementation_tests_resources_and_old_docs_are_absent(self):
        paths = [MAIN / TOKEN, APP / "test/java/com/openminis/app" / TOKEN,
                 APP / "main/res/values" / (TOOL + ".xml"),
                 APP / "main/res/values-zh" / (TOOL + ".xml"),
                 ROOT / "docs" / (TOOL.upper() + ".md")]
        for path in paths:
            with self.subTest(path=path):
                self.assertFalse(path.exists(), f"Removed component remains: {path}")

    def test_no_runtime_or_resource_references_remain(self):
        for path in APP.rglob("*"):
            if path.suffix not in {".kt", ".java", ".xml"}:
                continue
            source = path.read_text()
            with self.subTest(path=path):
                self.assertNotIn(TOKEN, source.lower())
                self.assertNotIn(TOOL, source.lower())
                self.assertNotIn("nativeUiFollowUp", source)
                self.assertNotIn("sendNativeUiFollowUp", source)

    def test_other_tools_and_history_fallback_remain(self):
        registry = (MAIN / "tools/AgentTools.kt").read_text()
        for definition in ["shellExecuteDefinition()", "FileReadTool.definition()",
                           "FileWriteTool.definition()", "FileEditTool.definition()",
                           "browserUseDefinition()", "memoryGetDefinition()",
                           "subAgentTaskDefinition(rosterNames)", "UserAskPromptText.definition()"]:
            self.assertIn(definition, registry)
        screen = (MAIN / "ui/chat/ChatScreen.kt").read_text()
        self.assertIn("is FlatChatItem.AssistantToolUse -> if (item.block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME)", screen)
        self.assertIn("ToolCallPill(", screen)
        self.assertIn("viewModel.openToolDetail(it)", screen)
        planner = (MAIN / "ui/chat/ToolFoldPlanner.kt").read_text()
        self.assertIn("ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)", planner)
        self.assertTrue((MAIN / "ui/chat/ComposerSurfaceGeometry.kt").is_file())
        self.assertTrue((APP / "test/java/com/openminis/app/ui/chat/ComposerSurfaceGeometryTest.kt").is_file())

    def test_ci_has_no_removed_test_filters_or_required_reports(self):
        workflow = (ROOT / ".github/workflows/android-ubuntu.yml").read_text()
        self.assertIn("test_removed_native_ui.py", workflow)
        for removed in ["*IntelligentUi*", "*UiMath*", "*UiExpressionTest", "*UiPlotGeometryTest", TOKEN + "."]:
            self.assertNotIn(removed, workflow)
        self.assertIn("--tests '*ComposerSurfaceGeometryTest'", workflow)
        self.assertIn("--tests '*ToolFoldPlannerTest'", workflow)

    def test_independent_next_identity_does_not_downgrade_to_upstream(self):
        gradle = (ROOT / "src/android/app/build.gradle.kts").read_text()
        self.assertIn('applicationId = "com.openminis.app.next"', gradle)
        self.assertIn('versionCode = 50', gradle)
        self.assertIn('versionName = "1.5"', gradle)
        self.assertIn('signingConfig = signingConfigs.getByName("nextRelease")', gradle)


if __name__ == "__main__":
    unittest.main()
