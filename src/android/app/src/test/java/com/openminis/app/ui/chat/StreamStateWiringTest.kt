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
        // [T-queued-bubble-atomic-delivery] The placeholder→bubble swap is ONE
        // CAS emit whose decision is taken inside it. The old two-emit shape
        // (remove by predicate, re-append gated on a predicate read earlier)
        // could leave neither.
        assertTrue(inject.contains("QueuedMessageState.deliver("))
        assertTrue(inject.contains("newBubbles = if (canContinue) listOf(queuedUserMsg, nextAssistantMsg)"))
        assertFalse("no pre-read predicate may gate the commit", inject.contains("val mayCommitUser ="))
        val drain = vm.substringAfter("private suspend fun drainQueuedPrompts(").substringBefore("@Volatile private var pendingProviderEdits")
        assertTrue(drain.contains("_messages.updateItems { m ->"))
        assertTrue(drain.contains("dbMessageId = drainedRow.id"))
        assertTrue(drain.contains("_promptQueue.update { current -> current.filterNot { it.id in queuedIds } }"))
        val finish = vm.substringAfter("private fun updateAssistantMessage(").substringBefore("internal fun effectiveContent")
        assertTrue(finish.contains("_messages.updateItems { m ->"))
        assertFalse(finish.contains("_messages.value = updated"))
    }
    @Test fun `live browser preview is a scoped subscription, not a self-owned loop`() {
        // [T-android-browser-preview-live] The producer moved into
        // BrowserTabPool.livePreview (shared, event-driven, cancellable); the
        // composable must only lease it. A `while (true)` capture loop here is
        // what made the thumbnail trail the page by one action and starve other
        // surfaces on the global capture mutex.
        val preview = ProductionSources.read("ui/chat/ChatMiscViews.kt")
            .substringAfter("internal fun rememberBrowserLiveSnapshot(")
            .substringBefore("internal fun ResumeBanner")
        assertTrue("leases the shared engine", preview.contains("tabPool.livePreview"))
        assertTrue("keeps the engine in step with the block", preview.contains("SideEffect { lease.live = isLive }"))
        assertTrue("closing the surface cancels capture", preview.contains("onDispose { lease.close() }"))
        assertTrue("background is forwarded", preview.contains("preview.setForeground(false)"))
        assertTrue("reads the shared frame", preview.contains("preview.frame.collectAsState()"))
        assertFalse("no capture loop in composition", preview.contains("captureLiveSnapshot()"))
        assertFalse("no unbounded delay loop in composition", preview.contains("while (true)"))
    }

    @Test fun `the queue drain claims the stream flag with a CAS`() {
        // [T-queue-atomic-claim] `resumeQueueAfterCancel` suspends before it
        // claims (200 ms delay, provider resolution, OAuth refresh,
        // buildSystemPrompt). The entry guard's `_isStreaming` read is not a
        // claim: a plain `_isStreaming.value = true` here let a concurrent send
        // — which claims with its own compareAndSet — run a second agent loop
        // over the same session and the same queued batch (duplicated replies,
        // a prompt delivered twice). The loser must stand down and leave the
        // queue to the winner's drain.
        val resume = vm.substringAfter("private fun resumeQueueAfterCancel(")
            .substringBefore("private fun handleUserCancelledCleanup(")
        assertTrue("sanity: this is the drain entry point", resume.contains("if (_promptQueue.value.isEmpty())"))
        assertTrue("the claim must be a CAS", resume.contains("if (!_isStreaming.compareAndSet(false, true)) {"))
        assertFalse("a plain write is not a claim", resume.contains("_isStreaming.value = true"))
        assertTrue("the loser stands down", resume.contains("return@launch"))
        assertTrue("one consumer at a time", resume.contains("queueConsumerKick?.isActive == true"))
        // Every turn tail must hand a still-pending queue on, or a prompt
        // accepted while the loop was tearing down has no consumer at all.
        for (reason in listOf("\"rerun-epilogue\"", "\"send-epilogue\"", "\"retry-epilogue\"")) {
            assertTrue("missing epilogue consumer $reason", vm.contains("ensureQueueConsumer($reason)"))
        }
        assertEquals(
            "both queue drain entry points (Stop-resume and Continue) kick one",
            2,
            Regex("ensureQueueConsumer\\(\"resume-epilogue\"\\)").findAll(vm).count(),
        )
    }
}
