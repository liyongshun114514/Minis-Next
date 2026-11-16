package com.openminis.app.groups

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [T-chat-group-identity] Who produced a chat turn.
 *
 * A model group is a per-SEND routing choice, like the model itself, so its
 * identity has to travel with the message (as `messages.model_id` /
 * `model_display_name` already do). Chat used to label every reply with ONE
 * mutable identity, so switching model or group relabelled the whole history.
 *
 * Pure Kotlin (no Android/Compose): the ViewModel, chat UI, export renderer and
 * plain JVM tests all share this definition.
 */
enum class ChatGroupIdentityKind {
    /** The turn ran through a model group; id/name/avatar frozen at send time. */
    GROUP,

    /** The turn ran on a directly picked model: the real model/provider speaks. */
    MODEL,

    /** Nothing known (pre-feature row, or a turn that has no identity yet). */
    NONE,
}

/** [T-chat-group-identity] Shape of the frozen avatar value. */
enum class ChatGroupAvatarKind {
    /** Bundled brand icon (`builtin:<catalog id>`). */
    BUILTIN,

    /** User picture (inline PNG data URI). */
    IMAGE,

    /** No own avatar: renderer's automatic match on [ChatGroupIdentity.avatarName]. */
    NONE,
}

/**
 * [T-chat-group-identity] One render-ready identity.
 *
 * [groupAvatar] is the *stored* value (`builtin:<id>` / `data:image/png;base64,…`)
 * — exactly what `ModelGroup.avatar` holds and what
 * [com.openminis.app.ui.components.ModelGroupAvatar] accepts, so no surface
 * needs its own decoder. Bitmap painters (share PNG, overlay) use
 * [avatarKind] / [builtinIconId] / [imageDataUri].
 */
data class ChatGroupIdentity(
    val kind: ChatGroupIdentityKind,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupAvatar: String? = null,
    val modelId: String? = null,
    val modelDisplayName: String? = null,
    val providerType: String? = null,
    val providerInstanceId: String? = null,
) {
    val isGrouped: Boolean
        get() = kind == ChatGroupIdentityKind.GROUP && !groupId.isNullOrBlank()

    /** The one name every chat surface prints. */
    val displayName: String
        get() {
            val group = groupName?.trim().orEmpty()
            val model = modelDisplayName?.trim().orEmpty()
            // modelId is a last resort for rows written before display names
            // were snapshotted: "gpt-4o-mini" still names the speaker, whereas
            // falling straight to the product name would look like a group.
            val modelIdLabel = modelId?.trim().orEmpty()
            return when (kind) {
                ChatGroupIdentityKind.GROUP ->
                    group.ifEmpty { model }.ifEmpty { modelIdLabel }.ifEmpty { DEFAULT_NAME }
                ChatGroupIdentityKind.MODEL ->
                    model.ifEmpty { modelIdLabel }.ifEmpty { DEFAULT_NAME }
                ChatGroupIdentityKind.NONE -> DEFAULT_NAME
            }
        }

    /**
     * Name for the avatar renderer's automatic brand match: the frozen group
     * name, or the model name for an ungrouped turn ("gpt-4o" → OpenAI mark).
     * Empty for [ChatGroupIdentityKind.NONE] (single fallback glyph).
     */
    val avatarName: String
        get() = when (kind) {
            ChatGroupIdentityKind.GROUP -> groupName?.trim().orEmpty()
            ChatGroupIdentityKind.MODEL ->
                modelDisplayName?.trim().orEmpty().ifEmpty { modelId?.trim().orEmpty() }
            ChatGroupIdentityKind.NONE -> ""
        }

    val avatarKind: ChatGroupAvatarKind
        get() {
            val avatar = groupAvatar ?: return ChatGroupAvatarKind.NONE
            return when {
                avatar.startsWith(ModelGroupAvatarValue.BUILTIN_PREFIX) -> ChatGroupAvatarKind.BUILTIN
                avatar.startsWith(ModelGroupAvatarValue.DATA_URI_PREFIX) -> ChatGroupAvatarKind.IMAGE
                else -> ChatGroupAvatarKind.NONE
            }
        }

    /** Catalog id behind a `builtin:` avatar, or null. */
    val builtinIconId: String?
        get() {
            val avatar = groupAvatar ?: return null
            if (!avatar.startsWith(ModelGroupAvatarValue.BUILTIN_PREFIX)) return null
            return ModelGroupAvatarValue.normalizedId(
                avatar.removePrefix(ModelGroupAvatarValue.BUILTIN_PREFIX),
            )
        }

    /** Inline PNG data URI behind an image avatar, or null. */
    val imageDataUri: String?
        get() = groupAvatar?.takeIf { it.startsWith(ModelGroupAvatarValue.DATA_URI_PREFIX) }

    override fun toString(): String =
        "ChatGroupIdentity(kind=$kind, group=$groupName/$groupId, " +
            "model=${modelDisplayName ?: modelId}, provider=$providerType)"

    companion object {
        /** Product name when nothing more specific is known (not localized). */
        const val DEFAULT_NAME = "Minis Next"

        val NONE = ChatGroupIdentity(kind = ChatGroupIdentityKind.NONE)
    }
}

