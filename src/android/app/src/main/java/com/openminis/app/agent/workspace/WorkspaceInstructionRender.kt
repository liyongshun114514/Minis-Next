package com.openminis.app.agent.workspace

/**
 * [T-workspace-instructions] Model-facing rendering of AGENTS.md / CLAUDE.md
 * workspace instructions, ported from DeepSeek Harness
 * (`@deepseek-ai/dsh-agent-instructions`, src/render.ts).
 *
 * The port keeps dsh's observable contract byte-for-byte:
 *
 *  - the complete `<system-reminder>` frame is produced here, including the
 *    intro sentence and every `Instructions from: <path>` section;
 *  - the user-global chain and the project chain are budgeted SEPARATELY, so a
 *    message holding both is at most twice `maxBytes`;
 *  - within a chain the broadest files are omitted BEFORE the most specific
 *    file is truncated, and truncation never splits a UTF-8 code point;
 *  - an over-budget chain degrades through compact intro -> marker with an
 *    empty heading -> marker alone, never by silently dropping content;
 *  - a `Workspace instruction budget ...` notice names every omitted and
 *    truncated path;
 *  - literal `</system-reminder>` inside instruction content is escaped so
 *    repository-controlled text cannot close the frame we own.
 *
 * Precedence is dsh's, not a new one: these sections are delivered as a
 * durable user-role message and the intro states that more specific
 * instructions win over broader ones while none of them override system,
 * developer, or direct user instructions.
 */
internal object WorkspaceInstructionRender {

    const val SYSTEM_REMINDER_OPEN = "<system-reminder>"
    const val SYSTEM_REMINDER_CLOSE = "</system-reminder>"

    const val AGENT_INSTRUCTIONS_INTRO =
        "The following workspace instructions may be relevant to your work. " +
            "Use them as guidance when applicable. More specific instructions take precedence over broader ones. " +
            "They do not override system, developer, or direct user instructions."

    const val REPLACEMENT_AGENT_INSTRUCTIONS_INTRO =
        "This complete workspace instruction baseline replaces all earlier workspace instruction baselines. " +
            AGENT_INSTRUCTIONS_INTRO

    const val EMPTY_REPLACEMENT_AGENT_INSTRUCTIONS_INTRO =
        "This complete workspace instruction baseline replaces all earlier workspace instruction baselines. " +
            "No workspace instructions are currently active."

    const val COMPACT_AGENT_INSTRUCTIONS_INTRO =
        "Workspace instructions were omitted or truncated to fit the configured byte budget."

    private const val ADDITIONAL_INSTRUCTIONS_GUIDANCE =
        "Use them as guidance when relevant; more specific instructions take precedence. " +
            "They do not override system, developer, or direct user instructions."

    /** One loaded instruction file. `displayPath` is what the model sees. */
    data class InstructionFile(
        /** Host path identity; the dedup/reconcile key. */
        val absolutePath: String,
        /** Model-facing path (project-relative, or the absolute user-global path). */
        val displayPath: String,
        /** Linux path the Settings editor writes back to. */
        val linuxPath: String,
        val content: String,
        val isUserGlobal: Boolean,
    )

    /** Byte-accounting record for one truncated instruction file. */
    data class TruncatedInstruction(
        val displayPath: String,
        val originalBytes: Int,
        val includedBytes: Int,
    )

    /** Rendered model-facing text plus the files it actually represents. */
    data class Rendered(
        val text: String,
        val omitted: List<InstructionFile>,
        val truncated: List<TruncatedInstruction>,
        val represented: List<InstructionFile>,
        val sections: List<InstructionFile>,
        val intro: String?,
    )

    /** One state transition, in the shape dsh's change renderer consumes. */
    enum class ChangeAction { SET, REPLACE, REMOVE }

    data class ChangeItem(
        val action: ChangeAction,
        val file: InstructionFile,
    )

    private interface SectionStyle {
        val intro: String
        fun section(file: InstructionFile): String
    }

