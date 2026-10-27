package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import com.nuvio.tv.data.remote.api.TheIntroDbApi
import com.nuvio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nuvio.tv.data.remote.api.TheIntroDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // intro/op, recap, outro/ed, movie-credits, post-credits
    val provider: String   // "theintrodb", "introdb", "aniskip", "animeskip"; in the player also "chapters", "picture"
)

/** What the skip-time databases have for one episode or movie. */
data class SkipTimes(
    /** The best of each segment: each from the highest-priority provider that has it. */
    val intervals: List<SkipInterval>,
    /** Every provider's plausible segments, highest priority first, for fitting to the file that plays. */
    val candidates: List<SkipInterval> = intervals,
    /** Segment kinds ([SkipSegments.category]) a database confirmed the episode doesn't have. */
    val confirmedAbsent: Set<String> = emptySet()
) {
    companion object {
        val NONE = SkipTimes(emptyList())
    }
}

/** Sorts the providers' segment types into kinds, so one of each kind is kept. */
object SkipSegments {
    const val OPENING = "opening"
    const val RECAP = "recap"
    const val ENDING = "ending"
    const val MOVIE_CREDITS = "movie-credits"

    fun category(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> OPENING
        "outro", "ed", "mixed-ed", "credits", "ending" -> ENDING
        "recap" -> RECAP
        "movie-credits" -> MOVIE_CREDITS
        else -> null
    }

    /**
     * Whether [interval] can belong to a file [durationMs] long: it starts inside it, an opening or recap in its
     * first half, an ending or the credits in its second. This drops times submitted under the wrong label or for
     * a very different release. Everything passes while the length is unknown.
     */
    fun plausible(interval: SkipInterval, durationMs: Long?): Boolean {
        val length = durationMs?.takeIf { it > 0 }?.div(1000.0) ?: return true
        if (interval.startTime >= length) return false
        return when (category(interval.type)) {
            OPENING, RECAP -> interval.startTime < length * 0.5
            ENDING, MOVIE_CREDITS -> interval.startTime >= length * 0.5
            else -> true
        }
    }
}

internal fun IntroDbSegmentsResponse.toSkipIntervals(movie: Boolean): List<SkipInterval> {
    if (!movie) return listOfNotNull(
        intro.toSkipIntervalOrNull("intro"),
        recap.toSkipIntervalOrNull("recap"),
        outro.toSkipIntervalOrNull("outro")
    )
    val credits = outro.toSkipIntervalOrNull("movie-credits")
    val scene = postCredits.toSkipIntervalOrNull("post-credits")
    // Skipping credits must not also skip the post-credits scene.
    val safeCredits = if (credits != null && scene != null &&
        scene.startTime < credits.endTime && scene.endTime > credits.startTime
    ) {
        credits.copy(endTime = scene.startTime).takeIf { it.endTime > it.startTime }
    } else credits
    return listOfNotNull(safeCredits, scene)
}

private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startSec ?: startMs?.let { it / 1000.0 } ?: return null
    val end = endSec ?: endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
}

/** The first of each segment; credits without an end run to the end of a file [durationMs] long. */
internal fun TheIntroDbMediaResponse.toSkipIntervals(movie: Boolean, durationMs: Long?): List<SkipInterval> {
    val fileEnd = durationMs?.takeIf { it > 0 }?.div(1000.0) ?: Double.MAX_VALUE
    fun List<TheIntroDbSegment>?.first(type: String, openEnded: Boolean): SkipInterval? =
        orEmpty().firstNotNullOfOrNull { segment ->
            // An intro without a start begins the file.
            val start = segment.startMs?.div(1000.0) ?: if (type == "intro") 0.0 else return@firstNotNullOfOrNull null
            val end = segment.endMs?.div(1000.0) ?: if (openEnded) fileEnd else return@firstNotNullOfOrNull null
            if (!start.isFinite() || start < 0 || end <= start) null
            else SkipInterval(startTime = start, endTime = end, type = type, provider = "theintrodb")
        }
    return listOfNotNull(
        intro.first("intro", openEnded = false),
        recap.first("recap", openEnded = false),
        credits.first(if (movie) "movie-credits" else "outro", openEnded = true)
    )
}

