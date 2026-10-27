package com.nuvio.tv.core.home

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data for the new look's built-in highlighted rows, all from TMDB (without a TMDB key they don't show):
 * - Trending This Week, Top 20 Movies Today and Top 20 Shows Today: TMDB's trending charts, fetched once a day.
 * - Because You Watched: recommendations from the title most recently counted as viewed by the
 *   "Threshold for viewed" setting.
 * - For You: recommendations from several watched titles (Nuvio's usual completed rule or marked watched),
 *   recent ones weighted more, titles that come up for more than one scored higher.
 * Titles already watched or in Continue Watching are left out of the last two.
 * The details page's thumbs count too ([Taste]): loved and liked titles seed For You (loved ones most), and titles
 * marked "not for me" never show, push down what's recommended alongside them, and don't seed Because You Watched.
 */
@Singleton
class BuiltInRowsRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val betaSettings: BetaPerformanceSettingsDataStore,
    private val profileManager: ProfileManager,
    private val geminiRanker: GeminiRanker,
    private val titleRatingsStore: com.nuvio.tv.data.local.TitleRatingsStore
) {
    data class BecauseYouWatched(val seedTitle: String, val items: List<MetaPreview>)

    data class Rows(
        val trendingWeek: List<MetaPreview> = emptyList(),
        val topMoviesToday: List<MetaPreview> = emptyList(),
        val topShowsToday: List<MetaPreview> = emptyList(),
        val becauseYouWatched: BecauseYouWatched? = null,
        val forYou: List<MetaPreview> = emptyList()
    )

    private val _rows = MutableStateFlow(Rows())
    val rows: StateFlow<Rows> = _rows.asStateFlow()

    /** True once a refresh has run to the end (rows or not), so the app's first Home can wait for it. */
    private val _firstLoadDone = MutableStateFlow(false)
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

    private val lock = Mutex()
    private var chartsDay: LocalDate? = null
    private var chartsLanguage: String? = null
    /** What the personal rows were last built from, so they're rebuilt only when it changes. */
    private var personalSignature: String? = null

    private val hasTmdb: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isNotBlank()

    /** Fetches what's stale: the charts once a day, the personal rows when what's been watched changes. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!hasTmdb) {
            _firstLoadDone.value = true
            return@withContext
        }
        if (!lock.tryLock()) return@withContext
        try {
            val language = tmdbSettingsDataStore.settings.value.language
            val today = LocalDate.now()
            if (chartsDay != today || chartsLanguage != language || _rows.value.trendingWeek.isEmpty()) {
                loadCharts(language)?.let { (week, movies, shows) ->
                    chartsDay = today
                    chartsLanguage = language
                    _rows.value = _rows.value.copy(trendingWeek = week, topMoviesToday = movies, topShowsToday = shows)
                }
            }
            loadPersonal(language, betaSettings.current.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "built-in rows failed: ${e.javaClass.simpleName}")
        } finally {
            _firstLoadDone.value = true
            lock.unlock()
        }
    }

    private suspend fun loadCharts(language: String): Triple<List<MetaPreview>, List<MetaPreview>, List<MetaPreview>>? = coroutineScope {
        val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
        val week = async { tmdbApi.getTrending("all", "week", key, language).body()?.results }
        val movies = async { tmdbApi.getTrending("movie", "day", key, language).body()?.results }
        val shows = async { tmdbApi.getTrending("tv", "day", key, language).body()?.results }
        val w = week.await() ?: return@coroutineScope null
        Triple(
            w.mapNotNull { it.toPreview(isTv = it.mediaType == "tv") }.take(20),
            movies.await().orEmpty().mapNotNull { it.toPreview(isTv = false) }.take(20),
            shows.await().orEmpty().mapNotNull { it.toPreview(isTv = true) }.take(20)
        )
    }

    private suspend fun loadPersonal(language: String, settings: BetaPerformanceSettings) {
        val progress = withTimeoutOrNull(5_000) { watchProgressRepository.allProgress.first() }.orEmpty()
        val watchedItems = withTimeoutOrNull(5_000) { watchProgressRepository.watchedItems.first() }.orEmpty()
        val ratings = withTimeoutOrNull(5_000) { titleRatingsStore.current() } ?: com.nuvio.tv.data.local.TitleRatings()
        val dislikedIds = ratings.titles.values
            .filter { it.rating == com.nuvio.tv.data.local.TitleRating.DISLIKE }.map { it.contentId }.toSet()

        val viewedSeed = BuiltInRanking.mostRecentViewed(
            progress.filter { it.contentId !in dislikedIds }, watchedItems.filter { it.contentId !in dislikedIds },
            settings.viewedThresholdUnit, settings.viewedThresholdMinutes, settings.viewedThresholdPercent
        )
        val watchedSeeds = BuiltInRanking.recentWatched(progress, watchedItems, MAX_FOR_YOU_SEEDS)
        val forYouSeeds = Taste.forYouSeeds(watchedSeeds, ratings, MAX_FOR_YOU_SEEDS)
        val dislikedSeeds = Taste.dislikedSeeds(ratings).take(MAX_PENALTY_SEEDS)
        val signature = listOf(
            profileManager.activeProfileId.value,
            language,
            viewedSeed?.id,
            watchedSeeds.joinToString(",") { it.id },
            ratings.signature
        ).joinToString("|")
        if (signature == personalSignature) return

        val notWatched = BuiltInRanking.notWatched(progress, watchedItems)
        val notRated = Taste.notRated(ratings)
        val keep: (MetaPreview) -> Boolean = { notWatched(it) && notRated(it) }
        val taste = GeminiRanker.Taste(
            loved = ratings.titles.values.filter { it.rating == com.nuvio.tv.data.local.TitleRating.LOVE }.map { it.title },
            liked = ratings.titles.values.filter { it.rating == com.nuvio.tv.data.local.TitleRating.LIKE }.map { it.title },
            disliked = dislikedSeeds.map { it.title }
        )
        // A failed fetch (e.g. offline at startup) leaves the signature unset, so the next refresh tries again.
        var failed = false
        suspend fun recs(seed: BuiltInRanking.Seed): List<MetaPreview> =
            recommendations(seed, language) ?: emptyList<MetaPreview>().also { failed = true }

        val because = viewedSeed?.let { seed ->
            val candidates = recs(seed).filter(keep).take(20)
            val ranked = geminiRanker.rank(listOf(seed.title), candidates, minKeep = 6, taste = taste)
            if (ranked.size >= 4) BecauseYouWatched(seed.title, ranked) else null
        }
        val forYou = if (forYouSeeds.size < MIN_FOR_YOU_SEEDS) emptyList() else {
            val (recsBySeed, penalties) = coroutineScope {
                val positive = forYouSeeds.map { s -> async { recs(s.seed) to s.weight } }
                // A disliked title's recommendations only lower scores, so a failed fetch there isn't a failure.
                val negative = dislikedSeeds.map { seed -> async { recommendations(seed, language).orEmpty() } }
                positive.awaitAll() to negative.awaitAll()
            }
            val candidates = BuiltInRanking.scoreWeighted(recsBySeed, keep, penalties)
            geminiRanker.rank(forYouSeeds.map { it.seed.title }, candidates, minKeep = 10, taste = taste).take(20)
        }
        if (!failed) personalSignature = signature
        // Keep what was shown before rather than blanking a row because a fetch failed.
        val current = _rows.value
        _rows.value = current.copy(
            becauseYouWatched = if (failed && because == null) current.becauseYouWatched else because,
            forYou = if (failed && forYou.isEmpty()) current.forYou else forYou
        )
    }

    private val recommendationCache = HashMap<String, List<MetaPreview>>()

    private suspend fun recommendations(seed: BuiltInRanking.Seed, language: String): List<MetaPreview>? {
        val isTv = seed.type.equals("series", true) || seed.type.equals("tv", true)
        val cacheKey = "${seed.id}:$language"
        synchronized(recommendationCache) { recommendationCache[cacheKey] }?.let { return it }
        val tmdbId = runCatching { tmdbService.ensureTmdbId(seed.id, if (isTv) "tv" else "movie") }
            .getOrNull()?.toIntOrNull() ?: return null
        val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
        val results = runCatching {
            if (isTv) tmdbApi.getTvRecommendations(tmdbId, key, language).body()?.results
            else tmdbApi.getMovieRecommendations(tmdbId, key, language).body()?.results
        }.getOrNull() ?: return null
        val previews = results.mapNotNull { it.toPreview(defaultTv = isTv) }
        synchronized(recommendationCache) { recommendationCache[cacheKey] = previews }
        return previews
    }

    private fun TmdbDiscoverResult.toPreview(isTv: Boolean): MetaPreview? {
        if (adult == true || mediaType == "person") return null
        return preview(
            id = id, isTv = isTv, title = if (isTv) name ?: title else title ?: name,
            originalTitle = originalTitle ?: originalName, posterPath = posterPath, backdropPath = backdropPath,
            overview = overview, date = if (isTv) firstAirDate else releaseDate, rating = voteAverage, votes = voteCount
        )
    }

    private fun TmdbRecommendationResult.toPreview(defaultTv: Boolean): MetaPreview? {
        val isTv = when (mediaType) {
            "tv" -> true
            "movie" -> false
            else -> defaultTv
        }
        return preview(
            id = id, isTv = isTv, title = if (isTv) name ?: title else title ?: name,
            originalTitle = originalTitle ?: originalName, posterPath = posterPath, backdropPath = backdropPath,
            overview = overview, date = if (isTv) firstAirDate else releaseDate, rating = voteAverage, votes = voteCount
        )
    }

    private fun preview(
        id: Int, isTv: Boolean, title: String?, originalTitle: String?, posterPath: String?, backdropPath: String?,
        overview: String?, date: String?, rating: Double?, votes: Int?
    ): MetaPreview? {
        val name = title?.takeIf { it.isNotBlank() } ?: originalTitle?.takeIf { it.isNotBlank() } ?: return null
        // Highlighted cards are all backdrop: a title without one would be an empty card.
        val backdrop = backdropPath?.takeIf { it.isNotBlank() } ?: return null
        val released = date?.takeIf { it.isNotBlank() }
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isTv) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (isTv) "series" else "movie",
            name = name,
            poster = posterPath?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE/w342$it" },
            posterShape = PosterShape.POSTER,
            background = "$IMAGE_BASE/w1280$backdrop",
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = released?.take(4),
            released = released,
            imdbRating = rating?.takeIf { it > 0 }?.toFloat(),
            voteCount = votes,
            genres = emptyList()
        )
    }

    private companion object {
        const val TAG = "BuiltInRows"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        const val MIN_FOR_YOU_SEEDS = 3
        const val MAX_FOR_YOU_SEEDS = 8
        const val MAX_PENALTY_SEEDS = 3
    }
}
