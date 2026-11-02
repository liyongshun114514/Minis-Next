package com.openminis.app.agent.prompts

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-prompt-overrides] One editable built-in prompt template.
 *
 * The catalog is DATA, not code: `assets/prompt-catalog.json` holds a JSON
 * array of these objects (`id` / `group` / `title` / `source` /
 * `defaultTemplate`) and [PromptOverrides.entries] hands it to the settings UI.
 * Nothing here decides which prompts exist or what their defaults say — that
 * stays with whoever generates the catalog, so a prompt can be added or
 * reworded without touching this file.
 *
 * @property id stable identity of the prompt (the key the override file and
 *   every `render(id, …)` call use). Never localised, never shown.
 * @property group UI grouping key (e.g. the subsystem the prompt belongs to).
 * @property title human-readable name for the editor row.
 * @property source where the default text lives in the code base, for the
 *   "this is the shipped default" hint in the editor. Informational only.
 * @property defaultTemplate the text shipped with the app; also the reference
 *   an override is validated against. Placeholders are `{{slotN}}`.
 */
data class PromptEntry(
    val id: String,
    val group: String,
    val title: String,
    val source: String,
    val defaultTemplate: String,
)

/**
 * [T-prompt-overrides] An edit that could not be accepted, with a message that
 * is safe and useful to show the user as-is.
 *
 * Two distinct failures share this type because the UI handles them the same
 * way — show the message, keep the editor open, keep the previous override:
 *  - the text does not preserve the template's placeholders (validation), and
 *  - the text could not be persisted (I/O), in which case the on-disk file is
 *    still the previous one.
 *
 * Both are [IllegalArgumentException] descendants so a caller that only guards
 * against bad input still catches them.
 */
class PromptOverrideException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * [T-prompt-overrides] The string-template rules, with no Android, no disk and
 * no catalog: everything here is a pure function of its arguments.
 *
 * Keeping this separate is what makes the interesting half of the feature
 * testable off-device: placeholder parsing, the multiset comparison that
 * decides whether an edit is acceptable, and single-pass substitution.
 *
 * Template language (deliberately tiny, two kinds of brace):
 *  - `{{slotN}}` (double braces, `slot` + decimal digits) is a SLOT: a dynamic
 *    value [render] fills from its arguments. Anything else inside `{{…}}` is
 *    an UNKNOWN placeholder and is refused rather than quietly rendered to the
 *    model as text.
 *  - `{name}` (single braces, a bare identifier) is a NAME the CALLER fills
 *    after rendering — the identity template renders its slots and then does
 *    `.replace("{name}", …)`. [render] leaves these untouched; validation
 *    preserves how many there are. Only identifier-shaped bodies count, so
 *    `{"a":1}`, `{1}` and `{ }` in prose stay text.
 *  - An unterminated `{{` or a `{{` whose token contains another `{` is
 *    ordinary text, not a placeholder. Refusing those would reject perfectly
 *    readable prose for a mistake the renderer cannot act on anyway.
 *  - A placeholder may repeat; each occurrence is substituted independently,
 *    which is why validation compares MULTISETS (counts per token) rather than
 *    sets — dropping one of two `{{slot1}}`, or the only `{name}`, is a real
 *    defect even though the name is still "present".
 */
object PromptTemplate {

    /** A double-brace slot body: `slot` + decimal digits, nothing else. */
    private val SLOT_NAME = Regex("slot[0-9]+")

