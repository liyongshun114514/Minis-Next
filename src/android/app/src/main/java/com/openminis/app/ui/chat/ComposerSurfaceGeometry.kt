package com.openminis.app.ui.chat

/**
 * [T-next-composer-surface] Pure geometry / inset math for the chat composer
 * (the bottom input card in [ChatScreen]).
 *
 * The composer used to sit 8dp above the window bottom when the system
 * gesture bar was hidden (immersive), which made the card look glued to the
 * screen edge, and its 20dp corners looked tighter than the reference design.
 * Two rules live here so they can be unit-tested without an Android runtime:
 *
 *  - Corner radius: one uniform radius for all four corners (40dp). The card
 *    is roughly 100-130dp tall, so this stays a rounded rectangle rather than
 *    a capsule, and it matches the wide bottom arcs in the reference image.
 *  - Insets: the card keeps an equal 12dp gap on the left, right and bottom
 *    edges of its safe area. A visible navigation bar contributes its own
 *    inset first (nav bar + 12dp) so the card never overlaps it; no extra
 *    minimum is added on top. While the IME is up, [ChatScreen]'s outer
 *    `imePadding()` already lifts the composer above the keyboard, so only
 *    the same 12dp gap is added — no navigation block above the keyboard.
 */
internal object ComposerSurfaceGeometry {

    /** Uniform corner radius of the composer card, in dp. */
    const val CORNER_RADIUS_DP: Float = 40f

    /**
     * Gap between the composer card and the safe-area edges: left, right and
     * bottom (and the IME when it is open). One value keeps the three sides
     * visually identical.
     */
    const val SIDE_INSET_DP: Float = 12f

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
        if (imeVisible) return SIDE_INSET_DP
        val navigation = navigationBarBottomDp.coerceAtLeast(0f)
        return navigation + SIDE_INSET_DP
    }
}
