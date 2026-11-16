package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.groups.ChatGroupIdentity
import com.openminis.app.ui.components.ModelGroupAvatar
import kotlinx.coroutines.flow.catch

/**
 * [T-chat-group-identity] Persisted identity for every row of this session,
 * keyed by message id.
 *
 * Reads the lightweight projection ([ChatRepository.observeMessageGroupIdentities],
 * six short columns — never `parts_json`), so labelling headers cannot make a
 * long history expensive. Empty map = nothing on disk yet, which the
 * [com.openminis.app.groups.ChatGroupIdentityIndex] treats as "still loading".
 */
@Composable
internal fun rememberChatGroupIdentityRows(
    sessionId: String,
    chatRepository: ChatRepository,
): State<Map<String, ChatGroupIdentity>> =
    produceState(
        initialValue = emptyMap<String, ChatGroupIdentity>(),
        sessionId,
        chatRepository,
    ) {
        chatRepository.observeMessageGroupIdentities(sessionId)
            .catch { emit(emptyMap()) }
            .collect { value = it }
    }

/**
 * [T-chat-group-identity] The ONE avatar renderer for chat identity surfaces.
 *
 * Delegates to [ModelGroupAvatar], so a group shows the same mark here as in
 * settings, the picker and the sidebar, custom pictures keep their alpha (no
 * backdrop is painted behind them), and the fallback glyph is the shared one.
 * A group with a manual icon gets it; otherwise the frozen NAME drives the
 * automatic brand match — for an ungrouped turn that name is the model, which
 * is what makes a bare-model reply show its provider's mark.
 */
@Composable
internal fun ChatGroupIdentityAvatar(
    identity: ChatGroupIdentity,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    ModelGroupAvatar(
        name = identity.avatarName,
        storedAvatar = identity.groupAvatar,
        modifier = modifier,
        size = size,
        contentDescription = identity.displayName,
    )
}