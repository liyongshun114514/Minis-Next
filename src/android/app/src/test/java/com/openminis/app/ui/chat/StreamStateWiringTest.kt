package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards supplement executable parser, tracker and CAS tests, not device tests. */
class StreamStateWiringTest {
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
    @Test fun `every stream registers and clears with job ownership`() {
        assertEquals(10, Regex("owner = coroutineContext\\[Job]").findAll(vm).count())
        assertEquals(5, Regex("setInactive\\(activeSessionId, owner = coroutineContext\\[Job]\\)").findAll(vm).count())
        assertEquals(5, Regex("SessionActivityTracker.ownsRun\\(activeSessionId, coroutineContext\\[Job]\\)").findAll(vm).count())
    }
    @Test fun `all provider chunks are normalized before renderer and persistence`() {
        assertTrue(vm.contains("turnChunks.splitThinkingPrefix().collect { chunk ->"))
        assertTrue(vm.contains("?: turnThinking.toString().takeIf { it.isNotEmpty() }"))
    }
    @Test fun `append and dequeue do not use whole-list snapshots`() {
        val enqueue = vm.substringAfter("    fun enqueuePrompt(").substringBefore("    /** Remove a queued prompt")
        assertTrue(enqueue.contains("_promptQueue.update { it + prompt }"))
        assertTrue(enqueue.contains("_messages.update { it + chatMsg }"))
        assertTrue(enqueue.indexOf("_messages.update { it + chatMsg }") < enqueue.indexOf("_promptQueue.update { it + prompt }"))
        val inject = vm.substringAfter("private suspend fun injectQueuedPromptsAsNewTurn(").substringBefore("private suspend fun drainQueuedPrompts(")
        assertTrue(inject.contains("withContext(NonCancellable + Dispatchers.Main)"))
        assertTrue(inject.contains("val canContinue = injectingJob?.isActive == true"))
        assertTrue(inject.contains("if (!mayCommitUser) current"))
        val drain = vm.substringAfter("private suspend fun drainQueuedPrompts(").substringBefore("@Volatile private var pendingProviderEdits")
        assertTrue(drain.contains("_messages.updateItems { m ->"))
        assertTrue(drain.contains("dbMessageId = drainedRow.id"))
        assertTrue(drain.contains("_promptQueue.update { current -> current.filterNot { it.id in queuedIds } }"))
        val finish = vm.substringAfter("private fun updateAssistantMessage(").substringBefore("internal fun effectiveContent")
        assertTrue(finish.contains("_messages.updateItems { m ->"))
        assertFalse(finish.contains("_messages.value = updated"))
    }
    @Test fun `live browser producer retries failure and respects cancellation`() {
        val preview = ProductionSources.read("ui/chat/ChatMiscViews.kt").substringAfter("internal fun rememberBrowserLiveSnapshot(").substringBefore("internal fun ResumeBanner")
        assertTrue(preview.contains("captureLiveSnapshot()"))
        assertTrue(preview.contains("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        assertTrue(preview.contains("throw cancelled"))
        assertTrue(preview.contains("catch (failure: Exception)"))
    }
}
