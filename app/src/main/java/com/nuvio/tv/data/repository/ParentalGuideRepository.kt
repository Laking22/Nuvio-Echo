package com.nuvio.tv.data.repository

import android.util.LruCache
import com.nuvio.tv.core.parental.ParentalGuideMerger
import com.nuvio.tv.core.parental.ParentalGuideSummary
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.AniListApi
import com.nuvio.tv.data.remote.api.AniListMedia
import com.nuvio.tv.data.remote.api.AniListQuery
import com.nuvio.tv.data.remote.api.AniListVariables
import com.nuvio.tv.data.remote.api.ImdbApiCertificate
import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideCategory
import com.nuvio.tv.data.remote.api.OmdbApi
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolved parental guide data with a single severity per category,
 * determined by the highest-voted severity level from the API.
 */
data class ParentalGuideResult(
    val nudity: String? = null,
    val violence: String? = null,
    val profanity: String? = null,
    val alcohol: String? = null,
)

/** What the player knows about the title; any id may be missing. */
data class ParentalGuideRequest(
    val imdbId: String?,
    val tmdbId: Int?,
    val isSeries: Boolean,
    val title: String?,
    val year: Int?,
    /** The age rating Nuvio's details badge shows (from the addon), used first when present. */
    val badgeRating: String?,
    /** True for kitsu:/mal:/anilist: ids or anime genres; null when unknown (TMDB decides). */
    val isAnime: Boolean?,
    val malId: Int?
) {
    val cacheKey: String get() = listOf(imdbId, tmdbId, isSeries, title, year).joinToString("|")
}

