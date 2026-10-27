package com.nuvio.tv.core.home

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Why a title is in the hero carousel, shown on the window's tag. */
enum class FeaturedTag { COMING_SOON, NEW_EPISODES, NEW_RELEASE, TOP_10, AIRING_NOW, HIGHLY_RATED, TRENDING }

/** What the new look shows about a title beyond its catalog entry: art, details line and the hero's tag. */
@Immutable
data class NewLookDetails(
    val logo: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val year: String? = null,
    val overview: String? = null,
    val tag: FeaturedTag? = null,
    /** For COMING_SOON: the season that's coming. */
    val tagSeason: Int? = null
)

/**
 * Fills in logos, genres and runtimes for the new look's cards and window from TMDB (cached by
 * [TmdbMetadataService]), and works out each featured title's tag from its TMDB schedule. Without a TMDB key it
 * returns nothing and the cards use their catalog data.
 */
@Singleton
class NewLookEnricher @Inject constructor(
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val cache = object : LinkedHashMap<String, NewLookDetails>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NewLookDetails>?) = size > 200
    }

    fun cached(id: String): NewLookDetails? = synchronized(cache) { cache[id] }

    /** [withTag] also looks up why the title would be featured (one more request for shows). */
    suspend fun details(item: MetaPreview, withTag: Boolean = false, topTenIds: Set<String> = emptySet()): NewLookDetails? =
        withContext(Dispatchers.IO) {
            cached(item.id)?.let { if (!withTag || it.tag != null) return@withContext it }
            if (com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isBlank()) return@withContext null
            val isTv = item.type == ContentType.SERIES || item.type == ContentType.TV ||
                item.apiType.equals("series", true)
            try {
                val tmdbId = tmdbService.ensureTmdbId(item.id, if (isTv) "tv" else "movie", item.imdbId) ?: return@withContext null
                val language = tmdbSettingsDataStore.settings.value.language
                val enrichment = tmdbMetadataService.fetchEnrichment(
                    tmdbId, if (isTv) ContentType.SERIES else ContentType.MOVIE, language
                )
                var details = NewLookDetails(
                    logo = enrichment?.logo,
                    backdrop = enrichment?.backdrop,
                    genres = enrichment?.genres.orEmpty(),
                    runtimeMinutes = enrichment?.runtimeMinutes,
                    rating = enrichment?.rating,
                    year = enrichment?.releaseInfo,
                    overview = enrichment?.description
                )
                if (withTag) {
                    val (tag, season) = tagFor(item, isTv, tmdbId.toIntOrNull(), details.rating, topTenIds, language)
                    details = details.copy(tag = tag, tagSeason = season)
                }
                synchronized(cache) { cache[item.id] = details }
                details
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun tagFor(
        item: MetaPreview,
        isTv: Boolean,
        tmdbId: Int?,
        rating: Double?,
        topTenIds: Set<String>,
        language: String
    ): Pair<FeaturedTag, Int?> {
        val today = LocalDate.now()
        val score = rating ?: item.imdbRating?.toDouble()
        if (isTv && tmdbId != null) {
            val schedule = runCatching { tmdbApi.getTvSchedule(tmdbId, com.nuvio.tv.core.apikeys.ApiKeys.tmdb, language).body() }.getOrNull()
            val next = schedule?.nextEpisodeToAir
            val nextDate = next?.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (next != null && nextDate != null && !nextDate.isBefore(today)) {
                val season = next.seasonNumber
                if (next.episodeNumber == 1 && season != null && season > 1) return FeaturedTag.COMING_SOON to season
                if (ChronoUnit.DAYS.between(today, nextDate) <= 14) return FeaturedTag.NEW_EPISODES to null
            }
            if (item.id in topTenIds) return FeaturedTag.TOP_10 to null
            val lastDate = schedule?.lastEpisodeToAir?.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (lastDate != null && ChronoUnit.DAYS.between(lastDate, today) <= 21) return FeaturedTag.AIRING_NOW to null
        } else {
            val released = item.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (released != null && !released.isAfter(today) && ChronoUnit.DAYS.between(released, today) <= 75) {
                return FeaturedTag.NEW_RELEASE to null
            }
            if (item.id in topTenIds) return FeaturedTag.TOP_10 to null
        }
        if (score != null && score >= 7.8) return FeaturedTag.HIGHLY_RATED to null
        return FeaturedTag.TRENDING to null
    }
}
