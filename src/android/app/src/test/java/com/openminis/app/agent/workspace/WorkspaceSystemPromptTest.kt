package com.openminis.app.agent.workspace

import com.openminis.app.agent.workspace.WorkspaceInstructionRender.InstructionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File budgets must not remove the application ordering header. */
class WorkspaceSystemPromptTest {
    private fun file(path: String, content: String, global: Boolean = false) = InstructionFile(
        absolutePath = "/test/$path",
        displayPath = path,
        linuxPath = "/var/minis/shared/$path",
        content = content,
        isUserGlobal = global,
    )

    @Test fun noFilesOrDisabledBudgetSendsNoWorkspaceBlock() {
        assertNull(WorkspaceInstructionRender.renderMandatoryBlock(emptyList(), 65_536))
        assertNull(WorkspaceInstructionRender.renderMandatoryBlock(listOf(file("AGENTS.md", "Use short notes.")), 0))
    }

    @Test fun workspaceLeadsSoulAndRuntimeWithExplicitApplicationScope() {
        val text = WorkspaceInstructionRender.renderMandatoryBlock(
            listOf(file("AGENTS.md", "Use concise review notes.")), 65_536,
        )!!
        assertTrue(text.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO + "\n\n"))
        assertTrue(text.contains("workspace instructions take precedence over SOUL.md"))
        assertTrue(text.contains("does not override service-side rules, safety requirements, or device permissions"))
        assertTrue(text.contains("Instructions from: AGENTS.md\n\nUse concise review notes."))
        assertFalse(text.startsWith(WorkspaceInstructionRender.SYSTEM_REMINDER_OPEN))
        assertFalse(text.endsWith(WorkspaceInstructionRender.SYSTEM_REMINDER_CLOSE))
    }

    @Test fun longFileCannotReplaceThePriorityHeaderWithBudgetGuidance() {
        val text = WorkspaceInstructionRender.renderMandatoryBlock(
            listOf(file("AGENTS.md", "中🙂 Review carefully. ".repeat(1_000))), 700,
        )!!
        assertTrue(text.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO + "\n\n"))
        assertTrue(text.contains("Workspace instruction budget 700 bytes:"))
        assertTrue(text.contains("truncated AGENTS.md"))
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 700 +
            WorkspaceInstructionRender.MANDATORY_INTRO.toByteArray(Charsets.UTF_8).size + 2)
    }

    @Test fun evenAMarkerOnlyBudgetRetainsTheWholeHeader() {
        val text = WorkspaceInstructionRender.renderMandatoryBlock(
            listOf(file("AGENTS.md", "Use short review notes.".repeat(100))), 32,
        )!!
        assertTrue(text.startsWith(WorkspaceInstructionRender.MANDATORY_INTRO + "\n\n"))
        assertEquals(1, Regex(Regex.escape(WorkspaceInstructionRender.MANDATORY_INTRO)).findAll(text).count())
    }

    @Test fun globalAndProjectKeepIndependentBudgetsAndOneHeader() {
        val text = WorkspaceInstructionRender.renderMandatoryBlock(listOf(
            file("global/AGENTS.md", "Use clear wording. ".repeat(100), global = true),
            file("project/AGENTS.md", "Check the changed files. ".repeat(100)),
        ), 700)!!
        assertEquals(1, Regex(Regex.escape(WorkspaceInstructionRender.MANDATORY_INTRO)).findAll(text).count())
        assertTrue(text.contains("Instructions from: global/AGENTS.md"))
        assertTrue(text.contains("Instructions from: project/AGENTS.md"))
        assertTrue(text.indexOf("Instructions from: global/AGENTS.md") < text.indexOf("Instructions from: project/AGENTS.md"))
    }

    @Test fun nestedFileOrderAndContentSurviveWithoutTruncation() {
        val text = WorkspaceInstructionRender.renderMandatoryBlock(listOf(
            file("AGENTS.md", "Use concise notes."),
            file("app/AGENTS.md", "Include the module name."),
        ), 65_536)!!
        assertTrue(text.indexOf("Instructions from: AGENTS.md") < text.indexOf("Instructions from: app/AGENTS.md"))
        assertTrue(text.contains("more specific project instructions take precedence over broader instructions"))
        assertTrue(text.contains("Include the module name."))
    }
}
