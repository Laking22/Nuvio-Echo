package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.seekpreview.SeekPreviewHosts
import com.nuvio.tv.core.player.seekpreview.SeekThumbnailSource
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityParser
import com.nuvio.tv.core.streams.isInPlayerSwitchable
import com.nuvio.tv.domain.model.Stream

/** Sources tried for seek thumbnails in all, before the playing one. */
private const val SEEK_PREVIEW_MAX_ALTERNATES = 6

/**
 * Seek thumbnails take other sources from the playing title's source search: starts it if it hasn't run for this
 * title (a finished or running one is left as it is).
 */
internal fun PlayerRuntimeController.requestSeekPreviewSources() = loadSourceStreams(forceRefresh = false)

/** The source search for the playing title has finished, so its list of sources won't change. */
internal fun PlayerRuntimeController.seekPreviewSourcesSettled(): Boolean =
    sourceStreamsFetchCompleted && sourceStreamsCacheRequestKey == playingSourceRequestKey()

/** The source search key of what's playing, built as [loadSourceStreams] builds it. */
internal fun PlayerRuntimeController.playingSourceRequestKey(): String? =
    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val vid = currentVideoId ?: contentId
        if (vid == null) null else buildSourceRequestKey(contentType.orEmpty(), vid, currentSeason, currentEpisode)
    } else {
        contentId?.let { buildSourceRequestKey(contentType ?: "movie", it, null, null) }
    }

/**
 * Other sources of the playing title to take seek thumbnails from, in the order to try them. A thumbnail costs one
 * keyframe download, and keyframes grow with the file's bitrate, so the smallest files come first, whatever their
 * quality: [minHeight] (Settings → Preview quality) is only the lowest quality allowed, and nothing above what's
 * playing is (its pictures take longer to decode). With the playing file's size known, only smaller files are taken.
 * Sources with no stated size come after, lowest quality first. In each group, those on a different server from
 * playback come first, so thumbnails don't compete with the stream for it, and servers that didn't answer last. Servers that gave thumbnails before go
 * first among equals; those that refused range requests, and sources that already failed, are left out. Only sources with a
 * link already: none is resolved just for a preview.
 */
internal fun PlayerRuntimeController.seekPreviewAlternates(minHeight: Int): List<SeekThumbnailSource> {
    val streams = _uiState.value.sourceAllStreams
    if (minHeight <= 0 || streams.isEmpty()) return emptyList()
    // Right after the next episode starts, the list can still be the last episode's.
    val playingKey = playingSourceRequestKey() ?: return emptyList()
    if (sourceStreamsCacheRequestKey != playingKey) return emptyList()
    val playing = currentPlaybackQuality()
    val playingSize = currentVideoSize?.takeIf { it > 0L }
    // An episode's other copies must name that same episode: addons do return other episodes (packs, wrong files), and
    // a show's episodes are often the same length, so the engine's length check can't tell them apart (once,
    // FROM S1E1 previews showed frames from three different episodes).
    val season = currentSeason
    val episodeNumber = currentEpisode
    val episode = if (contentType in listOf("series", "tv", "anime") && season != null && episodeNumber != null) {
        season to episodeNumber
    } else {
        null
    }

    class Candidate(val stream: Stream, val url: String, val quality: StreamQuality, val size: Long?)
    val candidates = streams.mapNotNull { stream ->
        if (!stream.isInPlayerSwitchable() || isCurrentPlaybackStream(stream)) return@mapNotNull null
        if (episode != null && !SeekPreviewEpisodes.names(stream, episode.first, episode.second)) return@mapNotNull null
        val quality = StreamQualityParser.parse(stream) ?: return@mapNotNull null
        if (quality.height < minHeight) return@mapNotNull null
        if (playing != null && quality.ordinal < playing.ordinal) return@mapNotNull null
        val url = stream.getStreamUrl()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (SeekPreviewHosts.refuses(url)) return@mapNotNull null
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(url, stream.behaviorHints?.proxyHeaders?.request)
        if (SeekPreviewHosts.rejected(request.url)) return@mapNotNull null
        val size = stream.behaviorHints?.videoSize?.takeIf { it > 0L }
        when {
            playingSize != null && size != null && size >= playingSize -> null
            // No size to compare: only a lower quality than what's playing is likely to be lighter.
            size == null && (playing == null || quality == playing) -> null
            else -> Candidate(stream, url, quality, size)
        }
    }
    val playbackHost = hostOf(currentStreamUrl)
    // Servers that didn't answer last time (this launch or an earlier one) go last.
    val answered = compareBy<Candidate> { if (SeekPreviewHosts.silent(it.url)) 1 else 0 }
    val otherServer = compareBy<Candidate> { if (playbackHost != null && hostOf(it.url) == playbackHost) 1 else 0 }
    val worked = compareBy<Candidate> { if (SeekPreviewHosts.worked(it.url)) 0 else 1 }
    val sized = candidates.filter { it.size != null }
        .sortedWith(answered.then(otherServer).then(compareBy<Candidate> { it.size }).then(worked))
    val unsized = candidates.filter { it.size == null }
        .sortedWith(answered.then(otherServer).then(compareBy<Candidate> { it.quality.height }).then(worked))
    return (sized + unsized).mapNotNull { candidate ->
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            candidate.url, candidate.stream.behaviorHints?.proxyHeaders?.request
        )
        SeekThumbnailSource.from(
            url = request.url,
            headers = request.headers,
            isTorrent = false,
            isLive = false,
            mimeType = PlayerMediaSourceFactory.inferMimeType(request.url, candidate.stream.behaviorHints?.filename),
            alternate = candidate.quality.label + (candidate.size?.let { " ${formatPreviewSize(it)}" } ?: ""),
        )
    }.take(SEEK_PREVIEW_MAX_ALTERNATES)
}

/** Which episode a source's name or file says it is. */
internal object SeekPreviewEpisodes {
    private val SXXEYY = Regex("(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val NXNN = Regex("(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val WORDS = Regex("season\\s*(\\d{1,2})\\D{1,12}episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    /** "Show - 03", anime's numbering (season 1 only). */
    private val DASHED = Regex("\\s-\\s0*(\\d{1,3})(?:v\\d)?(?=[\\s\\[(._]|$)")
    /** "E01-E08", "S01E01-08": a pack of several episodes. */
    private val RANGE = Regex("e(\\d{1,3})\\s*-\\s*e?(\\d{1,3})(?![0-9])", RegexOption.IGNORE_CASE)

    /** True when [stream] says it is season [season] episode [episode], and no other. */
    fun names(stream: Stream, season: Int, episode: Int): Boolean {
        val text = listOfNotNull(stream.behaviorHints?.filename, stream.title, stream.description, stream.name)
            .joinToString(" ")
        if (RANGE.containsMatchIn(text)) return false
        val found = (SXXEYY.findAll(text) + NXNN.findAll(text) + WORDS.findAll(text))
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        if (found.isNotEmpty()) return found.all { it == season to episode }
        if (season == 1) DASHED.findAll(text).map { it.groupValues[1].toInt() }.toList().let { numbers ->
            if (numbers.isNotEmpty()) return numbers.all { it == episode }
        }
        // Nothing says which episode it is: not trusted.
        return false
    }
}

private fun hostOf(url: String): String? = runCatching { android.net.Uri.parse(url).host }.getOrNull()

/** "2.1 GB", for logs. */
private fun formatPreviewSize(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
