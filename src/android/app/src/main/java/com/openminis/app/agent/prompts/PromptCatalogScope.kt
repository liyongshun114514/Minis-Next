package com.openminis.app.agent.prompts

/**
 * [K-system-prompt-scope] Which catalog entries belong on Settings → System Prompt.
 *
 * The catalog is generated from every `PromptOverrides.render(...)` call site,
 * so it also contains text that is not a system-prompt setting: the 86 per-tool
 * schema descriptions (工具定义), the scenario prompts used by voice, vision,
 * titles/groups, compaction, offload and helper models, the 44 shell-syntax
 * rules and the 35 runtime reminders. Listing all of them on one page made the
 * page 355 rows long and implied that a single tool's parameter description was
 * part of the global system prompt the user is configuring.
 *
 * This object is the single allowlist. Everything outside it stays in the
 * catalog and remains override-able by id (deep links, `minis-config`), it is
 * simply not presented as a system-prompt setting — those texts belong to the
 * feature that sends them and are edited next to that feature.
 *
 * Group names come from the catalog's own `group` field. A test in
 * `PromptCatalogScopeTest` pins the set against the shipped asset, so a renamed
 * or added group fails loudly instead of silently dropping rows.
 */
object PromptCatalogScope {

    /**
     * Groups a main chat turn assembles independently of which tool it calls:
     * the channel preamble and identity, the main system prompt body, workspace
     * instructions, privacy notice, memory block, skill disclosure, scheduled
     * task pre-execution text and MCP disclosure.
     */
    val GLOBAL_GROUPS: List<String> = listOf(
        "身份与全局",
        "主系统提示词",
        "工作区指令",
        "隐私提示",
        "记忆",
        "技能",
        "计划任务",
        "MCP",
    )

    /** `true` when [entry] is a global prompt, i.e. shown on the settings page. */
    fun isGlobal(entry: PromptEntry): Boolean = entry.group in GLOBAL_GROUPS

    /** Stable-order filter used by the hub; entry order is the catalog's order. */
    fun globalOnly(entries: List<PromptEntry>): List<PromptEntry> = entries.filter(::isGlobal)
}