    /** A single-brace placeholder body: a bare identifier, e.g. `name`. */
    private val NAMED_BODY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * What a brace run is:
     *
     *  - [SLOT] `{{slotN}}` — a dynamic value [render] fills from its
     *    `arguments`.
     *  - [NAMED] `{name}` — a placeholder the CALLER fills AFTER rendering. The
     *    identity template is the reason this exists: it renders its slots,
     *    then does `.replace("{name}", …)`. So [render] must leave `{name}`
     *    standing, and validation must preserve how many there are — deleting
     *    one would leave a literal `{name}` in the system prompt, and adding
     *    one would create a placeholder nothing ever fills.
     *  - [UNKNOWN] any other double-brace token (`{{user}}`, `{{result}}`).
     *    NOT filled by [render]. One that a default already carries is part of
     *    that default's contract and must be kept by count (a scheduled-task
     *    prompt legitimately contains `{{result}}`, which the scheduler
     *    substitutes later); one the editor ADDS is refused, because nothing
     *    would ever fill it and it would reach the model as literal text.
     */
    enum class Kind { SLOT, NAMED, UNKNOWN }

    /** A recognised brace run located in a template. */
    data class Occurrence(val token: String, val start: Int, val endExclusive: Int, val kind: Kind)

    /**
     * Every brace run in [template], in order of appearance.
     *
     * One scanner serves validation AND rendering, so the two can never
     * disagree about what a placeholder is — a divergence there would let a
     * saved template render differently from the text that was validated.
     */
    fun placeholders(template: String): List<Occurrence> {
        val found = ArrayList<Occurrence>()
        var i = 0
        val n = template.length
        while (i < n) {
            if (template[i] != '{') {
                i++
                continue
            }
            // Try the double-brace form first: `{{slot1}}` must not also be read
            // as two single-brace runs.
            if (i + 1 < n && template[i + 1] == '{') {
                val close = template.indexOf("}}", i + 2)
                if (close >= 0) {
                    val token = template.substring(i + 2, close)
                    if (token.indexOf('{') < 0) {
                        // `{{{x}}}` is not a double-brace run starting here; the
                        // `{{` one char later is, so fall through, not past.
                        found.add(
                            Occurrence(
                                token = token,
                                start = i,
                                endExclusive = close + 2,
                                kind = if (SLOT_NAME.matches(token)) Kind.SLOT else Kind.UNKNOWN,
                            ),
                        )
                        i = close + 2
                        continue
                    }
                }
            }
            // Single-brace `{name}`: identifier only, so `{"a":1}`, `{ }` and
            // `{1}` in prose stay text.
            val close = template.indexOf('}', i + 1)
            if (close > i + 1) {
                val token = template.substring(i + 1, close)
                if (NAMED_BODY.matches(token)) {
                    found.add(Occurrence(token, i, close + 1, Kind.NAMED))
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return found
    }

    /** The `{{slotN}}` runs only — what [render] substitutes. */
    fun occurrences(template: String): List<Occurrence> =
        placeholders(template).filter { it.kind == Kind.SLOT }

    /** Double-brace tokens that are not `slotN`, deduplicated, in order. */
    fun unknownTokens(template: String): List<String> =
        placeholders(template)
            .filter { it.kind == Kind.UNKNOWN }
            .map { it.token }
            .distinct()

    /** How many times each `slotN` appears (`{{slotN}}` runs only). */
    fun slotCounts(template: String): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (occurrence in occurrences(template)) {
            counts[occurrence.token] = (counts[occurrence.token] ?: 0) + 1
        }
        return counts
    }

    /**
     * The template's placeholder multiset, keyed by how it is written:
     * `"{{slot1}}"` for slots, `"{name}"` for caller-filled names, `"{{result}}"`
     * for any other double-brace token the default already carries.
     *
     * This is the unit of comparison for "can this edit replace that default?".
     * Every kind counts separately and repeatedly, so `{{slot1}}` twice must
     * stay twice, `{name}` once must stay once, and a default's own `{{result}}`
     * must survive an edit that is otherwise allowed.
     */
    fun signature(template: String): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (occurrence in placeholders(template)) {
            val display = when (occurrence.kind) {
                Kind.SLOT -> "{{" + occurrence.token + "}}"
                Kind.NAMED -> "{" + occurrence.token + "}"
                Kind.UNKNOWN -> "{{" + occurrence.token + "}}"
            }
            counts[display] = (counts[display] ?: 0) + 1
        }
        return counts
    }

    /**
     * True when [candidate] may stand in for [reference].
     *
     * Order is free; the multiset is not. This — not string equality — is what
     * makes an override survive the app rewording its own default (or the
     * catalog being regenerated): as long as the same slots, the same
     * caller-filled `{name}`s and the same default-owned tokens are present in
     * the same numbers, the user's edit still fits the code that fills them.
     *
     * An added `{{…}}` therefore fails this check on its own (it changes the
     * multiset), which is why no separate unknown-token test is needed — and why
     * a `{{result}}` the default itself contains is accepted.
     */
    fun isCompatible(candidate: String, reference: String): Boolean =
        signature(candidate) == signature(reference)

    /**
     * Substitute [arguments] into the template's `{{slotN}}` runs, in ONE pass.
     *
     * Two rules, both about not losing a value:
     *
     *  - A value is appended verbatim and never re-scanned, so a value that
     *    itself contains `{{…}}` (a quoted prompt, a file name, tool output)
     *    cannot expand into a second round of slots.
     *  - A slot with no argument — including the "no arguments at all" call —
     *    is left standing as `{{slotN}}` rather than replaced with an empty
     *    string. Showing the slot is a visible defect; dropping the value is a
     *    silent one.
     *
     * `{name}` is NOT touched: it belongs to the caller, which replaces it after
     * this call (and did so before this class existed). Substituting it here
     * would either double-handle it or break the caller's `.replace`.
     *
     * Lookup accepts both the bare slot name (`"slot1"`) and the braced form
     * (`"{{slot1}}"`) so a caller built against either spelling works.
     */
    fun render(template: String, arguments: Map<String, String>): String {
        val slots = occurrences(template)
        if (slots.isEmpty()) return template
        val out = StringBuilder(template.length + 32)
        var cursor = 0
        for (occurrence in slots) {
            val value = arguments[occurrence.token] ?: arguments["{{" + occurrence.token + "}}"]
            if (value == null) continue // Leave the placeholder in the literal run.
            out.append(template, cursor, occurrence.start)
            out.append(value)
            cursor = occurrence.endExclusive
        }
        out.append(template, cursor, template.length)
        return out.toString()
    }

    /** `"{{slot1}}×2, {name}"` — or `"no placeholders"`. For error messages. */
    fun describe(counts: Map<String, Int>): String {
        if (counts.isEmpty()) return "no placeholders"
        return counts.entries
            .sortedWith(
                compareBy<Map.Entry<String, Int>> { sortRank(it.key).first }
                    .thenBy { sortRank(it.key).second }
                    .thenBy { it.key },
            )
            .joinToString(", ") { (display, count) ->
                if (count > 1) display + "×" + count else display
            }
    }

    /** Slots in numeric order first (`slot2` before `slot10`), names after. */
    private fun sortRank(display: String): Pair<Int, Int> {
        val slotNumber = if (display.startsWith("{{slot") && display.endsWith("}}")) {
            display.substring(6, display.length - 2).toIntOrNull()
        } else {
            null
        }
        return if (slotNumber != null) 0 to slotNumber else 1 to 0
    }
}

