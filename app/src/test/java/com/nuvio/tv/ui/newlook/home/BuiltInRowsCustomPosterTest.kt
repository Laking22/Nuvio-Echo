package com.nuvio.tv.ui.newlook.home

import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BuiltInRowsCustomPosterTest {

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = "Title $id", poster = "https://image.tmdb.org/p/$id.jpg",
        posterShape = PosterShape.POSTER, background = "b/$id", logo = null, description = null, releaseInfo = "2026",
        imdbRating = 7.5f, genres = emptyList()
    )

    private val rows = BuiltInRowsRepository.Rows(
        trendingWeek = listOf(meta("tt0000001")),
        becauseYouWatched = BuiltInRowsRepository.BecauseYouWatched("Seed", listOf(meta("tt0000002"))),
        forYou = listOf(meta("tt0000003"))
    )

    @Test
    fun `built-in rows get the custom poster and keep the original to fall back to`() {
        val custom = rows.withCustomPosterUrls("https://posters.example/{imdb_id}.jpg")
        val trending = custom.trendingWeek.single()
        assertEquals("https://posters.example/tt0000001.jpg", trending.poster)
        assertEquals("https://image.tmdb.org/p/tt0000001.jpg", trending.rawPosterUrl)
        assertEquals("https://posters.example/tt0000002.jpg", custom.becauseYouWatched!!.items.single().poster)
        assertEquals("https://posters.example/tt0000003.jpg", custom.forYou.single().poster)
    }

    @Test
    fun `without a pattern the rows are left alone`() {
        assertSame(rows, rows.withCustomPosterUrls(""))
    }
}
