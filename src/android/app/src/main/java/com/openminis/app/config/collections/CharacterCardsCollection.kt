package com.openminis.app.config.collections

import android.content.Context
import com.openminis.app.agent.CharacterCard
import com.openminis.app.agent.CharacterCardScope
import com.openminis.app.agent.CharacterCardStore
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.config.*
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.*
import com.openminis.app.data.repository.ProviderRepository

/** One source of truth with Settings → Character Cards; all writes use the approval gate. */
class CharacterCardsCollection(
    private val context: Context,
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath = "characters"
    override val displayName = "Character cards"
    override val description = "Role cards used globally or for an exact provider/model pair. Imported card text is configuration, not executable code. Scope order: global, group, model."
    override val risk = ConfigRisk.SENSITIVE
    override val readableSubjectFormat = "Character card “%1\$@” – %2\$@"
    override fun childIds() = CharacterCardStore.load(context).map { it.id }
    private fun card(id: String) = CharacterCardStore.load(context).firstOrNull { it.id == id }
    override fun entityLabel(id: String) = card(id)?.name
    override fun readableForAdd(payload: RedactedConfigValue) = listOf(
        ReadableLine("Create character card “%@”", listOf(ReadableArg.Text(
            ((payload.value as? ConfigValue.Obj)?.value?.get("name") as? ConfigValue.Str)?.value ?: "?",
        ))),
    )
    override fun readableForRemove(id: String) = listOf(
        ReadableLine("Delete character card “%@”", listOf(ReadableArg.Text(card(id)?.name ?: id))),
    )

    override fun fields(forId: String): List<ConfigField> {
        if (card(forId) == null) return emptyList()
        val strings = listOf("name", "description", "personality", "scenario", "firstMessage",
            "messageExamples", "systemPrompt", "postHistoryInstructions", "creatorNotes", "style", "lang")
        return strings.map { key ->
            ClosureField(
                path = "$basePath.$forId.$key", displayName = key,
                description = "Character card $key. Preserves all other card fields. Changes require user approval.",
                valueSchema = ConfigSchema.Str(), risk = risk, revertable = true,
                readable = ConfigReadable.generic("Role-card field names are shown explicitly in the confirmation."),
                reader = { card(forId)?.let { ConfigValue.Str(read(it, key)) } ?: ConfigValue.Null },
                writer = { value ->
                    val text = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    mutate(forId) { write(it, key, text) }
                },
            )
        } + listOf(
            ClosureField(
                path = "$basePath.$forId.enabled", displayName = "Enabled",
                description = "Enable this character card without changing its scope.",
                valueSchema = ConfigSchema.Bool, risk = risk, revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = { card(forId)?.let { ConfigValue.Bool(it.enabled) } ?: ConfigValue.Null },
                writer = { value ->
                    val enabled = (value as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                    mutate(forId) { it.copy(enabled = enabled) }
                },
            ),
            ClosureField(
                path = "$basePath.$forId.target", displayName = "Scope and target",
                description = "Atomic object: {scope: GLOBAL|MODEL|GROUP, providerInstanceId, modelId, groupId}. MODEL requires an exact configured provider/model pair; GROUP is retained for migrated rules.",
                valueSchema = ConfigSchema.Json, risk = risk, revertable = true,
                readable = ConfigReadable.generic("Atomic scope/target object is displayed for approval."),
                reader = { card(forId)?.let { c -> ConfigValue.Obj(mapOf(
                    "scope" to ConfigValue.Str(c.scope.name),
                    "providerInstanceId" to ConfigValue.Str(c.providerInstanceId.orEmpty()),
                    "modelId" to ConfigValue.Str(c.modelId.orEmpty()),
                    "groupId" to ConfigValue.Str(c.groupId.orEmpty()),
                )) } ?: ConfigValue.Null },
                writer = { value ->
                    val obj = (value as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
                    mutate(forId) { target(it, obj) }
                },
            ),
        )
    }

    override fun add(payload: ConfigValue): String = synchronized(CharacterCardStore) {
        val obj = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
        var next = target(CharacterCard(enabled = false), obj)
        for (key in listOf("name", "description", "personality", "scenario", "firstMessage", "messageExamples",
            "systemPrompt", "postHistoryInstructions", "creatorNotes", "style", "lang")) {
            obj[key]?.let { value -> next = write(next, key,
                (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")) }
        }
        next = next.copy(enabled = (obj["enabled"] as? ConfigValue.Bool)?.value ?: false)
        if (next.name.isBlank()) throw ConfigError.InvalidValue("name is required")
        CharacterCardStore.save(context, CharacterCardStore.load(context) + next)
        next.id
    }
    override fun remove(id: String) = synchronized(CharacterCardStore) {
        val cards = CharacterCardStore.load(context)
        if (cards.none { it.id == id }) throw ConfigError.UnknownPath("$basePath.$id")
        CharacterCardStore.save(context, cards.filterNot { it.id == id })
    }
    private fun mutate(id: String, change: (CharacterCard) -> CharacterCard) = synchronized(CharacterCardStore) {
        val cards = CharacterCardStore.load(context)
        if (cards.none { it.id == id }) throw ConfigError.UnknownPath("$basePath.$id")
        CharacterCardStore.save(context, cards.map { if (it.id == id) change(it) else it })
    }
    private fun target(c: CharacterCard, obj: Map<String, ConfigValue>): CharacterCard {
        fun str(key: String): String? = (obj[key] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
        val scope = str("scope")?.let { raw -> CharacterCardScope.entries.firstOrNull { it.name == raw.uppercase() }
            ?: throw ConfigError.InvalidValue("scope must be GLOBAL, MODEL or GROUP") } ?: c.scope
        val provider = str("providerInstanceId")
        val model = str("modelId")
        val group = str("groupId")
        if (scope == CharacterCardScope.MODEL && repo.config.value.modelEntries.none {
            it.providerInstanceId == provider && it.model.id == model
        }) throw ConfigError.InvalidValue("Select an exact configured providerInstanceId and modelId")
        if (scope == CharacterCardScope.GROUP && repo.config.value.modelGroups.none { it.id == group })
            throw ConfigError.InvalidValue("Select a configured groupId")
        return c.copy(scope = scope,
            providerInstanceId = provider.takeIf { scope == CharacterCardScope.MODEL },
            modelId = model.takeIf { scope == CharacterCardScope.MODEL },
            groupId = group.takeIf { scope == CharacterCardScope.GROUP })
    }
    private fun read(c: CharacterCard, key: String): String = when (key) {
        "name" -> c.name; "description" -> c.description; "personality" -> c.personality
        "scenario" -> c.scenario; "firstMessage" -> c.firstMessage; "messageExamples" -> c.messageExamples
        "systemPrompt" -> c.systemPrompt; "postHistoryInstructions" -> c.postHistoryInstructions
        "creatorNotes" -> c.creatorNotes; "style" -> c.style; "lang" -> c.lang
        else -> error("Unknown character field")
    }
    private fun write(c: CharacterCard, key: String, value: String): CharacterCard {
        if (key == "name" && value.isBlank()) throw ConfigError.InvalidValue("name must not be blank")
        if (key != "creatorNotes" && SystemPromptBuilder.containsInjectionPattern(value))
            throw ConfigError.InvalidValue("Character text cannot override instruction hierarchy")
        return when (key) {
            "name" -> c.copy(name = value); "description" -> c.copy(description = value)
            "personality" -> c.copy(personality = value); "scenario" -> c.copy(scenario = value)
            "firstMessage" -> c.copy(firstMessage = value); "messageExamples" -> c.copy(messageExamples = value)
            "systemPrompt" -> c.copy(systemPrompt = value)
            "postHistoryInstructions" -> c.copy(postHistoryInstructions = value)
            "creatorNotes" -> c.copy(creatorNotes = value); "style" -> c.copy(style = value)
            "lang" -> c.copy(lang = value); else -> error("Unknown character field")
        }
    }
}
