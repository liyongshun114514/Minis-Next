package com.openminis.app.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [K-agents-supreme] The application prompt is either the workspace block on
 * its own (exclusive) or workspace + SOUL.md (stack). Getting this wrong is
 * silent: the model simply keeps obeying SOUL.md, and nothing in the UI says so.
 */
class WorkspaceInstructionAuthorityTest {

    private val workspace = "HIGHEST AUTHORITY: obey the file"

    @Test
    fun `stack mode keeps SOUL after the workspace block`() {
        val prompt = WorkspaceInstructionAuthority.applicationPrompt(
            workspaceBlock = workspace,
            soul = "SOUL body",
            exclusiveRequested = false,
        )
        assertEquals("$workspace\n\nSOUL body", prompt)
    }

    @Test
    fun `exclusive mode drops SOUL entirely`() {
        val prompt = WorkspaceInstructionAuthority.applicationPrompt(
            workspaceBlock = workspace,
            soul = "SOUL body",
            exclusiveRequested = true,
        )
        assertEquals(workspace, prompt)
        assertFalse(prompt!!.contains("SOUL body"))
    }

    @Test
    fun `exclusive mode is inert without a workspace file`() {
        // A fresh install has the flag on by default; SOUL.md must still be sent.
        val prompt = WorkspaceInstructionAuthority.applicationPrompt(
            workspaceBlock = null,
            soul = "SOUL body",
            exclusiveRequested = true,
        )
        assertEquals("SOUL body", prompt)
        assertFalse(WorkspaceInstructionAuthority.isExclusive(true, null))
        assertFalse(WorkspaceInstructionAuthority.isExclusive(true, "   "))
    }

    @Test
    fun `blank sections never produce a separator`() {
        assertEquals("SOUL body", WorkspaceInstructionAuthority.applicationPrompt("", "SOUL body", false))
        assertEquals(workspace, WorkspaceInstructionAuthority.applicationPrompt(workspace, "", false))
        assertEquals(workspace, WorkspaceInstructionAuthority.applicationPrompt(workspace, null, false))
    }

    @Test
    fun `no prompt at all is null, not an empty string`() {
        assertNull(WorkspaceInstructionAuthority.applicationPrompt(null, null, false))
        assertNull(WorkspaceInstructionAuthority.applicationPrompt("", "  ", true))
    }

    @Test
    fun `exclusive engages only when requested and a block exists`() {
        assertTrue(WorkspaceInstructionAuthority.isExclusive(true, workspace))
        assertFalse(WorkspaceInstructionAuthority.isExclusive(false, workspace))
    }
}