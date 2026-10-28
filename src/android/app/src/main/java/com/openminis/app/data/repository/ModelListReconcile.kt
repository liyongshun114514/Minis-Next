package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import java.util.UUID

/**
 * [T-authoritative-model-refresh] The decision rule for rebuilding one
 * provider's model entries from a LIVE, authoritative list.
 *
 * The rule, in one sentence: the provider's latest answer IS the catalog.
 *
 *  - an id in the answer keeps its entry — uuid, overrides, hidden flag,
 *    custom display name — so user metadata survives a refresh;
 *  - an id the provider no longer returns is DELETED, together with its uuid,
 *    so nothing downstream can route to a model the provider dropped;
 *  - an empty answer is a valid answer: it clears the provider's catalog;
 *  - a FAILED fetch never reaches this function (the caller keeps the old
 *    list and reports the error), so "empty" here can only mean "the vendor
 *    says it has no models".
 *
 * Two kinds of entries are not provider-catalog ids and are therefore kept
 * when absent from the answer:
 *  - `isCustom`: rows the user added by hand;
 *  - voice-template seeds ([protectedVoiceIds]): ASR/TTS voices that vendors
 *    like MiMo never expose through /v1/models at all.
 *
 * Kept as a pure function — no Android context, no config lock — so the
 * delete/add/metadata rules are unit-testable without a repository.
 */
internal object ModelListReconcile {

    data class Result(
        /** The instance's complete new entry list, in authoritative order. */
        val entries: List<ModelEntry>,
        /** uuids of entries this replacement dropped (group refs must be pruned). */
        val removedEntryIds: Set<String>,
        /** Model ids that survived (present in the answer or user-owned). */
        val keptModelIds: Set<String>,
    )

    fun apply(
        instanceId: String,
        existing: List<ModelEntry>,
        authoritative: List<LLMModel>,
        protectedVoiceIds: Set<String> = emptySet(),
        newUuid: () -> String = { UUID.randomUUID().toString() },
    ): Result {
        val listedIds = authoritative.map { it.id }.toSet()

        // A model id can appear twice (a custom row and a catalog row). Prefer
        // the catalog entry so its uuid/overrides are the ones carried forward.
        val priorByModelId = LinkedHashMap<String, ModelEntry>()
        for (entry in existing) {
            val current = priorByModelId[entry.baseModel.id]
            if (current == null || current.isCustom) priorByModelId[entry.baseModel.id] = entry
        }

        val refreshed = authoritative.map { model ->
            val prior = priorByModelId[model.id]
            val displayName = when {
                model.displayName.isBlank() || model.displayName == model.id ->
                    prior?.baseModel?.displayName
                        ?.takeIf { it.isNotBlank() && it != model.id }
                        ?: model.displayName
                else -> model.displayName
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = model.copy(displayName = displayName),
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: newUuid(),
                userModifiedAt = prior?.userModifiedAt,
                // Listed again ⇒ clear any legacy absence mark; an id the
                // provider dropped is deleted outright, never marked.
                absentSince = null,
            )
        }

        val userOwned = existing.filter { it.isCustom && it.baseModel.id !in listedIds }
        val voiceSeeds = existing.filter {
            !it.isCustom &&
                it.baseModel.id !in listedIds &&
                it.baseModel.id in protectedVoiceIds
        }

        val finalEntries = refreshed + userOwned + voiceSeeds
        val keptEntryIds = finalEntries.map { it.id }.toSet()
        return Result(
            entries = finalEntries,
            removedEntryIds = existing.map { it.id }.toSet() - keptEntryIds,
            keptModelIds = finalEntries.map { it.baseModel.id }.toSet(),
        )
    }
}
