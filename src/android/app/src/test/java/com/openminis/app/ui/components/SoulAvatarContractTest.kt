package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-soul-avatar-circle] The Soul avatar is a CIRCLE the image FILLS — never a
 * rectangle, never a rounded rectangle, and never a picture inset inside its
 * own frame.
 *
 * A Composable cannot be rendered in a JVM unit test, so the drawing rules are
 * pinned on the source: the same technique the overlay ring test uses for
 * geometry that only exists at draw time. Each assertion is a decision that
 * was made deliberately and would otherwise be silently undone:
 *
 *  - `CircleShape`, not `RoundedCornerShape`/`CORNER_RADIUS_FRACTION`: a 22%
 *    rounded rectangle reads as a squarish tile and leaves the identity card's
 *    margin visible as a ring around the picture.
 *  - `ContentScale.Crop`: the stored bitmap keeps its aspect ratio
 *    ([com.openminis.app.agent.SoulIcon.encode] does not crop on save), so Fit
 *    would letterbox the avatar with blank bands instead of filling it.
 *  - no `padding(`: an inset between the artwork and the frame edge is the
 *    "transparent padding" this work removes.
 */
class SoulAvatarContractTest {

    private val src = ProductionSources.read("ui/components/SoulAvatar.kt")

    @Test
    fun `the avatar is clipped to a circle`() {
        assertTrue("the image must be clipped with CircleShape", src.contains("clip(CircleShape)"))
        assertFalse("a rounded rectangle is not a circle", src.contains("RoundedCornerShape"))
        assertFalse(
            "the legacy 22% corner radius must not be used for drawing",
            src.contains("CORNER_RADIUS_FRACTION"),
        )
    }

    @Test
    fun `the bitmap fills the frame instead of being letterboxed`() {
        assertTrue("the image must be cropped to fill", src.contains("ContentScale.Crop"))
        assertFalse("Fit would leave blank bands", src.contains("ContentScale.Fit"))
    }

    @Test
    fun `nothing is inset between the artwork and the frame edge`() {
        assertFalse("padding would leave a visible ring", src.contains(".padding("))
    }

    @Test
    fun `the decode is memoised on the icon value`() {
        // The chat header and the assistant row recompose on every streaming
        // tick; re-decoding base64 there would be pure waste.
        assertTrue(src.contains("remember(icon) { SoulIcon.decode(icon) }"))
    }

    @Test
    fun `the settings card delegates to this renderer`() {
        // The call site lives in SoulSettingsScreen.kt (owned by the prompt
        // work); this pins that it no longer draws its own rounded rectangle.
        val settings = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        val glyph = settings.substringAfter("internal fun SoulIconGlyph(")
            .substringBefore("@Composable\nprivate fun SoulIconMenu(")
        assertTrue(
            "SoulIconGlyph must delegate to SoulAvatarGlyph",
            glyph.contains("SoulAvatarGlyph("),
        )
        assertFalse(
            "SoulIconGlyph must not draw its own rounded rectangle",
            glyph.contains("RoundedCornerShape"),
        )
    }
}