/**
 * [T-chat-group-identity] What is PERSISTED per assistant turn.
 *
 * Smaller than [ChatGroupIdentity] on purpose: the model half already lives in
 * `messages.model_id` / `model_display_name` / `provider_type` /
 * `provider_instance_id`, and duplicating it would create two sources of truth.
 * Only the new part — group ownership — is frozen here.
 *
 * Ungrouped turns are stamped explicitly ([MODEL]) so "written before this
 * feature" (no snapshot) stays distinguishable from "explicitly ungrouped".
 * [groupAvatar] carries the full stored value within the bounds
 * [ModelGroupAvatarValue] already enforces (≤ 64 KB, valid `builtin:` id or PNG
 * data URI); nothing is truncated, so a custom picture keeps rendering exactly
 * as it did when the turn was sent.
 */
data class ChatGroupOwnership(
    val kind: ChatGroupIdentityKind,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupAvatar: String? = null,
) {
    val isGrouped: Boolean
        get() = kind == ChatGroupIdentityKind.GROUP && !groupId.isNullOrBlank()

    companion object {
        /** Explicitly ungrouped: the model identity is the speaker. */
        val MODEL = ChatGroupOwnership(ChatGroupIdentityKind.MODEL)

        /** Explicit "nothing known" stamp. */
        val NONE = ChatGroupOwnership(ChatGroupIdentityKind.NONE)
    }
}

/**
 * [T-chat-group-identity] Persistence codec for [ChatGroupOwnership]:
 * ONE JSON string in `messages.group_identity`.
 *
 * ```json
 * {"v":1,"kind":"group","id":"…","name":"Work","avatar":"builtin:openai"}
 * ```
 *
 * kotlinx.serialization with `ignoreUnknownKeys` (forward compatible) and
 * `explicitNulls = false` (a compact row). Decoding is total: malformed,
 * unknown-versioned or partially written values yield null = "no snapshot",
 * which falls back to the row's own model identity — never to the picker.
 */
object ChatGroupIdentityCodec {

    const val VERSION = 1

