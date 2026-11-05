package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Wiring guard for the provider-chunk normalization that both the renderer and
 *  persistence depend on. The queue/ownership guards this file used to carry
 *  were removed together with the exclusive queue state machine they pinned. */
class StreamStateWiringTest {
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    @Test fun `all provider chunks are normalized before renderer and persistence`() {
        assertTrue(vm.contains("turnChunks.splitThinkingPrefix().collect { chunk ->"))
        assertTrue(vm.contains("?: turnThinking.toString().takeIf { it.isNotEmpty() }"))
    }
}
