package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultsMergerTest {

    private fun item(id: String, name: String, type: ContentType = ContentType.MOVIE, year: String? = null) = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = "https://example.invalid/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = year,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(catalogId: String, type: ContentType, vararg items: MetaPreview) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.invalid",
        catalogId = catalogId,
        catalogName = catalogId,
        type = type,
        items = items.toList()
    )

    @Test
    fun `rows are interleaved so every catalog's best match comes early`() {
        val movies = row("movies", ContentType.MOVIE, item("m1", "Alpha One"), item("m2", "Alpha Two"))
        val series = row("series", ContentType.SERIES, item("s1", "Alpha Show", ContentType.SERIES), item("s2", "Alpha Again", ContentType.SERIES))

        val merged = SearchResultsMerger.merge(listOf(movies, series), query = "")

        assertEquals(listOf("m1", "s1", "m2", "s2"), merged.map { it.id })
    }

    @Test
    fun `same title from two catalogs is kept once and placeholders are dropped`() {
        val first = row("a", ContentType.MOVIE, item("tt1", "Dark Matter", year = "2024"), item("__placeholder_1", "x"))
        val second = row("b", ContentType.MOVIE, item("tmdb:9", "Dark Matter", year = "2024"), item("tt2", "Dark Matters"))

        val merged = SearchResultsMerger.merge(listOf(first, second), query = "dark matter")

        assertEquals(listOf("tt1", "tt2"), merged.map { it.id })
    }

    @Test
    fun `exact and prefix matches move ahead of loose ones`() {
        val rows = listOf(
            row(
                "movies",
                ContentType.MOVIE,
                item("partial", "Office Space"),
                item("prefix", "The Office Party"),
                item("exact", "The Office")
            )
        )

        val merged = SearchResultsMerger.merge(rows, query = "the office")

        assertEquals(listOf("exact", "prefix", "partial"), merged.map { it.id })
    }

    @Test
    fun `tmdb ranking orders results and addon versions replace matching tmdb items`() {
        val rows = listOf(
            row(
                "series",
                ContentType.SERIES,
                item("tt-2015", "Dark Matter", ContentType.SERIES, year = "2015–2017"),
                item("tt-2024", "Dark Matter", ContentType.SERIES, year = "2024–"),
                item("tt-addon-only", "Dark Matters Unseen", ContentType.SERIES, year = "2019")
            )
        )
        val ranked = listOf(
            item("tmdb:1", "Dark Matter", ContentType.SERIES, year = "2024"),
            item("tmdb:2", "Dark Matter", ContentType.MOVIE, year = "2007"),
            item("tmdb:3", "Dark Matter", ContentType.SERIES, year = "2015")
        )

        val merged = SearchResultsMerger.merge(rows, "dark matter", ranked = ranked)

        assertEquals(listOf("tt-2024", "tmdb:2", "tt-2015", "tt-addon-only"), merged.map { it.id })
    }

    @Test
    fun `filter keeps only the chosen media type`() {
        val rows = listOf(
            row("movies", ContentType.MOVIE, item("m", "Cyberpunk")),
            row("series", ContentType.SERIES, item("s", "Cyberpunk: Edgerunners", ContentType.SERIES))
        )

        assertEquals(listOf("s"), SearchResultsMerger.merge(rows, "cyberpunk", BrowseMediaMode.SHOWS).map { it.id })
        assertEquals(listOf("m"), SearchResultsMerger.merge(rows, "cyberpunk", BrowseMediaMode.MOVIES).map { it.id })
    }
}
