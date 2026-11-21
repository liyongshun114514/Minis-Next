package com.openminis.app.ui.chat

import com.openminis.app.R

/** Eta's argumentsSummary adapted to Minis tools; resource lookup also serves chat search. */
internal fun etaToolName(name: String, string: (Int) -> String): String = when (name) {
    "shell_execute" -> string(R.string.eta_tool_terminal)
    "file_read" -> string(R.string.eta_tool_read)
    "file_write" -> string(R.string.eta_tool_write)
    "file_edit" -> string(R.string.eta_tool_edit)
    "browser_use" -> string(R.string.browser_title)
    "read_image" -> string(R.string.eta_tool_image)
    "memory_get", "memory_write" -> string(R.string.eta_tool_memory)
    "web_search" -> string(R.string.eta_tool_search)
    else -> helperToolLabel(name)
}

internal fun etaToolTitle(block: AssistantBlock, string: (Int) -> String): String {
    val category = etaToolName(block.toolName, string)
    val description = block.toolTitle.takeIf { it.isNotBlank() && it != block.toolName }
        ?: if (block.toolName == "shell_execute") string(R.string.eta_tool_execute) else null
    val command = if (block.toolName == "shell_execute") runCatching {
        org.json.JSONObject(block.toolArgs).optString("command")
    }.getOrDefault("") else ""
    // This describes the command's target, not an inferred/persisted guest backend.
    val platform = if (command.isBlank()) null else {
        val first = command.trimStart().substringBefore(' ').substringAfterLast('/')
        if (first.startsWith("android-")) "Android" else "Linux"
    }
    return listOfNotNull(category, description?.takeIf { it != category }, platform).joinToString(" · ")
}
