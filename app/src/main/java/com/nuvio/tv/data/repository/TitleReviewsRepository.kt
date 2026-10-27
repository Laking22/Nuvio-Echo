package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbReview
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reviews for the new details page. For a movie or a whole show: TMDB's reviews, plus Trakt's when the profile is
 * signed in to Trakt (reviews first, then the most-liked comments). For an episode only Trakt has any, so episodes
 * show reviews only when signed in. Both come back as [TraktCommentReview], so the existing review reader opens
 * either.
 */
@Singleton
class TitleReviewsRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val traktCommentsService: TraktCommentsService,
    private val traktAuthDataStore: TraktAuthDataStore
) {
    private val cache = HashMap<String, List<TraktCommentReview>>()

    suspend fun titleReviews(meta: Meta, itemId: String?, itemType: String?): List<TraktCommentReview> =
        withContext(Dispatchers.IO) {
            val key = "title|${meta.id}"
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val (trakt, tmdb) = coroutineScope {
                val trakt = async { trakt(meta, itemId, itemType, null) }
                val tmdb = async { tmdb(meta) }
                trakt.await() to tmdb.await()
            }
            val merged = mergeReviews(trakt, tmdb)
            if (trakt != null || tmdb != null) synchronized(cache) { cache[key] = merged }
            merged
        }

    suspend fun episodeReviews(meta: Meta, itemId: String?, itemType: String?, video: Video): List<TraktCommentReview> =
        withContext(Dispatchers.IO) {
            val key = "episode|${meta.id}|${video.season}|${video.episode}"
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val trakt = trakt(meta, itemId, itemType, video)
            val list = mergeReviews(trakt, null)
            if (trakt != null) synchronized(cache) { cache[key] = list }
            list
        }

    /** Whether Trakt can be asked at all: needs the build's Trakt client id and a signed-in profile. */
    private suspend fun traktReady(): Boolean =
        BuildConfig.TRAKT_CLIENT_ID.isNotBlank() &&
            (withTimeoutOrNull(2_000) { traktAuthDataStore.isAuthenticated.first() } == true)

    private suspend fun trakt(meta: Meta, itemId: String?, itemType: String?, episode: Video?): List<TraktCommentReview>? {
        if (!traktReady()) return null
        return try {
            traktCommentsService.getCommentsPage(
                meta = meta, fallbackItemId = itemId, fallbackItemType = itemType, targetEpisode = episode, page = 1
            ).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Trakt reviews failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun tmdb(meta: Meta): List<TraktCommentReview>? {
        val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
        if (key.isBlank()) return null
        val isTv = meta.type == ContentType.SERIES || meta.type == ContentType.TV ||
            meta.apiType.equals("series", true) || meta.apiType.equals("tv", true)
        return try {
            val tmdbId = tmdbService.ensureTmdbId(meta.id, if (isTv) "tv" else "movie", meta.imdbId)
                ?.toIntOrNull() ?: return null
            val response = if (isTv) tmdbApi.getTvReviews(tmdbId, key) else tmdbApi.getMovieReviews(tmdbId, key)
            if (!response.isSuccessful) return null
            response.body()?.results.orEmpty().mapNotNull { it.toReview() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB reviews failed: ${e.javaClass.simpleName}")
            null
        }
    }

    internal companion object {
        private const val TAG = "TitleReviews"
        const val MAX_REVIEWS = 20

        /** Trakt's (reviews, then the rest) ahead of TMDB's newest-first, without repeats, at most [MAX_REVIEWS]. */
        fun mergeReviews(trakt: List<TraktCommentReview>?, tmdb: List<TraktCommentReview>?): List<TraktCommentReview> {
            val fromTrakt = trakt.orEmpty().sortedByDescending { if (it.review) 1 else 0 }
            val fromTmdb = tmdb.orEmpty().sortedByDescending { it.createdAt.orEmpty() }
            return (fromTrakt + fromTmdb)
                .filter { it.comment.isNotBlank() }
                .distinctBy { it.authorDisplayName.lowercase() + "|" + it.comment.take(80) }
                .take(MAX_REVIEWS)
        }

        // Lazy, and with escaped brackets: Android's ICU regex is stricter than the JVM's.
        private val EMPHASIS by lazy { Regex("\\*\\*|__|\\*") }
        private val LINK by lazy { Regex("\\[([^\\]]*)\\]\\([^)]*\\)") }
        private val BLANK_LINES by lazy { Regex("\n{3,}") }

        /** TMDB reviews are Markdown-ish; the reader shows plain text. */
        fun plainText(content: String): String = content
            .replace(EMPHASIS, "")
            .replace(LINK, "$1")
            .replace("\r\n", "\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()

        fun TmdbReview.toReview(): TraktCommentReview? {
            val text = content?.let(::plainText)?.takeIf { it.isNotBlank() } ?: return null
            val name = authorDetails?.name?.takeIf { it.isNotBlank() }
                ?: author?.takeIf { it.isNotBlank() }
                ?: authorDetails?.username?.takeIf { it.isNotBlank() }
                ?: return null
            return TraktCommentReview(
                // TMDB ids are hex strings; the reader only needs a stable number.
                id = (id ?: "$name|${text.take(40)}").hashCode().toLong() and 0x7fffffffL or (1L shl 40),
                authorDisplayName = name,
                authorUsername = authorDetails?.username?.takeIf { it.isNotBlank() && it != name },
                comment = text,
                review = true,
                rating = authorDetails?.rating?.takeIf { it > 0 }?.let { Math.round(it).toInt() },
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }
}