/**
 * [T-prompt-overrides] User edits to the app's built-in prompt templates.
 *
 * WHAT THIS IS
 * ------------
 * Every built-in prompt stays compiled into the app exactly as before; this
 * object is only a *text substitution layer* on top of it. A prompt keeps
 * rendering from its own default template unless the user has stored a
 * replacement that still fits that template's `{{slotN}}` placeholders. So:
 *
 *  - The defaults are never rewritten, never moved, and never lost. Removing
 *    an override ([reset]) restores the shipped text immediately.
 *  - The override is stored as user data, in
 *    `<filesDir>/minis-global/memory/PROMPTS.json` — beside SOUL.md, GLOBAL.md
 *    and the daily notes. [BackupExporter.exportMemory] walks that directory,
 *    so the file is already covered by the existing memory backup and restore
 *    without any change here. SOUL.md / AGENTS.md are NOT touched by this
 *    feature.
 *  - Runtime gating is untouched. This class only decides which TEXT is handed
 *    to the code that builds a prompt; it does not decide whether a tool, a
 *    permission, or a privileged operation is allowed. Nothing here can widen
 *    access — a user editing text cannot turn a denied capability into an
 *    allowed one, because the gate is not a string.
 *
 * WHY PLACEHOLDERS ARE VALIDATED
 * ------------------------------
 * A prompt's placeholders are filled with live state (paths, modes, tool
 * lists, user input, the assistant's name). If an edit deletes one, the model
 * silently stops seeing that state; if an edit introduces one, it reaches the
 * model as a literal `{{slot7}}` / `{name}` — or worse, eats a value meant for
 * somewhere else. Both are invisible failures, so [save] refuses them instead
 * of writing a prompt that renders wrong:
 *
 *  - the multiset of `{{slotN}}` in the new text must equal the default's
 *    (order is free, counts are not, so `{{slot1}} {{slot1}}` must stay two),
 *  - the same for the single-brace `{name}` placeholders the CALLER fills after
 *    rendering: `{name}` must appear exactly as many times as in the default,
 *    because [render] does not touch it and nothing else would fill a new one,
 *  - a `{{…}}` the DEFAULT does not already contain is an "unknown slot" and
 *    is refused (one the default does contain — `{{result}}` in a
 *    scheduled-task prompt, filled by the scheduler — is kept by count),
 *  - empty text is accepted only when the default has no placeholders at all.
 *
 * A refused save throws [PromptOverrideException] with an actionable message
 * and leaves the stored override exactly as it was.
 *
 * COMPATIBILITY, DELIBERATELY LOOSE
 * ---------------------------------
 * An override is matched to a template by its PLACEHOLDER SIGNATURE (both
 * `{{slotN}}` and `{name}` counts), not by its text.
 * The app may reword a default, or pass a template whose surrounding words
 * differ from the catalog's, and a user's edit that touches only the prose
 * keeps applying. Conversely, when the shipped template's slots no longer match
 * the stored override (an app update added/removed a slot), the stale override
 * is IGNORED — [render] falls back to the default it was given, and
 * [overrideFor] reports null so the editor shows the shipped text instead of a
 * body that cannot render. The stale text stays on disk untouched: nothing is
 * destroyed by an upgrade, and the user can still reset it.
 *
 * THREADING AND DURABILITY
 * ------------------------
 * Reads go through one immutable [Snapshot] behind a `@Volatile` field, so a
 * render on the agent thread never sees a half-applied edit. Writes take
 * [writeLock], write the complete new file to a sibling `.tmp`, `fsync` it and
 * rename it over `PROMPTS.json` (atomic on the same filesystem), and only then
 * publish the new snapshot — a failed write therefore leaves BOTH the cache and
 * the file on the previous content. An empty override set deletes the file
 * rather than leaving `{}` behind, so "no overrides" has exactly one on-disk
 * representation.
 */
