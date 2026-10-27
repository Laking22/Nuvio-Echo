package com.nuvio.tv.core.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BrowseCatalogTest {

    @Test
    fun `anime is a mode, not a genre`() {
        assertFalse(BrowseCatalog.genres.any { it.id == "anime" })
        assertTrue(BrowseMediaMode.ANIME in BrowseMediaMode.entries)
    }

    @Test
    fun `every curated keyword carries its TMDB id so picking one costs a single request`() {
        val keywords = BrowseCatalog.genres.flatMap { it.keywords } +
            BrowseCatalog.genres.mapNotNull { it.tv?.keyword } +
            BrowseCatalog.suggestedKeywords(count = 64)
        keywords.forEach { keyword -> assertNotNull("No TMDB id for ${keyword.label}", keyword.tmdbId) }
    }

    @Test
    fun `every genre has twelve keywords and a way to query TMDB`() {
        BrowseCatalog.genres.forEach { genre ->
            assertEquals(genre.id, 12, genre.keywords.size)
            assertTrue(genre.id, genre.movie != null || genre.tv != null)
        }
    }

    @Test
    fun `suggested keywords stay the same through a day and change between days`() {
        val day = LocalDate.of(2026, 10, 10)
        assertEquals(BrowseCatalog.suggestedKeywords(day), BrowseCatalog.suggestedKeywords(day))
        assertEquals(8, BrowseCatalog.suggestedKeywords(day).size)
        val week = (0L..6L).map { BrowseCatalog.suggestedKeywords(day.plusDays(it)) }.toSet()
        assertTrue(week.size > 1)
    }

    @Test
    fun `anime mode drops genres with no anime and maps the rest to Kitsu where it can`() {
        val reality = BrowseCatalog.genres.first { it.id == "reality" }
        assertFalse(reality.hasAnime)
        assertEquals("", BrowseCatalog.genres.first { it.id == "animation" }.kitsuCategory)
        assertEquals("kids", BrowseCatalog.genres.first { it.id == "family" }.kitsuCategory)
        assertEquals("science-fiction", BrowseCatalog.genres.first { it.id == "scifi" }.kitsuCategory)
    }

    @Test
    fun `request keys differ by mode so anime pages are cached apart`() {
        val genre = BrowseCatalog.genres.first()
        val all = BrowseRequest(mode = BrowseMediaMode.ALL, genre = genre)
        val anime = BrowseRequest(mode = BrowseMediaMode.ANIME, genre = genre)
        assertFalse(all.key == anime.key)
    }
}
