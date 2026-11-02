package com.openminis.app.browser

/**
 * [T-android-browser-preview-live] Which tab the live preview should show, plus
 * a cheap signature of that tab's page state.
 *
 * The signature is what makes the preview *live* rather than interval-driven.
 * It is built from the tab's own StateFlows (URL, title, loading flag), so a
 * navigation, a finished load or a title change — the moments a page visibly
 * changes — schedule an immediate capture instead of waiting out the poll
 * interval. `tabId` is in its own field because a DIFFERENT tab must never
 * reuse the previous tab's pixels (see [BrowserPreviewPump.onTarget]).
 */
internal data class PreviewTarget(
    val tabId: Int,
    val signature: String,
)

/**
 * One published preview frame.
 *
 * [stale] is deliberately NOT "the image is old"; it means "the last capture
 * attempt did not produce a trustworthy frame, so this image is older than the
 * page". Before this type existed the UI could not tell a fresh frame from a
 * capture that had been silently falling back for minutes — the frozen
 * thumbnail looked exactly like a working one.
 */
internal data class BrowserPreviewFrame<T : Any>(
    val tabId: Int,
    val image: T?,
    val capturedAtMs: Long,
    val stale: Boolean,
    val reason: String?,
) {
    val hasImage: Boolean get() = image != null
}

/**
 * [T-android-browser-preview-live] The decision state behind the chat's live
 * browser preview: when to capture, what to keep when a capture fails, and
 * what to throw away when the tab changes.
 *
 * Pure Kotlin (no Android, no coroutines) so the timing/staleness rules are
 * unit-testable: the previous implementation was a `while(true) { capture();
 * delay(3s) }` loop inside a composable, where "did the frame update?" could
 * only be answered on a device.
 *
 * Three rules it enforces, each one a fixed defect:
 *
 *  1. **Capture now, and again after the page settles.** A browser action can
 *     finish in 150 ms. A capture-first/3 s-delay loop therefore takes exactly
 *     ONE frame — the state *before* the click or navigation — and is
 *     cancelled before the follow-up. The preview then trails the page by one
 *     action forever, which is what users report as "frozen". [onActivity]
 *     schedules the leading capture plus bounded follow-ups once the renderer
 *     has caught up.
 *  2. **A failure is not the end.** Failures keep the last image, mark it
 *     stale and back off; they never stop the pump and never disable a code
 *     path. Recovery is automatic on the next successful frame.
 *  3. **Never show another tab's page.** A target change drops the image
 *     before the new tab's first frame lands, instead of presenting a picture
 *     of a page that is not the one being previewed.
 */
