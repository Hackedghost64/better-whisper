package com.betterwhisper.feature.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementTest {

    private val viewport = BubbleViewport(
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        sideMarginPx = 40,
        topInsetPx = 80,
        bottomInsetPx = 140
    )

    private val bubble = 130

    @Test
    fun `x is clamped inside the side margins`() {
        assertEquals(40, viewport.clampX(-500, bubble))
        assertEquals(1080 - bubble - 40, viewport.clampX(5000, bubble))
        assertEquals(500, viewport.clampX(500, bubble))
    }

    @Test
    fun `docked positions are legal`() {
        assertEquals(40, viewport.dockedX(onRight = false, bubble))
        assertEquals(1080 - bubble - 40, viewport.dockedX(onRight = true, bubble))
    }

    @Test
    fun `docking side follows the bubble centre`() {
        assertTrue(viewport.shouldDockRight(900, bubble))
        assertFalse(viewport.shouldDockRight(100, bubble))
    }

    @Test
    fun `y stays above the navigation bar when the keyboard is closed`() {
        assertEquals(140, viewport.clampY(-900, imeHeightPx = 0, bubbleHeightPx = bubble))
    }

    @Test
    fun `y stays below the status bar`() {
        val maxY = 2400 - 80 - bubble
        assertEquals(maxY, viewport.clampY(9999, imeHeightPx = 0, bubbleHeightPx = bubble))
    }

    @Test
    fun `y is pushed above the keyboard`() {
        val imeHeight = 900
        val clamped = viewport.clampY(100, imeHeightPx = imeHeight, bubbleHeightPx = bubble)
        assertEquals(imeHeight + 40, clamped)
    }

    @Test
    fun `extremely short screens still produce a usable range`() {
        val tiny = viewport.copy(screenHeightPx = 200, topInsetPx = 80)
        val y = tiny.clampY(0, imeHeightPx = 900, bubbleHeightPx = bubble)
        assertEquals(900 + 40, y)
    }
}
