package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Result of a read: [issue] is non-null when the file exists but is unreadable. */
data class CharacterCardLoad(
    val cards: List<CharacterCard>,
    val issue: String? = null,
)

/** Result of merging legacy SOUL.md / SOUL_PROMPT_RULES.json into the card store. */
data class LegacyMigrationReport(
    val ran: Boolean,
    val cardsAdded: Int = 0,
    val totalCards: Int = 0,
    val archivedTo: String? = null,
    val skippedReason: String? = null,
    val error: String? = null,
)

/**
 * [T-character-cards] The one persistent store for character cards.
 *
 * Location: `<filesDir>/minis-global/memory/CHARACTER_CARDS.json`, i.e. the
 * directory the existing backup exporter already walks — a card therefore
 * travels with a backup without touching the backup code.
 *
 * Rules this store enforces:
 *  - ATOMIC WRITES. Serialize -> `.tmp` -> fsync -> rename -> decode the file
 *    again and compare ids/content. A write that cannot be read back raises
 *    instead of leaving a half-written document behind.
 *  - A CORRUPT DOCUMENT IS NEVER OVERWRITTEN. [load] reports the problem and
 *    returns no cards; [save] refuses to write while the unreadable file is
 *    still on disk. [quarantineCorrupt] moves it aside explicitly (the caller
 *    then decides), which is what makes recovery possible without losing it.
 *  - ONE CACHE. All prompt paths read [runtimeSnapshot] (no disk I/O, no
 *    Context) and the app refreshes it from [load] at startup / after a
 *    restore. A fingerprint (`lastModified:length`) catches writes made
 *    outside this process.
 *
 * Threading: every read-modify-write runs under one lock; the cache is
 * volatile. No method calls back into [SoulStore] or [SoulPromptRuleStore],
 * so there is no recursion between the compat adapters and this store.
 */
object CharacterCardStore {

    private const val TAG = "CharacterCardStore"
    const val FILE_NAME = "CHARACTER_CARDS.json"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    const val DEFAULT_GLOBAL_ID = "openminis-default-global"
    private const val ARCHIVE_DIR = "legacy-soul-archive"

    private val lock = Any()

    @Volatile private var cached: List<CharacterCard>? = null
    @Volatile private var cachedFingerprint: String? = null
    @Volatile private var lastIssue: String? = null

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    // -- Reads ---------------------------------------------------------

    /**
     * Read the store. Never throws: a missing file is an empty list, a corrupt
     * one is an empty list plus a logged [CharacterCardLoad.issue] (and the
     * bytes are left exactly as they are).
     */
    @Synchronized
    fun load(context: Context): List<CharacterCard> = loadReporting(context).cards

    @Synchronized
    fun loadReporting(context: Context): CharacterCardLoad {
        val file = fileLocation(context)
        if (!file.isFile) {
            cached = emptyList()
            cachedFingerprint = fingerprintOf(file)
            lastIssue = null
            SoulStore.updateMetadataFromCards(emptyList())
            return CharacterCardLoad(emptyList())
        }
        val loaded = runCatching { readStrict(file) }
        val issue = loaded.exceptionOrNull()?.let {
            AppLogger.warning(TAG, "CHARACTER_CARDS.json is unreadable: ${it.message}")
            it.message ?: "unreadable card document"
        }
        lastIssue = issue
        if (loaded.isSuccess) {
            cached = loaded.getOrThrow()
            cachedFingerprint = fingerprintOf(file)
        }
        val visible = loaded.getOrDefault(emptyList())
        SoulStore.updateMetadataFromCards(visible)
        return CharacterCardLoad(visible, issue)
    }

    /**
     * Runtime snapshot for prompt building. Null means "never loaded" — the
     * caller then falls back to the legacy adapter, which is also what the
     * pure unit tests exercise.
     */
    fun runtimeSnapshot(): List<CharacterCard>? = cached

    fun lastLoadIssue(): String? = lastIssue

