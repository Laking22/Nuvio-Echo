package com.nuvio.tv.core.home.smart

import com.nuvio.tv.core.apikeys.ApiKeys
import com.nuvio.tv.data.mdblist.MdbListApiClient
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListLibraryItem
import com.nuvio.tv.data.mdblist.MdbListThemeList
import com.nuvio.tv.data.mdblist.decodeMdbListLibraryPage
import com.nuvio.tv.data.mdblist.decodeMdbListThemeLists
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Highlights from MDBList, for a profile signed in to it:
 * - the account's own lists (static and dynamic), of which one leads each day (see [SmartHighlightsRepository]), and
 * - MDBList's top lists, which join the day's other themes.
 * Rows keep the list's name. MDBList gives no backdrops, so each title's comes from TMDB (titles without one are
 * left out, as highlighted cards are all backdrop); without a TMDB key there are none.
 */
@Singleton
class MdbListHighlightSource @Inject constructor(
    private val api: MdbListApiClient,
    private val store: MdbListAuthStore,
    private val tmdbApi: TmdbApi
) {
    /** Today's themes per account, so Home's refreshes that day don't ask MDBList again. */
    private var cachedKey: String? = null
    private var cached = Themes()
    /** TMDB details by "movie:id" / "tv:id", null when TMDB has no backdrop for it. */
    private val details = object : LinkedHashMap<String, MetaPreview?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MetaPreview?>?) = size > DETAILS_CACHE
    }

    data class Themes(val own: List<HighlightTheme> = emptyList(), val top: List<HighlightTheme> = emptyList())

    val available: Boolean get() = store.state.value.isAuthenticated && ApiKeys.tmdb.isNotBlank()

    /** Today's MDBList themes; empty when MDBList can't be reached (tried again on the next refresh). */
    suspend fun themes(epochDay: Long): Themes = withContext(Dispatchers.IO) {
        val scope = store.scope()
        val key = "$epochDay|$scope"
        synchronized(this@MdbListHighlightSource) { if (cachedKey == key) return@withContext cached }
        val own = load("/lists/user", mapOf("unified" to "true", "sort" to "ranked"))
        val top = load("/lists/top", emptyMap())
        if (own == null && top == null) return@withContext Themes()
        val themes = Themes(
            own = own.orEmpty().filter(::isUsableOwn).map { it.toTheme(OWN_PREFIX) },
            top = top.orEmpty().filter(::isUsableTop).sortedByDescending { it.likes ?: 0 }
                .take(MAX_TOP_THEMES).map { it.toTheme(TOP_PREFIX) }
        )
        synchronized(this@MdbListHighlightSource) {
            cachedKey = key
            cached = themes
        }
        themes
    }

    /** A list theme's titles with TMDB backdrops, in the list's order; null when MDBList couldn't be reached. */
    suspend fun items(theme: HighlightTheme, language: String): List<MetaPreview>? = coroutineScope {
        val pages = theme.mdbListIds.map { id ->
            async {
                runCatching {
                    val query = mapOf("limit" to ITEMS_PER_LIST.toString(), "sort" to "rank", "order" to "asc", "unified" to "true")
                    decodeMdbListLibraryPage(api.get("/lists/$id/items", query).body).items
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
        }.awaitAll()
        if (pages.all { it == null }) return@coroutineScope null
        // A twin's movies and shows alternate, as in the other highlights.
        val listed = pages.filterNotNull().let { lists ->
            (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        }
        val gate = Semaphore(TMDB_PARALLEL)
        listed.take(ITEMS_PER_LIST)
            .map { item -> async { gate.withPermit { preview(item, language) } } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun load(path: String, query: Map<String, String>): List<MdbListThemeList>? =
        runCatching { decodeMdbListThemeLists(api.get(path, query).body) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    private suspend fun preview(item: MdbListLibraryItem, language: String): MetaPreview? {
        val tmdbId = item.media.ids.tmdb?.toInt() ?: return null
        val isTv = item.type == MdbListItemType.SHOW
        val key = "${if (isTv) "tv" else "movie"}:$tmdbId"
        synchronized(details) { if (details.containsKey(key)) return details[key] }
        val apiKey = ApiKeys.tmdb
        val response = runCatching {
            if (isTv) tmdbApi.getTvDetails(tmdbId, apiKey, language) else tmdbApi.getMovieDetails(tmdbId, apiKey, language)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return null
        // Only a definite answer is remembered; a failed request is tried again next time.
        if (!response.isSuccessful) return null
        val preview = response.body()?.toPreview(isTv)
        synchronized(details) { details[key] = preview }
        return preview
    }

    private fun TmdbDetailsResponse.toPreview(isTv: Boolean): MetaPreview? {
        val title = (if (isTv) name ?: title else title ?: name)?.takeIf { it.isNotBlank() }
            ?: (originalTitle ?: originalName)?.takeIf { it.isNotBlank() } ?: return null
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
            genres = genres.orEmpty().map { it.name }
        )
    }

    private fun MdbListThemeList.toTheme(prefix: String) = HighlightTheme(
        // The first id is the list's own (a twin's movie half); stable, so a blocked list stays blocked.
        id = prefix + ids.first(),
        title = name.trim(),
        definition = description?.trim()?.takeIf { it.isNotEmpty() }?.take(DEFINITION_LENGTH) ?: name.trim(),
        movie = null,
        tv = null,
        mdbListIds = ids
    )

    internal companion object {
        /** Theme ids of the account's own lists and of MDBList's top lists: this plus the list's MDBList id. */
        const val OWN_PREFIX = "mdblist_own_"
        const val TOP_PREFIX = "mdblist_top_"
        private const val MAX_TOP_THEMES = 10
        private const val ITEMS_PER_LIST = 40
        private const val TMDB_PARALLEL = 6
        private const val DETAILS_CACHE = 600
        private const val DEFINITION_LENGTH = 200
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        /** Own lists of what's already been watched would be empty once watched titles are taken out. */
        private val WATCHED = Regex("""\b(watched|seen|history|finished|completed)\b""", RegexOption.IGNORE_CASE)

        /**
         * One of the account's own lists that can make a row: some titles, and a name that isn't offensive and isn't
         * about what's been watched. The person named it, so the word-count rules for other names don't apply.
         */
        fun isUsableOwn(list: MdbListThemeList): Boolean {
            val name = list.name.trim()
            if (name.length !in 2..MAX_OWN_NAME) return false
            if ((list.itemCount ?: 0) < MIN_OWN_ITEMS) return false
            if (WATCHED.containsMatchIn(name)) return false
            return !HighlightNameFilter.isOffensive(name)
        }

        /** A top list that reads as a theme: the same rules as Trakt's lists. */
        fun isUsableTop(list: MdbListThemeList): Boolean {
            if (list.private) return false
            val count = list.itemCount ?: return false
            if (count !in MIN_TOP_ITEMS..MAX_TOP_ITEMS) return false
            if ((list.likes ?: 0) < MIN_LIKES) return false
            return HighlightNameFilter.isAcceptable(list.name)
        }

        private const val MIN_OWN_ITEMS = 8
        private const val MAX_OWN_NAME = 40
        private const val MIN_TOP_ITEMS = 15
        private const val MAX_TOP_ITEMS = 500
        private const val MIN_LIKES = 5
    }
}
