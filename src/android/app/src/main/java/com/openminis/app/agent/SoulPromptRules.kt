package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [K-global-prompt-only] LEGACY COMPATIBILITY ONLY.
 *
 * Stored per-model / per-group prompt configuration was removed from the
 * runtime: every turn now uses the global SOUL.md body (see
 * [SystemPromptBuilder.applicationPrompt]). This file is kept so a user's
 * existing `SOUL_PROMPT_RULES.json` still decodes, can still be exported and
 * is never rewritten or deleted by this build — the data is preserved, but
 * nothing in the app reads it to build a prompt.
 *
 * The enum and the rule types below therefore exist to round-trip old JSON,
 * not to describe a behaviour:
 *  - [SoulPromptMode.GLOBAL] is what the product does now, always.
 *  - [SoulPromptMode.CUSTOM] can still appear in an old file; it is inert.
 *  - [SoulPromptRuleTarget.GROUP] and [SoulPromptRuleTarget.MODEL] rows are
 *    inert too. No resolver exists any more.
 */
@Serializable
enum class SoulPromptMode {
    GLOBAL,
    CUSTOM,
}

/**
 * [K-global-prompt-only] Scope recorded by an older build's rule.
 *
 * Both constants are decode-only: no code matches a rule against a group or a
 * model any more. They exist so an old `SOUL_PROMPT_RULES.json` still
 * round-trips without dropping the user's rows.
 */
@Serializable
enum class SoulPromptRuleTarget {
    GROUP,
    MODEL,
}

@Serializable
data class SoulPromptRule(
    val id: String = UUID.randomUUID().toString(),
    /** Optional human label shown in Settings; never sent to the model. */
    val name: String = "",
    val enabled: Boolean = true,
    val target: SoulPromptRuleTarget = SoulPromptRuleTarget.GROUP,
    /** Set for [SoulPromptRuleTarget.GROUP]. */
    val groupId: String? = null,
    /** Set for [SoulPromptRuleTarget.MODEL], together with [modelId]. */
    val providerInstanceId: String? = null,
    /** Set for [SoulPromptRuleTarget.MODEL], together with [providerInstanceId]. */
    val modelId: String? = null,
    val content: String = "",
) {
    /**
     * A target that can actually match something. Blank ids are NOT treated as
     * wildcards: no target means the rule is inert, not global.
     */
    val hasValidTarget: Boolean
        get() = when (target) {
            SoulPromptRuleTarget.GROUP -> !groupId.isNullOrBlank()
            SoulPromptRuleTarget.MODEL ->
                !providerInstanceId.isNullOrBlank() && !modelId.isNullOrBlank()
        }
}

/**
 * Persisted envelope. A single JSON document holds BOTH the mode and the
 * rules, so `save(context, rules)` and `setMode` rewrite one file atomically
 * and can never disagree (no torn "mode says CUSTOM, rules say nothing").
 *
 * [version] is explicit so a later format change can migrate instead of
 * guessing; unknown keys are ignored so a newer build's extra fields do not
 * destroy the list on an older one. A document without `mode` (version 1, or
 * a hand-written file) decodes as [SoulPromptMode.GLOBAL] — the shipped
 * behaviour before this mode existed.
 */
@Serializable
data class SoulPromptRulesDocument(
    val version: Int = CURRENT_VERSION,
    val mode: SoulPromptMode = SoulPromptMode.GLOBAL,
    val rules: List<SoulPromptRule> = emptyList(),
) {
    companion object {
        /** 1 = rules only (no `mode`); 2 = rules + [SoulPromptMode]. */
        const val CURRENT_VERSION: Int = 2
    }
}

