package com.openminis.app.agent.workspace

/**
 * [K-agents-supreme] What the application prompt of one request is.
 *
 * Two shapes exist and the difference is one line of text:
 *
 *  - **stack** (default before this feature): `AGENTS.md` block, then SOUL.md,
 *    joined by a blank line. The block's header claims precedence, but SOUL.md
 *    is still in the request, so a model that weighs the later text can still
 *    follow it.
 *  - **exclusive**: the workspace block alone. SOUL.md is not sent at all, and
 *    the caller also drops the generated identity paragraph, so nothing else
 *    in the system channel can compete with the file. Precedence by omission.
 *
 * Exclusive mode only engages when there is actually a workspace block: with no
 * AGENTS.md on disk the request falls back to SOUL.md rather than sending no
 * application prompt at all (dropping the user's own prompt because a directory
 * is empty would look like the feature was broken).
 */
internal object WorkspaceInstructionAuthority {

    fun isExclusive(requested: Boolean, workspaceBlock: String?): Boolean =
        requested && !workspaceBlock.isNullOrBlank()

    /** The provider's `applicationSystemPrompt`, or null when there is none. */
    fun applicationPrompt(
        workspaceBlock: String?,
        soul: String?,
        exclusiveRequested: Boolean,
    ): String? {
        val workspace = workspaceBlock?.takeIf { it.isNotBlank() }
        if (isExclusive(exclusiveRequested, workspace)) return workspace
        val soulBody = soul?.takeIf { it.isNotBlank() }
        return listOfNotNull(workspace, soulBody).joinToString("\n\n").ifBlank { null }
    }
}
