package com.openminis.app.browser

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-preview-live] Executable lifecycle of the shared preview
 * engine: capture cadence, staleness, tab targeting, foreground handling and —
 * the part the old producer could not even express — cancellation.
 *
 * These run the production [BrowserLivePreview] with a fake capture function
 * and a real dispatcher. No WebView, no GPU and no pixels are involved, so
 * nothing here claims on-device rendering; it does claim that a state update
 * happens, that a failure is retried, and that closing the last subscription
 * stops all work.
 */
class BrowserLivePreviewTest {

    private val policy = BrowserPreviewPump.Policy(
        minIntervalMs = 20L,
        maxIntervalMs = 200L,
        followUpDelaysMs = listOf(10L, 40L),
        minSpacingMs = 5L,
        idleIntervalMs = 50L,
    )

    private fun makePreview(
        scope: CoroutineScope,
        holdAfterActivityMs: Long = 20_000L,
        target: () -> PreviewTarget? = { PreviewTarget(7, "sig") },
        capture: suspend (Int) -> LiveCaptureResult<String>,
    ) = BrowserLivePreview(
        scope = scope,
        targetProvider = target,
        capture = capture,
        policy = policy,
        holdAfterActivityMs = holdAfterActivityMs,
        now = { System.nanoTime() / 1_000_000L },
    )

    private suspend fun await(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun `a live block publishes a frame for the agent's tab`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) { tab ->
            LiveCaptureResult("frame-${tab}-${count.incrementAndGet()}", true)
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value?.hasImage == true }
            val frame = preview.frame.value
            assertNotNull(frame)
            assertEquals("the agent's tab is previewed", 7, frame!!.tabId)
            assertFalse("a real capture is not stale", frame.stale)
            assertTrue("session is active while a block is live", preview.sessionActive.value)
            assertTrue(preview.isRunning)

            // Frames keep coming without any further event: the page is live.
            val first = count.get()
            await { count.get() > first }
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `closing the last lease cancels the loop and no further captures happen`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        // count is incremented inside the fake capture, so it tracks attempts.
        val preview = makePreview(scope) { count.incrementAndGet(); LiveCaptureResult("f", true) }
        val lease = preview.acquire()
        lease.live = true
        await { count.get() >= 2 }
        lease.close()
        assertFalse("loop must stop with the last subscriber", preview.isRunning)
        assertFalse("…and the session must go cold", preview.sessionActive.value)
        val settled = count.get()
        delay(250)
        assertEquals("no capture may run after the last lease closed", settled, count.get())
        scope.cancel()
    }

    @Test
    fun `a completed block keeps refreshing while the browser session is warm`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        // Short hold window: the engine must go cold on its own, then revive on
        // the next action. This is the freeze the user sees between two tool
        // calls — the old producer was cancelled with its block.
        val preview = makePreview(scope, holdAfterActivityMs = 80L) {
            LiveCaptureResult("f${count.incrementAndGet()}", true)
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value != null }
            lease.live = false // the tool call returned; the model is thinking
            await(2_000L) { !preview.sessionActive.value }
            val idle = count.get()
            delay(120)
            assertEquals("an idle browser session must stop capturing", idle, count.get())

            preview.onActivity() // the next action finished
            await(2_000L) { preview.sessionActive.value }
            await(2_000L) { count.get() > idle }
            assertTrue(count.get() > idle)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `capture failures keep the last frame, back off, then recover`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) {
            when (count.incrementAndGet()) {
                1 -> LiveCaptureResult("good", true)
                2, 3 -> LiveCaptureResult(null, false, "gpu miss")
                else -> LiveCaptureResult("recovered", true)
            }
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value?.image == "good" }
            await { preview.frame.value?.stale == true }
            assertEquals("the last good pixels survive a failure", "good", preview.frame.value?.image)
            await(10_000L) { preview.frame.value?.stale == false && preview.frame.value?.image == "recovered" }
            assertTrue("attempts continued past the failures", count.get() >= 4)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `a throwing capture does not kill the loop`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) {
            if (count.incrementAndGet() == 1) error("webview destroyed")
            LiveCaptureResult("ok", true)
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await(10_000L) { preview.frame.value?.image == "ok" }
            assertTrue(preview.isRunning)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `an untrusted frame is shown but never presented as live`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) {
            if (count.incrementAndGet() == 1) {
                LiveCaptureResult("detached-draw", false, "detached software draw")
            } else {
                LiveCaptureResult("fresh", true)
            }
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value?.image == "detached-draw" }
            assertTrue("a detached software draw is stale by definition", preview.frame.value?.stale == true)
            assertEquals("detached software draw", preview.frame.value?.reason)
            await(10_000L) { preview.frame.value?.image == "fresh" }
            assertFalse(preview.frame.value?.stale == true)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `background stops capture and returning to the foreground refreshes`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) { LiveCaptureResult("f${count.incrementAndGet()}", true) }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value != null }
            preview.setForeground(false)
            assertFalse(preview.sessionActive.value)
            delay(120) // let an in-flight capture finish
            val paused = count.get()
            delay(200)
            assertEquals("no captures while backgrounded", paused, count.get())

            preview.setForeground(true)
            await(2_000L) { count.get() > paused }
            assertTrue("foreground re-captures immediately", count.get() > paused)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `a tab switch republishes the new tab and never the old frame`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val tabs = java.util.concurrent.atomic.AtomicInteger(1)
        val preview = makePreview(scope, target = { PreviewTarget(tabs.get(), "sig-${tabs.get()}") }) { tab ->
            LiveCaptureResult("page-$tab", true)
        }
        val lease = preview.acquire()
        lease.live = true
        try {
            await { preview.frame.value?.tabId == 1 }
            tabs.set(2)
            preview.onActivity()
            await(5_000L) { preview.frame.value?.tabId == 2 }
            assertEquals("page-2", preview.frame.value?.image)
        } finally {
            lease.close()
            scope.cancel()
        }
    }

    @Test
    fun `no subscriber means no capture at all`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val count = AtomicInteger()
        val preview = makePreview(scope) { count.incrementAndGet(); LiveCaptureResult("f", true) }
        preview.onActivity()
        delay(200)
        assertEquals("an unobserved preview must not capture", 0, count.get())
        assertFalse(preview.isRunning)
        scope.cancel()
    }
}