    @Serializable
    private data class Wire(
        val v: Int = VERSION,
        val kind: String,
        val id: String? = null,
        val name: String? = null,
        val avatar: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Encode an ownership stamp; null in, null out. */
    fun encode(ownership: ChatGroupOwnership?): String? {
        ownership ?: return null
        val kind = when (ownership.kind) {
            ChatGroupIdentityKind.GROUP -> "group"
            ChatGroupIdentityKind.MODEL -> "model"
            ChatGroupIdentityKind.NONE -> "none"
        }
        return runCatching {
            json.encodeToString(
                Wire.serializer(),
                Wire(
                    kind = kind,
                    id = ownership.groupId?.takeIf { it.isNotBlank() },
                    name = ownership.groupName?.takeIf { it.isNotBlank() },
                    // Full stored value, bounded by ModelGroupAvatarValue itself.
                    avatar = ownership.groupAvatar
                        ?.takeIf { ModelGroupAvatarValue.isUsable(it) },
                ),
            )
        }.getOrNull()
    }

    /**
     * Decode a stored stamp; null when absent or unusable.
     *
     * Decoding is done on the JSON tree, not through [Wire], because the
     * serializer is deliberately permissive in one direction that matters
     * here: it accepts a QUOTED number for `v` (`{"v":"1"}`). Nothing this
     * build writes produces that, so a value like it came from somewhere else
     * and must not be trusted. The same check keeps `id`/`name`/`avatar` from
     * arriving as numbers/objects and being silently coerced.
     */
    fun decode(raw: String?): ChatGroupOwnership? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val versionPrim = obj["v"] as? JsonPrimitive ?: return null
            if (versionPrim.isString) return null
            val version = versionPrim.content.toIntOrNull() ?: return null
            if (version <= 0 || version > VERSION) return null

            val kindPrim = obj["kind"] as? JsonPrimitive ?: return null
            if (!kindPrim.isString) return null
            val kind = when (kindPrim.content) {
                "group" -> ChatGroupIdentityKind.GROUP
                "model" -> ChatGroupIdentityKind.MODEL
                "none" -> ChatGroupIdentityKind.NONE
                else -> return null
            }
            ChatGroupOwnership(
                kind = kind,
                groupId = stringField(obj, "id")?.takeIf { it.isNotBlank() },
                groupName = stringField(obj, "name")?.takeIf { it.isNotBlank() },
                groupAvatar = stringField(obj, "avatar")
                    ?.takeIf { ModelGroupAvatarValue.isUsable(it) },
            )
        }.getOrNull()
    }

    /** A string field, or null when absent/null; throws on any other JSON type. */
    private fun stringField(obj: JsonObject, key: String): String? =
        when (val element = obj[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                require(element.isString) { "$key must be a string" }
                element.content
            }
            else -> throw IllegalArgumentException("$key must be a string")
        }
}

/**
 * [T-chat-group-identity] The ONE place that turns configuration into an
 * identity and stored snapshots back into one.
 */
object ChatGroupIdentityResolver {

    /**
     * Freeze a turn's group identity. Capture at DISPATCH (or persist — the
     * caller decides) so a switch during a long tool loop cannot mislabel it.
     *
     * A null group is an explicit ungrouped stamp ([ChatGroupOwnership.MODEL]),
     * not "unknown".
     */
    fun ownership(group: ModelGroup?): ChatGroupOwnership {
        if (group == null) return ChatGroupOwnership.MODEL
        val name = group.name.trim()
        if (group.id.isBlank() && name.isEmpty()) return ChatGroupOwnership.MODEL
        return ChatGroupOwnership(
            kind = ChatGroupIdentityKind.GROUP,
            groupId = group.id.takeIf { it.isNotBlank() },
            groupName = name.takeIf { it.isNotEmpty() },
            groupAvatar = group.avatar?.takeIf { ModelGroupAvatarValue.isUsable(it) },
        )
    }

    /**
     * The identity the picker points at — for the empty chat, composer
     * placeholder, typing row and the in-flight turn ONLY. Never applied to a
     * message that carries its own snapshot.
     */
    fun current(
        group: ModelGroup?,
        modelName: String?,
        providerName: String? = null,
        modelId: String? = null,
        providerType: String? = null,
        providerInstanceId: String? = null,
    ): ChatGroupIdentity {
        val modelLabel = modelName?.trim().orEmpty().ifEmpty { providerName?.trim().orEmpty() }
        if (group != null) {
            val name = group.name.trim()
            return ChatGroupIdentity(
                kind = ChatGroupIdentityKind.GROUP,
                groupId = group.id.takeIf { it.isNotBlank() },
                groupName = name.takeIf { it.isNotEmpty() },
                groupAvatar = group.avatar?.takeIf { ModelGroupAvatarValue.isUsable(it) },
                modelId = modelId,
                modelDisplayName = modelLabel.takeIf { it.isNotEmpty() },
                providerType = providerType,
                providerInstanceId = providerInstanceId,
            )
        }
        return ChatGroupIdentity(
            kind = if (modelLabel.isEmpty()) {
                ChatGroupIdentityKind.NONE
            } else {
                ChatGroupIdentityKind.MODEL
            },
            modelDisplayName = modelLabel.takeIf { it.isNotEmpty() },
            modelId = modelId,
            providerType = providerType,
            providerInstanceId = providerInstanceId,
        )
    }

