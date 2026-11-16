package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [T-multi-soul] Multiple SOUL profiles: storage, per-chat selection and the
 * one legacy-compatible default.
 *
 * Until this object existed Android had exactly one persona file,
 * `<filesDir>/minis-global/memory/SOUL.md`, owned by [SoulStore]. That file is
 * the legacy default and is still read and written THROUGH [SoulStore] — its
 * path, sidecar icon ([SoulIcon.SIDECAR_NAME]), parser, backup coverage and
 * cached metadata are untouched. Nothing in this object rewrites or migrates
 * the legacy file except a normal [save] of [DEFAULT_ID], which delegates.
 *
 * Additional profiles live under
 *
 * ```
 * <filesDir>/minis-global/memory/
 *   SOUL.md                     legacy default, owned by SoulStore
 *   AGENTS.md                   workspace instructions, never written here
 *   souls/
 *     <uuid>.md                 one additional SOUL profile each
 *     selections.json           sessionId -> profileId
 *     .agents-import.json       one-shot AGENTS.md import marker
 * ```
 *
 * `souls/` is inside the memory directory on purpose: [BackupExporter] walks
 * that directory with `walkTopDown()` and copies every file, so every profile,
 * the selection map and the marker are all covered by the existing memory
 * backup without touching the exporter.
 *
 * Format of a non-default profile is the same frontmatter + Markdown as
 * SOUL.md, produced through [SoulMDParser] so there is exactly one parser to
 * keep correct. Icons in these files are stored INLINE (a
 * `data:image/png;base64,…` value in the `icon:` key), not in a sidecar: the
 * sidecar name is a single fixed path under the memory root and would make
 * every profile share (and overwrite) one avatar. Inline keeps each profile
 * self-contained and still parses through [SoulMDParser].
 *
 * ## Threading and writes
 *
 * Every entry point is `synchronized` on one private lock, and every file
 * write goes through [writeAtomic] (sibling `.tmp` + `renameTo`, with
 * fsync before the rename). Callers may invoke these from the main thread or
 * from `Dispatchers.IO`; no method performs partial updates — the disk write
 * happens first and only then are [profiles] / [selections] republished, so a
 * failed write leaves both the old file and the flows intact and reports the
 * failure by throwing.
 *
 * ## Identity / deletion rules
 *
 * - [DEFAULT_ID] is always present in [profiles] and can never be deleted.
 *   Reading it when SOUL.md does not exist yields null from [load] (legacy
 *   behaviour) but [profiles] still carries the built-in default content so
 *   the editor has something to show before the first save.
 * - A session selection that points at a deleted or unreadable profile falls
 *   back to [DEFAULT_ID] at read time ([selectedId], [selected]). The stale
 *   mapping is also removed by [delete], so the published [selections] map
 *   cannot keep advertising a profile that no longer exists.
 * - IDs are validated against [ID_PATTERN] before any [File] is constructed,
 *   which is what makes path traversal (`../`, absolute paths, separators,
 *   `.`/`..`) impossible rather than merely unlikely.
 *
 * ## AGENTS.md content rescue (one shot)
 *
 * Older builds had a separate workspace-instruction feature that read
 * `memory/AGENTS.md` and injected it before SOUL. That feature is gone: no
 * prompt path reads AGENTS.md any more, and there is no entry point for it in
 * Settings. Users who put real instructions in that file would otherwise lose
 * them silently, so [refresh] imports the text ONCE as an ordinary,
 * selectable SOUL profile (deterministic ID [AGENTS_IMPORT_ID]) and writes a
 * marker. It is a copy, never a move: the source file, the sidecar icon and
 * every other memory file are left byte-identical, and nothing injects them.
 *
 * The marker (not the profile's existence) is the "have we imported" bit: if
 * the user deletes the imported profile, later refreshes must NOT resurrect
 * it, which is why the marker is written even though the profile file is the
 * visible artifact. If the marker write itself fails the import is retried on
 * the next refresh (the profile file already existing prevents a duplicate).
 *
 * ## Per-session identity (background surfaces)
 *
 * The chat's own renderers read [SoulStore.cachedMetadata], which only ever
 * describes the OPEN chat. The foreground-service notification and the
 * floating capsule are about whichever chat is RUNNING, which may be another
 * one, so they resolve identity with [metadata] / [metadataFrom] instead:
 * both are pure lookups over the published [selections] and [profiles] maps
 * (no disk I/O), safe to call on every notification rebuild.
 */
object SoulProfiles {

    /** The ID of the legacy `SOUL.md` profile. Never deletable. */
    const val DEFAULT_ID: String = "default"

    /**
     * One listed profile. [file] is the parsed contents; for [DEFAULT_ID] it
     * may be the built-in default when SOUL.md does not exist yet.
     */
    data class SoulProfile(val id: String, val file: SoulFile)

    private const val TAG = "SoulProfiles"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    private const val SOULS_SUBDIR = "souls"
    private const val SOUL_EXTENSION = "md"
    private const val SELECTIONS_FILE = "selections.json"
    private const val MIGRATION_MARKER = ".agents-import.json"
    private const val AGENTS_SOURCE = "AGENTS.md"
    private const val AGENTS_IMPORT_ID = "agents-md"
    private const val AGENTS_FALLBACK_NAME = "AGENTS"

    /**
     * Allowed profile IDs: letters, digits, `_` and `-`, at most 64 chars.
     *
     * UUIDs from [create] match. `.` and `/` are absent by construction, so
     * no ID can escape `souls/` or address a hidden file. `default` is
     * reserved and handled before the file path is ever built.
     */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    private val lock = Any()

    private val _profiles = MutableStateFlow<List<SoulProfile>>(emptyList())

    /**
     * Every profile, default first, remaining profiles sorted by display name
     * then ID. Republished by [refresh], [save], [create] and [delete], so
     * edits made through this object are visible to collectors immediately
     * (and external edits after [refresh]).
     */
    val profiles: StateFlow<List<SoulProfile>> = _profiles.asStateFlow()

    private val _selections = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Durable `sessionId -> profileId` selections, loaded by [refresh]. */
    val selections: StateFlow<Map<String, String>> = _selections.asStateFlow()

    /**
     * Metadata of every listed profile, keyed by ID, refreshed together with
     * [profiles]. This is what lets [metadata] answer without touching the
     * disk: the foreground notification and the overlay capsule rebuild
     * often and must not read a file per frame.
     */
    private val _metadataById = MutableStateFlow<Map<String, SoulMetadata>>(emptyMap())

    /**
     * Increments on every mutation that can change any profile's metadata or
     * any selection. Background surfaces combine it into their state so a
     * SOUL edit, rename or per-chat switch repaints them without waiting for
     * an unrelated event.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * True once [selections] has been filled from disk. Distinguishes "no
     * selections exist" from "not loaded yet" so a read taken before the
     * first [refresh] still goes to disk instead of silently answering
     * [DEFAULT_ID].
     */
    @Volatile
    private var selectionsLoaded: Boolean = false

    /**
     * [T-multi-soul] Identity of the profile the OPEN chat uses, for surfaces
     * that cannot see a session id (assistant header, typing indicator,
     * notification). Updated by [setActiveSession] and by every mutation that
     * can change the active profile; mirrored into
     * [SoulStore.publishActiveMetadata] so the existing chat renderers keep a
     * single source of truth.
     */
    private val _activeMetadata = MutableStateFlow(SoulMetadata.DEFAULT)
    val activeMetadata: StateFlow<SoulMetadata> = _activeMetadata.asStateFlow()

    /** Session whose SOUL is published as [activeMetadata]; null before any chat opens. */
    @Volatile
    private var activeSessionId: String? = null

    /**
     * Per-session identity: `sessionId -> metadata of the SOUL that chat uses`,
     * rebuilt whenever [selections] or [profiles] change.
     *
     * This is what makes identity a property of a conversation instead of one
     * process-wide value: a background chat's SOUL cannot leak into the open
     * chat's header, and a selection made in an inactive chat never has to
     * touch [activeMetadata] at all.
     */
    private val _sessionMetadata = MutableStateFlow<Map<String, SoulMetadata>>(emptyMap())
    val sessionMetadata: StateFlow<Map<String, SoulMetadata>> = _sessionMetadata.asStateFlow()

    /**
     * Point the published identity at [sessionId]'s selected SOUL.
     *
     * Called only by the ChatScreen that is actually on screen. A chat that is
     * not active must not call this — see [refreshActiveIfCurrent], which is
     * the no-steal variant used after a selection.
     */
    fun setActiveSession(context: Context, sessionId: String?) {
        synchronized(lock) {
            activeSessionId = sessionId?.takeIf { it.isNotBlank() }
            publishActiveLocked(context)
        }
    }

    /**
     * Republish [sessionId]'s identity ONLY if that chat is the active one.
     *
     * The selection write itself is what changes the durable state; this
     * exists so the open chat's header updates immediately without giving an
     * inactive ViewModel the power to overwrite the identity of the chat the
     * user is looking at. An inactive session is a no-op here (its own
     * consumers read [metadata] / [sessionMetadata]).
     */
    fun refreshActiveIfCurrent(context: Context, sessionId: String) {
        synchronized(lock) {
            if (activeSessionId == sessionId) publishActiveLocked(context)
        }
    }

    /**
     * Move the active claim from a draft placeholder key to the real session id
     * after the first message is persisted, so the identity the draft already
     * showed survives the promotion. A no-op when neither key is active, which
     * is what keeps a background chat's first send from stealing focus.
     */
    fun transferActiveSession(context: Context, fromSessionId: String, toSessionId: String) {
        if (fromSessionId.isBlank() || toSessionId.isBlank() || fromSessionId == toSessionId) return
        synchronized(lock) {
            if (activeSessionId == fromSessionId) {
                activeSessionId = toSessionId
                publishActiveLocked(context)
            }
        }
    }

    /** Republish after an external edit (backup restore, minis-config). */
    fun refreshActive(context: Context) {
        synchronized(lock) { publishActiveLocked(context) }
    }

    private fun publishActiveLocked(context: Context) {
        val session = activeSessionId
        val id = session?.let { selectedIdLocked(context, it) } ?: DEFAULT_ID
        val metadata = metadataForIdLocked(context, id)
        _activeMetadata.value = metadata
        // Chat surfaces read SoulStore.cachedMetadata; keep them in step with
        // the selection instead of forcing every renderer to know about chats.
        runCatching { SoulStore.publishActiveMetadata(metadata) }
    }

    /**
     * Republish profiles + metadata after a mutation, bump [revision] and
     * re-resolve the open chat's identity. Every entry point that writes ends
     * here, so the flows can never describe different disk states.
     */
    private fun afterMutationLocked(context: Context) {
        _profiles.value = readProfilesLocked(context)
        _metadataById.value = _profiles.value.associate { it.id to it.file.metadata }
        rebuildSessionMetadataLocked()
        _revision.value = _revision.value + 1
        publishActiveLocked(context)
    }

    /**
     * Flatten `sessionId -> profileId` into `sessionId -> metadata`.
     *
     * A selection naming a profile that is no longer listed resolves to the
     * default here too, so [sessionMetadata] and [selectedId] can never
     * disagree about a chat whose profile was deleted.
     */
    private fun rebuildSessionMetadataLocked() {
        val byId = _metadataById.value
        val default = byId[DEFAULT_ID] ?: SoulMetadata.DEFAULT
        _sessionMetadata.value = _selections.value.entries.associate { (sessionId, profileId) ->
            sessionId to (byId[profileId] ?: default)
        }
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    internal fun memoryDir(context: Context): File = File(context.filesDir, MEMORY_SUBDIR)

    internal fun soulsDir(context: Context): File = File(memoryDir(context), SOULS_SUBDIR)

    internal fun profileFile(context: Context, id: String): File =
        File(soulsDir(context), "$id.$SOUL_EXTENSION")

    internal fun selectionsFile(context: Context): File = File(soulsDir(context), SELECTIONS_FILE)

    internal fun markerFile(context: Context): File = File(soulsDir(context), MIGRATION_MARKER)

    /** True when [id] is a legal profile ID (including [DEFAULT_ID]). */
    internal fun isValidId(id: String): Boolean =
        id == DEFAULT_ID || (ID_PATTERN.matches(id) && id != DEFAULT_ID)

    private fun requireValidId(id: String) {
        require(isValidId(id)) { "Invalid SOUL profile id: $id" }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Re-read every profile, the selection map and the legacy SOUL.md cache.
     *
     * This is the only call that notices edits made outside this object
     * (minis-config, a text editor, BackupImporter restoring `memory/`), and
     * it also runs the one-shot AGENTS.md import. Safe to call on every screen
     * entry / app start; it never creates or rewrites the legacy SOUL.md.
     */
    fun refresh(context: Context) {
        synchronized(lock) {
            val dir = soulsDir(context)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                AppLogger.warning(TAG, "Could not create ${dir.absolutePath}")
            }
            importAgentsOnceLocked(context)
            _profiles.value = readProfilesLocked(context)
            _metadataById.value = _profiles.value.associate { it.id to it.file.metadata }
            _selections.value = readSelectionsLocked(context)
            selectionsLoaded = true
            rebuildSessionMetadataLocked()
            // Keep the chat header / bubble metadata source in sync too, as
            // required for callers that only know about SoulStore. The active
            // SESSION decides which profile that is; before any chat opens it
            // resolves to the legacy default.
            publishActiveLocked(context)
        }
    }

    /**
     * Parse one profile, or null when it does not exist / cannot be read /
     * [id] is invalid. [DEFAULT_ID] delegates to [SoulStore.load], preserving
     * its sidecar-icon resolution and null-on-missing contract.
     */
    fun load(context: Context, id: String = DEFAULT_ID): SoulFile? {
        if (!isValidId(id)) return null
        synchronized(lock) { return loadLocked(context, id) }
    }

    /**
     * Persist [file] as [id].
     *
     * [DEFAULT_ID] delegates to [SoulStore.save] (legacy SOUL.md + icon
     * sidecar). Any other valid ID is written atomically to
     * `souls/<id>.md`; [file]'s icon is serialized inline. Throws on write
     * failure — callers must surface the error and keep the user's draft.
     *
     * [save] also creates the file when the ID does not exist yet. That is
     * used by the one-shot AGENTS.md import (deterministic ID); user-created
     * profiles should go through [create] so the ID is a fresh UUID.
     */
    fun save(context: Context, id: String, file: SoulFile) {
        requireValidId(id)
        synchronized(lock) {
            if (id == DEFAULT_ID) {
                SoulStore.save(context, file)
            } else {
                writeSoulFile(profileFile(context, id), file)
            }
            // A save can rename or re-icon the SOUL this chat is using; the
            // header must not keep showing the previous identity.
            afterMutationLocked(context)
        }
    }

    /**
     * Create a new profile with a fresh UUID and return its ID.
     *
     * The write happens before the ID is returned, so a caller can immediately
     * [load] it. Throws on write failure (nothing is listed on failure).
     */
    fun create(context: Context, file: SoulFile): String {
        synchronized(lock) {
            val dir = soulsDir(context)
            ensureDirOrThrow(dir)
            var id: String
            var target: File
            do {
                id = UUID.randomUUID().toString()
                target = File(dir, "$id.$SOUL_EXTENSION")
            } while (target.exists())
            writeSoulFile(target, file)
            afterMutationLocked(context)
            return id
        }
    }

    /**
     * Delete an additional profile. [DEFAULT_ID] is a no-op (the legacy file
     * and its sidecar are never removed) and an invalid ID throws before any
     * filesystem access.
     *
     * Deleting a missing file is idempotent. Selections that pointed at the
     * deleted profile are removed so [selectedId] / [selected] fall back to
     * [DEFAULT_ID] and the published [selections] map stops naming it.
     * Throws when the file exists but cannot be removed, so the UI can report
     * a real failure instead of showing a list entry that reappears.
     */
    fun delete(context: Context, id: String) {
        requireValidId(id)
        if (id == DEFAULT_ID) return
        synchronized(lock) {
            val target = profileFile(context, id)
            if (target.exists() && !target.delete()) {
                throw IOException("Could not delete SOUL profile $id")
            }
            // Best effort: a leftover temp file is never listed (wrong
            // extension) but should not outlive a deletion either.
            File(target.parentFile, "${target.name}.tmp").delete()

            val before = readSelectionsLocked(context)
            val after = before.filterValues { it != id }
            if (after != before) {
                writeSelectionsLocked(context, after)
            }
            _selections.value = after
            selectionsLoaded = true
            afterMutationLocked(context)
        }
    }

    /**
     * Forget one session's selection (no-op when it has none).
     *
     * Used when a draft placeholder key is retired after the chat is promoted
     * to a real session: the durable selection moved to the real id, and the
     * placeholder would otherwise keep accumulating, one entry per chat that
     * ever picked a SOUL before its first message.
     */
    fun clearSelection(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val before = readSelectionsLocked(context)
            if (!before.containsKey(sessionId)) return
            val after = before - sessionId
            writeSelectionsLocked(context, after)
            _selections.value = after
            selectionsLoaded = true
            afterMutationLocked(context)
        }
    }

    /**
     * The profile a chat currently uses.
     *
     * Returns [DEFAULT_ID] for a blank/unknown session, for a missing mapping,
     * and when the mapped profile no longer exists — the fallback is part of
     * the contract, not an error. Never throws: an unreadable selections file
     * is treated as "no selections".
     */
    fun selectedId(context: Context, sessionId: String): String {
        if (sessionId.isBlank()) return DEFAULT_ID
        synchronized(lock) { return selectedIdLocked(context, sessionId) }
    }

    /**
     * Selection resolution over the published caches.
     *
     * Returns [DEFAULT_ID] when nothing is mapped, and also when the mapped
     * profile is no longer listed — a deleted profile must never keep a chat
     * pointing at a file that is gone.
     */
    private fun selectedIdLocked(context: Context, sessionId: String): String {
        if (sessionId.isBlank()) return DEFAULT_ID
        val map = if (selectionsLoaded) _selections.value else readSelectionsLocked(context)
        val id = map[sessionId] ?: return DEFAULT_ID
        val exists = if (_profiles.value.isNotEmpty()) {
            _profiles.value.any { it.id == id }
        } else {
            profileExistsLocked(context, id)
        }
        return if (exists) id else DEFAULT_ID
    }

    /**
     * [T-multi-soul] Identity of the SOUL [sessionId] uses, for surfaces that
     * run OUTSIDE that chat: the foreground-service notification and the
     * floating capsule may describe a background chat while the user is
     * looking at another one, so they must not read the open chat's identity.
     *
     * Pure cache lookup once [refresh] has run (no file read, no exception),
     * with a disk fallback before that, and [SoulMetadata.DEFAULT] as the
     * last resort. A null/blank session resolves to the legacy default.
     */
    fun metadata(context: Context, sessionId: String?): SoulMetadata {
        synchronized(lock) {
            if (sessionId.isNullOrBlank()) return metadataForIdLocked(context, DEFAULT_ID)
            // Prefer the flattened per-session view, which already resolved
            // the fallback when the selection was built.
            _sessionMetadata.value[sessionId]?.let { return it }
            return metadataForIdLocked(context, selectedIdLocked(context, sessionId))
        }
    }

    /**
     * [T-multi-soul] The same resolution as [metadata], as a pure function of
     * already-published values. Used to derive a flow (overlay capsule) that
     * must not hold a [Context] and must not lock. The fallback mirrors
     * [selectedId] exactly: unknown session, missing profile or unlisted
     * profile all name the default.
     */
    fun metadataFrom(
        selections: Map<String, String>,
        profiles: List<SoulProfile>,
        sessionId: String?,
    ): SoulMetadata {
        val default = profiles.firstOrNull { it.id == DEFAULT_ID }?.file?.metadata
            ?: SoulMetadata.DEFAULT
        val sid = sessionId?.takeIf { it.isNotBlank() } ?: return default
        val wanted = selections[sid] ?: return default
        return profiles.firstOrNull { it.id == wanted }?.file?.metadata ?: default
    }

    private fun metadataForIdLocked(context: Context, id: String): SoulMetadata {
        _metadataById.value[id]?.let { return it }
        loadLocked(context, id)?.metadata?.let { return it }
        return _metadataById.value[DEFAULT_ID]
            ?: loadLocked(context, DEFAULT_ID)?.metadata
            ?: SoulMetadata.DEFAULT
    }

    /**
     * Persist the SOUL chosen for one chat.
     *
     * An invalid or non-existent [id] is coerced to [DEFAULT_ID] rather than
     * stored, because a selection that names nothing would immediately read
     * back as the default anyway. [sessionId] must not be blank (there is no
     * meaningful global session). Throws when the selection file cannot be
     * written, so runtime integration can report a failed switch instead of
     * pretending the chat changed persona.
     */
    fun select(context: Context, sessionId: String, id: String) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        synchronized(lock) {
            val target = if (isValidId(id) && profileExistsLocked(context, id)) id else DEFAULT_ID
            val map = readSelectionsLocked(context).toMutableMap()
            map[sessionId] = target
            writeSelectionsLocked(context, map)
            _selections.value = map
            selectionsLoaded = true
            // A switch changes who THIS chat answers as; keep the published
            // identity and the background surfaces in step immediately.
            afterMutationLocked(context)
        }
    }

    /**
     * The parsed persona for [sessionId], falling back to the default profile
     * when the selected one is gone. Null only when the default SOUL.md itself
     * cannot be read/does not exist — callers that need a guaranteed value use
     * `load(...) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)` like the
     * editor does.
     */
    fun selected(context: Context, sessionId: String): SoulFile? {
        synchronized(lock) {
            val id = selectedId(context, sessionId)
            val parsed = loadLocked(context, id)
            if (parsed != null) return parsed
            return if (id != DEFAULT_ID) loadLocked(context, DEFAULT_ID) else null
        }
    }

    // ── Internals (caller holds [lock]) ──────────────────────────────────────

    private fun loadLocked(context: Context, id: String): SoulFile? {
        if (!isValidId(id)) return null
        if (id == DEFAULT_ID) return SoulStore.load(context)
        val file = profileFile(context, id)
        if (!file.isFile) return null
        return try {
            SoulMDParser.parse(file.readText())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "SOUL profile $id load failed: ${t.message}")
            null
        }
    }

    private fun profileExistsLocked(context: Context, id: String): Boolean {
        if (!isValidId(id)) return false
        // The legacy default is the documented fallback even before its file
        // has been seeded, so it always "exists" for selection purposes.
        if (id == DEFAULT_ID) return true
        return profileFile(context, id).isFile
    }

    private fun readProfilesLocked(context: Context): List<SoulProfile> {
        val default = SoulProfile(
            id = DEFAULT_ID,
            file = loadLocked(context, DEFAULT_ID)
                ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT),
        )
        val dir = soulsDir(context)
        val others = (dir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isFile && it.extension == SOUL_EXTENSION }
            .mapNotNull { file ->
                val id = file.nameWithoutExtension
                if (!isValidId(id) || id == DEFAULT_ID) return@mapNotNull null
                try {
                    SoulProfile(id, SoulMDParser.parse(file.readText()))
                } catch (t: Throwable) {
                    // One unreadable profile must not hide the rest. The
                    // editor loads the profile by ID again and reports the
                    // failure there, where the user can react.
                    AppLogger.warning(TAG, "Skipping unreadable SOUL profile ${file.name}: ${t.message}")
                    null
                }
            }
            .sortedWith(compareBy({ it.file.metadata.name.lowercase() }, { it.id }))
            .toList()
        return listOf(default) + others
    }

    private fun writeSoulFile(target: File, file: SoulFile) {
        // Icons stay INLINE here (no sidecar): each profile is self-contained
        // and SoulStore's single SOUL.icon.png must not be shared.
        writeAtomic(target, SoulMDParser.serialize(file))
    }

    private fun writeSelectionsLocked(context: Context, selections: Map<String, String>) {
        val json = JSONObject()
        selections.toSortedMap().forEach { (sessionId, profileId) ->
            if (sessionId.isNotBlank() && isValidId(profileId)) {
                json.put(sessionId, profileId)
            }
        }
        writeAtomic(selectionsFile(context), json.toString(2) + "\n")
    }

    private fun readSelectionsLocked(context: Context): Map<String, String> {
        val file = selectionsFile(context)
        if (!file.isFile) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val result = LinkedHashMap<String, String>()
            for (key in json.keys()) {
                val value = json.optString(key, "")
                if (key.isNotBlank() && isValidId(value)) result[key] = value
            }
            result
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "selections.json unreadable (${t.message}) — treating as empty")
            emptyMap()
        }
    }

    // ── AGENTS.md one-shot import ────────────────────────────────────────────

    private fun importAgentsOnceLocked(context: Context) {
        val marker = markerFile(context)
        if (marker.exists()) return
        val source = File(memoryDir(context), AGENTS_SOURCE)
        if (!source.isFile) return
        val text = try {
            source.readText()
        } catch (t: Throwable) {
            // Unreadable now; do not mark, so a later refresh can retry.
            AppLogger.warning(TAG, "AGENTS.md migration read failed: ${t.message}")
            return
        }
        if (text.isBlank()) return

        val target = profileFile(context, AGENTS_IMPORT_ID)
        if (!target.exists()) {
            val imported = SoulFile(
                metadata = SoulMetadata(
                    name = agentsDisplayName(text),
                    emoji = SoulMetadata.DEFAULT.emoji,
                    icon = "",
                    style = SoulMetadata.DEFAULT.style,
                    lang = SoulMetadata.DEFAULT.lang,
                ),
                body = text.trimEnd(),
            )
            try {
                writeSoulFile(target, imported)
            } catch (t: Throwable) {
                // No marker: retry next refresh. Do not break the rest of the
                // profile list because one optional migration failed.
                AppLogger.warning(TAG, "AGENTS.md migration write failed: ${t.message}")
                return
            }
        }
        try {
            val meta = JSONObject()
                .put("id", AGENTS_IMPORT_ID)
                .put("source", AGENTS_SOURCE)
                .put("importedAt", System.currentTimeMillis())
            writeAtomic(marker, meta.toString(2) + "\n")
        } catch (t: Throwable) {
            // Marker write failed. The profile now exists, so the next refresh
            // will not duplicate it; the marker will be retried then.
            AppLogger.warning(TAG, "AGENTS.md migration marker write failed: ${t.message}")
        }
    }

    private fun agentsDisplayName(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotEmpty() }
            ?: AGENTS_FALLBACK_NAME

    // ── Atomic file helpers ─────────────────────────────────────────────────

    private fun ensureDirOrThrow(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Could not create ${dir.absolutePath}")
        }
    }

    /**
     * Write [text] through a sibling `.tmp`, fsync it, then rename over
     * [target]. A reader (or a crash) sees either the old complete file or the
     * new complete file, never a half-written profile. On a filesystem that
     * rejects the rename (rare inside filesDir) the bytes are written directly
     * and the temp file is removed, matching [SoulStore.save]'s fallback.
     */
    private fun writeAtomic(target: File, text: String) {
        val dir = target.parentFile ?: throw IOException("No parent directory for ${target.path}")
        ensureDirOrThrow(dir)
        val tmp = File(dir, "${target.name}.tmp")
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw IOException("Could not write ${target.name}: ${t.message}", t)
        }
        if (!tmp.renameTo(target)) {
            try {
                target.writeBytes(bytes)
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }
}