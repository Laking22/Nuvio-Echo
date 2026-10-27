package com.nuvio.tv.core.home.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHighlightCatalogTest {

    @Test
    fun `the names asked for are all there`() {
        val names = SmartHighlightCatalog.themes.map { it.title }
        listOf(
            "The Butler Did It", "Tear Jerker", "Grab the Tissues", "Back-to-Back Action", "Short Watches",
            "Back in Blood: Revenge", "Zero to Hero", "Hero to Zero", "Enemies to Lovers", "Love Triangle"
        ).forEach { assertTrue(it, it in names) }
        assertEquals(30, names.size)
    }

    @Test
    fun `ids and names are unique and every theme has a query`() {
        val themes = SmartHighlightCatalog.themes
        assertEquals(themes.size, themes.map { it.id }.toSet().size)
        assertEquals(themes.size, themes.map { it.title.lowercase() }.toSet().size)
        themes.forEach { theme ->
            assertTrue(theme.id, theme.movie != null || theme.tv != null)
            assertTrue(theme.id, Regex("[a-z_]+").matches(theme.id))
            assertNotNull(SmartHighlightCatalog.theme(theme.id))
        }
    }

    @Test
    fun `genre and keyword filters use TMDB's id syntax`() {
        val syntax = Regex("""\d+([,|]\d+)*""")
        SmartHighlightCatalog.themes.flatMap { listOfNotNull(it.movie, it.tv).map { q -> it.id to q } }.forEach { (id, q) ->
            listOfNotNull(q.genres, q.withoutGenres, q.keywords).forEach { assertTrue("$id: $it", syntax.matches(it)) }
            q.releasedBefore?.let { assertTrue(id, Regex("""\d{4}-\d{2}-\d{2}""").matches(it)) }
        }
    }
}
