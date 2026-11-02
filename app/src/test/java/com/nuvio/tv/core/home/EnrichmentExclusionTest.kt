package com.nuvio.tv.core.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentExclusionTest {

    private fun title(type: ContentType, genres: List<String>, language: String? = null) = MetaPreview(
        id = "tmdb:1", type = type, name = "Title", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = genres, language = language
    )

    @Test
    fun `types match movies, series, anime and K-dramas`() {
        val movie = title(ContentType.MOVIE, listOf("Comedy"), "en")
        val anime = title(ContentType.SERIES, listOf("Animation", "Action & Adventure"), "ja")
        val kdrama = title(ContentType.SERIES, listOf("Drama"), "ko")
        assertTrue(EnrichmentExclusion.excludes(movie, setOf("type:movie")))
        assertFalse(EnrichmentExclusion.excludes(movie, setOf("type:series")))
        assertTrue(EnrichmentExclusion.excludes(anime, setOf("type:anime")))
        assertFalse(EnrichmentExclusion.excludes(kdrama, setOf("type:anime")))
        assertTrue(EnrichmentExclusion.excludes(kdrama, setOf("type:kdrama")))
        assertFalse(EnrichmentExclusion.excludes(anime, setOf("type:kdrama")))
    }

    @Test
    fun `genres match TMDB names and Trakt slugs`() {
        assertTrue(EnrichmentExclusion.excludes(title(ContentType.MOVIE, listOf("Horror")), setOf("genre:horror")))
        assertTrue(EnrichmentExclusion.excludes(title(ContentType.MOVIE, listOf("science-fiction")), setOf("genre:scifi")))
        assertTrue(EnrichmentExclusion.excludes(title(ContentType.SERIES, listOf("Sci-Fi & Fantasy")), setOf("genre:fantasy")))
        assertFalse(EnrichmentExclusion.excludes(title(ContentType.MOVIE, listOf("Comedy")), setOf("genre:horror")))
        assertFalse(EnrichmentExclusion.excludes(title(ContentType.MOVIE, listOf("Horror")), emptySet()))
    }
}
