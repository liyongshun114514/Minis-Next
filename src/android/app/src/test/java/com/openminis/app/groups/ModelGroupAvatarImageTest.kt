package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-model-group-avatar] Pure arithmetic behind picture normalization. The
 * bitmap round trip is asserted separately in
 * [ModelGroupAvatarImageRoboTest]; these are the decisions (never upscale,
 * preserve aspect ratio, power-of-two sampling) that a JVM test can pin.
 */
class ModelGroupAvatarImageTest {

    @Test
    fun `landscape keeps its ratio at the stored edge`() {
        assertEquals(192 to 144, ModelGroupAvatarImage.scaledSize(1200, 900, 192))
    }

    @Test
    fun `portrait keeps its ratio`() {
        assertEquals(144 to 192, ModelGroupAvatarImage.scaledSize(900, 1200, 192))
    }

    @Test
    fun `a panorama is capped on its longest edge`() {
        assertEquals(192 to 48, ModelGroupAvatarImage.scaledSize(4000, 1000, 192))
    }

    @Test
    fun `a small picture is never upscaled`() {
        assertEquals(100 to 80, ModelGroupAvatarImage.scaledSize(100, 80, 192))
    }

    @Test
    fun `a degenerate size does not produce a zero dimension`() {
        assertEquals(192 to 1, ModelGroupAvatarImage.scaledSize(3000, 1, 192))
        assertEquals(1 to 192, ModelGroupAvatarImage.scaledSize(1, 3000, 192))
        assertEquals(0 to 0, ModelGroupAvatarImage.scaledSize(0, 100, 192))
    }

    @Test
    fun `sampling is a power of two that keeps the edge above the target`() {
        assertEquals(1, ModelGroupAvatarImage.sampleSize(1000, 800, 1024))
        assertEquals(2, ModelGroupAvatarImage.sampleSize(4000, 3000, 1024))
        assertEquals(8, ModelGroupAvatarImage.sampleSize(9000, 6000, 1024))
        assertEquals(1, ModelGroupAvatarImage.sampleSize(0, 0, 1024))
    }
}