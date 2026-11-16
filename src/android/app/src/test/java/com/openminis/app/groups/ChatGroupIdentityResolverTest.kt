package com.openminis.app.groups

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chat-group-identity] The resolver's contracts — the rules that keep a
 * model/group switch from relabelling history.
 */
class ChatGroupIdentityResolverTest {

    private fun group(
        id: String = "g1",
        name: String = "Work",
        avatar: String? = "builtin:openai",
        members: List<String> = emptyList(),
    ) = ModelGroup(id = id, name = name, memberEntryIds = members.toMutableList()).also {
        it.avatar = avatar
    }

    private fun entry(uuid: String, modelId: String, displayName: String = modelId) =
        ModelEntry(
            providerInstanceId = "p1",
            baseModel = LLMModel(id = modelId, displayName = displayName, provider = "openai"),
            uuid = uuid,
        )

    // ── freezing ────────────────────────────────────────────────────────────

    @Test
    fun `ownership freezes id name and avatar at generation time`() {
        val live = group()
        val stamp = ChatGroupIdentityResolver.ownership(live)

        // The user renames the group and swaps its icon afterwards.
        live.name = "Personal"
        live.avatar = "builtin:claude"

        assertEquals(ChatGroupIdentityKind.GROUP, stamp.kind)
        assertEquals("g1", stamp.groupId)
        assertEquals("Work", stamp.groupName)
        assertEquals("builtin:openai", stamp.groupAvatar)
        assertEquals("Work", ChatGroupIdentityResolver.persisted(stamp).displayName)
    }

    @Test
    fun `a bare model turn is stamped as explicitly ungrouped`() {
        val stamp = ChatGroupIdentityResolver.ownership(null)
        assertEquals(ChatGroupOwnership.MODEL, stamp)
        assertFalse(stamp.isGrouped)
        assertNull(stamp.groupId)
    }

    @Test
    fun `an unusable group avatar is not frozen into every row`() {
        val stamp = ChatGroupIdentityResolver.ownership(group(avatar = "not-an-avatar"))
        assertNull(stamp.groupAvatar)
        assertEquals("Work", stamp.groupName)
    }

    // ── current (picker → surfaces) ─────────────────────────────────────────

    @Test
    fun `current renders the selected group name and avatar`() {
        val identity = ChatGroupIdentityResolver.current(group(), modelName = "GPT-5")
        assertEquals(ChatGroupIdentityKind.GROUP, identity.kind)
        assertEquals("Work", identity.displayName)
        assertEquals("Work", identity.avatarName)
        assertEquals(ChatGroupAvatarKind.BUILTIN, identity.avatarKind)
        assertEquals("openai", identity.builtinIconId)
    }

    @Test
    fun `an ungrouped picker falls back to the real model identity`() {
        val identity = ChatGroupIdentityResolver.current(null, modelName = "GPT-5 mini")
        assertEquals(ChatGroupIdentityKind.MODEL, identity.kind)
        assertEquals("GPT-5 mini", identity.displayName)
        assertEquals("GPT-5 mini", identity.avatarName)
        assertEquals(ChatGroupAvatarKind.NONE, identity.avatarKind)
    }

    @Test
    fun `provider name is the last resort before the product name`() {
        val withProvider = ChatGroupIdentityResolver.current(null, null, providerName = "OpenAI")
        assertEquals(ChatGroupIdentityKind.MODEL, withProvider.kind)
        assertEquals("OpenAI", withProvider.displayName)

        val nothing = ChatGroupIdentityResolver.current(null, null, providerName = "  ")
        assertEquals(ChatGroupIdentityKind.NONE, nothing.kind)
        assertEquals(ChatGroupIdentity.DEFAULT_NAME, nothing.displayName)
    }

    // ── persisted (history) ─────────────────────────────────────────────────

    @Test
    fun `a pre-feature row keeps its model identity and never borrows a group`() {
        val identity = ChatGroupIdentityResolver.persisted(
            ownership = null,
            modelId = "gpt-4o-mini",
            modelDisplayName = null,
        )
        assertEquals(ChatGroupIdentityKind.MODEL, identity.kind)
        assertNull(identity.groupId)
        // Regression: an old row that only snapshotted model_id must not render
        // as the product name.
        assertEquals("gpt-4o-mini", identity.displayName)
    }

    @Test
    fun `a row with no attribution at all renders the product identity`() {
        val identity = ChatGroupIdentityResolver.persisted(ownership = null)
        assertEquals(ChatGroupIdentityKind.NONE, identity.kind)
        assertEquals(ChatGroupIdentity.DEFAULT_NAME, identity.displayName)
    }

    @Test
    fun `a grouped row reports the frozen group even when the model changed`() {
        val stamp = ChatGroupIdentityResolver.ownership(group())
        val identity = ChatGroupIdentityResolver.persisted(
            ownership = stamp,
            modelId = "claude-opus-4-8",
            modelDisplayName = "Claude Opus 4.8",
        )
        assertEquals(ChatGroupIdentityKind.GROUP, identity.kind)
        assertEquals("Work", identity.displayName)
        assertEquals("Claude Opus 4.8", identity.modelDisplayName)
    }