internal class BrowserPreviewPump<T : Any>(
    private val policy: Policy = Policy(),
    private val now: () -> Long,
) {
    /**
     * Timing policy. Defaults are tuned for a 100×65 dp chat thumbnail: ~1 s
     * while the page is moving, exponential backoff up to [maxIntervalMs] when
     * captures keep failing, and two follow-up captures after a known page
     * change (Chromium presents a re-attached page over several frames, so a
     * single immediate capture can catch a half-rasterised frame).
     */
    data class Policy(
        val minIntervalMs: Long = 1_200L,
        val maxIntervalMs: Long = 12_000L,
        /** Absolute offsets after a page change at which to capture again. */
        val followUpDelaysMs: List<Long> = listOf(250L, 900L),
        /** Floor between two attempts, so follow-ups cannot busy-loop a renderer. */
        val minSpacingMs: Long = 120L,
        /** Attempts allowed while the tab is unchanged and the page is idle. */
        val idleIntervalMs: Long = 3_000L,
    )

    var frame: BrowserPreviewFrame<T>? = null
        private set

    var target: PreviewTarget? = null
        private set

    var failureCount: Int = 0
        private set

    private var paused = false
    private var dueAtMs = Long.MAX_VALUE
    private var lastAttemptAtMs = 0L
    private var hasAttempted = false
    private val followUps = ArrayList<Long>()

    /**
     * The earliest moment an attempt would be allowed, ignoring [isDue]'s pause.
     *
     * `dueAtMs` and the follow-up queue are two independent schedules (the
     * steady cadence and the post-action re-renders), so the earlier of the two
     * wins. Taking the follow-up queue alone would delay a leading capture that
     * is already due.
     */
    fun nextAttemptAtMs(): Long {
        val followUp = followUps.minOrNull() ?: Long.MAX_VALUE
        return minOf(dueAtMs, followUp)
    }

    /**
     * Adopt the current target. Returns true when the target changed enough to
     * require an immediate capture (new tab, new page signature).
     */
    fun onTarget(next: PreviewTarget?): Boolean {
        val previous = target
        if (next == null) {
            target = null
            if (previous != null) {
                frame = null
                followUps.clear()
                dueAtMs = Long.MAX_VALUE
            }
            return false
        }
        if (previous?.tabId != next.tabId) {
            // Different tab: its predecessor's pixels describe another page.
            frame = null
            failureCount = 0
            followUps.clear()
            target = next
            dueAtMs = now()
            return true
        }
        target = next
        if (previous.signature != next.signature) {
            // Same tab, new content (URL / title / loading flag moved).
            scheduleNow()
            return true
        }
        return false
    }

    /**
     * A browser action just finished, or a tab list/selection change happened:
     * the page may have moved, so capture now and re-check once the renderer
     * has settled. Never schedules more than one outstanding run of follow-ups.
     */
    fun onActivity() {
        scheduleNow()
    }

    private fun scheduleNow() {
        val t = now()
        dueAtMs = t
        followUps.clear()
        policy.followUpDelaysMs.forEach { followUps.add(t + it) }
    }

    fun onPaused() {
        paused = true
    }

    /** Coming back to the foreground always re-captures: the page may have moved. */
    fun onResumed() {
        if (!paused) {
            scheduleNow()
            return
        }
        paused = false
        failureCount = 0
        scheduleNow()
    }

    fun isPaused(): Boolean = paused

    /** True when an attempt should run at [nowMs]. */
    fun isDue(nowMs: Long = now()): Boolean {
        if (paused || target == null) return false
        if (hasAttempted && nowMs - lastAttemptAtMs < policy.minSpacingMs) return false
        return nowMs >= nextAttemptAtMs()
    }

    /** Milliseconds until [isDue] can become true; bounded by [maxIntervalMs]. */
    fun delayUntilDue(nowMs: Long = now()): Long {
        if (paused || target == null) return policy.maxIntervalMs
        val spacing = if (hasAttempted) (lastAttemptAtMs + policy.minSpacingMs) - nowMs else 0L
        val due = nextAttemptAtMs() - nowMs
        return maxOf(due, spacing, 0L)
    }

    /**
     * Called immediately before a capture starts, so a slow capture cannot be
     * started twice and consumed follow-ups do not fire again.
     */
    fun beginAttempt(nowMs: Long = now()) {
        lastAttemptAtMs = nowMs
        hasAttempted = true
        // Consume only the follow-ups that have actually come due; a pending one
        // must survive this attempt, or the post-render capture it exists for is
        // lost (the leading capture is usually taken before the page is painted).
        while (followUps.isNotEmpty() && followUps.first() <= nowMs) followUps.removeAt(0)
        // Fallback cadence: if the caller never reports a result (cancelled
        // capture), the pump must not stall until the next external event.
        dueAtMs = nowMs + policy.maxIntervalMs
    }

    fun onCaptureSuccess(tabId: Int, image: T, nowMs: Long = now()) {
        failureCount = 0
        frame = BrowserPreviewFrame(tabId, image, nowMs, stale = false, reason = null)
        // A success must not swallow a follow-up that is still pending: the
        // leading capture after an action is typically taken before the
        // renderer has finished presenting the new page, and that follow-up is
        // the frame the user actually needs to see.
        val cadence = nowMs + policy.minIntervalMs
        val followUp = followUps.minOrNull()
        dueAtMs = if (followUp != null) minOf(followUp, cadence) else cadence
    }

    /**
     * A frame arrived but cannot be trusted to be current (the detached-WebView
     * software draw). Keep the pixels — they are still better than a blank
     * placeholder — but mark them stale and keep the backoff ladder, because a
     * "successful" untrusted capture must not look like a live preview.
     */
    fun onCapturePartial(tabId: Int, image: T, reason: String, nowMs: Long = now()) {
        failureCount++
        frame = BrowserPreviewFrame(tabId, image, nowMs, stale = true, reason = reason)
        // Pending follow-ups are dropped: they were scheduled to catch a
        // render that has just failed to produce a trustworthy frame, and the
        // backoff below is what the failing path needs.
        followUps.clear()
        dueAtMs = nowMs + backoffMs(failureCount)
    }

    /**
     * [reason] is surfaced in the frame so a stuck capture path is diagnosable
     * from the UI/logs instead of looking like a working preview.
     */
    fun onCaptureFailure(tabId: Int, reason: String, nowMs: Long = now()) {
        failureCount++
        val current = frame
        frame = if (current != null && current.tabId == tabId) {
            current.copy(stale = true, reason = reason)
        } else {
            BrowserPreviewFrame(tabId, null, nowMs, stale = true, reason = reason)
        }
        followUps.clear()
        dueAtMs = nowMs + backoffMs(failureCount)
    }

    /** Exponential backoff, capped, never "give up". */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return policy.minIntervalMs
        var interval = policy.minIntervalMs
        var remaining = failures
        while (remaining > 0 && interval < policy.maxIntervalMs) {
            interval *= 2
            remaining--
        }
        return interval.coerceAtMost(policy.maxIntervalMs)
    }
}
