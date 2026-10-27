package com.nuvio.tv.ui.newlook.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every size on the new look's Home comes from this one set, so rows, scroll positions and
 * the hero spacing all follow it. Cards are the standard size except
 * posters, whose width is Layout's poster card width, and every card's corners follow Layout's corner radius.
 */
@Immutable
data class NewLookDimens(
    val cwWidth: Dp,
    val cwHeight: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    /** A focused poster opens out to this width (same height, so about 16:9). */
    val posterOpenWidth: Dp,
    val highlightWidth: Dp,
    val highlightHeight: Dp,
    val gap: Dp,
    /** Row title and its margin. */
    val rowHead: Dp = 33.dp,
    /** The details line under a poster row. */
    val posterMeta: Dp = 24.dp,
    /** Details line plus synopsis under a highlighted row. */
    val highlightMeta: Dp = 84.dp,
    /** Space after each row. */
    val rowSpace: Dp = 26.dp,
    val cardRadius: Dp = 12.dp,
    /**
     * A grand row's card: the hero window's size, whatever the Card size. Home sets the width from the screen and
     * the sidebar ([withGrandWidth]); this default is the 960 dp screen with the sidebar.
     */
    val grandWidth: Dp = 830.dp,
    val grandHeight: Dp = 430.dp
) {
    /** The same sizes with grand cards as wide as the hero window on a [screenWidth] screen from [contentStart]. */
    fun withGrandWidth(screenWidth: Dp, contentStart: Dp): NewLookDimens =
        copy(grandWidth = screenWidth - contentStart - NewLookLayout.WindowRight, grandHeight = NewLookLayout.WindowHeight)

    /** Ranked rows put a big number behind each card: the card sits this far in from the item's left. */
    val rankPad: Dp get() = highlightHeight * 0.62f
    val rankFontSize: Float get() = highlightHeight.value * 1.18f

    companion object {
        private val Medium = NewLookDimens(
            cwWidth = 317.dp,
            cwHeight = 178.dp,
            posterWidth = 132.dp,
            posterHeight = 198.dp,
            posterOpenWidth = 352.dp,
            highlightWidth = 396.dp,
            highlightHeight = 234.dp,
            gap = 10.dp
        )

        /** Layout's default poster card width, which gives the new look's poster size. */
        const val DEFAULT_POSTER_WIDTH_DP = 126

        /**
         * Home's sizes for Layout's poster card [posterWidthDp] and corner [cornerRadiusDp]. Only posters change
         * size: they keep 2:3 and open out to 16:9 at the same height; every other card stays the standard size.
         */
        fun of(posterWidthDp: Int = DEFAULT_POSTER_WIDTH_DP, cornerRadiusDp: Int = 12): NewLookDimens {
            val s = posterWidthDp / DEFAULT_POSTER_WIDTH_DP.toFloat()
            val posterHeight = Medium.posterHeight * s
            return Medium.copy(
                posterWidth = Medium.posterWidth * s,
                posterHeight = posterHeight,
                posterOpenWidth = posterHeight * (16f / 9f),
                cardRadius = cornerRadiusDp.coerceAtLeast(0).dp
            )
        }
    }
}

/** Home's page scrolling, in pixels. The page is one list: the hero block, then the rows. */
internal object NewLookScroll {
    /**
     * How far the page scrolls with row [index] focused: just enough for its [visiblePx] (title, cards and details)
     * to rest [bottomMarginPx] above the bottom of a [viewportPx] screen, and never above the top. 0 for no row.
     */
    fun target(headerPx: Int, rowHeightsPx: List<Int>, index: Int, visiblePx: Int, viewportPx: Int, bottomMarginPx: Int): Int {
        if (index !in rowHeightsPx.indices) return 0
        var top = headerPx
        for (i in 0 until index) top += rowHeightsPx[i]
        return (top + visiblePx - (viewportPx - bottomMarginPx)).coerceAtLeast(0)
    }

    /** A scroll distance as the list's (item, offset): item 0 is the hero block, item n is row n - 1. */
    fun toItem(scrollPx: Int, headerPx: Int, rowHeightsPx: List<Int>): Pair<Int, Int> {
        var remaining = scrollPx.coerceAtLeast(0)
        if (remaining < headerPx) return 0 to remaining
        remaining -= headerPx
        var index = 1
        for (h in rowHeightsPx) {
            if (remaining < h) break
            remaining -= h
            index++
        }
        return index to remaining
    }
}

/** Where things sit on the 960×540 screen. */
internal object NewLookLayout {
    val WindowTop = 18.dp
    val WindowHeight = 430.dp
    /** With the menu on top (and showing), the bar sits above a shorter window; rows still start at 462. */
    val WindowTopUnderBar = 56.dp
    val WindowHeightUnderBar = 392.dp
    val WindowRight = 24.dp
    /** Window and rows start right of the sidebar column... */
    val ContentStartWithSidebar = 106.dp
    /** ...or near the edge when there's no sidebar (menu hidden or on top). */
    val ContentStartFullWidth = 24.dp
    val RowsTop = 462.dp
    /** The edge-to-edge hero: the whole screen tall, its bottom fading out under the first row. */
    val EdgeHeroHeight = 540.dp
    /** Its text and pills end this far above the first row. */
    val EdgeHeroTextAboveRows = 26.dp
}
