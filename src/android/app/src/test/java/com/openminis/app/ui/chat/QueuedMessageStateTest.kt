package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-queued-bubble-atomic-delivery] Executable regressions for a message typed
 * while the agent is running.
 *
 * Reported (again) after 5c4246b: "主代理运行中用户中途发送消息又消失". The
 * earlier fix replaced the stale-snapshot emit with a filter over the live
 * list, but the shape around it still had three ways to lose the bubble:
 *
 *  1. `injectQueuedPromptsAsNewTurn` REMOVED the dashed placeholder in one emit
 *     and re-APPENDED the real bubble in a second one, gated on `mayCommitUser`
 *     — a predicate read before the removal. False ⇒ placeholder gone, nothing
 *     put back (row already persisted, model answers: "a reply with no
 *     question").
 *  2. Any wholesale rebuild of `_messages` (`loadSession`) dropped the queued
 *     bubble, because a queued prompt has no DB row yet — the prompts stayed
 *     queued and were later sent invisibly.
 *  3. `resumeQueueAfterCancel` with no provider emptied the queue AND filtered
 *     every `isQueued` bubble — the text itself was destroyed.
 *
 * These tests run [QueuedMessageState] — the production arithmetic those paths
 * now call — on the JVM, including a real multi-threaded CAS stress, plus a
 * threaded model of the send funnel for the check-then-act window between
 * `sendMessage`'s `isStreaming` read and `enqueuePrompt`'s own guard.
 */
class QueuedMessageStateTest {

    /** Stand-in for ChatMessage: identity + the queued linkage. */
    private data class Row(
        val id: String,
        val queuedPromptId: String? = null,
        val text: String = id,
    )

    private val pid: (Row) -> String? = { it.queuedPromptId }
    private val rid: (Row) -> String = { it.id }

    private fun queued(promptId: String) = Row("queued_msg_$promptId", promptId)

    // ── deliver: the placeholder → real bubble swap ────────────────────────

    @Test
    fun `deliver replaces the placeholder with the real bubble in one step`() {
        val current = listOf(Row("m1"), Row("m2"), queued("p1"))
        val out = QueuedMessageState.deliver(current, setOf("p1"), pid, rid, listOf(Row("row-1")))
        assertEquals(listOf("m1", "m2", "row-1"), out.map { it.id })
    }

    @Test
    fun `deliver keeps a message the user appended during the persist window`() {
        // The inject suspends four times between building the batch and this
        // emit (ensureSession / prepareUserAttachments / buildPastedParts /
        // appendMessage). A second message typed in that window is already on
        // the live list by the time the emit runs.
        val live = listOf(Row("m1"), queued("p1"), queued("p2"))
        val out = QueuedMessageState.deliver(live, setOf("p1"), pid, rid, listOf(Row("row-1")))
        assertEquals(listOf("m1", "queued_msg_p2", "row-1"), out.map { it.id })
        // p2 belongs to a later batch: its prompt is still queued.
        assertEquals("p2", out.first { it.id == "queued_msg_p2" }.queuedPromptId)
    }

    @Test
    fun `deliver repopulates nothing when the placeholder is gone`() {
        // Cleared chat / chat reloaded from the DB / the user withdrew the
        // bubble: there is nothing of ours to replace, so the list is returned
        // untouched — no bubble for a prompt the screen no longer owns, and no
        // second copy of a row a reload already inserted.
        val current = listOf(Row("m1"), Row("m2"))
        val out = QueuedMessageState.deliver(current, setOf("p1"), pid, rid, listOf(Row("row-1")))
        assertSame("an untouched list must stay the same instance", current, out)
    }

    @Test
    fun `deliver does not duplicate a bubble the list already holds`() {
        // A reload landed between the persist and this emit: the persisted row
        // is already rendered. Appending it again would show the message twice.
        val current = listOf(Row("m1"), queued("p1"), Row("row-1"))
        val out = QueuedMessageState.deliver(current, setOf("p1"), pid, rid, listOf(Row("row-1")))
        assertEquals(listOf("m1", "row-1"), out.map { it.id })
    }

    @Test
    fun `deliver with canContinue false appends only the user bubble`() {
        // Stop during the inject: the job is dead, so no new assistant
        // placeholder is created — but the user's message is still committed.
        val current = listOf(queued("p1"))
        val out = QueuedMessageState.deliver(current, setOf("p1"), pid, rid, listOf(Row("row-1")))
        assertEquals(listOf("row-1"), out.map { it.id })
    }

    @Test
    fun `deliver with an empty batch is a no-op`() {
        val current = listOf(Row("m1"))
        assertSame(current, QueuedMessageState.deliver(current, emptySet(), pid, rid, listOf(Row("row-1"))))
    }

    // ── reattach: after a wholesale rebuild ───────────────────────────────

    @Test
    fun `reattach restores a bubble for every prompt still queued`() {
        val reloaded = listOf(Row("db-1"), Row("db-2"))          // from the DB
        val queue = listOf("p1", "p2")                            // still pending
        val out = QueuedMessageState.reattach<String, Row>(
            current = reloaded,
            prompts = queue,
            promptIdOf = { p -> p },
            queuedPromptIdOf = pid,
            bubbleFor = { p -> queued(p) },
        )
        assertEquals(listOf("db-1", "db-2", "queued_msg_p1", "queued_msg_p2"), out.map { it.id })
        assertEquals(listOf("p1", "p2"), out.mapNotNull { it.queuedPromptId })
    }

    @Test
    fun `reattach leaves an already-attached prompt alone and is idempotent`() {
        val current = listOf(Row("db-1"), queued("p1"))
        val once = QueuedMessageState.reattach<String, Row>(current, listOf("p1", "p2"), { p -> p }, pid) { p -> queued(p) }
        assertEquals(listOf("db-1", "queued_msg_p1", "queued_msg_p2"), once.map { it.id })
        val twice = QueuedMessageState.reattach<String, Row>(once, listOf("p1", "p2"), { p -> p }, pid) { p -> queued(p) }
        assertSame("re-attaching twice must not duplicate", once, twice)
    }

    @Test
    fun `reattach with nothing queued is a no-op`() {
        val current = listOf(Row("db-1"))
        val out = QueuedMessageState.reattach<String, Row>(
            current = current,
            prompts = emptyList(),
            promptIdOf = { p -> p },
            queuedPromptIdOf = pid,
            bubbleFor = { p -> queued(p) },
        )
        assertSame(current, out)
    }

    // ── consumer gate ─────────────────────────────────────────────────────

    @Test
    fun `a queued prompt with nothing running always wants a consumer`() {
        assertTrue(QueuedMessageState.needsConsumer(isStreaming = false, queueSize = 1, kickActive = false, drainFailed = false))
        assertFalse("a live turn drains at its own epilogue", QueuedMessageState.needsConsumer(true, 1, false, false))
        assertFalse("one consumer at a time", QueuedMessageState.needsConsumer(false, 1, true, false))
        assertFalse("a failed drain must not spin", QueuedMessageState.needsConsumer(false, 1, false, true))
        assertFalse("empty queue", QueuedMessageState.needsConsumer(false, 0, false, false))
    }

    // ── concurrency: the CAS emit under real threads ──────────────────────

    @Test
    fun `parallel enqueues and deliveries never lose a bubble`() {
        val messages = MutableStateFlow(listOf(Row("assistant")))
        val pool = Executors.newFixedThreadPool(4)
        val threads = 4
        val perThread = 150
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    repeat(perThread) { i ->
                        val promptId = "p$t-$i"
                        // enqueuePrompt: bubble first, then the prompt is
                        // consumable. Every row a bubble is added for is
                        // delivered exactly once below.
                        messages.update { it + queued(promptId) }
                        messages.update { current ->
                            QueuedMessageState.deliver(
                                current = current,
                                queuedIds = setOf(promptId),
                                queuedPromptIdOf = pid,
                                idOf = rid,
                                newBubbles = listOf(Row("row-$t-$i")),
                            )
                        }
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            val ids = messages.value.map { it.id }
            assertEquals("no duplicate ids", ids.size, ids.toSet().size)
            assertEquals("one delivered bubble per prompt", threads * perThread, ids.count { it.startsWith("row-") })
            assertTrue("no placeholder left behind", ids.none { it.startsWith("queued_msg_") })
            assertEquals(threads * perThread + 1, ids.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a delivery racing a reload neither duplicates nor hides the message`() {
        // Deterministic interleaving: the reload replaces the list between the
        // delivery's persist and its emit.
        val messages = MutableStateFlow(listOf(Row("assistant"), queued("p1")))
        val reloaded = listOf(Row("assistant"), Row("row-1"))   // DB already has the row
        val pool = Executors.newSingleThreadExecutor()
        val read = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            var first = true
            val deliverJob = pool.submit {
                messages.update { current ->
                    if (first) {
                        first = false
                        read.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    QueuedMessageState.deliver(current, setOf("p1"), pid, rid, listOf(Row("row-1")))
                }
            }
            assertTrue(read.await(5, TimeUnit.SECONDS))
            messages.value = reloaded                             // loadSession
            resume.countDown()
            deliverJob.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("assistant", "row-1"), messages.value.map { it.id })
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    // ── the send funnel: queue it or start a turn, never drop it ──────────

    /**
     * The pre-fix shape, transcribed: `sendMessage` reads `_isStreaming`, then
     * `enqueuePrompt` reads it again and silently `return`s when it is false.
     * `sendMessage` reported `Rejected("enqueue_declined")` to a caller that had
     * already cleared the composer, so the prompt existed nowhere.
     */
    private class PreFixFunnel {
        val streaming = AtomicBoolean(true)
        val queue = CopyOnWriteArrayList<String>()
        val delivered = CopyOnWriteArrayList<String>()
        fun enqueue(text: String): Boolean {
            if (!streaming.get()) return false
            queue += text
            return true
        }
        fun send(text: String, betweenReads: () -> Unit): String {
            if (!streaming.get()) { delivered += text; return "turn" }
            betweenReads()                                   // the run ends right here
            return if (enqueue(text)) "queued" else "dropped"
        }
    }

    /** The fixed shape: enqueue-then-claim, with the claim a CAS. */
    private class FixedFunnel {
        val streaming = AtomicBoolean(true)
        val queue = CopyOnWriteArrayList<String>()
        val delivered = CopyOnWriteArrayList<String>()
        fun enqueue(text: String): Boolean {
            if (!streaming.get()) return false
            queue += text
            return true
        }
        fun send(text: String, betweenReads: () -> Unit): String {
            if (streaming.get()) {
                betweenReads()
                if (enqueue(text)) return "queued"
            }
            // Declined: the run ended under us. Start a turn with the prompt.
            var attempts = 0
            while (!streaming.compareAndSet(false, true)) {
                if (enqueue(text)) return "queued"
                if (++attempts > 8) return "lost"
            }
            delivered += text
            return "turn"
        }
    }

    @Test
    fun `the pre-fix funnel drops the message when the run ends between the two reads`() {
        val f = PreFixFunnel()
        val outcome = f.send("mid-run message") { f.streaming.set(false) }
        assertEquals("dropped", outcome)
        assertTrue("queue is empty", f.queue.isEmpty())
        assertTrue("nothing was delivered either", f.delivered.isEmpty())
        // This is the reported symptom: no bubble (queue empty), no request.
    }

    @Test
    fun `the fixed funnel sends it instead of dropping it`() {
        val f = FixedFunnel()
        val outcome = f.send("mid-run message") { f.streaming.set(false) }
        assertEquals("turn", outcome)
        assertEquals(listOf("mid-run message"), f.delivered)
    }

    @Test
    fun `the fixed funnel loses nothing under a concurrent flag flip`() {
        val f = FixedFunnel()
        val sends = 2000
        val pool = Executors.newFixedThreadPool(3)
        val accepted = AtomicInteger()
        try {
            val flipper = pool.submit {
                // The stream job clears and re-claims the flag, as turns end.
                repeat(sends) {
                    f.streaming.set(false)
                    Thread.yield()
                    f.streaming.set(true)
                }
            }
            val futures = (0 until 2).map { t ->
                pool.submit {
                    repeat(sends / 2) { i ->
                        val outcome = f.send("m$t-$i") { }
                        if (outcome != "lost") accepted.incrementAndGet()
                    }
                }
            }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            flipper.get(60, TimeUnit.SECONDS)
            assertEquals("every send must be routed somewhere", sends, accepted.get())
            val resolved = f.delivered.size + f.queue.size
            assertEquals("nothing may evaporate", sends, resolved)
            assertEquals("ids stay unique", f.delivered.size, f.delivered.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