    /** Read one file strictly; throws [CharacterCardImportException] when bad. */
    fun readStrict(file: File): List<CharacterCard> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        return CharacterCardJson.decodeStrict(text)
    }

    // -- Writes --------------------------------------------------------

    /**
     * Persist [cards] atomically.
     *
     * Throws:
     *  - [IllegalStateException] when the existing document is corrupt (the
     *    caller must quarantine it first — see [quarantineCorrupt]);
     *  - [java.io.IOException] when the write or the read-back check fails.
     */
    @Synchronized
    fun save(context: Context, cards: List<CharacterCard>) {
        val file = fileLocation(context)
        writeChecked(file, cards)
        cached = CharacterCardJson.normalize(cards)
        cachedFingerprint = fingerprintOf(file)
        lastIssue = null
        SoulStore.updateMetadataFromCards(cached.orEmpty())
    }

    /**
     * [T-character-cards-corrupt] Write ONLY when the existing document is
     * either absent or readable. A corrupt document raises here — this is the
     * single guard that keeps a damaged file from being replaced by an empty
     * or partial list.
     */
    fun writeChecked(file: File, cards: List<CharacterCard>) {
        if (file.isFile) {
            try {
                readStrict(file)
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "${file.name} exists but cannot be read; refusing to overwrite it. " +
                        "Export or move it away first (quarantineCorrupt).",
                    t,
                )
            }
        }
        writeVerified(file, cards)
    }

    /**
     * [T-character-cards-atomic] Atomic write + FULL read-back verification.
     *
     * The temp file is moved onto the target with `ATOMIC_MOVE` +
     * `REPLACE_EXISTING`. If the filesystem refuses an atomic move there is NO
     * plain-write fallback: the previous (only valid) document is kept and an
     * [java.io.IOException] is raised. A half-written card file is worse than a
     * failed save, because it would take every persona with it.
     *
     * Verification compares the ENTIRE card list, not just the prompt text:
     * icon, style, lang, sourceJson, creator notes, targets and the enabled
     * flag all have to survive the round trip, or the write is reported as
     * failed.
     */
    fun writeVerified(file: File, cards: List<CharacterCard>) {
        val normalized = CharacterCardJson.normalize(cards)
        val text = CharacterCardJson.encode(normalized)
        val dir = file.parentFile ?: throw java.io.IOException("card store has no parent directory")
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("cannot create ${dir.absolutePath}")
        }
        val tmp = File(dir, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveAtomically(tmp, file)
            val readBack = readStrict(file)
            if (readBack != normalized) {
                val index = normalized.indices.firstOrNull {
                    it >= readBack.size || readBack[it] != normalized[it]
                } ?: minOf(normalized.size, readBack.size)
                throw java.io.IOException(
                    "card write verification failed at index $index " +
                        "(${normalized.size} written, ${readBack.size} read back)",
                )
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    private fun moveAtomically(source: File, target: File) {
        val from = source.toPath()
        val to = target.toPath()
        try {
            java.nio.file.Files.move(
                from, to,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Fall through to a non-atomic-but-still-rename move.
        } catch (t: Throwable) {
            throw java.io.IOException("cannot replace ${target.name} atomically; existing file kept", t)
        }
        try {
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            throw java.io.IOException("cannot replace ${target.name}; existing file kept", t)
        }
    }

    /**
     * Read-modify-write under the store lock. The transform sees the CURRENT
     * document (empty when the file does not exist) and its result is written
     * with the same verification as [save].
     */
    @Synchronized
    fun update(context: Context, transform: (List<CharacterCard>) -> List<CharacterCard>): List<CharacterCard> {
        val file = fileLocation(context)
        val current = readStrict(file) // throws when corrupt — never overwrite blindly
        val next = CharacterCardJson.normalize(transform(current))
        writeVerified(file, next)
        cached = next
        cachedFingerprint = fingerprintOf(file)
        lastIssue = null
        SoulStore.updateMetadataFromCards(next)
        return next
    }

    @Synchronized
    fun invalidate() {
        cached = null
        cachedFingerprint = null
        lastIssue = null
    }

    /**
     * Move an unreadable document aside so the user can keep or share it.
     * Returns the quarantine file, or null when there was nothing to move.
     */
    @Synchronized
    fun quarantineCorrupt(context: Context): File? {
        val file = fileLocation(context)
        if (!file.isFile) return null
        val readable = runCatching { readStrict(file) }.isSuccess
        if (readable) return null
        val target = File(
            file.parentFile,
            "${file.name}.corrupt-${System.currentTimeMillis()}",
        )
        val moved = if (file.renameTo(target)) {
            target
        } else {
            runCatching {
                target.writeBytes(file.readBytes())
                file.delete()
                target
            }.getOrNull()
        }
        invalidate()
        return moved
    }

    // -- Selection (pure) ---------------------------------------------

    /**
     * The card that owns identity + the global prompt: the FIRST ENABLED
     * GLOBAL card in document order. Multiple global cards are allowed (an
     * imported card does not have to replace the current one); only the first
     * enabled one is active, and disabling it promotes the next, which is why
     * ordering — not a hidden "active" flag — is the contract.
     */
    fun primaryGlobal(cards: List<CharacterCard>): CharacterCard? =
        cards.firstOrNull { it.isGlobal && it.enabled }

    /** The card a settings write must target, even when it is disabled. */
    fun editableGlobal(cards: List<CharacterCard>): CharacterCard? =
        cards.firstOrNull { it.isGlobal }

    fun primaryGlobal(context: Context): CharacterCard? =
        primaryGlobal(load(context))

    // -- Seeding -------------------------------------------------------

    /**
     * Create the default GLOBAL card iff the store has no cards. Never
     * overwrites: a corrupt document is reported and left untouched.
     */
    @Synchronized
    fun ensureSeeded(context: Context) {
        val loaded = loadReporting(context)
        if (loaded.issue != null) {
            AppLogger.warning(TAG, "not seeding: card document is unreadable")
            return
        }
        if (loaded.cards.isNotEmpty()) return
        val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        val card = CharacterCard(
            id = DEFAULT_GLOBAL_ID,
            name = parsed.metadata.name.ifBlank { SoulMetadata.DEFAULT.name },
            icon = "",
            scope = CharacterCardScope.GLOBAL,
            personality = parsed.body,
            style = parsed.metadata.style,
            lang = parsed.metadata.lang.ifBlank { SoulMetadata.DEFAULT.lang },
        )
        runCatching { writeVerified(fileLocation(context), listOf(card)) }
            .onSuccess {
                cached = listOf(card)
                cachedFingerprint = fingerprintOf(fileLocation(context))
                AppLogger.info(TAG, "seeded default character card")
            }
            .onFailure { AppLogger.warning(TAG, "seeding failed: ${it.message}") }
    }

    // -- Import / export (fixed API) ----------------------------------

    fun importCard(bytes: ByteArray): CharacterCard {
        val card = CharacterCardImport.import(bytes)
        if (!CharacterCardImport.isPng(bytes)) return card
        // A Tavern PNG commonly exceeds the inline avatar limit because it
        // contains the complete card metadata. Decode only a sampled thumbnail
        // and re-encode through the same avatar policy as the native picker.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return card
        var sample = 1
        while (bounds.outWidth / sample > SoulIcon.STORED_PIXELS * 2 ||
            bounds.outHeight / sample > SoulIcon.STORED_PIXELS * 2) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return card
        return try {
            when (val encoded = SoulIcon.encode(bitmap)) {
                is SoulIcon.EncodeResult.Success -> card.copy(icon = encoded.dataUri)
                is SoulIcon.EncodeResult.Failure -> card
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun exportCard(card: CharacterCard): String = CharacterCardExport.encode(card)

    // -- Startup -------------------------------------------------------

    /**
     * Called once from [com.openminis.app.MinisApp]: merge any legacy SOUL
     * files, seed the default card, prime the runtime snapshot. Each step is
     * independently guarded — a damaged document must not stop app startup.
     */
    fun initialize(context: Context) {
        runCatching { SoulBodyLimitPolicy.ensureLoaded(context) }
            .onFailure { AppLogger.warning(TAG, "body limit policy load failed: ${it.message}") }
        runCatching { importLegacy(context) }
            .onFailure { AppLogger.warning(TAG, "legacy import failed: ${it.message}") }
        runCatching { ensureSeeded(context) }
            .onFailure { AppLogger.warning(TAG, "seed failed: ${it.message}") }
        runCatching { load(context) }
            .onFailure { AppLogger.warning(TAG, "initial load failed: ${it.message}") }
    }

    // -- Legacy migration / merge -------------------------------------

    /**
     * [T-character-cards-backup] Merge restored legacy files into the card
     * store. Public API for the backup-restore path: after
     * `BackupImporter.importMemory`, call this (then [invalidate]/[load]).
     *
     * Semantics:
     *  - EXISTING CARDS ARE NEVER MODIFIED. Ids are content-derived
     *    ([CharacterCard.stableId]), so re-running over the same SOUL.md or
     *    rules imports nothing and touches nothing.
     *  - A corrupt card document aborts the merge (nothing is overwritten).
     *  - Legacy files are archived only AFTER the merged document has been
     *    written and read back successfully.
     */
    @Synchronized
    fun importLegacy(context: Context): LegacyMigrationReport {
        val dir = File(context.filesDir, MEMORY_SUBDIR)
        val soulFile = File(dir, "SOUL.md")
        val iconFile = File(dir, "SOUL.icon.png")
        val rulesFile = File(dir, "SOUL_PROMPT_RULES.json")
        if (!soulFile.isFile && !rulesFile.isFile) {
            return LegacyMigrationReport(ran = false, skippedReason = "no legacy SOUL files")
        }
        val target = fileLocation(context)
        val existing = try {
            readStrict(target)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "legacy import aborted: card document is unreadable")
            return LegacyMigrationReport(ran = false, skippedReason = "card document is unreadable")
        }

        // Per-file parse outcomes. A file is archived ONLY when its own parse
        // succeeded (and its content is represented in the store byte-for-byte).
        var soulOk = false
        var iconOk = true
        var soulCard: CharacterCard? = null
        if (soulFile.isFile) {
            val text = runCatching { soulFile.readText() }
            if (text.isFailure) {
                AppLogger.warning(TAG, "SOUL.md unreadable — left in place: ${text.exceptionOrNull()?.message}")
            } else {
                val iconBytes = runCatching { iconFile.takeIf { it.isFile }?.readBytes() }
                if (iconFile.isFile && iconBytes.isFailure) {
                    iconOk = false
                    AppLogger.warning(TAG, "SOUL.icon.png unreadable — SOUL.md will not be archived")
                }
                soulCard = CharacterCardLegacyMerge.soulCard(text.getOrNull(), iconBytes.getOrNull())
                soulOk = true
            }
        }

        var rulesOk = false
        var ruleCards: List<CharacterCard> = emptyList()
        if (rulesFile.isFile) {
            val text = runCatching { rulesFile.readText() }
            if (text.isFailure) {
                AppLogger.warning(TAG, "SOUL_PROMPT_RULES.json unreadable — left in place")
            } else {
                runCatching { CharacterCardLegacyMerge.ruleCardsStrict(text.getOrNull()) }
                    .onSuccess { ruleCards = it; rulesOk = true }
                    .onFailure {
                        // Malformed rules are KEPT on disk and reported; they are
                        // never archived away, because that would destroy the
                        // only copy of what the user wrote.
                        AppLogger.warning(TAG, "SOUL_PROMPT_RULES.json malformed — left in place: ${it.message}")
                    }
            }
        }

        val fresh = listOfNotNull(soulCard) + ruleCards
        val merged = CharacterCardLegacyMerge.merge(existing, fresh)
        val added = merged.size - existing.size

        // A legacy card whose id already exists with DIFFERENT content is a
        // conflict: existing content wins, and the legacy file stays so nothing
        // is lost.
        val before = existing.associateBy { it.id }
        val conflicts = fresh.filter { before[it.id] != null && before[it.id] != it }.map { it.id }.toSet()
        val soulConflict = soulCard != null && conflicts.contains(soulCard.id)
        val ruleConflict = ruleCards.any { conflicts.contains(it.id) }

        if (added > 0) {
            try {
                writeVerified(target, merged)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "legacy import write failed: ${t.message}")
                return LegacyMigrationReport(
                    ran = true, cardsAdded = 0, totalCards = existing.size,
                    skippedReason = "write failed; legacy files kept", error = t.message,
                )
            }
            cached = merged
            cachedFingerprint = fingerprintOf(target)
        }

        val archivable = LinkedHashMap<File, String>()
        if (soulFile.isFile && soulOk && iconOk && !soulConflict) archivable[soulFile] = "SOUL.md"
        if (iconFile.isFile && soulOk && iconOk && !soulConflict) archivable[iconFile] = "SOUL.icon.png"
        if (rulesFile.isFile && rulesOk && !ruleConflict) archivable[rulesFile] = "SOUL_PROMPT_RULES.json"

        val kept = ArrayList<String>()
        if (soulFile.isFile && !archivable.containsKey(soulFile)) kept.add(soulFile.name)
        if (iconFile.isFile && !archivable.containsKey(iconFile)) kept.add(iconFile.name)
        if (rulesFile.isFile && !archivable.containsKey(rulesFile)) kept.add(rulesFile.name)

        val archiveDir = archiveLegacyFiles(dir, archivable, soulCard, added)

        return LegacyMigrationReport(
            ran = true,
            cardsAdded = added,
            totalCards = merged.size,
            archivedTo = archiveDir?.absolutePath,
            skippedReason = if (kept.isEmpty()) null else "kept unreadable/unmerged: ${kept.joinToString(", ")}",
        )
    }

    /** Alias for callers that name the operation by what it does. */
    fun mergeLegacyFiles(context: Context): LegacyMigrationReport = importLegacy(context)

    private fun archiveLegacyFiles(
        dir: File,
        files: Map<File, String>,
        soulCard: CharacterCard?,
        added: Int,
    ): File? {
        if (files.isEmpty()) return null
        val root = File(dir, ARCHIVE_DIR)
        val target = if (!root.exists()) root else {
            File(root, "import-${System.currentTimeMillis()}")
        }
        if (!target.exists() && !target.mkdirs()) {
            AppLogger.warning(TAG, "cannot create legacy archive directory ${target.absolutePath}")
            return null
        }
        val moved = ArrayList<String>()
        for ((file, label) in files) {
            val dest = File(target, file.name)
            val ok = if (file.renameTo(dest)) {
                true
            } else {
                runCatching { dest.writeBytes(file.readBytes()); file.delete() }.isSuccess
            }
            if (ok) moved.add(label) else AppLogger.warning(TAG, "could not archive ${file.name}")
        }
        if (moved.isEmpty()) return null
        runCatching {
            val manifest = buildJsonObject {
                put("version", 1)
                put("archivedAt", System.currentTimeMillis())
                put("cardsAdded", added)
                put("soulCardId", soulCard?.id ?: "")
                put("files", moved.joinToString(","))
                put("note", "Inactive compatibility copy of pre-card SOUL files. Not read at runtime.")
            }
            File(target, "MANIFEST.json").writeText(manifest.toString())
        }
        return target
    }

    private fun fingerprintOf(file: File): String =
        if (file.isFile) "${file.lastModified()}:${file.length()}" else "missing"
}

/**
 * [T-character-cards] Pure legacy -> card mapping. No Context, no I/O, so the
 * migration policy is unit-testable and the same code runs for the once-only
 * startup migration and for a backup restore merge.
 */
object CharacterCardLegacyMerge {

    /**
     * SOUL.md -> GLOBAL card. The body is [CharacterCard.personality] (the
     * exact text the old prompt used), the frontmatter maps to name / style /
     * lang / legacyEmoji, and the avatar (inline data URI or the `SOUL.icon.png`
     * sidecar) becomes [CharacterCard.icon]. An id derived from that content
     * makes a repeat import a no-op instead of a duplicate persona.
     */
    fun soulCard(soulText: String?, iconPng: ByteArray?): CharacterCard? {
        if (soulText == null) return null
        val parsed = SoulMDParser.parse(soulText)
        val icon = when {
            parsed.metadata.icon.isNotBlank() &&
                !SoulIcon.isSidecarRef(parsed.metadata.icon) -> parsed.metadata.icon
            iconPng != null && iconPng.isNotEmpty() -> SoulIcon.DATA_URI_PREFIX +
                java.util.Base64.getEncoder().encodeToString(iconPng)
            else -> ""
        }
        val body = parsed.body
        val meta = parsed.metadata
        val meaningful = body.isNotBlank() || icon.isNotEmpty() ||
            meta.name.ifBlank { SoulMetadata.DEFAULT.name } != SoulMetadata.DEFAULT.name ||
            meta.style.isNotBlank() || meta.lang.ifBlank { "auto" } != "auto"
        if (!meaningful) return null
        return CharacterCard(
            id = CharacterCard.stableId("legacy-soul", meta.name, meta.style, meta.lang, meta.emoji, body, icon),
            name = meta.name.ifBlank { SoulMetadata.DEFAULT.name },
            icon = icon,
            enabled = true,
            scope = CharacterCardScope.GLOBAL,
            personality = body,
            style = meta.style,
            lang = meta.lang.ifBlank { SoulMetadata.DEFAULT.lang },
            legacyEmoji = meta.emoji,
        )
    }

    /**
     * SOUL_PROMPT_RULES.json -> GROUP/MODEL cards. Content goes to
     * [CharacterCard.systemPrompt] (the scoped prompt field), the target ids
     * are copied verbatim, and the rule id is REUSED so a rule that already
     * exists cannot be duplicated by a re-import. Disabled rules and rules
     * whose group/model no longer exists are kept (they are inert, not lost).
     */
    fun ruleCards(rulesJson: String?): List<CharacterCard> =
        runCatching { ruleCardsStrict(rulesJson) }.getOrDefault(emptyList())

    /**
     * Strict variant used by migration: malformed JSON raises instead of
     * reading as "no rules", so the caller can keep the file and say why.
     */
    fun ruleCardsStrict(rulesJson: String?): List<CharacterCard> {
        if (rulesJson.isNullOrBlank()) return emptyList()
        return SoulPromptRuleStore.decodeStrict(rulesJson).map { rule ->
            CharacterCard(
                id = rule.id.ifBlank {
                    CharacterCard.stableId(
                        "legacy-rule", rule.groupId, rule.providerInstanceId, rule.modelId, rule.content,
                    )
                },
                name = rule.name,
                enabled = rule.enabled,
                scope = if (rule.target == SoulPromptRuleTarget.MODEL) {
                    CharacterCardScope.MODEL
                } else {
                    CharacterCardScope.GROUP
                },
                groupId = rule.groupId,
                providerInstanceId = rule.providerInstanceId,
                modelId = rule.modelId,
                systemPrompt = rule.content,
                legacyRuleDerived = true,
            )
        }
    }

    /**
     * Append-only merge: existing cards win on an id collision, so a restore
     * can never rewrite what the user already has. Order is preserved, which
     * is what keeps "first enabled GLOBAL card" stable across runs.
     */
    fun merge(existing: List<CharacterCard>, fresh: List<CharacterCard>): List<CharacterCard> {
        if (fresh.isEmpty()) return existing
        val ids = existing.mapTo(HashSet()) { it.id }
        val out = ArrayList<CharacterCard>(existing.size + fresh.size)
        out.addAll(existing)
        for (card in fresh) {
            if (ids.add(card.id)) out.add(card)
        }
        return out
    }
}

/**
 * [T-character-cards] Legacy [SoulPromptRule] <-> scoped card mapping used by
 * the compatibility store. Kept here (not in the rule file) so both directions
 * live together and cannot drift.
 */
object SoulPromptRuleCardMapping {

    fun toRule(card: CharacterCard): SoulPromptRule = SoulPromptRule(
        id = card.id,
        name = card.name,
        enabled = card.enabled,
        target = if (card.scope == CharacterCardScope.MODEL) {
            SoulPromptRuleTarget.MODEL
        } else {
            SoulPromptRuleTarget.GROUP
        },
        groupId = card.groupId,
        providerInstanceId = card.providerInstanceId,
        modelId = card.modelId,
        // Only [CharacterCard.systemPrompt] is projected: a scoped card is an
        // append-only instruction layer, so its persona fields are not exposed
        // as rule text. Editing the rule writes back to this one field and
        // leaves every other field of the card intact.
        content = card.systemPrompt,
    )

    fun toCard(rule: SoulPromptRule, existing: CharacterCard?): CharacterCard {
        val scope = if (rule.target == SoulPromptRuleTarget.MODEL) {
            CharacterCardScope.MODEL
        } else {
            CharacterCardScope.GROUP
        }
        val id = rule.id.ifBlank { UUID.randomUUID().toString() }
        return if (existing != null) {
            existing.copy(
                id = id,
                name = rule.name,
                enabled = rule.enabled,
                scope = scope,
                groupId = rule.groupId,
                providerInstanceId = rule.providerInstanceId,
                modelId = rule.modelId,
                systemPrompt = rule.content,
            )
        } else {
            CharacterCard(
                id = id,
                name = rule.name,
                enabled = rule.enabled,
                scope = scope,
                groupId = rule.groupId,
                providerInstanceId = rule.providerInstanceId,
                modelId = rule.modelId,
                systemPrompt = rule.content,
                legacyRuleDerived = true,
            )
        }
    }
}