    /** [current] from the picker's group id + configured groups. */
    fun current(
        groupId: String?,
        groups: List<ModelGroup>,
        modelName: String?,
        providerName: String? = null,
        modelId: String? = null,
        providerType: String? = null,
        providerInstanceId: String? = null,
    ): ChatGroupIdentity = current(
        group = groups.firstOrNull { it.id == groupId },
        modelName = modelName,
        providerName = providerName,
        modelId = modelId,
        providerType = providerType,
        providerInstanceId = providerInstanceId,
    )

    /**
     * Rebuild a persisted turn: frozen group stamp + the row's own model
     * snapshot. A null [ownership] is a pre-feature row and resolves to its
     * model identity — never to the current picker.
     */
    fun persisted(
        ownership: ChatGroupOwnership?,
        modelId: String? = null,
        modelDisplayName: String? = null,
        providerType: String? = null,
        providerInstanceId: String? = null,
    ): ChatGroupIdentity {
        val model = modelDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        val modelIdValue = modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (ownership == null) {
            val known = model != null || modelIdValue != null
            return ChatGroupIdentity(
                kind = if (known) ChatGroupIdentityKind.MODEL else ChatGroupIdentityKind.NONE,
                modelId = modelIdValue,
                modelDisplayName = model,
                providerType = providerType,
                providerInstanceId = providerInstanceId,
            )
        }
        return ChatGroupIdentity(
            kind = ownership.kind,
            groupId = ownership.groupId,
            groupName = ownership.groupName,
            groupAvatar = ownership.groupAvatar,
            modelId = modelIdValue,
            modelDisplayName = model,
            providerType = providerType,
            providerInstanceId = providerInstanceId,
        )
    }

    /**
     * The ONE group that owns [modelId], or null when none does OR when more
     * than one does. The same model is commonly a member of a "cheap" and a
     * "careful" group; picking either would relabel history with a group the
     * user never chose for that turn, so ambiguity resolves to null.
     */
    fun uniqueGroupOwningModel(modelId: String?, config: ProviderConfig): ModelGroup? {
        val id = modelId?.trim().orEmpty()
        if (id.isEmpty()) return null
        val entriesById = config.modelEntries.associateBy { it.uuid }
        val owners = config.modelGroups.filter { group ->
            group.memberEntryIds.any { entryId ->
                val entry = entriesById[entryId] ?: return@any false
                entry.model.id == id || entry.baseModel.id == id
            }
        }.distinctBy { it.id }
        return owners.singleOrNull()
    }

    /**
     * Legacy-only backfill stamp for a row without `group_identity`. Null when
     * ownership is unknown OR ambiguous — the caller then leaves the row
     * unstamped so it keeps its model identity.
     */
    fun legacyBackfill(modelId: String?, config: ProviderConfig): ChatGroupOwnership? =
        uniqueGroupOwningModel(modelId, config)?.let { ownership(it) }
}

/**
 * [T-chat-group-identity] The lookup policy every chat surface shares.
 *
 * 1. the message's OWN frozen identity;
 * 2. the live picker — only for [isLiveTurn] (reply streaming now, row not yet
 *    persisted) or while [loaded] is false (first composition);
 * 3. [ChatGroupIdentity.NONE] for a legacy row with no snapshot.
 *
 * Deliberately NOT "fall back to the picker": that single rule is what
 * relabelled history before this feature.
 */
class ChatGroupIdentityIndex(
    private val byMessageId: Map<String, ChatGroupIdentity>,
    val current: ChatGroupIdentity,
    val loaded: Boolean = true,
) {
    fun identityFor(messageId: String, isLiveTurn: Boolean = false): ChatGroupIdentity =
        byMessageId[messageId]
            ?: if (isLiveTurn || !loaded) current else ChatGroupIdentity.NONE

    companion object {
        /** Before the projection has answered: rows may borrow the picker. */
        fun loading(current: ChatGroupIdentity): ChatGroupIdentityIndex =
            ChatGroupIdentityIndex(emptyMap(), current, loaded = false)
    }
}