object PromptOverrides {

    private const val TAG = "PromptOverrides"

    /** Where the catalog ships. Absent is a legal state (see [initialize]). */
    private const val CATALOG_ASSET = "prompt-catalog.json"

    /** Shared with SOUL.md / GLOBAL.md / the daily notes — hence already backed up. */
    private const val MEMORY_SUBDIR = "minis-global/memory"

    private const val FILE_NAME = "PROMPTS.json"
    private const val FILE_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_OVERRIDES = "overrides"

    /**
     * One immutable view of everything [PromptOverrides] knows: the catalog,
     * the overrides currently applied, and where they live.
     *
     * [memoryDir] is null until [initialize] runs; that is the "not
     * initialised" state, in which rendering still works (defaults only) but
     * saving cannot.
     */
    private data class Snapshot(
        val memoryDir: File?,
        val catalog: List<PromptEntry>,
        val catalogById: Map<String, PromptEntry>,
        val overrides: Map<String, String>,
    )

    private val UNINITIALIZED = Snapshot(null, emptyList(), emptyMap(), emptyMap())

    @Volatile
    private var snapshot: Snapshot = UNINITIALIZED

    private val writeLock = Any()

    /**
     * Load the catalog and the stored overrides for this process. Called once
     * from `MinisApp.onCreate`, next to `SoulStore.ensureExists`.
     *
     * Never throws: a missing or malformed catalog (the file is generated
     * separately) simply leaves [entries] empty, and an unreadable
     * `PROMPTS.json` leaves every prompt on its default. Both are logged.
     * Rendering stays correct in both cases because [render] always receives
     * the caller's own default template.
     *
     * Safe to call again at any time: it re-reads both files and swaps the
     * snapshot atomically, so a caller that rewrites `PROMPTS.json` underneath
     * us (a backup restore into the memory directory) can refresh with another
     * [initialize] instead of waiting for the next launch.
     */
    fun initialize(context: Context) {
        initializeAt(context.filesDir, readCatalogAsset(context))
    }

