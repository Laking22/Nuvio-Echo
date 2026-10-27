package com.nuvio.tv.core.browse

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class BrowseRatingSource { IMDB, TMDB, KITSU }

/** What the side panel shows for the poster in focus. */
@Immutable
data class BrowseItemDetails(
    val id: String,
    val title: String,
    val poster: String?,
    val logo: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val ageRating: String? = null,
    val rating: Float? = null,
    val ratingSource: BrowseRatingSource = BrowseRatingSource.IMDB,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val cast: List<String> = emptyList(),
    /** False while only the grid's own data is shown and the full details are still loading. */
    val isComplete: Boolean = false
)

/**
 * Loads panel details for a focused poster. TMDB items use the same enrichment call the details
 * page makes, and addon items the same meta lookup, so resting on a poster also warms the page it
 * opens.
 */
@Singleton
class BrowseDetailsLoader @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val browseRepository: BrowseRepository
) {
    private val cache = object : LinkedHashMap<String, BrowseItemDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowseItemDetails>?): Boolean =
            size > MAX_CACHED
    }

    fun cached(item: MetaPreview): BrowseItemDetails? = synchronized(cache) { cache[item.id] }

    /** Immediate panel content from the grid item, shown until [load] finishes. */
    fun preview(item: MetaPreview): BrowseItemDetails = cached(item) ?: BrowseItemDetails(
        id = item.id,
        title = item.name,
        poster = item.poster,
        logo = item.logo,
        year = item.releaseInfo?.take(4),
        runtime = item.runtime?.takeIf { it.isNotBlank() }?.let { if (it.any(Char::isLetter)) it else "$it min" },
        ageRating = item.ageRating,
        rating = item.imdbRating,
        ratingSource = when {
            item.id.startsWith("tmdb:") -> BrowseRatingSource.TMDB
            item.id.startsWith("kitsu:") -> BrowseRatingSource.KITSU
            else -> BrowseRatingSource.IMDB
        },
        genres = item.genres.take(3),
        overview = item.description
    )

    suspend fun load(item: MetaPreview): BrowseItemDetails {
        cached(item)?.let { return it }
        val tmdbId = item.id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.substringBefore(':')
        val details = if (tmdbId != null) {
            if (browseRepository.hasTmdb) loadFromTmdb(item, tmdbId) else null
        } else {
            loadFromAddons(item)
        } ?: preview(item).copy(isComplete = true)
        synchronized(cache) { cache[item.id] = details }
        return details
    }

    private suspend fun loadFromTmdb(item: MetaPreview, tmdbId: String): BrowseItemDetails? = coroutineScope {
        val language = tmdbSettingsDataStore.settings.value.language
        // The IMDb score comes from the addon meta for the IMDb id: the same id and lookup the
        // details page makes before it opens, so this also warms that page.
        val imdbRating = async {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                val imdbId = tmdbId.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, item.apiType) }
                    ?: return@withTimeoutOrNull null
                val result = metaRepository.getMetaFromAllAddons(item.apiType, imdbId)
                    .first { it !is NetworkResult.Loading }
                (result as? NetworkResult.Success<Meta>)?.data?.imdbRating?.takeIf { it > 0f }
            }
        }
        val enrichment = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            tmdbMetadataService.fetchEnrichment(tmdbId, item.type, language)
        } ?: return@coroutineScope null
        val imdb = imdbRating.await()
        BrowseItemDetails(
            id = item.id,
            title = enrichment.localizedTitle ?: item.name,
            poster = item.poster,
            logo = enrichment.logo,
            year = (enrichment.releaseInfo ?: item.releaseInfo)?.take(4),
            runtime = enrichment.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
            ageRating = enrichment.ageRating,
            rating = imdb ?: enrichment.rating?.toFloat()?.takeIf { it > 0f } ?: item.imdbRating,
            ratingSource = if (imdb != null) BrowseRatingSource.IMDB else BrowseRatingSource.TMDB,
            genres = enrichment.genres.take(3),
            overview = enrichment.description ?: item.description,
            cast = enrichment.castMembers.take(3).map { it.name },
            isComplete = true
        )
    }

    private suspend fun loadFromAddons(item: MetaPreview): BrowseItemDetails? {
        val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
                .first { it !is NetworkResult.Loading }
        }
        val meta = (result as? NetworkResult.Success<Meta>)?.data ?: return null
        return BrowseItemDetails(
            id = item.id,
            title = meta.name.ifBlank { item.name },
            poster = item.poster ?: meta.poster,
            logo = meta.logo ?: item.logo,
            year = (meta.releaseInfo ?: item.releaseInfo)?.take(4),
            runtime = meta.runtime?.takeIf { it.isNotBlank() }?.let { runtime ->
                if (runtime.any(Char::isLetter)) runtime else "$runtime min"
            },
            ageRating = meta.ageRating,
            rating = meta.imdbRating ?: item.imdbRating,
            ratingSource = if (meta.imdbRating == null && item.id.startsWith("kitsu:")) BrowseRatingSource.KITSU else BrowseRatingSource.IMDB,
            // Anime that IMDb/TMDB-based addons can't label keeps Kitsu's genres.
            genres = meta.genres.ifEmpty { item.genres }.take(3),
            overview = meta.description ?: item.description,
            // castMembers leads with directors and writers; the plain cast list is actors only.
            cast = meta.cast.ifEmpty {
                meta.castMembers.filter { it.character != "Director" && it.character != "Writer" }.map { it.name }
            }.take(3),
            isComplete = true
        )
    }

    private companion object {
        const val MAX_CACHED = 64
        const val LOAD_TIMEOUT_MS = 8_000L
    }
}
