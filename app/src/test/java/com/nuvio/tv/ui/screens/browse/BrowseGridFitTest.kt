package com.nuvio.tv.ui.screens.browse

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseGridFitTest {

    // The keyboard search's grid starts 280 dp in, with 8 dp of padding, on a 960 dp screen.
    private fun roomFor(contentInset: Float) = (960f - contentInset - 280f - 8f).dp

    @Test
    fun `classic sidebar keeps the usual 128 dp posters`() {
        assertEquals(128f, browseCardWidthToFit(roomFor(0f)).value, 0.01f)
        assertEquals(128f, browseCardWidthToFit(2000.dp).value, 0.01f)
    }

    @Test
    fun `new look shrinks posters just enough for five columns to fit`() {
        val room = roomFor(54f)
        val card = browseCardWidthToFit(room)
        assertEquals(117.2f, card.value, 0.01f)
        assertTrue(browseGridWidth(card) <= room)
        assertEquals(room.value, browseGridWidth(card).value, 0.01f)
    }
}