/** Whether a segment starts at or after [lengthMs]: these times were taken on a longer release. */
internal fun TheIntroDbMediaResponse.startsPast(lengthMs: Long): Boolean =
    listOfNotNull(intro, recap, credits, preview).flatten().any { (it.startMs ?: 0L) >= lengthMs }

/** Skipping the credits must not also skip a post-credits scene: they stop where it starts. */
internal fun List<SkipInterval>.creditsBeforeScene(scene: SkipInterval?): List<SkipInterval> {
    if (scene == null) return this
    return mapNotNull { interval ->
        if (interval.type != "movie-credits" || scene.startTime >= interval.endTime || scene.endTime <= interval.startTime) {
            interval
        } else {
            interval.copy(endTime = scene.startTime).takeIf { it.endTime > it.startTime }
        }
    }
}

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val theIntroDbApi: TheIntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val animeSkipApi: AnimeSkipApi,
    private val simklResolver: SimklIdResolver,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private val cache = ConcurrentHashMap<String, SkipTimes>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    /** IntroDB's segments, and the kinds it confirmed the title doesn't have. */
    private class IntroDbResult(val intervals: List<SkipInterval>, val absent: Set<String>) {
        companion object {
            val NONE = IntroDbResult(emptyList(), emptySet())
        }
    }

    /** [durationMs] is the playing file's length when known: it picks times submitted for a release that long. */
    suspend fun getMovieSkipIntervals(contentId: String?, videoId: String? = null, durationMs: Long? = null): SkipTimes {
        val ids = listOfNotNull(contentId, videoId).distinct()
        val imdbId = ids.firstNotNullOfOrNull { id ->
            id.substringBefore(':').takeIf { it.matches(Regex("tt[0-9]+")) }
        } ?: ids.firstNotNullOfOrNull { id ->
            val parts = id.split(':')
            val value = parts.getOrNull(1) ?: return@firstNotNullOfOrNull null
            when (parts[0]) {
                "tmdb" -> value.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, "movie") }
                "mal", "kitsu" -> simklResolver.resolveIds(parts[0], value)?.imdb
                else -> null
            }
        } ?: return SkipTimes.NONE
        val key = "movie:$imdbId:${durationKey(durationMs)}"
        cache[key]?.let { return it }
        return coroutineScope {
            val introDb = async { fetchFromIntroDb(imdbId, isMovie = true) }
            val theIntroDb = async { fetchFromTheIntroDb(imdbId, null, null, durationMs, movie = true) }
            val fromIntroDb = introDb.await()
            combine(durationMs, movie = true, listOf(theIntroDb.await(), fromIntroDb.intervals), fromIntroDb.absent)
        }.also { cache[key] = it }
    }

    /**
     * Standard path for IMDB-identified content.
     */
    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int, durationMs: Long? = null): SkipTimes = coroutineScope {
        if (imdbId == null) return@coroutineScope SkipTimes.NONE
        val cacheKey = "$imdbId:$season:$episode:${durationKey(durationMs)}"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async { fetchFromIntroDb(imdbId, season, episode) }
        val theIntroDbDeferred = async { fetchFromTheIntroDb(imdbId, season, episode, durationMs, movie = false) }
        // Resolve IMDB -> season-specific MAL/AniList via Simkl episode mapping
        val simklIdsDeferred = async { simklResolver.resolveIdsForImdbEpisode(imdbId, season, episode) }
        val simklIds = simklIdsDeferred.await()
        val malId = simklIds?.mal
        val anilistId = simklIds?.anilist

        // Remap the TVDB episode number to the anime-entry-local episode number.
        // When the resolved entry owns a specific TVDB season, its episode mapping
        // tells us which anime episode corresponds to the requested TVDB episode.
        val animeEpisode = if (simklIds != null) {
            val mapping = simklResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode, durationMs) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        val introDb = introDbDeferred.await()
        return@coroutineScope combine(
            durationMs,
            movie = false,
            listOf(theIntroDbDeferred.await(), introDb.intervals, animeSkipDeferred.await(), aniSkipDeferred.await()),
            introDb.absent
        ).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
        durationMs: Long? = null
    ): SkipTimes = coroutineScope {
        val cacheKey = "mal:$malId:$episode:${durationKey(durationMs)}"
        cache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode, durationMs) }

        val simklIdsDeferred = async { simklResolver.resolveIds("mal", malId) }
        val simklIds = simklIdsDeferred.await()
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("mal", malId, episode)
            } else null
        }

        return@coroutineScope fromImdbAndAnime(
            resolvedImdbId, imdbSeason, imdbEpisode, episode, simklIds?.anilist, durationMs,
            tvdb = { tvdbDeferred.await() },
            aniSkip = { aniSkipDeferred.await() }
        ).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
        durationMs: Long? = null
    ): SkipTimes = coroutineScope {
        val cacheKey = "kitsu:$kitsuId:$episode:${durationKey(durationMs)}"
        cache[cacheKey]?.let { return@coroutineScope it }

        // Resolve all IDs via Simkl
        val simklIdsDeferred = async { simklResolver.resolveIds("kitsu", kitsuId) }
        val simklIds = simklIdsDeferred.await()
        val malIdStr = simklIds?.mal
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val aniSkipDeferred = async {
            if (malIdStr != null) fetchFromAniSkip(malIdStr, episode, durationMs) else emptyList()
        }

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("kitsu", kitsuId, episode)
            } else null
        }

        return@coroutineScope fromImdbAndAnime(
            resolvedImdbId, imdbSeason, imdbEpisode, episode, simklIds?.anilist, durationMs,
            tvdb = { tvdbDeferred.await() },
            aniSkip = { aniSkipDeferred.await() }
        ).also { cache[cacheKey] = it }
    }

    /**
     * The MAL and Kitsu paths once their ids are resolved: IntroDB and TheIntroDB by the IMDB episode (from
     * [imdbSeason]/[imdbEpisode], else the TVDB mapping), Anime-Skip by AniList, and AniSkip.
     */
    private suspend fun fromImdbAndAnime(
        resolvedImdbId: String?,
        imdbSeason: Int?,
        imdbEpisode: Int?,
        episode: Int,
        anilistId: String?,
        durationMs: Long?,
        tvdb: suspend () -> Pair<Int, Int>?,
        aniSkip: suspend () -> List<SkipInterval>
    ): SkipTimes = coroutineScope {
        var introDb = IntroDbResult.NONE
        var theIntroDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val mapped = if (imdbSeason == null) tvdb() else null
            val season = imdbSeason ?: mapped?.first
            if (season != null) {
                val imdbEp = imdbEpisode ?: mapped?.second ?: episode
                val introDbDeferred = async { fetchFromIntroDb(resolvedImdbId, season, imdbEp) }
                val theIntroDbDeferred = async { fetchFromTheIntroDb(resolvedImdbId, season, imdbEp, durationMs, movie = false) }
                val animeSkipDeferred = async {
                    if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
                }
                introDb = introDbDeferred.await()
                theIntroDb = theIntroDbDeferred.await()
                animeSkip = animeSkipDeferred.await()
            }
        } else if (anilistId != null) {
            animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }
        combine(durationMs, movie = false, listOf(theIntroDb, introDb.intervals, animeSkip, aniSkip()), introDb.absent)
    }

    /**
     * [providerResults] in priority order (TheIntroDB, IntroDB, then Anime-Skip, then AniSkip as fallback). Times that
     * can't belong to a file [durationMs] long are dropped first, so a mislabelled entry never shadows a good one.
     */
    private fun combine(
        durationMs: Long?,
        movie: Boolean,
        providerResults: List<List<SkipInterval>>,
        absent: Set<String>
    ): SkipTimes {
        val plausible = providerResults.map { result -> result.filter { SkipSegments.plausible(it, durationMs) } }
        var merged = mergeByPriority(plausible)
        if (movie) {
            val scene = plausible.flatten().filter { it.type == "post-credits" }.minByOrNull { it.startTime }
            merged = merged.creditsBeforeScene(scene) + listOfNotNull(scene)
        }
        return SkipTimes(merged, plausible.flatten(), absent)
    }

    /**
     * Merge provider results into one best-of: fill each segment category (opening / ending /
     * recap / movie credits) from the highest-priority provider that has it. Arguments MUST be
     * passed in priority order, so a partial result from one provider never shadows a complete
     * segment from another.
     */
    private fun mergeByPriority(providerResults: List<List<SkipInterval>>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = SkipSegments.category(interval.type) ?: continue
                chosen.putIfAbsent(category, interval)
            }
        }
        return chosen.values.toList()
    }

    /** Lengths within a second share a cache entry. */
    private fun durationKey(durationMs: Long?): Long = durationMs?.takeIf { it > 0 }?.div(1000) ?: 0L

    private suspend fun fetchFromIntroDb(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = false
    ): IntroDbResult {
        if (!introDbConfigured) return IntroDbResult.NONE
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode, true.takeIf { isMovie })
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val absent = buildSet {
                    if (body.noIntro == true) add(SkipSegments.OPENING)
                    if (body.noRecap == true) add(SkipSegments.RECAP)
                    if (body.noOutro == true) add(if (isMovie) SkipSegments.MOVIE_CREDITS else SkipSegments.ENDING)
                }
                IntroDbResult(body.toSkipIntervals(isMovie), absent)
            } else IntroDbResult.NONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: no data for $imdbId S${season}E${episode}")
            IntroDbResult.NONE
        }
    }

    /**
     * The submissions TheIntroDB picks for a file [durationMs] long. When those have something starting after the
     * file's end they were timed on another release, so the times most submissions agree on are used instead.
     */
    private suspend fun fetchFromTheIntroDb(
        imdbId: String,
        season: Int?,
        episode: Int?,
        durationMs: Long?,
        movie: Boolean
    ): List<SkipInterval> {
        val lengthMs = durationMs?.takeIf { it > 0 }
        var media = fetchTheIntroDbMedia(imdbId, season, episode, lengthMs, movie) ?: return emptyList()
        if (lengthMs != null && media.startsPast(lengthMs)) {
            Log.i("SkipIntro", "TheIntroDB: the times for this length run past its end; using the general ones")
            fetchTheIntroDbMedia(imdbId, season, episode, null, movie)?.let { media = it }
        }
        return media.toSkipIntervals(movie, durationMs)
    }

    /** [askForMs] is the length TheIntroDB matches a release by. */
    private suspend fun fetchTheIntroDbMedia(
        imdbId: String,
        season: Int?,
        episode: Int?,
        askForMs: Long?,
        movie: Boolean
    ): TheIntroDbMediaResponse? {
        return try {
            val response = theIntroDbApi.getMedia(
                imdbId = imdbId,
                season = season.takeUnless { movie },
                episode = episode.takeUnless { movie },
                durationMs = askForMs
            )
            response.body()?.takeIf { response.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "TheIntroDB: no data for $imdbId S${season}E${episode}")
            null
        }
    }

    /** Times submitted on a release about [durationMs] long; any length when none match it. */
    private suspend fun fetchFromAniSkip(malId: String, episode: Int, durationMs: Long?): List<SkipInterval> {
        val length = durationMs?.takeIf { it > 0 }?.div(1000)?.toInt() ?: 0
        val matched = fetchFromAniSkip(malId, episode, length)
        return if (matched.isEmpty() && length > 0) fetchFromAniSkip(malId, episode, 0) else matched
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int, episodeLength: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types, episodeLength)
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()!!.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season number when using fallback ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodesResponse = animeSkipApi.query(
                    clientId = clientId,
                    body = AnimeSkipRequest(
                        query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    )
                )
                if (!episodesResponse.isSuccessful) continue

                val episodes = episodesResponse.body()?.data?.findEpisodesByShowId ?: continue
                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits", "new credits" -> "ed"
                        "mixed intro" -> "mixed-op"
                        "mixed credits" -> "mixed-ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipApi.query(
                clientId = clientId,
                body = AnimeSkipRequest(
                    query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                )
            ).body()?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    companion object {
        private const val NO_ID = "__none__"
    }
}