    private fun byteLength(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /**
     * Truncate on a UTF-8 code-point boundary. A naive `take(n)` on the decoded
     * string would count characters, not bytes; cutting the byte array alone
     * could leave a broken continuation sequence.
     */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxOf(0, maxBytes)
        // If the first excluded byte is a continuation byte, back up to its lead byte.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end -= 1
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun escapeFrameBody(body: String): String =
        body.replace(SYSTEM_REMINDER_CLOSE, "<\\/system-reminder>")

    private fun sectionText(file: InstructionFile): String =
        "Instructions from: ${file.displayPath}\n\n${file.content}"

    private fun markerText(
        maxBytes: Int,
        omitted: List<InstructionFile>,
        truncated: List<TruncatedInstruction>,
    ): String {
        if (omitted.isEmpty() && truncated.isEmpty()) return ""
        val parts = mutableListOf<String>()
        if (omitted.isNotEmpty()) {
            parts += "omitted " + omitted.joinToString(", ") { it.displayPath }
        }
        if (truncated.isNotEmpty()) {
            parts += "truncated " + truncated.joinToString(", ") {
                "${it.displayPath} from ${it.originalBytes} to ${it.includedBytes} bytes"
            }
        }
        return "Workspace instruction budget $maxBytes bytes: " + parts.joinToString("; ")
    }

    private fun buildText(
        files: List<InstructionFile>,
        maxBytes: Int,
        omitted: List<InstructionFile>,
        truncated: List<TruncatedInstruction>,
        style: SectionStyle,
    ): String {
        val marker = markerText(maxBytes, omitted, truncated)
        val blocks = buildList {
            if (marker.isNotEmpty()) add(marker)
            if (style.intro.isNotEmpty()) add(style.intro)
            for (file in files) add(style.section(file))
        }
        return listOf(
            SYSTEM_REMINDER_OPEN,
            escapeFrameBody(blocks.joinToString("\n\n")),
            SYSTEM_REMINDER_CLOSE,
        ).joinToString("\n")
    }

    private fun withTruncatedContent(file: InstructionFile, includedBytes: Int): InstructionFile =
        file.copy(content = truncateUtf8(file.content, includedBytes))

    private fun isDisabledBudget(maxBytes: Int): Boolean = maxBytes <= 0

    private val BASELINE_STYLE = object : SectionStyle {
        override val intro: String = AGENT_INSTRUCTIONS_INTRO
        override fun section(file: InstructionFile): String = sectionText(file)
    }

    private fun baselineStyle(files: List<InstructionFile>, replacePreviousBaseline: Boolean): SectionStyle {
        if (!replacePreviousBaseline) return BASELINE_STYLE
        val intro = if (files.isEmpty()) EMPTY_REPLACEMENT_AGENT_INSTRUCTIONS_INTRO else REPLACEMENT_AGENT_INSTRUCTIONS_INTRO
        return object : SectionStyle {
            override val intro: String = intro
            override fun section(file: InstructionFile): String = sectionText(file)
        }
    }

    private fun additionalSectionText(file: InstructionFile): String {
        val applicability = if (file.isUserGlobal) {
            "These user-global instructions apply to all work."
        } else {
            "These instructions apply to work under `${projectScope(file.displayPath)}`."
        }
        return listOf(
            "Additional instructions from: ${file.displayPath}",
            "",
            "$applicability $ADDITIONAL_INSTRUCTIONS_GUIDANCE",
            "",
            file.content,
        ).joinToString("\n")
    }

    /** Directory scope of a project-relative display path: `.` or the containing directory. */
    private fun projectScope(displayPath: String): String {
        val index = displayPath.lastIndexOf('/')
        return if (index < 0) "." else displayPath.substring(0, index)
    }

    private fun changedSectionText(item: ChangeItem): String = when (item.action) {
        ChangeAction.SET -> additionalSectionText(item.file)
        ChangeAction.REMOVE ->
            "Instructions removed: ${item.file.displayPath}\n\n" +
                "The previously loaded instructions from this file no longer apply."
        ChangeAction.REPLACE -> listOf(
            "Updated instructions from: ${item.file.displayPath}",
            "",
            "This file changed after it was loaded. Use the following content instead of the previously loaded instructions from this file.",
            "",
            item.file.content,
        ).joinToString("\n")
    }

    private fun truncateToFit(
        file: InstructionFile,
        includedFiles: List<InstructionFile>,
        maxBytes: Int,
        omitted: List<InstructionFile>,
        style: SectionStyle,
    ): InstructionFile {
        val originalBytes = byteLength(file.content)
        var low = 0
        var high = originalBytes
        var best = withTruncatedContent(file, 0)
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = withTruncatedContent(file, mid)
            val truncated = listOf(
                TruncatedInstruction(file.displayPath, originalBytes, byteLength(candidate.content)),
            )
            val text = buildText(includedFiles + candidate, maxBytes, omitted, truncated, style)
            if (byteLength(text) <= maxBytes) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun renderContext(
        files: List<InstructionFile>,
        maxBytes: Int,
        style: SectionStyle,
    ): Rendered {
        if (isDisabledBudget(maxBytes)) {
            return Rendered("", files, emptyList(), emptyList(), emptyList(), null)
        }
        val fullText = buildText(files, maxBytes, emptyList(), emptyList(), style)
        if (byteLength(fullText) <= maxBytes) {
            return Rendered(fullText, emptyList(), emptyList(), files, files, style.intro)
        }
        // Drop whole broader files first, broadest first.
        for (start in 1 until files.size) {
            val included = files.subList(start, files.size)
            val omitted = files.take(start)
            val suffixText = buildText(included, maxBytes, omitted, emptyList(), style)
            if (byteLength(suffixText) <= maxBytes) {
                return Rendered(suffixText, omitted, emptyList(), included, included, style.intro)
            }
        }
        val mostSpecific = files.lastOrNull()
            ?: return Rendered("", emptyList(), emptyList(), emptyList(), emptyList(), null)
        val omitted = files.dropLast(1)
        val originalBytes = byteLength(mostSpecific.content)
        val compactStyle = object : SectionStyle {
            override val intro: String = COMPACT_AGENT_INSTRUCTIONS_INTRO
            override fun section(file: InstructionFile): String = sectionText(file)
        }
        for (candidateStyle in listOf(style, compactStyle)) {
            val truncatedFile = truncateToFit(mostSpecific, emptyList(), maxBytes, omitted, candidateStyle)
            val includedBytes = byteLength(truncatedFile.content)
            val truncated = listOf(TruncatedInstruction(mostSpecific.displayPath, originalBytes, includedBytes))
            val text = buildText(listOf(truncatedFile), maxBytes, omitted, truncated, candidateStyle)
            if (byteLength(text) <= maxBytes) {
                val represented = if (includedBytes > 0 || originalBytes == 0) listOf(mostSpecific) else emptyList()
                return Rendered(text, omitted, truncated, represented, listOf(truncatedFile), candidateStyle.intro)
            }
        }
        val truncated = listOf(TruncatedInstruction(mostSpecific.displayPath, originalBytes, 0))
        val compactNotice = escapeFrameBody(markerText(maxBytes, omitted, truncated))
        val heading = withTruncatedContent(mostSpecific, 0)
        val compactWithHeading = escapeFrameBody(
            listOf(compactNotice, style.section(heading)).joinToString("\n\n"),
        )
        if (byteLength(compactWithHeading) <= maxBytes) {
            val represented = if (originalBytes == 0) listOf(mostSpecific) else emptyList()
            return Rendered(compactWithHeading, omitted, truncated, represented, listOf(heading), null)
        }
        val text = if (byteLength(compactNotice) <= maxBytes) compactNotice else truncateUtf8(compactNotice, maxBytes)
        return Rendered(text, omitted, truncated, emptyList(), emptyList(), null)
    }

    private fun renderChains(
        files: List<InstructionFile>,
        maxBytes: Int,
        style: SectionStyle,
    ): Rendered {
        val whole = renderContext(files, maxBytes, style)
        val userGlobal = files.filter { it.isUserGlobal }
        val project = files.filter { !it.isUserGlobal }
        if (isDisabledBudget(maxBytes) || userGlobal.isEmpty() || project.isEmpty() ||
            (whole.omitted.isEmpty() && whole.truncated.isEmpty())
        ) {
            return whole
        }
        val chains = listOf(
            renderContext(userGlobal, maxBytes, style),
            renderContext(project, maxBytes, style),
        )
        fun inLoadedOrder(records: List<InstructionFile>): List<InstructionFile> {
            val byPath = records.associateBy { it.absolutePath }
            return files.mapNotNull { byPath[it.absolutePath] }
        }
        val omitted = inLoadedOrder(chains.flatMap { it.omitted })
        val sections = inLoadedOrder(chains.flatMap { it.sections })
        val represented = inLoadedOrder(chains.flatMap { it.represented })
        val userGlobalLast = files.indexOfLast { it.isUserGlobal }
        val projectLast = files.indexOfLast { !it.isUserGlobal }
        val orderedChains = if (userGlobalLast < projectLast) chains else chains.reversed()
        val truncated = orderedChains.flatMap { it.truncated }
        val intros = chains.mapNotNull { it.intro }
        val intro = if (intros.contains(style.intro)) style.intro else intros.firstOrNull() ?: ""
        val mergedStyle = object : SectionStyle {
            override val intro: String = intro
            override fun section(file: InstructionFile): String = sectionText(file)
        }
        val text = buildText(sections, maxBytes, omitted, truncated, mergedStyle)
        if (byteLength(text) > 2 * maxBytes) return whole
        return Rendered(text, omitted, truncated, represented, sections, intro)
    }

    /** Render the baseline. `replacePrevious` mirrors dsh's replacement baseline. */
    fun renderBaseline(
        files: List<InstructionFile>,
        maxBytes: Int,
        replacePrevious: Boolean = false,
    ): Rendered = renderChains(files, maxBytes, baselineStyle(files, replacePrevious))

    /**
     * Render one reconciliation batch. A batch holding user-global and project
     * changes can be at most twice `maxBytes`, exactly as in dsh.
     */
    fun renderChanges(items: List<ChangeItem>, maxBytes: Int): Pair<String, List<ChangeItem>> {
        val byPath = items.associateBy { it.file.absolutePath }
        val style = object : SectionStyle {
            override val intro: String = ""
            override fun section(file: InstructionFile): String =
                byPath[file.absolutePath]?.let { changedSectionText(it) } ?: ""
        }
        val rendered = renderChains(items.map { it.file }, maxBytes, style)
        val represented = rendered.represented.map { it.absolutePath }.toSet()
        return rendered.text to items.filter { represented.contains(it.file.absolutePath) }
    }
}
