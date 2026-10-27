package com.nuvio.tv.core.browse

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.KitsuAnime
import com.nuvio.tv.data.remote.api.KitsuAnimeResponse
import com.nuvio.tv.data.remote.api.KitsuApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the next page of each source of a browse grid starts; null once that source is used up.
 * [kitsu] is an offset into Kitsu's anime list, used where TMDB runs short of anime.
 */
data class BrowseCursor(val movie: Int?, val tv: Int?, val kitsu: Int? = null) {
    val exhausted: Boolean get() = movie == null && tv == null && kitsu == null
}

data class BrowsePage(val items: List<MetaPreview>, val next: BrowseCursor)

/**
 * Feeds the Genres tab and the keyboard search screen. TMDB drives everything when the build has
 * an API key (genres, keywords, latest, most rated, trending). Without one it falls back to
 * Cinemeta's popular and featured catalogs, which cover genres but have no keywords or dates.
 */
@Singleton
class BrowseRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val catalogRepository: CatalogRepository,
    private val kitsuApi: KitsuApi,
    private val addonRepository: AddonRepository
) {
    val hasTmdb: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isNotBlank()

    private val apiKey: String get() = com.nuvio.tv.core.apikeys.ApiKeys.tmdb

    private data class CachedPage(val page: BrowsePage, val storedAtMs: Long)

    private val pageCache = object : LinkedHashMap<String, CachedPage>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPage>?): Boolean =
            size > MAX_CACHED_PAGES
    }
    private val pagesInFlight = ConcurrentHashMap<String, CompletableDeferred<BrowsePage>>()

    private val keywordPrefs = context.getSharedPreferences(KEYWORD_PREFS, Context.MODE_PRIVATE)
    private val keywordIds = ConcurrentHashMap<String, Int>()
    private val keywordsInFlight = ConcurrentHashMap<String, CompletableDeferred<Int?>>()

    /** Genres the active source can serve; Cinemeta has no anime or music filter. */
    fun availableGenres(mode: BrowseMediaMode = BrowseMediaMode.ALL): List<BrowseGenre> = when {
        !hasTmdb -> BrowseCatalog.genres.filter { it.cinemetaGenre != null }
        mode == BrowseMediaMode.ANIME -> BrowseCatalog.genres.filter { it.hasAnime }
        else -> BrowseCatalog.genres
    }

    fun supportsKeywords(): Boolean = hasTmdb

    fun supportedSorts(): List<BrowseSort> =
        if (hasTmdb) BrowseSort.entries else listOf(BrowseSort.POPULAR, BrowseSort.MOST_RATED)

    /** Cached pages come back without a request; [cursor] null asks for the first page. */
    fun cachedPage(request: BrowseRequest, cursor: BrowseCursor?): BrowsePage? {
        val key = cacheKey(request, cursor)
        return synchronized(pageCache) {
            pageCache[key]?.takeIf { System.currentTimeMillis() - it.storedAtMs < CACHE_TTL_MS }?.page
        }
    }

    suspend fun load(request: BrowseRequest, cursor: BrowseCursor?): BrowsePage =
        withContext(Dispatchers.IO) { loadShared(request, cursor) }

    /** Fetches past the page cache (for the speed test), then caches the fresh page as usual. */
    suspend fun loadFresh(request: BrowseRequest, cursor: BrowseCursor? = null): BrowsePage = withContext(Dispatchers.IO) {
        val page = if (hasTmdb) loadFromTmdb(request, cursor) else loadFromCinemeta(request, cursor)
        synchronized(pageCache) { pageCache[cacheKey(request, cursor)] = CachedPage(page, System.currentTimeMillis()) }
        page
    }

    /** One request per page however many screens ask for it at once. */
    private suspend fun loadShared(request: BrowseRequest, cursor: BrowseCursor?): BrowsePage {
        val key = cacheKey(request, cursor)
        while (true) {
            cachedPage(request, cursor)?.let { return it }
            val existing = pagesInFlight[key]
            if (existing != null) {
                try {
                    return existing.await()
                } catch (cancelled: CancellationException) {
                    // The caller that started this request went away (say a prefetch that was
                    // superseded); unless this caller is cancelled too, fetch it here instead.
                    currentCoroutineContext().ensureActive()
                    pagesInFlight.remove(key, existing)
                    continue
                }
            }
            val deferred = CompletableDeferred<BrowsePage>()
            if (pagesInFlight.putIfAbsent(key, deferred) != null) continue
            try {
                val page = if (hasTmdb) loadFromTmdb(request, cursor) else loadFromCinemeta(request, cursor)
                synchronized(pageCache) { pageCache[key] = CachedPage(page, System.currentTimeMillis()) }
                deferred.complete(page)
                return page
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
                throw error
            } finally {
                pagesInFlight.remove(key, deferred)
            }
        }
    }

    private val titleSearchCache = object : LinkedHashMap<String, List<MetaPreview>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MetaPreview>>?): Boolean =
            size > 24
    }

    /**
     * Movies and shows matching [text] in TMDB's popularity-weighted order, used to rank the addon
     * search results the way WuPlay does. Empty without a TMDB key.
     */
    suspend fun searchTitles(text: String): List<MetaPreview> = withContext(Dispatchers.IO) {
        val query = text.trim()
        if (!hasTmdb || query.length < 2) return@withContext emptyList()
        val cacheKey = "$language|${query.lowercase(Locale.ROOT)}"
        synchronized(titleSearchCache) { titleSearchCache[cacheKey] }?.let { return@withContext it }
        val pages = coroutineScope {
            (1..2).map { page -> async { runCatching { tmdbApi.searchMulti(apiKey, query, language, page).body() }.getOrNull() } }
                .map { it.await() }
        }
        val items = pages.filterNotNull().flatMap { it.results.orEmpty() }.mapNotNull { result ->
            when (result.mediaType) {
                "movie" -> result.toPreview(isTv = false)
                "tv" -> result.toPreview(isTv = true)
                else -> null
            }
        }.distinctBy { it.id }
        synchronized(titleSearchCache) { titleSearchCache[cacheKey] = items }
        items
    }

    /** TMDB keywords matching [text], best matches first. */
    suspend fun searchKeywords(text: String, limit: Int = 8): List<BrowseKeyword> = withContext(Dispatchers.IO) {
        val query = text.trim()
        if (!hasTmdb || query.length < 2) return@withContext emptyList()
        val lower = query.lowercase(Locale.ROOT)
        val results = tmdbApi.searchKeywords(apiKey, query).body()?.results.orEmpty()
        results
            .mapNotNull { result ->
                val name = result.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return@mapNotNull null
                BrowseKeyword(label = name.toTitleCase(), tmdbName = name, tmdbId = result.id)
            }
            .distinctBy { it.tmdbName.lowercase(Locale.ROOT) }
            .sortedWith(
                compareBy<BrowseKeyword> {
                    val name = it.tmdbName.lowercase(Locale.ROOT)
                    when {
                        name == lower -> 0
                        name.startsWith(lower) -> 1
                        name.split(' ').any { word -> word.startsWith(lower) } -> 2
                        else -> 3
                    }
                }.thenBy { it.tmdbName.length }
            )
            .take(limit)
            .also { keywords -> keywords.forEach { kw -> kw.tmdbId?.let { keywordIds[kw.tmdbName.lowercase(Locale.ROOT)] = it } } }
    }

    private val language: String get() = tmdbSettingsDataStore.settings.value.language

    private fun cacheKey(request: BrowseRequest, cursor: BrowseCursor?): String =
        "${if (hasTmdb) "tmdb|$language" else "cinemeta"}|${request.key}|${cursor?.movie}:${cursor?.tv}:${cursor?.kitsu}"

    // region TMDB

    private suspend fun loadFromTmdb(request: BrowseRequest, cursor: BrowseCursor?): BrowsePage = coroutineScope {
        if (request.trending) {
            val page = cursor?.movie ?: 1
            val body = tmdbApi.getTrending("all", "day", apiKey, language, page).bodyOrThrow()
            val items = body.results.orEmpty().mapNotNull { result ->
                when (result.mediaType) {
                    "movie" -> result.toPreview(isTv = false)
                    "tv" -> result.toPreview(isTv = true)
                    else -> null
                }
            }
            val next = if (page < (body.totalPages ?: page) && items.isNotEmpty()) page + 1 else null
            return@coroutineScope BrowsePage(items, BrowseCursor(movie = next, tv = null))
        }

        // Past TMDB's last page an anime grid continues with Kitsu.
        if (cursor != null && cursor.movie == null && cursor.tv == null && cursor.kitsu != null) {
            return@coroutineScope kitsuPage(request, cursor.kitsu)
        }

        val wantsMovies = request.mode != BrowseMediaMode.SHOWS && (request.genre == null || request.genre.movie != null)
        val wantsShows = request.mode != BrowseMediaMode.MOVIES && (request.genre == null || request.genre.tv != null)
        val moviePage = if (cursor == null) (if (wantsMovies) 1 else null) else cursor.movie
        val tvPage = if (cursor == null) (if (wantsShows) 1 else null) else cursor.tv

        val movies = moviePage?.let { page -> async { discoverTmdb(request, isTv = false, page, language) } }
        val shows = tvPage?.let { page -> async { discoverTmdb(request, isTv = true, page, language) } }
        val movieResult = movies?.await()
        val showResult = shows?.await()

        val page = BrowsePage(
            items = mergeBySort(movieResult?.first.orEmpty(), showResult?.first.orEmpty()),
            next = BrowseCursor(movie = movieResult?.second, tv = showResult?.second)
        )
        // TMDB knows little anime for some genres; Kitsu fills in when it has next to nothing.
        val tmdbRanShort = cursor == null &&
            request.mode == BrowseMediaMode.ANIME &&
            page.next.exhausted &&
            page.items.size < KITSU_FALLBACK_BELOW
        if (!tmdbRanShort) return@coroutineScope page
        val kitsu = try {
            kitsuPage(request, offset = 0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.i(TAG, "Kitsu fallback failed: ${error.message}")
            return@coroutineScope page
        }
        val seen = page.items.mapTo(HashSet()) { it.name.titleKey() }
        BrowsePage(page.items + kitsu.items.filter { seen.add(it.name.titleKey()) }, kitsu.next)
    }

    private data class Ranked(val preview: MetaPreview, val metric: Double)

    private suspend fun discoverTmdb(
        request: BrowseRequest,
        isTv: Boolean,
        page: Int,
        language: String
    ): Pair<List<Ranked>, Int?> {
        val filter = if (isTv) request.genre?.tv else request.genre?.movie
        // A picked keyword already narrows the genre; requiring the genre's own stand-in keyword
        // as well (TMDB ANDs comma-separated keywords) would leave almost nothing.
        val keyword = request.keyword ?: filter?.keyword
        val keywordIdsCsv = keyword?.let { keywordId(it) }?.toString()
        if (keyword != null && keywordIdsCsv == null) return emptyList<Ranked>() to null

        // Anime: Japanese animation within the genre (comma-separated TMDB genres must all match).
        val anime = request.mode == BrowseMediaMode.ANIME
        val genreIds = when {
            !anime -> filter?.genreIds
            filter?.genreIds == null || filter.genreIds == ANIMATION_GENRE -> ANIMATION_GENRE
            else -> "$ANIMATION_GENRE,${filter.genreIds}"
        }
        val originalLanguage = if (anime) "ja" else filter?.originalLanguage
        val today = LocalDate.now().toString()
        val sort = request.sort.takeIf { request.keyword == null } ?: BrowseSort.POPULAR
        val response: Response<TmdbDiscoverResponse> = if (isTv) {
            tmdbApi.discoverTv(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = when (sort) {
                    BrowseSort.POPULAR -> "popularity.desc"
                    BrowseSort.LATEST -> "first_air_date.desc"
                    BrowseSort.MOST_RATED -> "vote_average.desc"
                },
                firstAirDateLte = if (sort == BrowseSort.LATEST) today else null,
                voteCountGte = when (sort) {
                    BrowseSort.POPULAR -> null
                    BrowseSort.LATEST -> 3
                    BrowseSort.MOST_RATED -> 150
                },
                withGenres = genreIds,
                withOriginalLanguage = originalLanguage,
                withKeywords = keywordIdsCsv
            )
        } else {
            tmdbApi.discoverMovies(
                apiKey = apiKey,
                language = language,
                page = page,
                sortBy = when (sort) {
                    BrowseSort.POPULAR -> "popularity.desc"
                    BrowseSort.LATEST -> "primary_release_date.desc"
                    BrowseSort.MOST_RATED -> "vote_average.desc"
                },
                releaseDateLte = if (sort == BrowseSort.LATEST) today else null,
                voteCountGte = when (sort) {
                    BrowseSort.POPULAR -> null
                    BrowseSort.LATEST -> 5
                    BrowseSort.MOST_RATED -> 300
                },
                withGenres = genreIds,
                withOriginalLanguage = originalLanguage,
                withKeywords = keywordIdsCsv
            )
        }
        val body = response.bodyOrThrow()
        val ranked = body.results.orEmpty().mapNotNull { result ->
            val preview = result.toPreview(isTv) ?: return@mapNotNull null
            val metric = when (sort) {
                BrowseSort.POPULAR -> result.popularity ?: 0.0
                BrowseSort.LATEST -> (preview.released ?: "").toEpochDayOrZero().toDouble()
                BrowseSort.MOST_RATED -> result.voteAverage ?: 0.0
            }
            Ranked(preview, metric)
        }
        val next = if (page < (body.totalPages ?: page) && ranked.isNotEmpty()) page + 1 else null
        return ranked to next
    }

    /** Both lists are already sorted by TMDB; merging keeps that order across movies and shows. */
    private fun mergeBySort(movies: List<Ranked>, shows: List<Ranked>): List<MetaPreview> {
        if (movies.isEmpty()) return shows.map { it.preview }
        if (shows.isEmpty()) return movies.map { it.preview }
        val merged = ArrayList<MetaPreview>(movies.size + shows.size)
        var m = 0
        var s = 0
        while (m < movies.size || s < shows.size) {
            val takeMovie = when {
                m >= movies.size -> false
                s >= shows.size -> true
                else -> movies[m].metric >= shows[s].metric
            }
            if (takeMovie) merged += movies[m++].preview else merged += shows[s++].preview
        }
        return merged
    }

    /** TMDB id for [keyword]: curated and searched keywords carry one; anything else is looked up once. */
    private suspend fun keywordId(keyword: BrowseKeyword): Int? {
        keyword.tmdbId?.let { return it }
        val name = keyword.tmdbName.lowercase(Locale.ROOT)
        while (true) {
            keywordIds[name]?.let { return it }
            keywordPrefs.getInt(name, -1).takeIf { it > 0 }?.let {
                keywordIds[name] = it
                return it
            }
            val existing = keywordsInFlight[name]
            if (existing != null) {
                try {
                    return existing.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    keywordsInFlight.remove(name, existing)
                    continue
                }
            }
            val deferred = CompletableDeferred<Int?>()
            if (keywordsInFlight.putIfAbsent(name, deferred) != null) continue
            try {
                val response = tmdbApi.searchKeywords(apiKey, keyword.tmdbName)
                // A failed lookup must not read as "no such keyword", or an empty page gets cached.
                val results = response.bodyOrThrow().results.orEmpty()
                val id = (results.firstOrNull { it.name.equals(keyword.tmdbName, ignoreCase = true) } ?: results.firstOrNull())?.id
                if (id != null) {
                    keywordIds[name] = id
                    keywordPrefs.edit().putInt(name, id).apply()
                } else {
                    Log.i(TAG, "No TMDB keyword for \"${keyword.tmdbName}\"")
                }
                deferred.complete(id)
                return id
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
                throw error
            } finally {
                keywordsInFlight.remove(name, deferred)
            }
        }
    }

    private fun TmdbDiscoverResult.toPreview(isTv: Boolean): MetaPreview? {
        if (adult == true) return null
        val poster = posterPath?.takeIf { it.isNotBlank() } ?: return null
        val title = (if (isTv) name ?: title else title ?: name)?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val date = (if (isTv) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
        val type = if (isTv) ContentType.SERIES else ContentType.MOVIE
        return MetaPreview(
            id = "tmdb:$id",
            type = type,
            rawType = if (isTv) "series" else "movie",
            name = title,
            poster = "$TMDB_IMAGE_BASE/w342$poster",
            posterShape = PosterShape.POSTER,
            background = backdropPath?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE_BASE/w1280$it" },
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
            released = date,
            imdbRating = voteAverage?.takeIf { it > 0 }?.toFloat(),
            voteCount = voteCount,
            genres = emptyList()
        )
    }

    // endregion

    // region Kitsu

    private val kitsuCategoryValid = ConcurrentHashMap<String, Boolean>()

    /** What to ask Kitsu for: a category, and a text search for keywords it has no category for. */
    private data class KitsuQuery(val category: String?, val text: String?)

    private suspend fun kitsuQueryFor(request: BrowseRequest): KitsuQuery? {
        val genreCategory = request.genre?.kitsuCategory?.ifEmpty { null }
        val keyword = request.keyword
        if (keyword != null) {
            val slug = KITSU_KEYWORD_CATEGORIES[keyword.label]
                ?: keyword.label.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-')
            if (kitsuCategoryExists(slug)) return KitsuQuery(category = slug, text = null)
            // Searching titles and synopses within the genre finds Spy x Family for "Spy".
            return KitsuQuery(category = genreCategory, text = keyword.label)
        }
        val genre = request.genre ?: return KitsuQuery(category = null, text = null)
        return genre.kitsuCategory?.let { KitsuQuery(category = it.ifEmpty { null }, text = null) }
    }

    /** Kitsu returns every anime for an unknown category, so slugs are checked once first. */
    private suspend fun kitsuCategoryExists(slug: String): Boolean {
        if (slug.isEmpty()) return false
        kitsuCategoryValid[slug]?.let { return it }
        val exists = kitsuApi.categories(slug).bodyOrThrow().data.orEmpty().isNotEmpty()
        kitsuCategoryValid[slug] = exists
        return exists
    }

    private suspend fun kitsuPage(request: BrowseRequest, offset: Int): BrowsePage {
        val query = kitsuQueryFor(request) ?: return BrowsePage(emptyList(), BrowseCursor(null, null))
        val sort = request.sort.takeIf { request.keyword == null } ?: BrowseSort.POPULAR
        val response = kitsuApi.anime(
            category = query.category,
            text = query.text,
            status = if (sort == BrowseSort.LATEST) "current,finished" else null,
            sort = when (sort) {
                BrowseSort.POPULAR -> "popularityRank"
                BrowseSort.LATEST -> "-startDate"
                BrowseSort.MOST_RATED -> "ratingRank"
            },
            limit = KITSU_PAGE,
            offset = offset
        ).bodyOrThrow()
        // A text search also matches one-off specials and unrated shorts; keep what people watch.
        val items = response.toPreviews().filter { query.text == null || it.imdbRating != null }
        val next = if (response.links?.next != null && response.data.orEmpty().isNotEmpty()) offset + KITSU_PAGE else null
        return BrowsePage(items, BrowseCursor(movie = null, tv = null, kitsu = next))
    }

    private fun KitsuAnimeResponse.toPreviews(): List<MetaPreview> {
        val categoryTitles = included.orEmpty()
            .filter { it.type == "categories" && it.id != null }
            .associate { it.id!! to it.attributes?.title.orEmpty() }
        return data.orEmpty().mapNotNull { anime -> anime.toPreview(categoryTitles) }
    }

    private fun KitsuAnime.toPreview(categoryTitles: Map<String, String>): MetaPreview? {
        val attrs = attributes ?: return null
        if (attrs.nsfw == true || attrs.ageRating.equals("R18", ignoreCase = true)) return null
        if (attrs.subtype.equals("music", ignoreCase = true) || attrs.subtype.equals("special", ignoreCase = true)) return null
        val poster = attrs.posterImage?.medium ?: attrs.posterImage?.small ?: attrs.posterImage?.large ?: return null
        val titles = attrs.titles.orEmpty()
        val name = listOf(titles["en"], titles["en_us"], attrs.canonicalTitle, titles["en_jp"])
            .firstOrNull { !it.isNullOrBlank() } ?: return null
        val isMovie = attrs.subtype.equals("movie", ignoreCase = true)
        // Kitsu mixes genres with settings and themes ("Earth", "Pirate"); genres go first.
        val genres = relationships?.categories?.data.orEmpty()
            .mapNotNull { ref -> categoryTitles[ref.id]?.takeIf { it.isNotBlank() } }
            .sortedBy { if (it in KITSU_GENRE_TITLES) 0 else 1 }
        return MetaPreview(
            id = "kitsu:$id",
            type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
            rawType = if (isMovie) "movie" else "series",
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = attrs.coverImage?.large ?: attrs.coverImage?.original,
            logo = null,
            description = attrs.synopsis?.takeIf { it.isNotBlank() },
            releaseInfo = attrs.startDate?.take(4),
            released = attrs.startDate,
            imdbRating = attrs.averageRating?.toFloatOrNull()?.div(10f)?.takeIf { it > 0f },
            genres = genres,
            ageRating = attrs.ageRating,
            runtime = if (isMovie) attrs.episodeLength?.takeIf { it > 0 }?.toString() else null
        )
    }

    /**
     * Kitsu titles open through an installed addon that serves Kitsu ids; without one they open as
     * the matching TMDB title, which needs no addon. Anything else passes through unchanged.
     */
    suspend fun resolveForDetails(item: MetaPreview): MetaPreview = withContext(Dispatchers.IO) {
        if (!item.id.startsWith("kitsu:")) return@withContext item
        val addons = runCatching { addonRepository.getInstalledAddons().first().enabledAddons() }.getOrDefault(emptyList())
        val servesKitsu = addons.any { addon ->
            val meta = addon.resources.firstOrNull { it.name == "meta" } ?: return@any false
            (meta.idPrefixes ?: addon.idPrefixes).any { it.startsWith("kitsu") }
        }
        if (servesKitsu || !hasTmdb) return@withContext item
        val year = item.releaseInfo?.toIntOrNull()
        val wantTv = item.type != ContentType.MOVIE
        val results = runCatching { tmdbApi.searchMulti(apiKey, item.name, language).body()?.results }.getOrNull().orEmpty()
        val match = results
            .filter { (it.mediaType == "tv") == wantTv && (it.mediaType == "tv" || it.mediaType == "movie") }
            .sortedBy { result ->
                val resultYear = (result.firstAirDate ?: result.releaseDate)?.take(4)?.toIntOrNull()
                val yearGap = if (year != null && resultYear != null) kotlin.math.abs(year - resultYear) else 5
                yearGap + if (result.originalLanguage == "ja") 0 else 10
            }
            .firstOrNull()
            ?: return@withContext item
        match.toPreview(isTv = wantTv) ?: item
    }

    // endregion

    // region Cinemeta fallback

    private suspend fun loadFromCinemeta(request: BrowseRequest, cursor: BrowseCursor?): BrowsePage = coroutineScope {
        val genre = request.genre
        val genreName = genre?.cinemetaGenre
        if (request.keyword != null || (genre != null && genreName == null)) {
            return@coroutineScope BrowsePage(emptyList(), BrowseCursor(null, null))
        }
        val catalogId = if (request.sort == BrowseSort.MOST_RATED && !request.trending) "imdbRating" else "top"
        val wantsMovies = request.mode != BrowseMediaMode.SHOWS && genre?.cinemetaSeriesOnly != true
        val wantsShows = request.mode != BrowseMediaMode.MOVIES
        val movieSkip = if (cursor == null) (if (wantsMovies) 0 else null) else cursor.movie
        val tvSkip = if (cursor == null) (if (wantsShows) 0 else null) else cursor.tv
        val extras = genreName?.let { mapOf("genre" to it) }.orEmpty()

        val movies = movieSkip?.let { skip -> async { cinemetaCatalog("movie", catalogId, skip, extras) } }
        val shows = tvSkip?.let { skip -> async { cinemetaCatalog("series", catalogId, skip, extras) } }
        val movieResult = movies?.await()
        val showResult = shows?.await()

        // Cinemeta gives no comparable score across types, so alternate them.
        val a = movieResult?.first.orEmpty()
        val b = showResult?.first.orEmpty()
        val merged = ArrayList<MetaPreview>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < a.size) merged += a[i]
            if (i < b.size) merged += b[i]
        }
        BrowsePage(merged, BrowseCursor(movie = movieResult?.second, tv = showResult?.second))
    }

    private suspend fun cinemetaCatalog(
        type: String,
        catalogId: String,
        skip: Int,
        extras: Map<String, String>
    ): Pair<List<MetaPreview>, Int?> {
        val result = catalogRepository.getCatalog(
            addonBaseUrl = CINEMETA_URL,
            addonId = CINEMETA_ID,
            addonName = "Cinemeta",
            catalogId = catalogId,
            catalogName = catalogId,
            type = type,
            skip = skip,
            skipStep = CINEMETA_PAGE,
            extraArgs = extras,
            supportsSkip = true
        ).first { it !is NetworkResult.Loading }
        return when (result) {
            is NetworkResult.Success -> {
                val row = result.data
                val items = row.items.filter { !it.poster.isNullOrBlank() }
                items to (row.nextSkip.takeIf { row.hasMore && it > skip && skip + CINEMETA_PAGE <= CINEMETA_MAX_SKIP })
            }
            is NetworkResult.Error -> throw IllegalStateException(result.message)
            NetworkResult.Loading -> emptyList<MetaPreview>() to null
        }
    }

    // endregion

    private fun <T> Response<T>.bodyOrThrow(): T =
        if (isSuccessful) body() ?: error("Empty TMDB response") else error("TMDB request failed (${code()})")

    private fun String.titleKey(): String = lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "")

    private fun String.toEpochDayOrZero(): Long =
        runCatching { LocalDate.parse(take(10)).toEpochDay() }.getOrDefault(0L)

    private fun String.toTitleCase(): String =
        split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private companion object {
        const val TAG = "BrowseRepository"
        const val ANIMATION_GENRE = "16"
        const val KEYWORD_PREFS = "browse_keyword_ids"
        const val MAX_CACHED_PAGES = 80
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        const val CINEMETA_ID = "com.linvo.cinemeta"
        const val CINEMETA_PAGE = 100
        const val CINEMETA_MAX_SKIP = 1000
        const val KITSU_PAGE = 20
        /** Keywords whose Kitsu category has another name. */
        val KITSU_KEYWORD_CATEGORIES = mapOf(
            "Post-apocalyptic Future" to "post-apocalypse",
            "Organized Crime" to "mafia",
            "Gangster" to "mafia",
            "Soldier" to "military"
        )
        val KITSU_GENRE_TITLES = setOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Romance",
            "Science Fiction", "Thriller", "Slice of Life", "Sports", "Psychological", "Supernatural",
            "Mecha", "Music", "Military", "Historical", "Crime", "Kids", "Magic", "Martial Arts",
            "Super Power", "Isekai", "Space", "Samurai", "Vampire", "Demon", "Detective", "Police"
        )
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val NON_SLUG = Regex("[^a-z0-9]+")
        /** Anime grids with fewer TMDB titles than this get Kitsu's titles added. */
        const val KITSU_FALLBACK_BELOW = 10
    }
}
