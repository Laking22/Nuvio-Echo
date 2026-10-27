package com.nuvio.tv.core.home.smart

import android.util.Log
import com.nuvio.tv.core.home.BuiltInRanking
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One Smart Highlight ready for Home. [title] is what shows; [catalogueTitle] is the theme's own name. */
data class SmartHighlightRow(
    val themeId: String,
    val title: String,
    val catalogueTitle: String,
    /** True when [title] was written by Gemini (and passed [HighlightNameFilter]). */
    val aiNamed: Boolean,
    val items: List<MetaPreview>
)

/**
 * The new look's Smart Highlights: themed rows from the [SmartHighlightCatalog], built once a day per profile.
 * 1. The themes are put in today's order ([SmartHighlightPlanner]), lifted by the genres the profile watches (in the
 *    app and on every connected service: Trakt, Simkl, MDBList), saves (library and watchlists) and rates up on the
 *    details page, and lowered by the genres of titles marked "not for me". Themes shown in the last few days sink,
 *    so most days bring new ones rather than the same rows renamed.
 * 2. Each theme is a TMDB discover query for movies and one for shows, on a page that changes by day; watched
 *    titles and titles without a backdrop are dropped, so are rated ones, and no title shows in two Smart Highlights.
 *    Titles shown in the last few days go last, and themes with enough new titles come first.
 * 3. With a Gemini key, one request checks which titles really fit and (with AI-written names on) names the rows.
 * Blocks, snoozes and removed titles are applied on Home from the settings, so they take effect at once; a refresh
 * then fills the gap with the next theme. Without a TMDB key nothing shows.
 */
