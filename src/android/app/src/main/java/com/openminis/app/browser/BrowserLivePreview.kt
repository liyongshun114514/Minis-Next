package com.openminis.app.browser

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-browser-preview-live] One capture result, with the honesty bit the
 * preview was missing: whether the pixels can be trusted to be *current*.
 *
 * `image != null && !trustworthy` is the detached-WebView software draw — a
 * real picture of the page, but possibly a composited frame from minutes ago
 * (see [BrowserUseManager.captureLiveFrame]). It is worth showing, and it must
 * not be mistaken for a live frame.
 */
internal data class LiveCaptureResult<T : Any>(
    val image: T?,
    val trustworthy: Boolean,
    val reason: String? = null,
)

/**
 * [T-android-browser-preview-live] Pool-scoped live preview engine.
 *
 * Replaces the per-composable `while (true) { capture(); delay(3_000) }`
 * producer. Everything the old loop could not do:
 *
 *  - **One poller per browser pool.** Every surface that showed a thumbnail
 *    (the floating tool bar, the tool detail sheet, a sub agent's punched
 *    through tile) ran its own loop, and every capture serialises on one
 *    global mutex inside [HeadlessRenderHost]. Two visible surfaces could
 *    therefore starve each other for seconds at a time. Subscribers now share
 *    one loop and one frame.
 *  - **Event-driven, not interval-driven.** [onActivity] (browser action
 *    finished, tab created/closed/selected) plus [PreviewTarget.signature]
 *    changes (URL/title/loading) capture immediately and again after the
 *    renderer settles, so a 150 ms `click` still produces an up-to-date frame
 *    instead of the pre-click one.
 *  - **Session-scoped liveness.** The loop keeps capturing while a browser tool
 *    block is live AND for [holdAfterActivityMs] after the last action, then
 *    parks. A completed block therefore keeps a live-looking frame across the
 *    model's thinking gaps, and an idle chat pays nothing.
 *  - **Cancellation that actually stops work.** The loop is cancelled when the
 *    last subscriber leaves or the pool is torn down; nothing keeps capturing
 *    in the background, and [isRunning] / [sessionActive] are observable, which
 *    is what the unit tests drive.
 *  - **Foreground-aware.** [setForeground] parks the loop when the app is not
 *    visible (the old loop kept building GPU hosts — and burning the host's
 *    failure budget — while backgrounded) and forces a fresh capture on return.
 *
 * Threading: mutable counters are touched from the caller's thread (Main in
 * production, the test thread in tests) and the loop runs on [scope]; the
 * published state is a [StateFlow], so readers never see torn state.
 */
