package com.nuvio.tv.core.home.smart

import com.nuvio.tv.data.mdblist.MdbListThemeList
import com.nuvio.tv.data.mdblist.decodeMdbListThemeLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListHighlightSourceTest {

    private fun list(name: String, items: Int? = 60, likes: Int? = 100, private: Boolean = false) =
        MdbListThemeList(ids = listOf(7L), name = name, description = null, itemCount = items, likes = likes, private = private)

    @Test
    fun `top and own lists decode, twins keep both ids, and broken rows are skipped`() {
        val body = """
            [
              {"id": 2194, "name": "Latest TV Shows", "description": "", "mediatype": "show", "items": 300, "likes": 477},
              {"ids": [11, 12], "name": "Cozy Mysteries", "items": 40, "private": true, "dynamic": true},
              {"id": 5, "items": 3},
              "not a list"
            ]
        """.trimIndent()
        val lists = decodeMdbListThemeLists(body)
        assertEquals(listOf("Latest TV Shows", "Cozy Mysteries"), lists.map { it.name })
        assertEquals(listOf(2194L), lists[0].ids)
        assertEquals(477, lists[0].likes)
        assertEquals(listOf(11L, 12L), lists[1].ids)
        assertTrue(lists[1].private)
    }

    @Test
    fun `the account's own lists only need some titles and a decent name`() {
        // One word and private are fine: the person named it and it's theirs.
        assertTrue(MdbListHighlightSource.isUsableOwn(list("Horror", items = 20, likes = 0, private = true)))
        assertFalse(MdbListHighlightSource.isUsableOwn(list("Horror", items = 3)))
        assertFalse(MdbListHighlightSource.isUsableOwn(list("Watched in 2025")))
        assertFalse(MdbListHighlightSource.isUsableOwn(list("Holy Shit Movies")))
    }

    @Test
    fun `top lists follow the same rules as Trakt's`() {
        assertTrue(MdbListHighlightSource.isUsableTop(list("Latest TV Shows")))
        assertFalse(MdbListHighlightSource.isUsableTop(list("Latest TV Shows", likes = 1)))
        assertFalse(MdbListHighlightSource.isUsableTop(list("Latest TV Shows", items = 5)))
        assertFalse(MdbListHighlightSource.isUsableTop(list("Latest TV Shows", private = true)))
        assertFalse(MdbListHighlightSource.isUsableTop(list("Horror")))
    }
}