    /**
     * [initialize] without a `Context`, so the whole feature can be exercised
     * against a temp directory in a JVM test. `internal`, not public API.
     */
    internal fun initializeAt(filesDir: File, catalogJson: String?) = synchronized(writeLock) {
        val catalog = runCatching { parseCatalog(catalogJson) }.getOrElse {
            AppLogger.warning(TAG, "prompt catalog ignored: ${it.message}")
            emptyList()
        }
        val memoryDir = File(filesDir, MEMORY_SUBDIR)
        // A write interrupted between "tmp written" and "renamed" would leave
        // this behind, and the memory backup copies every file it finds.
        runCatching { File(memoryDir, FILE_NAME + ".tmp").delete() }
        val overrides = readOverrides(memoryDir)
        val byId = LinkedHashMap<String, PromptEntry>(catalog.size)
        for (entry in catalog) byId[entry.id] = entry
        snapshot = Snapshot(memoryDir, catalog, byId, overrides)
        AppLogger.info(
            TAG,
            "initialized: ${catalog.size} catalog entries, ${overrides.size} override(s) at ${File(memoryDir, FILE_NAME).absolutePath}",
        )
    }

    /**
     * Return to the pre-[initialize] state (empty catalog, no overrides, no
     * storage target). Test-only: the process calls [initialize] exactly once,
     * and the "not initialised" behaviour — render from the passed default,
     * refuse to save — has to be reproducible to be tested.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        synchronized(writeLock) { snapshot = UNINITIALIZED }
    }

    /**
     * The catalog, in the order the JSON array lists it. Empty before
     * [initialize] (or when the app ships no catalog), which is why the UI must
     * not treat this as "the app has no prompts".
     */
    fun entries(): List<PromptEntry> = snapshot.catalog

    /**
     * The stored override for [id], or null when there is none OR when the one
     * on disk no longer fits the catalog's default (see the class KDoc): the
     * editor must show what [render] would actually use, not text it would
     * silently ignore.
     */
    fun overrideFor(id: String): String? {
        val snap = snapshot
        val text = snap.overrides[id] ?: return null
        val default = snap.catalogById[id]?.defaultTemplate ?: return text
        return if (PromptTemplate.isCompatible(text, default)) text else null
    }

    /**
     * Render a prompt, applying the user's override when one is stored and
     * still fits the template passed in.
     *
     * [defaultTemplate] is the caller's own compiled-in text, always. That is
     * what makes this safe to adopt incrementally: a call site can switch to
     * [render] before the catalog knows about it, keep its own wording, and
     * still get user edits the moment a catalog entry with the same id exists.
     * Before [initialize] — or for an id with no override — this returns
     * [defaultTemplate] with [arguments] substituted, i.e. exactly today's
     * behaviour.
     *
     * Placeholders with no matching argument are left standing (see
     * [PromptTemplate.render]); values are inserted once and never re-expanded.
     */
    fun render(
        id: String,
        defaultTemplate: String,
        arguments: Map<String, String> = emptyMap(),
    ): String {
        val stored = snapshot.overrides[id]
        val template = if (stored != null && PromptTemplate.isCompatible(stored, defaultTemplate)) {
            stored
        } else {
            defaultTemplate
        }
        return PromptTemplate.render(template, arguments)
    }