/**
 * File-backed store for the prompt MODE and the [SoulPromptRule]s.
 *
 * Location: `<filesDir>/minis-global/memory/SOUL_PROMPT_RULES.json`, beside
 * `SOUL.md`. The memory directory is already what backup export walks, so the
 * configuration travels with a backup without a second integration.
 *
 * [K-global-prompt-only] LEGACY STORAGE ONLY. Nothing in the runtime calls
 * this store to build a prompt; the global SOUL.md body is the only source.
 * Mode and rules live in ONE document ([SoulPromptRulesDocument]) so an old
 * file continues to round-trip:
 *  - [save] rewrites the rules and PRESERVES the stored mode;
 *  - [setMode] rewrites the mode and PRESERVES the stored rules;
 *  - both go through one atomic write, so a crash cannot tear the document.
 *
 * Threading: the public methods are synchronized. The snapshot cache only
 * serves this legacy API and is never read on the prompt path.
 *
 * Failure policy: reads NEVER throw. A missing (or zero-byte) file means the
 * default document. A file that EXISTS but cannot be decoded reads as a
 * no-rules document while its bytes are left untouched, so the user can still
 * recover or export it. Writes are stricter (see below).
 *
 * Writes are stricter on purpose: they first re-read the CURRENT bytes and
 * refuse ([IOException]) when the file exists but cannot be decoded, so a
 * damaged configuration — the user's only copy — is never replaced by
 * defaults. A failed write (corrupt input, I/O error, refused rename) leaves
 * the previous file byte-for-byte intact and only ever removes the temp file.
 * Settings surfaces the throw as "your change did not stick".
 */
object SoulPromptRuleStore {

    private const val TAG = "SoulPromptRules"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    private const val FILE_NAME = "SOUL_PROMPT_RULES.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    // ── Rules ───────────────────────────────────────────────────────────

    @Synchronized
    fun load(context: Context): List<SoulPromptRule> = readDocument(fileLocation(context)).rules

    /**
     * Rewrite the rules and KEEP the stored mode. This is the signature the
     * Settings screen already calls, and the reason editing a rule can never
     * silently flip GLOBAL/CUSTOM.
     */
    @Synchronized
    fun save(context: Context, rules: List<SoulPromptRule>) = save(fileLocation(context), rules)

    // ── Mode ────────────────────────────────────────────────────────────

    @Synchronized
    fun loadMode(context: Context): SoulPromptMode = readDocument(fileLocation(context)).mode

    /**
     * Switch the product's prompt mode. Rules are preserved verbatim (the
     * legacy GROUP records stay in the file, they are simply inert), so
     * toggling back to [SoulPromptMode.CUSTOM] restores the user's rules
     * instead of finding them deleted.
     */
    @Synchronized
    fun setMode(context: Context, mode: SoulPromptMode) = setMode(fileLocation(context), mode)

    /**
     * Explicit two-part write for callers that change both at once (import /
     * migration). The Settings flow keeps using [save] + [setMode].
     */
    @Synchronized
    fun save(
        context: Context,
        mode: SoulPromptMode,
        rules: List<SoulPromptRule>,
    ) = writeDocument(
        fileLocation(context),
        SoulPromptRulesDocument(mode = mode, rules = normalize(rules)),
    )

    /**
     * [K-global-prompt-only] Kept for the backup importer's call site. There is
     * no runtime snapshot to drop: the prompt path never reads this store.
     */
    @Synchronized
    fun invalidate() = Unit

    // ── File-level entry points (no Context) ────────────────────────────
    //
    // These are the real implementations; the Context overloads above only
    // resolve `<filesDir>/minis-global/memory/SOUL_PROMPT_RULES.json`. Keeping
    // them separate is what lets the unit tests drive corruption, mode
    // preservation and write failures against a real temporary file instead of
    // a mocked Context.
    //
    // All of them are `@Synchronized`: the mode and the rules live in ONE
    // document, so two Settings screens (mode tab + rule editor) writing
    // concurrently must serialize or one half would silently revert the other.

    @Synchronized
    fun load(file: File): List<SoulPromptRule> = readDocument(file).rules

    @Synchronized
    fun loadMode(file: File): SoulPromptMode = readDocument(file).mode

    /**
     * Rewrite rules, keep the stored mode (and the rest of the envelope).
     * Throws — and touches nothing — when the existing file cannot be decoded.
     */
    @Synchronized
    fun save(file: File, rules: List<SoulPromptRule>) {
        val current = readDocumentForWrite(file)
        writeDocument(file, current.copy(rules = normalize(rules)))
    }

