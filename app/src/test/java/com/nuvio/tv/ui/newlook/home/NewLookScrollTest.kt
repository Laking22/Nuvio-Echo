package com.nuvio.tv.ui.newlook.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewLookScrollTest {

    private val header = 422
    private val heights = listOf(300, 200, 350, 200)

    @Test
    fun `no row or the hero means the page sits at the top`() {
        assertEquals(0, NewLookScroll.target(header, heights, index = -1, visiblePx = 250, viewportPx = 540, bottomMarginPx = 10))
        assertEquals(0, NewLookScroll.target(header, heights, index = 99, visiblePx = 250, viewportPx = 540, bottomMarginPx = 10))
    }

    @Test
    fun `a row that already fits doesn't scroll the page`() {
        // Row 0 starts at 422 and shows 100 px: ends at 522, inside 540 - 10.
        assertEquals(0, NewLookScroll.target(header, heights, index = 0, visiblePx = 100, viewportPx = 540, bottomMarginPx = 10))
    }

    @Test
    fun `a lower row scrolls just enough to rest above the bottom`() {
        // Row 2 starts at 422 + 300 + 200 = 922 and shows 300 px: ends at 1222, so scroll 1222 - 530 = 692.
        assertEquals(692, NewLookScroll.target(header, heights, index = 2, visiblePx = 300, viewportPx = 540, bottomMarginPx = 10))
    }

    @Test
    fun `scroll distances map to the list's item and offset`() {
        assertEquals(0 to 0, NewLookScroll.toItem(0, header, heights))
        assertEquals(0 to 100, NewLookScroll.toItem(100, header, heights))
        assertEquals(1 to 0, NewLookScroll.toItem(422, header, heights))
        assertEquals(1 to 299, NewLookScroll.toItem(721, header, heights))
        assertEquals(2 to 0, NewLookScroll.toItem(722, header, heights))
        assertEquals(3 to 170, NewLookScroll.toItem(692 + 400, header, heights))
        // Past the last row: the offset runs on and the list clamps it.
        assertEquals(5 to 50, NewLookScroll.toItem(422 + 1050 + 50, header, heights))
        assertEquals(0 to 0, NewLookScroll.toItem(-5, header, heights))
    }

    @Test
    fun `stress - item and offset always add back up to the distance`() {
        val random = Random(99)
        repeat(5_000) {
            val rows = List(random.nextInt(0, 40)) { random.nextInt(1, 600) }
            val head = random.nextInt(1, 800)
            val scroll = random.nextInt(0, head + rows.sum() + 500)
            val (index, offset) = NewLookScroll.toItem(scroll, head, rows)
            val before = if (index == 0) 0 else head + rows.take(index - 1).sum()
            assertEquals(scroll, before + offset)
            assertTrue(offset >= 0)
            if (index in 1..rows.size) assertTrue(offset < rows[index - 1] || index == rows.size + 1)
            if (index == 0) assertTrue(offset < head)

            val target = NewLookScroll.target(head, rows, random.nextInt(-1, rows.size + 1), random.nextInt(1, 500), 540, 10)
            assertTrue(target >= 0)
        }
    }

    @Test
    fun `posters and highlights scale with poster width while grand dimensions stay separate`() {
        val standard = NewLookDimens.of()
        assertEquals(317.dp, standard.cwWidth)
        assertEquals(132.dp, standard.posterWidth)
        assertEquals(352f, standard.posterOpenWidth.value, 0.5f)
        listOf(104, 112, 120, 126, 134, 140).forEach { width ->
            val d = NewLookDimens.of(posterWidthDp = width, cornerRadiusDp = 4)
            // Posters keep 2:3 and open out to 16:9 at the same height.
            assertEquals(132f * width / 126f, d.posterWidth.value, 0.01f)
            assertEquals(1.5f, d.posterHeight.value / d.posterWidth.value, 0.01f)
            assertEquals(16f / 9f, d.posterOpenWidth.value / d.posterHeight.value, 0.01f)
            assertTrue(d.posterOpenWidth > d.posterWidth)
            // Wide and grand cards stay independent of the poster width.
            assertEquals(standard.cwWidth, d.cwWidth)
            assertEquals(396f * width / 126f, d.highlightWidth.value, 0.01f)
            assertEquals(234f * width / 126f, d.highlightHeight.value, 0.01f)
            assertEquals(standard.grandWidth, d.grandWidth)
            assertEquals(standard.grandHeight, d.grandHeight)
            assertEquals(standard.gap, d.gap)
            assertEquals(4.dp, d.cardRadius)
        }
    }

    @Test
    fun `row heights add up from their parts`() {
        val d = NewLookDimens.of()
        val row = NewLookRow("k", "t", RowKind.HIGHLIGHT, emptyList(), ranked = true)
        assertEquals((d.rowHead + d.highlightHeight + d.highlightMeta + d.rowSpace).value, row.height(d).value, 0.01f)
        assertEquals((d.rankPad + d.highlightWidth).value, row.cardWidth(d).value, 0.01f)
        val wide = NewLookRow("cw", "t", RowKind.WIDE, emptyList())
        assertEquals((d.rowHead + d.cwHeight + d.rowSpace).value, wide.height(d).value, 0.01f)
    }

    @Test
    fun `years come out of every date shape addons send`() {
        assertEquals("2026", yearOf("July 4, 2026"))
        assertEquals("2022", yearOf("2022–"))
        assertEquals("2026", yearOf("2026-07-04"))
        assertEquals(null, yearOf("July"))
        assertEquals(null, yearOf(null))
    }
}
