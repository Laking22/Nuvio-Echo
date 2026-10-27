package com.nuvio.tv.data.trailer

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImdbTrailerSource"
private const val ENDPOINT = "https://api.graphql.imdb.com/"
private const val QUERY = "query(\$id: ID!) { title(id: \$id) { primaryVideos(first: 5) { edges { node { " +
    "contentType { displayName { value } } runtime { value } playbackURLs { url videoMimeType videoDefinition } " +
    "} } } } }"
/** A trailer shorter than this is usually a teaser cut or a TV spot; a full trailer is preferred when there is one. */
private const val FULL_TRAILER_MIN_SECONDS = 60
/** Featurettes and clips run long; past this a "trailer" isn't one. */
private const val TRAILER_MAX_SECONDS = 300
/** IMDb's links are signed and expire; the source is dropped this long before. */
private const val EXPIRY_MARGIN_MS = 10 * 60_000L
private const val DEFAULT_TTL_MS = 2 * 60 * 60_000L
private val EXPIRES_REGEX = Regex("[?&]Expires=(\\d+)")

/**
 * The title's trailer from IMDb, which hosts its own copies (one MP4 with picture and sound, up to 1080p, or HLS).
 * YouTube refuses trailer requests from an address it has flagged ("confirm you're not a bot", on every client);
 * IMDb doesn't, so this is the fallback that keeps trailers playing then (every YouTube client
 * can refuse from such an address while IMDb answers for every title).
 */
@Singleton
class ImdbTrailerSource @Inject constructor() {
    private val gson = Gson()
    private val http by lazy {
        OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private data class Cached(val source: TrailerPlaybackSource?, val until: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** The trailer for IMDb title [imdbId] ("tt..."), at most [maxHeight] tall, or null when IMDb has none. */
    suspend fun trailer(imdbId: String, maxHeight: Int): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (!imdbId.startsWith("tt")) return@withContext null
        val key = "$imdbId|$maxHeight"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.until > now }?.let { return@withContext it.source }
        try {
            val body = gson.toJson(mapOf("query" to QUERY, "variables" to mapOf("id" to imdbId)))
            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/129.0 Safari/537.36")
                .header("Accept-Language", "en-US")
                .header("Origin", "https://www.imdb.com")
                .header("Referer", "https://www.imdb.com/")
                .header("x-imdb-client-name", "imdb-web-next-localized")
                .build()
            val json = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "IMDb video lookup failed (${response.code}) for $imdbId")
                    return@withContext null
                }
                response.body?.string().orEmpty()
            }
            val source = pickImdbTrailer(parseVideos(json), maxHeight)
            val until = source?.videoUrl?.let(::expiresAtMs)?.minus(EXPIRY_MARGIN_MS) ?: (now + DEFAULT_TTL_MS)
            cache[key] = Cached(source, until)
            if (source == null) Log.w(TAG, "IMDb has no trailer for $imdbId")
            source
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "IMDb video lookup error for $imdbId: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parseVideos(json: String): List<ImdbVideo> {
        val root = gson.fromJson(json, Map::class.java) ?: return emptyList()
        val edges = ((((root["data"] as? Map<*, *>)?.get("title") as? Map<*, *>)
            ?.get("primaryVideos") as? Map<*, *>)?.get("edges") as? List<*>).orEmpty()
        return edges.mapNotNull { edge ->
            val node = (edge as? Map<*, *>)?.get("node") as? Map<*, *> ?: return@mapNotNull null
            val type = ((node["contentType"] as? Map<*, *>)?.get("displayName") as? Map<*, *>)?.get("value") as? String
            val runtime = ((node["runtime"] as? Map<*, *>)?.get("value") as? Number)?.toInt()
            val urls = (node["playbackURLs"] as? List<*>).orEmpty().mapNotNull { p ->
                val m = p as? Map<*, *> ?: return@mapNotNull null
                val url = m["url"] as? String ?: return@mapNotNull null
                ImdbPlayback(url, m["videoMimeType"] as? String, m["videoDefinition"] as? String)
            }
            ImdbVideo(type, runtime, urls)
        }
    }

    private fun expiresAtMs(url: String): Long? =
        EXPIRES_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
}

internal data class ImdbPlayback(val url: String, val mimeType: String?, val definition: String?)
internal data class ImdbVideo(val contentType: String?, val runtimeSeconds: Int?, val playbacks: List<ImdbPlayback>)

/** "DEF_1080p" -> 1080; "DEF_SD" -> 360; anything else (DEF_AUTO) -> null. */
internal fun imdbDefinitionHeight(definition: String?): Int? = when {
    definition == null -> null
    definition.equals("DEF_SD", true) -> 360
    else -> Regex("DEF_(\\d+)p", RegexOption.IGNORE_CASE).find(definition)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * The video to play: the first full-length trailer IMDb lists (it lists the main one first), else the first
 * trailer, else the first video. Its tallest MP4 no taller than [maxHeight], else its HLS stream.
 */
internal fun pickImdbTrailer(videos: List<ImdbVideo>, maxHeight: Int): TrailerPlaybackSource? {
    val playable = videos.filter { it.playbacks.isNotEmpty() }
    val trailers = playable.filter { it.contentType.equals("Trailer", true) }
    val video = trailers.firstOrNull { (it.runtimeSeconds ?: 0) in FULL_TRAILER_MIN_SECONDS..TRAILER_MAX_SECONDS }
        ?: trailers.firstOrNull()
        ?: playable.firstOrNull()
        ?: return null
    val mp4 = video.playbacks
        .filter { it.mimeType.equals("MP4", true) }
        .mapNotNull { p -> imdbDefinitionHeight(p.definition)?.takeIf { it <= maxHeight }?.let { it to p } }
        .maxByOrNull { it.first }?.second
    val chosen = mp4 ?: video.playbacks.firstOrNull { it.mimeType.equals("M3U8", true) } ?: return null
    return TrailerPlaybackSource(videoUrl = chosen.url)
}
