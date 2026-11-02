package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-gpu-capture] Which captures go through the GPU host, and
 * the safeguards that keep it from costing the UI anything.
 *
 * The host itself needs a real display and GPU, so its behaviour is verified
 * on device (WebGL / three.js / ordinary pages, foreground / background /
 * screen off); this pins the decisions a refactor could silently undo.
 */
class HeadlessGpuCaptureWiringTest {

    private val manager = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt").readText()
    private val host = File("src/main/java/com/openminis/app/browser/HeadlessRenderHost.kt").readText()

    @Test
    fun `agent screenshots and live preview refresh GPU while full-page stays bounded`() {
        assertTrue("viewport screenshot", manager.contains("captureWebViewBitmap(gpu = !didStretch)"))
        assertTrue("auto snapshot after an action", manager.contains("captureWebViewBitmap(gpu = true) ?: return result"))
        // Software draw alone can return a stale detached Chromium frame.
        // Live capture must use the bounded, serialised GPU refresh path too,
        // and must report which path produced the pixels so the preview can
        // tell "current page" from "last composited frame".
        assertTrue(manager.contains("suspend fun captureLiveFrame(): BrowserLiveFrame?"))
        assertTrue(
            "live frames are bounded in memory",
            manager.contains("private const val PREVIEW_MAX_EDGE_PX = 720"),
        )
        assertTrue(
            "a GPU miss falls through to the software draw, and the path is reported",
            manager.contains("HeadlessRenderHost.capture(webView, w, h)?.let { frame ->") &&
                manager.contains("onPath(LiveFramePath.GPU)") &&
                manager.contains("if (webView.isAttachedToWindow) LiveFramePath.SOFTWARE_ATTACHED else LiveFramePath.SOFTWARE_DETACHED"),
        )
        assertTrue(
            "an attached view draws live pixels, a detached one does not",
            File("src/main/java/com/openminis/app/browser/BrowserLiveFrame.kt")
                .readText().contains("val trustworthy: Boolean get() = path != LiveFramePath.SOFTWARE_DETACHED"),
        )
    }

    @Test
    fun `a WebView on screen in the browser sheet is never taken`() {
        assertTrue(host.contains("if (webView.parent != null || webView.isAttachedToWindow) return null"))
        assertTrue("sheet opening mid-capture", host.contains("if (webView.parent !== host)"))
    }

    @Test
    fun `the host is private, unfocusable, bounded and released when idle`() {
        assertTrue(host.contains("DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY"))
        assertTrue("no public flag", !host.contains("VIRTUAL_DISPLAY_FLAG_PUBLIC"))
        assertTrue("IME must never rise on the real screen", host.contains("FLAG_NOT_FOCUSABLE"))
        assertTrue(host.contains("const val IDLE_RELEASE_MS = 15_000L"))
        assertTrue(host.contains("main.postDelayed(releaseTask, IDLE_RELEASE_MS)"))
        assertTrue(host.contains("w > MAX_EDGE_PX || h > MAX_EDGE_PX"))
        // [T-android-browser-preview-live] Degrade, don't die: a kill switch
        // turned one background trip into a permanently frozen preview.
        assertTrue("bounded cooldown, not a permanent disable", host.contains("retryGate.recordFailure(reason)"))
        assertTrue("success clears the ladder", host.contains("retryGate.recordSuccess()"))
        assertTrue("the path is always retried later", host.contains("if (!retryGate.allow()) return null"))
        assertTrue("no permanent kill switch", !host.contains("GPU capture disabled for this process"))
        assertEquals("pixel copy runs off the main thread", 1,
            Regex("""withContext\(Dispatchers\.Default\) \{ toBitmap""").findAll(host).count())
    }
}
