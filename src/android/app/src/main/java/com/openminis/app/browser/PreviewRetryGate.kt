package com.openminis.app.browser

/**
 * [T-android-browser-preview-live] Time-based retry gate for the offscreen GPU
 * capture path.
 *
 * Why this replaces a permanent kill switch: [HeadlessRenderHost] used to set
 * `disabled` after three failed host setups — for the rest of the process.
 * Every failure it counted can be transient by nature:
 *
 *  - `Presentation.show()` in the background throws `BadTokenException`, and
 *    the live preview keeps ticking while the user switches apps mid-task;
 *  - the WebView can be re-parented into the browser sheet in the middle of a
 *    capture (explicitly detected and treated as a miss);
 *  - a virtual display can be refused while the device is in a low-power or
 *    split-screen state.
 *
 * Three of those in a row permanently downgraded every later capture to the
 * software draw of a *detached* WebView — which returns Chromium's last
 * composited frame, i.e. exactly the frozen thumbnail the user reports. A
 * one-second background trip could brick the preview until the app restarted.
 *
 * The gate keeps the cost control the kill switch was for (back off, up to
 * [MAX_COOLDOWN_MS]) and drops the permanence: the next attempt after the
 * cooldown is always allowed, and any success resets the ladder.
 */
internal class PreviewRetryGate(
    private val now: () -> Long,
    private val cooldownsMs: List<Long> = DEFAULT_COOLDOWNS_MS,
) {
    companion object {
        /**
         * Cooldown after the 1st, 2nd, 3rd… consecutive failure: two seconds,
         * then ten, thirty, and a minute from then on. Bounded so a genuinely
         * unsupported device does not pay a GPU host build on every preview
         * tick, but never stops trying — devices recover from all of the
         * transient causes above, and that recovery must not need a restart.
         */
        val DEFAULT_COOLDOWNS_MS = listOf(2_000L, 10_000L, 30_000L, 60_000L)
        const val MAX_COOLDOWN_MS = 60_000L
    }

    private var failures = 0
    private var nextAllowedAtMs = Long.MIN_VALUE

    var lastReason: String? = null
        private set

    val failureCount: Int get() = failures

    fun allow(nowMs: Long = now()): Boolean = nowMs >= nextAllowedAtMs

    fun cooldownRemainingMs(nowMs: Long = now()): Long = (nextAllowedAtMs - nowMs).coerceAtLeast(0L)

    fun recordSuccess() {
        failures = 0
        nextAllowedAtMs = Long.MIN_VALUE
        lastReason = null
    }

    fun recordFailure(reason: String, nowMs: Long = now()) {
        failures++
        lastReason = reason
        val cooldown = cooldownsMs.getOrElse(failures - 1) { cooldownsMs.lastOrNull() ?: 0L }
            .coerceIn(0L, MAX_COOLDOWN_MS)
        nextAllowedAtMs = nowMs + cooldown
    }
}