@Singleton
class SmartHighlightsRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val betaSettings: BetaPerformanceSettingsDataStore,
    private val profileManager: ProfileManager,
    private val classifier: SmartHighlightClassifier,
    private val titleRatingsStore: com.nuvio.tv.data.local.TitleRatingsStore,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val libraryRepository: LibraryRepository,
    @ApplicationContext context: Context
) {
    private val history = HighlightHistory(context)

    private val _rows = MutableStateFlow<List<SmartHighlightRow>>(emptyList())
    val rows: StateFlow<List<SmartHighlightRow>> = _rows.asStateFlow()

    /**
     * True once the first refresh has decided which rows show (Gemini's review afterwards only renames and trims
     * them), so the app's first Home can wait for it.
     */
    private val _firstLoadDone = MutableStateFlow(false)
    val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

    private val lock = Mutex()
    /** What the rows were last built from, so they're rebuilt only when it changes (a new day, a block...). */
    private var signature: String? = null
    private val genreCache = HashMap<String, List<Int>>()

    private val hasTmdb: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isNotBlank()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        // Read from the file, not the shared state: a refresh started by a block must see that block.
        val settings = betaSettings.settings.first()
        if (!settings.smartHighlights || !hasTmdb) {
            _rows.value = emptyList()
            signature = null
            _firstLoadDone.value = true
            return@withContext
        }
        if (!lock.tryLock()) return@withContext
        try {
            val language = tmdbSettingsDataStore.settings.value.language
            val today = LocalDate.now().toEpochDay()
            val profileId = profileManager.activeProfileId.value
            // What was watched in the app and on every connected service.
            val (connectedProgress, connectedWatched) = connectedHistory()
            val progress = withTimeoutOrNull(5_000) { watchProgressRepository.allProgress.first() }.orEmpty() +
                connectedProgress
            val watchedItems = withTimeoutOrNull(5_000) { watchProgressRepository.watchedItems.first() }.orEmpty() +
                connectedWatched
            val seeds = BuiltInRanking.recentWatched(progress, watchedItems, AFFINITY_SEEDS)
            val watchedIds = seeds.map { it.id }.toSet()
            val saved = savedTitles()
                .filter { it.id !in watchedIds }
                .sortedByDescending { it.listedAt }
                .take(SAVED_SEEDS)
                .map { BuiltInRanking.Seed(it.id, it.type, it.name, it.listedAt) }
            val ratings = withTimeoutOrNull(5_000) { titleRatingsStore.current() } ?: com.nuvio.tv.data.local.TitleRatings()
            val newSignature = listOf(
                profileId, language, today, settings.enrichment, settings.smartHighlightAiNames,
                settings.blockedHighlights.sorted(), settings.snoozedHighlights.sorted(),
                seeds.joinToString(",") { it.id }, saved.joinToString(",") { it.id }, ratings.signature
            ).joinToString("|")
            if (newSignature == signature) return@withContext

            // Watched titles count once, saved ones a little less; rated ones by their thumbs (a disliked one counts
            // against its genres).
            val rated = ratings.titles.values.sortedByDescending { it.ratedAt }.take(AFFINITY_SEEDS)
            val ratedIds = rated.map { it.contentId }.toSet()
            val weighted = seeds.filter { it.id !in ratedIds }.mapNotNull { seed -> genresOf(seed)?.let { it to 1.0 } } +
                saved.filter { it.id !in ratedIds }.mapNotNull { seed -> genresOf(seed)?.let { it to SAVED_WEIGHT } } +
                rated.mapNotNull { r ->
                    genresOf(BuiltInRanking.Seed(r.contentId, r.contentType, r.title, r.ratedAt))
                        ?.let { it to com.nuvio.tv.core.home.Taste.ratingWeight(r.rating) }
                }
            val affinity = com.nuvio.tv.core.home.Taste.genreAffinity(weighted)
            val ordered = SmartHighlightPlanner.order(
                SmartHighlightCatalog.themes, settings.blockedHighlights, settings.snoozedHighlights,
                today, profileId.toLong(), affinity, history.themes(profileId)
            )
            val recentTitles = history.recentTitles(profileId, today)
            val notWatched = BuiltInRanking.notWatched(progress, watchedItems)
            val notRated = com.nuvio.tv.core.home.Taste.notRated(ratings)
            val keep: (MetaPreview) -> Boolean = { notWatched(it) && notRated(it) }
            val wanted = SmartHighlightPlanner.maxRows(settings.enrichment)
            // A few spare themes, fetched together, in case some come back too thin (Heavy, with no limit, tries all).
            val tried = if (wanted == Int.MAX_VALUE) ordered else ordered.take(wanted + SPARE_THEMES)
            val gate = Semaphore(FETCH_PARALLEL)
            val fetched = coroutineScope {
                tried.map { theme -> async { gate.withPermit { theme to fetch(theme, language, today, keep) } } }.awaitAll()
            }
            if (fetched.all { it.second == null }) return@withContext // offline: keep what's shown, try again later

            val used = HashSet<String>()
            val picked = ArrayList<Pair<HighlightTheme, List<MetaPreview>>>()
            // Themes with enough titles that weren't in Smart Highlights these last days first; then, while rows are
            // still wanted, the rest.
            for (needNew in listOf(true, false)) {
                for ((theme, items) in fetched) {
                    if (picked.size >= wanted) break
                    if (picked.any { it.first.id == theme.id }) continue
                    val available = items.orEmpty().filter { it.id !in used }
                    val (new, seen) = available.partition { it.id !in recentTitles }
                    if (needNew && new.size < SmartHighlightPlanner.MIN_ITEMS + 2) continue
                    // Spare titles over the minimum, so removing a few still leaves a row; titles shown lately last.
                    val fresh = (new + seen).take(MAX_ITEMS)
                    if (fresh.size < SmartHighlightPlanner.MIN_ITEMS + 2) continue
                    picked += theme to fresh
                    used += fresh.map { it.id }
                }
            }
            history.record(profileId, today, picked.map { it.first.id }, picked.flatMap { (_, items) -> items.map { it.id } })

            // First load: show TMDB's rows now rather than after Gemini (up to 15 s); the review replaces them.
            if (_rows.value.isEmpty() && classifier.available) {
                _rows.value = picked.map { (theme, items) -> SmartHighlightRow(theme.id, theme.title, theme.title, false, items) }
            }
            _firstLoadDone.value = true
            val reviews = classifier.review(picked, settings.smartHighlightAiNames)
            val blockedNames = settings.blockedHighlights
                .filter { it.startsWith(com.nuvio.tv.data.local.BetaPerformanceSettings.SMART_NAME_PREFIX) }
                .map { it.removePrefix(com.nuvio.tv.data.local.BetaPerformanceSettings.SMART_NAME_PREFIX) }
                .toSet()
            _rows.value = picked.map { (theme, items) ->
                val review = reviews[theme.id]
                val byId = items.associateBy { it.id }
                val reviewed = review?.keep?.mapNotNull { byId[it] }.orEmpty()
                // Gemini's cut only when it leaves a full row; otherwise TMDB's order.
                val shown = if (reviewed.size >= SmartHighlightPlanner.MIN_ITEMS) reviewed else items
                val aiName = review?.name
                    ?.takeIf { settings.smartHighlightAiNames && HighlightNameFilter.isAcceptable(it, blockedNames) }
                if (review?.name != null && aiName == null) Log.i(TAG, "AI name for ${theme.id} rejected by the filter")
                SmartHighlightRow(
                    themeId = theme.id,
                    title = aiName ?: theme.title,
                    catalogueTitle = theme.title,
                    aiNamed = aiName != null,
                    items = shown
                )
            }
            signature = newSignature
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "smart highlights failed: ${e.javaClass.simpleName}")
        } finally {
            _firstLoadDone.value = true
            lock.unlock()
        }
    }

    /** The theme's movies and shows interleaved, or null when both requests failed. */
    private suspend fun fetch(
        theme: HighlightTheme,
        language: String,
        today: Long,
        keep: (MetaPreview) -> Boolean
    ): List<MetaPreview>? = coroutineScope {
        // Page 1, 2 or 3 by day, so a theme that comes back shows something different.
        val page = 1 + Math.floorMod(today + theme.id.hashCode(), DISCOVER_PAGES.toLong()).toInt()
        val movies = theme.movie?.let { q -> async { discover(q, isTv = false, language, page) } }
        val shows = theme.tv?.let { q -> async { discover(q, isTv = true, language, page) } }
        val m = movies?.await()
        val s = shows?.await()
        if (m == null && s == null) return@coroutineScope null
        interleave(m.orEmpty(), s.orEmpty()).filter(keep).distinctBy { it.id }
    }

    private suspend fun discover(q: ThemeQuery, isTv: Boolean, language: String, page: Int): List<MetaPreview>? {
        suspend fun load(p: Int): List<TmdbDiscoverResult>? = runCatching {
            val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
            val response = if (isTv) {
                tmdbApi.discoverTv(
                    apiKey = key, language = language, page = p, sortBy = q.sort, voteCountGte = q.minVotes,
                    withGenres = q.genres, voteAverageGte = q.minRating, withKeywords = q.keywords,
                    firstAirDateLte = q.releasedBefore, withoutGenres = q.withoutGenres,
                    runtimeGte = q.runtimeMin, runtimeLte = q.runtimeMax, voteCountLte = q.maxVotes
                )
            } else {
                tmdbApi.discoverMovies(
                    apiKey = key, language = language, page = p, sortBy = q.sort, voteCountGte = q.minVotes,
                    withGenres = q.genres, voteAverageGte = q.minRating, withKeywords = q.keywords,
                    releaseDateLte = q.releasedBefore, withoutGenres = q.withoutGenres,
                    runtimeGte = q.runtimeMin, runtimeLte = q.runtimeMax, voteCountLte = q.maxVotes
                )
            }
            if (response.isSuccessful) response.body()?.results.orEmpty() else null
        }.getOrNull()

        var results = load(page) ?: return null
        if (page > 1 && results.size < SmartHighlightPlanner.MIN_ITEMS) results = load(1) ?: results
        return results.mapNotNull { it.toPreview(isTv) }
    }

    /** Progress and watched titles on every connected service (Trakt, Simkl, MDBList) that's signed in. */
    private suspend fun connectedHistory(): Pair<List<WatchProgress>, List<WatchedItem>> = coroutineScope {
        val parts = progressProviders.providers().map { provider ->
            async {
                if (withTimeoutOrNull(2_000) { provider.isAuthenticated.first() } != true) return@async null
                val progress = withTimeoutOrNull(5_000) { provider.allProgress.first() }.orEmpty()
                val watched = withTimeoutOrNull(5_000) { provider.watchedItems.first() }.orEmpty()
                progress to watched
            }
        }.awaitAll().filterNotNull()
        parts.flatMap { it.first } to parts.flatMap { it.second }
    }

    /** Titles saved in the app's library (bins) and to the lists and watchlists of every connected service. */
    private suspend fun savedTitles(): List<LibraryEntry> = coroutineScope {
        val local = async { withTimeoutOrNull(3_000) { libraryRepository.libraryItems.first() }.orEmpty() }
        val connected = libraryProviders.providers().map { provider ->
            async {
                if (withTimeoutOrNull(2_000) { provider.isAuthenticated.first() } != true) emptyList()
                else withTimeoutOrNull(5_000) { provider.items.first() }.orEmpty()
            }
        }
        (local.await() + connected.awaitAll().flatten()).distinctBy { it.id }
    }

    /** TMDB genre ids of a watched title, for the affinity; cached for the app's run. */
    private suspend fun genresOf(seed: BuiltInRanking.Seed): List<Int>? {
        synchronized(genreCache) { genreCache[seed.id] }?.let { return it }
        val isTv = seed.type.equals("series", true) || seed.type.equals("tv", true)
        val tmdbId = runCatching { tmdbService.ensureTmdbId(seed.id, if (isTv) "tv" else "movie") }
            .getOrNull()?.toIntOrNull() ?: return null
        val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
        val genres = runCatching {
            (if (isTv) tmdbApi.getTvDetails(tmdbId, key) else tmdbApi.getMovieDetails(tmdbId, key))
                .body()?.genres?.map { it.id }
        }.getOrNull() ?: return null
        synchronized(genreCache) { genreCache[seed.id] = genres }
        return genres
    }

    private fun TmdbDiscoverResult.toPreview(isTv: Boolean): MetaPreview? {
        if (adult == true) return null
        val title = (if (isTv) name ?: title else title ?: name)?.takeIf { it.isNotBlank() }
            ?: (originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
        // Highlighted cards are all backdrop: a title without one would be an empty card.
        val backdrop = backdropPath?.takeIf { it.isNotBlank() } ?: return null
        val released = (if (isTv) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isTv) ContentType.SERIES else ContentType.MOVIE,
            rawType = if (isTv) "series" else "movie",
            name = title,
            poster = posterPath?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE/w342$it" },
            posterShape = PosterShape.POSTER,
            background = "$IMAGE_BASE/w1280$backdrop",
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = released?.take(4),
            released = released,
            imdbRating = voteAverage?.takeIf { it > 0 }?.toFloat(),
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    internal companion object {
        private const val TAG = "SmartHighlights"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        private const val AFFINITY_SEEDS = 12
        /** Saved titles (library, watchlists) that count toward the affinity, and how much each counts. */
        private const val SAVED_SEEDS = 8
        private const val SAVED_WEIGHT = 0.7
        private const val SPARE_THEMES = 3
        private const val MAX_ITEMS = 20
        /** TMDB discover pages a theme rotates through by day. */
        private const val DISCOVER_PAGES = 3
        /** Themes fetched at once (Heavy fetches every theme). */
        private const val FETCH_PARALLEL = 6

        fun <T> interleave(a: List<T>, b: List<T>): List<T> {
            val out = ArrayList<T>(a.size + b.size)
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(out::add)
                b.getOrNull(i)?.let(out::add)
            }
            return out
        }
    }
}