@Singleton
class ParentalGuideRepository @Inject constructor(
    private val api: ParentalGuideApi,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val aniListApi: AniListApi,
    private val omdbApi: OmdbApi,
    private val betaSettings: BetaPerformanceSettingsDataStore
) {
    private val cache = ConcurrentHashMap<String, ParentalGuideResult>()
    private val parentsGuideCache = ConcurrentHashMap<String, List<ImdbApiParentsGuideCategory>>()
    private val detailedCache = LruCache<String, ParentalGuideSummary>(64)

    /**
     * Age rating plus every category any source knows about. Sources run in parallel, each capped
     * at [SOURCE_TIMEOUT_MS], so a slow one only costs its own details.
     *
     * Category order (most detailed first): IMDb parents guide, AniList (anime), TMDB, OMDb.
     * Rating order: the details badge's rating, TMDB (same pick as the badge), IMDb, OMDb.
     */
    suspend fun getDetailedGuide(request: ParentalGuideRequest): ParentalGuideSummary? {
        detailedCache.get(request.cacheKey)?.let { return it }
        val summary = coroutineScope {
            val imdbGuide = async {
                request.imdbId?.let { id -> timed { fetchParentsGuide(id) } }
                    ?.let { ParentalGuideMerger.fromImdb(it) }
            }
            val tmdbId = async {
                request.tmdbId ?: request.imdbId?.let { id ->
                    timed { tmdbService.imdbToTmdb(id, if (request.isSeries) "tv" else "movie") }
                }
            }
            val language = tmdbSettings.settings.value.language
            val tmdbRating = async {
                tmdbId.await()?.let { id -> timed { tmdbMetadataService.fetchRatingInfo(id, request.isSeries, language) } }
            }
            val tmdbKeywords = async {
                tmdbId.await()?.let { id -> timed { fetchTmdbKeywords(id, request.isSeries) } }.orEmpty()
            }
            val aniList = async {
                val anime = request.isAnime ?: tmdbId.await()?.let { id -> timed { isAnimeOnTmdb(id, request.isSeries) } } ?: false
                if (anime) timed { findAniList(request) }?.let { ParentalGuideMerger.fromAniList(it) } else null
            }

            val tmdbInfo = tmdbRating.await()
            val reports = listOfNotNull(
                imdbGuide.await(),
                aniList.await(),
                ParentalGuideMerger.fromTmdb(tmdbInfo?.descriptors.orEmpty(), tmdbKeywords.await())
            )
            val rating = request.badgeRating?.takeIf { it.isNotBlank() }
                ?: tmdbInfo?.rating?.takeIf { it.isNotBlank() }
                ?: request.imdbId?.let { id -> timed { imdbCertificate(id, request.isSeries, language) } }
                ?: request.imdbId?.let { id -> timed { omdbRating(id) } }
            ParentalGuideMerger.merge(rating, reports)
        }
        if (!summary.isEmpty) detailedCache.put(request.cacheKey, summary)
        return summary.takeIf { !it.isEmpty }
    }

    private suspend fun <T> timed(block: suspend () -> T?): T? =
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { block() }.getOrNull() }

    private suspend fun fetchParentsGuide(imdbId: String): List<ImdbApiParentsGuideCategory>? {
        parentsGuideCache[imdbId]?.let { return it }
        val response = api.getParentsGuide(imdbId)
        val categories = response.body()?.parentsGuide?.takeIf { response.isSuccessful && it.isNotEmpty() }
            ?: return null
        parentsGuideCache[imdbId] = categories
        return categories
    }

    private suspend fun fetchTmdbKeywords(tmdbId: Int, isSeries: Boolean): List<String> {
        val key = tmdbService.apiKey()
        val body = if (isSeries) tmdbApi.getTvKeywords(tmdbId, key).body() else tmdbApi.getMovieKeywords(tmdbId, key).body()
        return (body?.keywords ?: body?.results).orEmpty().mapNotNull { it.name }
    }

    private suspend fun isAnimeOnTmdb(tmdbId: Int, isSeries: Boolean): Boolean {
        val key = tmdbService.apiKey()
        val details = (if (isSeries) tmdbApi.getTvDetails(tmdbId, key) else tmdbApi.getMovieDetails(tmdbId, key)).body()
            ?: return false
        return details.originalLanguage == "ja" && details.genres.orEmpty().any { it.id == TMDB_ANIMATION_GENRE }
    }

    /** By MAL id when the player has one, else the closest title search match by year and format. */
    private suspend fun findAniList(request: ParentalGuideRequest): AniListMedia? {
        val variables = when {
            request.malId != null -> AniListVariables(idMal = request.malId)
            !request.title.isNullOrBlank() -> AniListVariables(search = request.title)
            else -> return null
        }
        val media = aniListApi.query(AniListQuery(ANILIST_QUERY, variables)).body()?.data?.page?.media.orEmpty()
        if (media.isEmpty()) return null
        if (request.malId != null) return media.first()
        val wantMovie = !request.isSeries
        return media
            .filter { request.year == null || it.startDate?.year == null || kotlin.math.abs(it.startDate.year - request.year) <= 1 }
            .sortedBy { if ((it.format == "MOVIE") == wantMovie) 0 else 1 }
            .firstOrNull()
    }

    /** IMDb's certificate for the user's region, else the US or UK; TV ratings for shows only. */
    private suspend fun imdbCertificate(imdbId: String, isSeries: Boolean, language: String): String? {
        val certificates = api.getCertificates(imdbId).body()?.certificates.orEmpty()
        if (certificates.isEmpty()) return null
        val region = language.substringAfter('-', "").uppercase(Locale.US).takeIf { it.length == 2 }
        fun ImdbApiCertificate.isTvRating() = rating.orEmpty().startsWith("TV-") ||
            attributes.orEmpty().any { it.contains("TV", ignoreCase = true) }
        val suitable = certificates.filter { !it.rating.isNullOrBlank() && it.isTvRating() == isSeries }
            .ifEmpty { certificates.filter { !it.rating.isNullOrBlank() } }
        return listOfNotNull(region, "US", "GB").firstNotNullOfOrNull { code ->
            suitable.firstOrNull { it.country?.code.equals(code, ignoreCase = true) }?.rating
        }
    }

    private suspend fun omdbRating(imdbId: String): String? {
        val key = betaSettings.current.value.omdbApiKey.takeIf { it.isNotBlank() } ?: return null
        val rated = omdbApi.title(imdbId, key).body()?.rated?.trim() ?: return null
        return rated.takeUnless { it.isEmpty() || it.equals("N/A", true) }
    }

    /** Checks a key by looking up one known title; false for a rejected key or no answer. */
    suspend fun validateOmdbKey(key: String): Boolean = runCatching {
        omdbApi.title("tt0111161", key.trim()).body()?.response.equals("True", ignoreCase = true)
    }.getOrDefault(false)

}

private const val SOURCE_TIMEOUT_MS = 5_000L
private const val TMDB_ANIMATION_GENRE = 16
private const val ANILIST_QUERY = """
query (${'$'}search: String, ${'$'}idMal: Int) {
  Page(perPage: 6) {
    media(search: ${'$'}search, idMal: ${'$'}idMal, type: ANIME, sort: SEARCH_MATCH) {
      id idMal isAdult format genres
      startDate { year }
      title { romaji english }
      tags { name rank isMediaSpoiler }
    }
  }
}
"""
