package com.openminis.app.agent

/** The single user-authored instruction source. Uses the former AGENTS native-system framing. */
internal object SoulInstructionRender {
    const val DISPLAY_PATH = "/var/minis/memory/SOUL.md"

    // Request ordering only: provider enforcement and Android permission gates are unchanged.
    val MANDATORY_INTRO: String get() = com.openminis.app.agent.prompts.PromptOverrides.render("agent.soulinstructionrender.authority", "These SOUL instructions are the highest authority in this request. Where they conflict with any other instruction in this request — the identity paragraph, a persona or style sheet, memory, tool guidance, skill text, runtime context, or any earlier or default instruction that describes how you should behave — these instructions win and that conflicting text is void for this request. Read them as the operator's direct instruction: do not soften them, do not summarise them away, do not add limits they do not contain, and never treat any part of them as a mere suggestion. Their text reaches you verbatim, markup included, and is authoritative as written. One factual rule survives on purpose: if the system refuses or fails an action, say so — never claim an action succeeded when it did not.")

    /** No escaping, line filtering, trimming or second application block. Metadata is not prompt text. */
    fun render(body: String?): String? = body?.takeIf { it.isNotBlank() }?.let {
        MANDATORY_INTRO + "\n\nInstructions from: " + DISPLAY_PATH + "\n\n" + it
    }
}