    /**
     * Store [text] as the user's replacement for [id].
     *
     * The reference for validation is the CATALOG's default for [id]. When the
     * id is not in the catalog (an old override whose prompt has since left the
     * catalog, or no catalog asset at all) the existing override stands in for
     * it, so editing still works; with neither, the id is unknown and the save
     * is refused rather than creating an override nothing can render.
     *
     * Persists before publishing, so any failure — validation or I/O, both
     * [PromptOverrideException] — leaves the previous override in force both in
     * memory and on disk. That is the contract the settings UI relies on: an
     * error message and the user's previous text still there.
     */
    fun save(id: String, text: String) {
        val key = id.trim()
        if (key.isEmpty()) throw PromptOverrideException("prompt id must not be blank")
        synchronized(writeLock) {
            val snap = snapshot
            val memoryDir = snap.memoryDir ?: throw PromptOverrideException(
                "prompt overrides are not initialised: call PromptOverrides.initialize(context) during Application startup",
            )
            val reference = snap.catalogById[key]?.defaultTemplate
                ?: snap.overrides[key]
                ?: throw PromptOverrideException(
                    "unknown prompt id '" + key + "': it is not in the prompt catalog (" + CATALOG_ASSET + ")",
                )
            validate(key, text, reference)
            val next = snap.overrides + (key to text)
            if (next == snap.overrides) return
            persist(memoryDir, next)
            snapshot = snap.copy(overrides = next)
        }
    }

    /**
     * Drop the override for [id] and go back to the shipped default.
     *
     * Idempotent: resetting a prompt that has no override writes nothing and
     * touches no file, so a UI "Restore default" tap on a pristine prompt is a
     * no-op. A failed write throws [PromptOverrideException] and keeps the
     * override — the button never half-undoes itself.
     */
    fun reset(id: String) {
        val key = id.trim()
        synchronized(writeLock) {
            val snap = snapshot
            if (!snap.overrides.containsKey(key)) return
            val memoryDir = snap.memoryDir ?: return
            val next = snap.overrides - key
            persist(memoryDir, next)
            snapshot = snap.copy(overrides = next)
        }
    }

    /**
     * Reject anything that would render differently from the template it
     * replaces: the editor shows these messages verbatim, and a user who typed
     * `{{slot 1}}` or deleted a `{name}` needs to be told exactly that.
     *
     * Both placeholder kinds are compared by count, not presence — see
     * [PromptTemplate.signature]. The `{name}` kind matters as much as the
     * slots: render hands it back to the caller's `.replace("{name}", …)`, so a
     * dropped one leaves the literal text in the system prompt while an added
     * one is never filled at all.
     */
    private fun validate(id: String, text: String, reference: String) {
        val expected = PromptTemplate.signature(reference)
        val actual = PromptTemplate.signature(text)
        if (expected == actual) return

        // An ADDED `{{…}}` that is not a slot gets its own message: it is the one
        // mistake whose fix ("use {{slotN}} or keep the default's form") differs
        // from "you dropped something". Tokens the default already carries —
        // `{{result}}` in a scheduled-task prompt — are not unknown here; they
        // are simply part of the multiset and show up as "missing" if dropped.
        val introduced = PromptTemplate.unknownTokens(text)
            .filter { token -> (actual["{{" + token + "}}"] ?: 0) > (expected["{{" + token + "}}"] ?: 0) }
        if (introduced.isNotEmpty()) {
            throw PromptOverrideException(
                "prompt '" + id + "' contains unknown placeholder(s) " +
                    introduced.joinToString(", ") { "{{" + it + "}}" } +
                    "; only {{slotN}} slots (for example {{slot1}}) are filled by the app — " +
                    "use a single-brace {name} for text the caller substitutes after rendering",
            )
        }
        if (text.isEmpty()) {
            throw PromptOverrideException(
                "prompt '" + id + "' cannot be empty: its default template requires " +
                    PromptTemplate.describe(expected),
            )
        }
        val missing = expected.entries
            .filter { (token, count) -> (actual[token] ?: 0) < count }
            .map { (token, count) -> PromptTemplate.describe(mapOf(token to (count - (actual[token] ?: 0)))) }
        val added = actual.entries
            .filter { (token, count) -> (expected[token] ?: 0) < count }
            .map { (token, count) -> PromptTemplate.describe(mapOf(token to (count - (expected[token] ?: 0)))) }
        val parts = ArrayList<String>(2)
        if (missing.isNotEmpty()) parts.add("missing " + missing.joinToString(", "))
        if (added.isNotEmpty()) parts.add("unexpected " + added.joinToString(", "))
        throw PromptOverrideException(
            "prompt '" + id + "' must keep every placeholder of the default template: " +
                parts.joinToString("; ") +
                " (default has " + PromptTemplate.describe(expected) +
                "; this text has " + PromptTemplate.describe(actual) + ")",
        )
    }

