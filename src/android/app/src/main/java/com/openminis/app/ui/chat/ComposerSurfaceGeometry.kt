package com.openminis.app.ui.chat

/**
 * [T-next-composer-surface] Pure geometry / inset math for the chat composer
 * (the bottom input card in [ChatScreen]).
 *
 * Eta-inspired 24dp rounded glass card, 10dp horizontal gutters and
 * 12dp bottom safe-area clearance.
 * A visible navigation bar contributes its inset exactly once. While the IME
 * is up, [ChatScreen]'s outer `imePadding()` already lifts the composer, so
 * only the 12dp clearance is added above the keyboard.
 */
internal object ComposerSurfaceGeometry {

    /** Uniform corner radius of the composer card, in dp. */
    const val CORNER_RADIUS_DP: Float = 24f

    /** Narrow, symmetric left/right gutters matching the glass composer. */
    const val SIDE_INSET_DP: Float = 10f

    /** Bottom clearance above the navigation safe area or the open keyboard. */
    const val BOTTOM_INSET_DP: Float = 12f

    /** Baseline editor height. The attachment menu is measured in its own overlay. */
    const val EDITOR_MIN_HEIGHT_DP: Float = 56f

    /**
     * Padding below the composer card.
     *
     * @param navigationBarBottomDp current bottom navigation-bar inset in dp
     *        (0 when immersive / hidden, 24-48 when the gesture or button bar
     *        is showing). Negative values are treated as 0.
     * @param imeVisible whether the soft keyboard currently occupies the
     *        bottom inset (the caller already applies `imePadding()`).
     */
    fun bottomPaddingDp(navigationBarBottomDp: Float, imeVisible: Boolean): Float {
        if (imeVisible) return BOTTOM_INSET_DP
        val navigation = navigationBarBottomDp.coerceAtLeast(0f)
        return navigation + BOTTOM_INSET_DP
    }
}
