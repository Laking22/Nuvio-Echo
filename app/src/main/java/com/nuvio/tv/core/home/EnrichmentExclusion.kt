package com.nuvio.tv.core.home

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.browse.BrowseCatalog
import com.nuvio.tv.domain.model.MetaPreview

/**
 * Enrichment's exclusions: kinds of title kept out of the Smart Highlights, by type (movies, series, anime, K-dramas)
 * and by genre. Titles come from TMDB, Trakt and MDBList, so genres are matched by name (TMDB's English names and
 * Trakt's slugs both); anime and K-dramas by genre and original language.
 */
object EnrichmentExclusion {
    data class Option(val key: String, @StringRes val labelRes: Int)

    private const val MOVIE = "type:movie"
    private const val SERIES = "type:series"
    private const val ANIME = "type:anime"
    private const val KDRAMA = "type:kdrama"
    private const val GENRE_PREFIX = "genre:"

    val types: List<Option> = listOf(
        Option(MOVIE, R.string.enrichment_exclusion_movie),
        Option(SERIES, R.string.enrichment_exclusion_series),
        Option(ANIME, R.string.enrichment_exclusion_anime),
        Option(KDRAMA, R.string.enrichment_exclusion_kdrama)
    )

    val genres: List<Option> = BrowseCatalog.genres.map { Option(GENRE_PREFIX + it.id, it.labelRes) }

    /** True when [item] is one of the [exclusions]' kinds. */
    fun excludes(item: MetaPreview, exclusions: Set<String>): Boolean {
        if (exclusions.isEmpty()) return false
        val series = item.apiType.equals("series", true) || item.apiType.equals("tv", true)
        val names = item.genres.map(::normalise).toSet()
        val language = item.language?.lowercase()
        val anime = "anime" in names || ("animation" in names && language == "ja")
        for (key in exclusions) {
            val hit = when (key) {
                MOVIE -> !series
                SERIES -> series
                ANIME -> anime
                KDRAMA -> series && (language == "ko" || item.country.equals("KR", true))
                else -> key.startsWith(GENRE_PREFIX) &&
                    genreNames[key.removePrefix(GENRE_PREFIX)].orEmpty().any { it in names }
            }
            if (hit) return true
        }
        return false
    }

    /** English names for TMDB's genre ids (movie and TV), so titles from any language setting match by name. */
    fun tmdbGenreName(id: Int): String? = tmdbGenres[id]

    private fun normalise(name: String): String = name.trim().lowercase().replace('-', ' ')

    /** Each genre's names as TMDB (movie and TV) and Trakt give them, normalised. */
    private val genreNames: Map<String, Set<String>> = mapOf(
        "action" to setOf("action", "action & adventure"),
        "adventure" to setOf("adventure", "action & adventure"),
        "animation" to setOf("animation", "anime"),
        "comedy" to setOf("comedy"),
        "crime" to setOf("crime"),
        "documentary" to setOf("documentary"),
        "drama" to setOf("drama"),
        "family" to setOf("family", "kids"),
        "fantasy" to setOf("fantasy", "sci fi & fantasy"),
        "history" to setOf("history"),
        "horror" to setOf("horror"),
        "music" to setOf("music", "musical"),
        "mystery" to setOf("mystery"),
        "reality" to setOf("reality", "reality tv"),
        "romance" to setOf("romance"),
        "scifi" to setOf("science fiction", "sci fi", "sci fi & fantasy"),
        "thriller" to setOf("thriller"),
        "war" to setOf("war", "war & politics"),
        "western" to setOf("western")
    )

    private val tmdbGenres: Map<Int, String> = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
        10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
        10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics"
    )
}
