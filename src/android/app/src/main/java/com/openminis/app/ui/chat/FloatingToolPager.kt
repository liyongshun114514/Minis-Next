package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperRunner

/** Background delegates must not pin the shared work bar/preview. */
internal fun floatingToolPreferredIndex(blocks: List<AssistantBlock>): Int {
    val activeMain = blocks.indexOfLast {
        !HelperRunner.isSubAgentToolName(it.toolName) &&
            (it.toolStatus == ToolBlockStatus.RUNNING ||
             it.toolStatus == ToolBlockStatus.STREAMING ||
             it.toolStatus == ToolBlockStatus.PENDING)
    }
    if (activeMain >= 0) return activeMain
    // An active background child must not hide the main model's latest result.
    val latestMain = blocks.indexOfLast { !HelperRunner.isSubAgentToolName(it.toolName) }
    return if (latestMain >= 0) latestMain else blocks.lastIndex
}

/** Keep manual selection stable through appends and in-place status updates. */
internal fun floatingToolSelectedIndex(
    blocks: List<AssistantBlock>, selectedId: String?, followLatest: Boolean,
): Int {
    val selected = blocks.indexOfFirst { it.id == selectedId }
    return if (!followLatest && selected >= 0) selected else floatingToolPreferredIndex(blocks)
}