    @Test
    fun `a deleted group still names the turn`() {
        val stamp = ChatGroupOwnership(
            kind = ChatGroupIdentityKind.GROUP,
            groupId = "gone",
            groupName = "Old group",
        )
        val identity = ChatGroupIdentityResolver.persisted(stamp, modelId = "gpt-5")
        assertEquals("Old group", identity.displayName)
        assertNull(identity.groupAvatar)
        // The name no longer matches a live group, so the renderer auto-matches
        // on that frozen name rather than drawing someone else's icon.
        assertEquals("Old group", identity.avatarName)
    }

    // ── legacy backfill: unique ownership only ──────────────────────────────

    @Test
    fun `unique group ownership is found`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("e1", "gpt-5")),
            modelGroups = mutableListOf(group(id = "g1", name = "Fast", members = listOf("e1"))),
        )
        assertEquals("g1", ChatGroupIdentityResolver.uniqueGroupOwningModel("gpt-5", config)?.id)
        assertNotNull(ChatGroupIdentityResolver.legacyBackfill("gpt-5", config))
    }

    @Test
    fun `a model in two groups is ambiguous and is never backfilled`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("e1", "gpt-5"), entry("e2", "gpt-5")),
            modelGroups = mutableListOf(
                group(id = "g1", name = "Fast", members = listOf("e1")),
                group(id = "g2", name = "Careful", members = listOf("e2")),
            ),
        )
        assertNull(ChatGroupIdentityResolver.uniqueGroupOwningModel("gpt-5", config))
        assertNull(ChatGroupIdentityResolver.legacyBackfill("gpt-5", config))
    }

    @Test
    fun `a group holding the same model twice is still one owner`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("e1", "gpt-5"), entry("e2", "gpt-5")),
            modelGroups = mutableListOf(group(id = "g1", name = "Fast", members = listOf("e1", "e2"))),
        )
        assertEquals("g1", ChatGroupIdentityResolver.uniqueGroupOwningModel("gpt-5", config)?.id)
    }

    @Test
    fun `an ungrouped model or missing model id is not backfilled`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("e1", "gpt-5")),
            modelGroups = mutableListOf(group(id = "g1", name = "Fast", members = listOf("e1"))),
        )
        assertNull(ChatGroupIdentityResolver.legacyBackfill("claude-opus-4-8", config))
        assertNull(ChatGroupIdentityResolver.legacyBackfill(null, config))
        assertNull(ChatGroupIdentityResolver.legacyBackfill("  ", config))
    }

    @Test
    fun `a dangling member entry does not crash or claim ownership`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("e1", "gpt-5")),
            modelGroups = mutableListOf(
                group(id = "g1", name = "Fast", members = listOf("e1")),
                group(id = "g2", name = "Broken", members = listOf("missing")),
            ),
        )
        assertEquals("g1", ChatGroupIdentityResolver.uniqueGroupOwningModel("gpt-5", config)?.id)
    }

    // ── lookup policy ───────────────────────────────────────────────────────

    @Test
    fun `a persisted identity always wins over the current picker`() {
        val frozen = ChatGroupIdentityResolver.persisted(
            ChatGroupIdentityResolver.ownership(group(id = "old", name = "Old")),
            modelId = "gpt-5",
        )
        val index = ChatGroupIdentityIndex(
            byMessageId = mapOf("m1" to frozen),
            current = ChatGroupIdentityResolver.current(group(id = "new", name = "New"), "GPT-5"),
        )
        assertEquals("Old", index.identityFor("m1").displayName)
    }

    @Test
    fun `only the live turn borrows the picker`() {
        val index = ChatGroupIdentityIndex(
            byMessageId = emptyMap(),
            current = ChatGroupIdentityResolver.current(group(), "GPT-5"),
        )
        assertEquals("Work", index.identityFor("streaming", isLiveTurn = true).displayName)
        assertEquals(ChatGroupIdentity.NONE, index.identityFor("streaming"))
    }

    @Test
    fun `before the projection loads every row may borrow the picker`() {
        val index = ChatGroupIdentityIndex.loading(
            ChatGroupIdentityResolver.current(group(), "GPT-5"),
        )
        assertFalse(index.loaded)
        assertEquals("Work", index.identityFor("any").displayName)
    }

    @Test
    fun `an ungrouped persisted row keeps the model name when another group is selected`() {
        val ungrouped = ChatGroupIdentityResolver.persisted(
            ChatGroupOwnership.MODEL,
            modelId = "gpt-5",
            modelDisplayName = "GPT-5",
        )
        val index = ChatGroupIdentityIndex(
            byMessageId = mapOf("m1" to ungrouped),
            current = ChatGroupIdentityResolver.current(group(id = "new", name = "New"), "Claude"),
        )
        assertEquals("GPT-5", index.identityFor("m1").displayName)
        assertTrue(index.identityFor("m1").isGrouped.not())
    }
}