    /** Rewrite both halves in one atomic document write. */
    @Synchronized
    fun save(file: File, mode: SoulPromptMode, rules: List<SoulPromptRule>) =
        writeDocument(file, SoulPromptRulesDocument(mode = mode, rules = normalize(rules)))

    /** Rewrite the mode, keep the stored rules byte-identical. */
    @Synchronized
    fun setMode(file: File, mode: SoulPromptMode) {
        val current = readDocumentForWrite(file)
        if (current.mode == mode) return
        writeDocument(file, current.copy(mode = mode))
    }

    // ── Pure codec (used by tests, no Context) ──────────────────────────

    /** Pure encoder with the legacy default mode. */
    fun encode(rules: List<SoulPromptRule>): String =
        encode(SoulPromptMode.GLOBAL, rules)

    fun encode(mode: SoulPromptMode, rules: List<SoulPromptRule>): String =
        json.encodeToString(
            SoulPromptRulesDocument.serializer(),
            SoulPromptRulesDocument(mode = mode, rules = normalize(rules)),
        )

    /**
     * Pure decoder for the whole document. Accepts the current object
     * envelope and, defensively, a bare JSON array (a hand-written or
     * third-party file, which can only mean rules + the default mode). Any
     * parse failure yields the default document instead of throwing into the
     * prompt path.
     */
    fun decodeDocument(text: String): SoulPromptRulesDocument =
        decodeOrNull(text) ?: SoulPromptRulesDocument()