    // MARK: - Disk

    /**
     * Serialize the WHOLE override set, write it to a `fsync`ed `.tmp`, then
     * rename it over the real file. Writing the complete set (not a
     * read-modify-write of the file) is what keeps a crash from producing a
     * merged, half-old state; the rename is the commit point.
     */
    private fun persist(memoryDir: File, overrides: Map<String, String>) {
        val target = File(memoryDir, FILE_NAME)
        try {
            if (overrides.isEmpty()) {
                // "No overrides" is the absence of the file, not an empty object.
                if (target.exists() && !target.delete()) {
                    throw PromptOverrideException("could not remove " + FILE_NAME + "; previous overrides are unchanged")
                }
                return
            }
            if (!memoryDir.isDirectory && !memoryDir.mkdirs()) {
                throw PromptOverrideException("memory directory is not writable: " + memoryDir.absolutePath)
            }
            val bytes = serialize(overrides).toByteArray(Charsets.UTF_8)
            val tmp = File(memoryDir, FILE_NAME + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Never fall back to truncating the live file: a failed rename
                // must preserve both the old disk value and cached snapshot.
                tmp.delete()
                throw PromptOverrideException("could not atomically replace " + FILE_NAME + "; previous overrides are unchanged")
            }
        } catch (t: Throwable) {
            if (t is PromptOverrideException) throw t
            throw PromptOverrideException("could not write " + FILE_NAME + " (" + t.message + ")", t)
        }
    }

    private fun serialize(overrides: Map<String, String>): String {
        val root = JSONObject()
        root.put(KEY_VERSION, FILE_VERSION)
        val body = JSONObject()
        for ((id, text) in overrides) body.put(id, text)
        root.put(KEY_OVERRIDES, body)
        return root.toString(2) + "\n"
    }

