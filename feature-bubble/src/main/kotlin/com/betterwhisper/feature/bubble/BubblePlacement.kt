package com.betterwhisper.feature.bubble

/**
 * Pure placement math for the floating bubble.
 *
 * The bubble window uses `Gravity.BOTTOM or Gravity.START`, so:
 *  - `x` grows to the right,
 *  - `y` grows **upward from the bottom edge** of the screen.
 *
 * Getting that wrong is what used to push the bubble off-screen when the IME
 * opened. Keeping the math here (no Android imports) makes it unit-testable.
 */
data class BubbleViewport(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val sideMarginPx: Int,
    /** Status bar + any extra breathing room at the top. */
    val topInsetPx: Int,
    /** Navigation bar inset + side margin at the bottom. */
    val bottomInsetPx: Int
) {

    fun clampX(x: Int, bubbleWidthPx: Int): Int {
        val maxX = (screenWidthPx - bubbleWidthPx - sideMarginPx).coerceAtLeast(sideMarginPx)
        return x.coerceIn(sideMarginPx, maxX)
    }

    /**
     * Keeps the bubble above the IME when it is open, above the navigation bar
     * when it is closed, and below the status bar in every case.
     *
     * [imeHeightPx] is measured from the bottom of the screen.
     */
    fun clampY(y: Int, imeHeightPx: Int, bubbleHeightPx: Int): Int {
        val minY = if (imeHeightPx > 0) imeHeightPx + sideMarginPx else bottomInsetPx
        val maxY = (screenHeightPx - topInsetPx - bubbleHeightPx).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    fun dockedX(onRight: Boolean, bubbleWidthPx: Int): Int =
        if (onRight) clampX(screenWidthPx - bubbleWidthPx, bubbleWidthPx) else sideMarginPx

    fun shouldDockRight(x: Int, bubbleWidthPx: Int): Boolean =
        x + bubbleWidthPx / 2 >= screenWidthPx / 2
}