    /**
     * Same parse, but `null` on failure. The READ path uses [decodeDocument]
     * (tolerant, so a damaged file cannot break prompt building); the WRITE
     * path uses this one to tell "no configuration" apart from "unreadable
     * configuration" and refuse to overwrite the latter.
     */
    private fun decodeOrNull(text: String): SoulPromptRulesDocument? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SoulPromptRulesDocument()
        return runCatching {
            if (trimmed.startsWith("[")) {
                SoulPromptRulesDocument(
                    rules = json.decodeFromString(ListSerializer(SoulPromptRule.serializer()), trimmed),
                )
            } else {
                json.decodeFromString(SoulPromptRulesDocument.serializer(), trimmed)
            }
        }.getOrNull()?.let { it.copy(rules = normalize(it.rules)) }
    }

    /** Rules-only decoder; `mode` is available through [decodeDocument]. */
    fun decode(text: String): List<SoulPromptRule> = decodeDocument(text).rules

    /**
     * Fill in a missing id and otherwise keep the list untouched — including
     * rules whose target no longer exists. Keeping an orphaned rule lets the
     * user repair it; it cannot match, so it has no runtime effect.
     */
    fun normalize(rules: List<SoulPromptRule>): List<SoulPromptRule> =
        rules.map { rule ->
            if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Read for the WRITE path. Unlike [readDocument] it never guesses:
     *  - missing file → default document (first save);
     *  - zero-byte/blank file → default document (nothing to preserve);
     *  - path that is not a regular file, unreadable bytes, or a JSON document
     *    this build cannot decode → [IOException].
     *
     * Throwing is the point: a corrupt `SOUL_PROMPT_RULES.json` is the user's
     * only copy of their configuration, so a save must fail loudly instead of
     * replacing it with defaults. This read does not touch the runtime cache.
     */
    private fun readDocumentForWrite(file: File): SoulPromptRulesDocument {
        if (!file.exists()) return SoulPromptRulesDocument()
        if (!file.isFile) {
            AppLogger.warning(TAG, "refusing to write ${file.name}: not a regular file")
            throw IOException("${file.name} exists but is not a regular file; refusing to overwrite it")
        }
        val text = file.readText()
        if (text.isBlank()) return SoulPromptRulesDocument()
        val decoded = decodeOrNull(text)
        if (decoded == null) {
            AppLogger.warning(TAG, "refusing to write ${file.name}: existing bytes are corrupt or from an unknown version")
            throw IOException("${file.name} is corrupt or from an unknown version; refusing to overwrite it")
        }
        return decoded
    }

    /**
     * The document used when an EXISTING configuration cannot be decoded.
     *
     * The value is only a data placeholder: nothing in the runtime consults it,
     * so a corrupt legacy file can neither suppress nor replace the global
     * SOUL.md prompt. The bytes are left untouched so the user can still repair
     * or export them.
     */
    private fun failClosedDocument(): SoulPromptRulesDocument =
        SoulPromptRulesDocument(mode = SoulPromptMode.GLOBAL, rules = emptyList())

    /**
     * Read + decode for the READ path. Two different failure behaviours, on
     * purpose:
     *  - file missing / zero-byte → the shipped default document
     *    ([SoulPromptMode.GLOBAL], no rules);
     *  - file exists but its bytes cannot be read or decoded → [failClosedDocument]
     *    (an empty placeholder), with the bytes untouched.
     *
     * Either way the file is left exactly as it is on disk, so the user's last
     * bytes survive a prompt build and can still be repaired or exported.
     */
    private fun readDocument(file: File): SoulPromptRulesDocument {
        val document = if (!file.isFile) {
            SoulPromptRulesDocument()
        } else {
            val text = runCatching { file.readText() }.getOrNull()
            when {
                text == null -> {
                    AppLogger.warning(
                        TAG,
                        "read failed (file left untouched) — failing closed: no application prompt",
                    )
                    failClosedDocument()
                }
                text.isBlank() -> SoulPromptRulesDocument()
                else -> decodeOrNull(text) ?: run {
                    AppLogger.warning(
                        TAG,
                        "existing configuration is corrupt or from an unknown version " +
                            "(file left untouched) — failing closed: no application prompt",
                    )
                    failClosedDocument()
                }
            }
        }
        return document
    }

    /**
     * Write the whole document.
     *
     * Order matters:
     *  1. [readDocumentForWrite] validates the CURRENT bytes. A corrupt or
     *     unreadable configuration aborts here — before a single byte is
     *     written — so the user's only copy is never replaced by defaults.
     *  2. The new document is serialized to a sibling `.tmp` and fsync'ed.
     *  3. [replaceAtomically] swaps it in.
     *  4. Only then is the runtime snapshot updated.
     *
     * There is no backup dance: an earlier version renamed the target to
     * `.bak` and could delete it late, which risked destroying the one
     * remaining copy. Here a failure leaves the target untouched and only the
     * temp file is cleaned up.
     */
    private fun writeDocument(file: File, document: SoulPromptRulesDocument) {
        readDocumentForWrite(file)
        val normalized = document.copy(rules = normalize(document.rules))
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val text = json.encodeToString(SoulPromptRulesDocument.serializer(), normalized)
        val tmp = File(parent, "${file.name}.tmp")
        try {
            val out = FileOutputStream(tmp)
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            } finally {
                out.close()
            }
            replaceAtomically(tmp, file)
        } catch (t: Throwable) {
            // Only the temp file: the target still holds the previous bytes.
            if (tmp.exists()) runCatching { tmp.delete() }
            AppLogger.warning(TAG, "save failed: ${t.message}")
            throw t
        }
    }

    /**
     * One replacement attempt, never a destructive sequence.
     *
     * [Files.move] with `ATOMIC_MOVE` + `REPLACE_EXISTING` is the real
     * contract: on Android/Linux this is `rename(2)`, so the target is either
     * the old file or the new one, never a mix. Providers that reject the
     * flags fall back to a SINGLE `File.renameTo`; if that returns false the
     * target is left as it was and the caller deletes only the temp file. No
     * copy of the previous document is created, moved or deleted here.
     */
    private fun replaceAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: AtomicMoveNotSupportedException) {
            AppLogger.warning(TAG, "atomic move unsupported (${e.message}); trying a single rename")
        } catch (e: IOException) {
            // A half-done move would already have consumed the temp file; only
            // fall through when the temp is still there and the target intact.
            if (!tmp.exists()) throw e
            AppLogger.warning(TAG, "atomic move failed (${e.message}); trying a single rename")
        }
        if (!tmp.renameTo(target)) throw IOException("cannot replace ${target.name}")
    }


}