    /**
     * Read `PROMPTS.json`. Tolerant by design — this file lives in the memory
     * directory, which users do edit by hand and backups restore verbatim:
     *  - a missing or blank file means "no overrides",
     *  - `{"version":1,"overrides":{id:"text"}}` is the shape this class writes,
     *  - `{"prompts":{…}}` and a bare `{id:"text"}` map are accepted as the same
     *    thing, and an `{ "text": … }` object is accepted in place of a string,
     *    so a file written by a future/older reader still yields the text,
     *  - unparseable content is logged and ignored rather than crashing a
     *    launch: the prompts fall back to their defaults.
     *
     * Nothing is dropped for its content: whether a stored text still fits the
     * default is decided where the default is known — [render] and
     * [overrideFor]. A hand-edited override with a stale placeholder is then
     * ignored rather than silently deleted from the file.
     */
    private fun readOverrides(memoryDir: File): Map<String, String> {
        val file = File(memoryDir, FILE_NAME)
        if (!file.isFile) return emptyMap()
        return try {
            val raw = file.readText()
            if (raw.isBlank()) emptyMap() else parseOverrides(raw)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, FILE_NAME + " ignored (" + t.message + "); prompts use their defaults")
            emptyMap()
        }
    }

    private fun parseOverrides(raw: String): Map<String, String> {
        val root = JSONObject(raw)
        val body = root.optJSONObject(KEY_OVERRIDES)
            ?: root.optJSONObject("prompts")
            ?: if (root.has(KEY_VERSION) || root.has(KEY_OVERRIDES)) JSONObject() else root
        val out = LinkedHashMap<String, String>()
        val keys = body.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val id = rawKey.trim()
            if (id.isEmpty() || id == KEY_VERSION) continue
            val value = body.opt(rawKey)
            val text = when (value) {
                is String -> value
                is JSONObject -> if (value.has("text")) value.optString("text") else null
                else -> null
            } ?: continue
            out[id] = text
        }
        return out
    }

    /**
     * The catalog asset as text, or null when the app ships none.
     *
     * A missing catalog is NOT an error: the file is generated separately from
     * this code, and a build without it must still start — nothing can be
     * edited, but every prompt renders from the call site's default.
     */
    private fun readCatalogAsset(context: Context): String? = try {
        context.assets.open(CATALOG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: java.io.FileNotFoundException) {
        AppLogger.info(TAG, "no " + CATALOG_ASSET + " asset: no built-in prompts are editable in this build")
        null
    } catch (t: Throwable) {
        AppLogger.warning(TAG, CATALOG_ASSET + " unreadable: " + t.message)
        null
    }

    /**
     * Parse the catalog array: `[{id, group, title, source, defaultTemplate}]`.
     *
     * Tolerated so a regenerated file cannot brick the settings page: a
     * top-level object wrapping the array under `prompts` / `entries` /
     * `catalog`, missing optional strings (they become ""), and duplicate ids
     * (first entry wins, later ones are logged and skipped). Entries with a
     * blank id or no `defaultTemplate` key are skipped — without a default
     * there is nothing to validate an edit against and nothing to restore.
     */
    private fun parseCatalog(json: String?): List<PromptEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: runCatching {
            val root = JSONObject(json)
            root.optJSONArray("prompts") ?: root.optJSONArray("entries") ?: root.optJSONArray("catalog")
        }.getOrNull() ?: run {
            AppLogger.warning(TAG, CATALOG_ASSET + " is not a JSON array; ignored")
            return emptyList()
        }
        val out = ArrayList<PromptEntry>(array.length())
        val seen = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) {
                AppLogger.warning(TAG, CATALOG_ASSET + " entry #$i has no id; skipped")
                continue
            }
            if (!obj.has("defaultTemplate")) {
                AppLogger.warning(TAG, CATALOG_ASSET + " entry '$id' has no defaultTemplate; skipped")
                continue
            }
            if (!seen.add(id)) {
                AppLogger.warning(TAG, CATALOG_ASSET + " has a duplicate id '$id'; keeping the first")
                continue
            }
            val entry = PromptEntry(
                id = id,
                group = obj.optString("group", ""),
                title = obj.optString("title", ""),
                source = obj.optString("source", ""),
                defaultTemplate = obj.optString("defaultTemplate", ""),
            )
            val unknown = PromptTemplate.unknownTokens(entry.defaultTemplate)
            if (unknown.isNotEmpty()) {
                // Keep it (the catalog owner may still fix it) but say so: no
                // edit for this id can pass validation until the default does.
                AppLogger.warning(
                    TAG,
                    "catalog entry '$id' uses unknown placeholder(s) " + unknown.joinToString(", "),
                )
            }
            out.add(entry)
        }
        return out
    }
}
