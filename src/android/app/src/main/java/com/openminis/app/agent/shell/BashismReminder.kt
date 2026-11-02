package com.openminis.app.agent.shell

/**
 * Builds the <system-reminder> appended after a sh tool-result when a script hit
 * bashism rules but ran under busybox sh (T-bash-on-demand §4.2). Isomorphic to
 * iOS BashismReminder.swift. Script lines are untrusted, so they are sanitized
 * before embedding (M4).
 */
object BashismReminder {

    /** M4: neutralize anything that could break out of the trusted wrapper. */
    fun sanitize(line: String): String {
        var s = line
            .replace("<", "‹") // ‹
            .replace(">", "›") // ›
        s = s.filter { it.code >= 0x20 || it == ' ' }
        if (s.length > 120) s = s.substring(0, 120) + "…"
        return s
    }

    /** Returns null when there is nothing worth telling the model. */
    fun build(hits: List<BashismDetector.Hit>, installFailure: String?): String? {
        if (hits.isEmpty()) return null

        val seen = HashSet<String>()
        val unique = ArrayList<BashismDetector.Hit>()
        for (h in hits) {
            if (seen.add("${h.line}:${h.ruleName}")) unique.add(h)
        }
        val overflow = (unique.size - 8).coerceAtLeast(0)
        val shown = unique.take(8)

        val sb = StringBuilder()
        sb.appendLine("<system-reminder>")
        if (installFailure != null) {
            sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.ee292c0ffbe4", "This command was executed by busybox sh (NOT bash), because bash installation failed: {{slot0}}.", mapOf("slot0" to (sanitize(installFailure)).toString())))
        } else {
            sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.419cbb96eaac", "This command was executed by busybox sh (NOT bash)."))
        }
        sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.bfc9f20590c2", "The script contains bash-only syntax that busybox sh handles incorrectly, which is"))
        sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.606ad081edbc", "likely (part of) why it failed or produced a wrong result. Detected:"))
        sb.appendLine()
        for (h in shown) {
            sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.4ac24f2c3ca8", "  - line {{slot0}}: `{{slot1}}`", mapOf("slot0" to (h.line).toString(), "slot1" to (sanitize(h.matchedText)).toString())))
            sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.d7782bdded34", "    rule: {{slot0}} — {{slot1}}", mapOf("slot0" to (h.ruleName).toString(), "slot1" to (com.openminis.app.agent.prompts.PromptOverrides.render("bashism." + h.ruleName + ".behavior", h.behaviorNote)).toString())))
            sb.appendLine("    fix:  ${com.openminis.app.agent.prompts.PromptOverrides.render("bashism." + h.ruleName + ".fix", h.fixHint)}")
        }
        if (overflow > 0) sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.c6f4a9a20c9a", "  … and {{slot0}} more.", mapOf("slot0" to (overflow).toString())))
        sb.appendLine()
        sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.1ec1d7693c99", "Detected lines are quoted from the submitted script, for locating only."))
        sb.appendLine(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.af774b453cd4", "Either rewrite using the POSIX forms above and retry with sh, or retry unchanged"))
        sb.append(com.openminis.app.agent.prompts.PromptOverrides.render("agent.shell.bashismreminder.84d715beeecd", "later (bash may become installable when the network recovers).\n</system-reminder>"))
        return sb.toString()
    }
}