internal class BrowserLivePreview<T : Any>(
    private val scope: CoroutineScope,
    private val targetProvider: () -> PreviewTarget?,
    private val capture: suspend (tabId: Int) -> LiveCaptureResult<T>,
    private val policy: BrowserPreviewPump.Policy = BrowserPreviewPump.Policy(),
    private val holdAfterActivityMs: Long = DEFAULT_HOLD_AFTER_ACTIVITY_MS,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "BrowserLivePreview"

        /**
         * How long after the last browser action the preview keeps refreshing.
         * Long enough to cover the model's gap between two tool calls (a
         * navigation's late raster, a lazy image, a redirect), short enough
         * that an abandoned chat stops capturing on its own.
         */
        const val DEFAULT_HOLD_AFTER_ACTIVITY_MS = 45_000L

        /** A capture this slow is worth a log line: it is what a freeze feels like. */
        private const val SLOW_CAPTURE_WARN_MS = 2_500L

        /** Wake-up granularity while the loop is waiting for a due capture. */
        private const val MAX_WAIT_MS = 1_000L
        private const val MIN_WAIT_MS = 16L
    }

    private val pump = BrowserPreviewPump<T>(policy, now)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _frame = MutableStateFlow<BrowserPreviewFrame<T>?>(null)
    val frame: StateFlow<BrowserPreviewFrame<T>?> = _frame.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)

    /**
     * True while the engine is actually refreshing (a live block or a recent
     * action, foreground, at least one subscriber). The UI uses this to decide
     * whether the frame may stand in for the newest browser block; when the
     * browser session is over, the saved screenshot is the honest source.
     */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var job: Job? = null
    private var subscribers = 0
    private var liveSubscribers = 0
    private var foreground = true
    @Volatile
    private var lastActivityAtMs = Long.MIN_VALUE
    private var publishedFailureCount = -1

    val isRunning: Boolean get() = job?.isActive == true

    /** Current target tab and page signature, for diagnostics/tests. */
    val currentTarget: PreviewTarget? get() = pump.target

    /**
     * Hold a subscription for as long as a surface shows the preview. Closing
     * the last lease cancels the loop — the preview must not outlive the UI
     * that asked for it.
     */
    fun acquire(): Lease {
        subscribers++
        ensureLoop()
        wake.trySend(Unit)
        Log.i(TAG, "subscriber attached ($subscribers total)")
        return Lease()
    }

    /** A browser action finished / the tab set changed: capture now. */
    fun onActivity() {
        lastActivityAtMs = now()
        // Nobody is watching (no thumbnail on screen, no loop): record the
        // activity for the hold window and skip the dispatch. This keeps the
        // pool's action path free of coroutine/looper work on chats whose tool
        // preview is closed.
        if (subscribers == 0) {
            lastActivityAtMs = now()
            return
        }
        // Self-healing: if the loop ever ended unexpectedly (a capture path that
        // threw something unrecoverable), the next action restarts it instead of
        // leaving the preview silently dead.
        // Pool callbacks run on whatever dispatcher the action used (the pool
        // deliberately does not funnel them all through Main), while the pump's
        // state belongs to the loop. Hop onto the engine's scope so the only
        // mutator is the loop itself.
        scope.launch {
            if (subscribers == 0) return@launch
            ensureLoop()
            pump.onActivity()
            wake.trySend(Unit)
        }
    }

    /** App visibility. False parks the loop; true captures immediately. */
    fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        if (visible) pump.onResumed() else pump.onPaused()
        if (visible) ensureLoop()
        wake.trySend(Unit)
        publishFrame()
        if (!visible) publishActive(false)
        Log.i(TAG, "foreground=$visible")
    }

    /** Test/diagnostic hook: run at most one attempt if one is due. */
    suspend fun tickOnce() {
        val target = targetProvider()
        pump.onTarget(target)
        publishFrame()
        if (computeActive(now()) && pump.isDue(now())) attempt(target)
    }

    inner class Lease internal constructor() {
        /**
         * Whether a browser tool block is RUNNING for this surface. A live
         * block alone forces refreshing; without one the [holdAfterActivityMs]
         * window still keeps it refreshing after the action completed.
         */
        var live: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                if (value) liveSubscribers++ else liveSubscribers--
                wake.trySend(Unit)
            }

        private var closed = false

        fun close() {
            if (closed) return
            closed = true
            if (live) {
                live = false
            }
            subscribers = (subscribers - 1).coerceAtLeast(0)
            if (subscribers == 0) {
                publishActive(false)
                stopLoop("no subscribers")
            }
            Log.i(TAG, "subscriber detached ($subscribers left)")
        }
    }

    private fun computeActive(nowMs: Long): Boolean {
        if (!foreground || subscribers == 0) return false
        if (liveSubscribers > 0) return true
        if (lastActivityAtMs == Long.MIN_VALUE) return false
        return nowMs - lastActivityAtMs <= holdAfterActivityMs
    }

    private fun ensureLoop() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    private fun stopLoop(why: String) {
        val running = job
        job = null
        running?.cancel()
        Log.i(TAG, "loop stopped ($why)")
    }

    private suspend fun runLoop() {
        Log.i(TAG, "loop started")
        try {
            while (currentCoroutineContext().isActive) {
                val target = targetProvider()
                pump.onTarget(target)
                publishFrame()

                val nowMs = now()
                val active = computeActive(nowMs)
                publishActive(active)

                if (active && pump.isDue(nowMs)) {
                    attempt(target)
                    continue
                }

                if (!active) {
                    // Nothing to do until someone tells us otherwise. The last
                    // lease closing cancels this coroutine, so this is bounded
                    // by the UI's lifetime, not by a timer.
                    wake.receive()
                } else {
                    // Bounded wait: an event wakes us immediately, otherwise the
                    // beat re-evaluates `isDue` so a follow-up whose event was
                    // coalesced away still runs.
                    val wait = pump.delayUntilDue(nowMs).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The loop must not die on an unexpected error; individual capture
            // failures are already handled and retried.
            Log.e(TAG, "loop failed: ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            publishActive(false)
            Log.i(TAG, "loop exited")
        }
    }

    private suspend fun attempt(target: PreviewTarget?) {
        if (target == null) {
            pump.onTarget(null)
            publishFrame()
            return
        }
        pump.beginAttempt()
        val started = now()
        val result = try {
            capture(target.tabId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LiveCaptureResult<T>(null, false, "${failure.javaClass.simpleName}: ${failure.message}")
        }
        val tookMs = now() - started

        when {
            result.image != null && result.trustworthy ->
                pump.onCaptureSuccess(target.tabId, result.image, started)
            result.image != null ->
                pump.onCapturePartial(target.tabId, result.image, result.reason ?: "unverified frame", started)
            else ->
                pump.onCaptureFailure(target.tabId, result.reason ?: "no frame", started)
        }
        publishFrame()

        if (tookMs >= SLOW_CAPTURE_WARN_MS) {
            Log.w(TAG, "slow capture tab=${target.tabId} ${tookMs}ms (${result.reason ?: "ok"})")
        }
        val failures = pump.failureCount
        if (failures != publishedFailureCount) {
            publishedFailureCount = failures
            if (failures > 0) {
                Log.w(TAG, "capture failed x$failures (${result.reason}); retry in ${pump.backoffMs(failures)}ms")
            } else {
                Log.i(TAG, "capture recovered for tab ${target.tabId}")
            }
        }
    }

    private fun publishFrame() {
        _frame.value = pump.frame
    }

    private fun publishActive(active: Boolean) {
        if (_sessionActive.value != active) _sessionActive.value = active
    }
}
