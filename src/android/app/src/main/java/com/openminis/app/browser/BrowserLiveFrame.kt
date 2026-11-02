package com.openminis.app.browser

import android.graphics.Bitmap

/**
 * [T-android-browser-preview-live] How a live preview frame was produced.
 *
 * The distinction is load-bearing, not diagnostic: see
 * [BrowserUseManager.captureLiveFrame].
 */
internal enum class LiveFramePath {
    /** Composited by the GPU host after re-attaching the WebView: current. */
    GPU,

    /** WebView is on screen (browser sheet open) and drew itself: current. */
    SOFTWARE_ATTACHED,

    /**
     * WebView is detached and the GPU host did not produce a frame, so this is
     * a software replay of its last composited content — possibly minutes old.
     */
    SOFTWARE_DETACHED,
}

/** One captured preview frame plus whether it can stand for the current page. */
internal data class BrowserLiveFrame(
    val bitmap: Bitmap,
    val path: LiveFramePath,
) {
    val trustworthy: Boolean get() = path != LiveFramePath.SOFTWARE_DETACHED
    val reason: String?
        get() = when (path) {
            LiveFramePath.GPU -> null
            LiveFramePath.SOFTWARE_ATTACHED -> null
            LiveFramePath.SOFTWARE_DETACHED -> "detached software draw (last composited frame)"
        }
}
