package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.seekpreview.FileSkipEvidence
import com.nuvio.tv.core.player.seekpreview.MediaChapter
import com.nuvio.tv.core.player.skiptimes.CreditsDetector
import com.nuvio.tv.core.player.skiptimes.SkipTimeFitter
import com.nuvio.tv.data.local.SkipTimeFileCheck
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.SkipTimes
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the file playing from [streamUrl] says about itself: its chapters, and its keyframe times when Fit asked. */
internal class SkipFileEvidence(val streamUrl: String, val chapters: List<MediaChapter>, val keyframesMs: LongArray?)

/** Credits found in the pictures of the file playing from [streamUrl]. */
internal class SkipFoundCredits(val streamUrl: String, val interval: SkipInterval)

/** How long the databases wait for the file's length, which picks the release their times were taken on. */
private const val SKIP_DURATION_WAIT_MS = 10_000L
private const val SKIP_FILE_CALL_TIMEOUT_MS = 20_000L
/** Credits are looked for this long before the stretch they could start in, which leaves time to finish. */
private const val CREDITS_SEARCH_LEAD_MS = 5 * 60_000L
/** Between two pictures, so the thumbnail thread and the network stay free for playback and scrubbing. */
private const val CREDITS_GRAB_PACE_MS = 250L
/** A file this much longer or shorter than the one the times were fetched for is another release. */
private const val SKIP_REFETCH_LENGTH_CHANGE_MS = 5_000L

/** The playing file's length once the player knows it; null when it doesn't in time. */
internal suspend fun PlayerRuntimeController.awaitSkipTimesDurationMs(): Long? {
    val deadline = SystemClock.elapsedRealtime() + SKIP_DURATION_WAIT_MS
    while (true) {
        val duration = currentPlaybackDurationMs()
        if (duration > 0 && !isShortPlaceholderDuration(duration)) return duration
        if (SystemClock.elapsedRealtime() >= deadline) return null
        delay(250)
    }
}

internal fun PlayerRuntimeController.applySkipTimes(times: SkipTimes, durationMs: Long?) {
    skipTimes = times
    skipTimesDurationMs = durationMs
    refreshSkipIntervals()
}

/** [skipIntervals] again, from the databases' times and what the file has said so far (each only while its check is on). */
internal fun PlayerRuntimeController.refreshSkipIntervals() {
    val checks = skipTimeFileChecks
    val url = currentStreamUrl
    val evidence = skipFileEvidence?.takeIf {
        it.streamUrl == url && (SkipTimeFileCheck.CHAPTERS in checks || SkipTimeFileCheck.FIT in checks)
    }
    val credits = skipFoundCredits?.takeIf { it.streamUrl == url && SkipTimeFileCheck.CREDITS in checks }?.interval
    val next = SkipTimeFitter.combine(
        times = skipTimes,
        durationMs = currentPlaybackDurationMs().coerceAtLeast(0L),
        movie = contentType.equals("movie", ignoreCase = true),
        chapters = evidence?.chapters,
        keyframesMs = evidence?.keyframesMs,
        credits = credits,
        chapterNames = SkipTimeFileCheck.CHAPTERS in checks,
        fit = SkipTimeFileCheck.FIT in checks
    )
    if (next == skipIntervals) return
    skipIntervals = next
    Log.i(
        PlayerRuntimeController.TAG,
        "SKIP_TIMES: " + next.joinToString { "${it.type} ${seconds(it.startTime)}-${seconds(it.endTime)} (${it.provider})" }
            .ifEmpty { "none" }
    )
}

private fun seconds(value: Double): String =
    if (value == Double.MAX_VALUE) "end" else String.format(Locale.ROOT, "%.1f", value)

/** Turning a check off stops what it had running and drops what it found. */
internal fun PlayerRuntimeController.updateSkipTimeFileChecks(checks: Set<SkipTimeFileCheck>) {
    if (checks == skipTimeFileChecks) return
    skipTimeFileChecks = checks
    if (SkipTimeFileCheck.CREDITS !in checks) {
        skipCreditsJob?.cancel()
        skipCreditsJob = null
        skipCreditsSearchedFor = null
        skipFoundCredits = null
    }
    if (SkipTimeFileCheck.CHAPTERS !in checks && SkipTimeFileCheck.FIT !in checks) {
        skipFileJob?.cancel()
        skipFileJob = null
        skipFileReadKey = null
        skipFileEvidence = null
    }
    refreshSkipIntervals()
}

/** A new episode or source: its times start over. */
internal fun PlayerRuntimeController.resetSkipTimes() {
    skipTimes = SkipTimes.NONE
    skipTimesDurationMs = null
    skipIntervals = emptyList()
    skipFileJob?.cancel()
    skipFileJob = null
    skipFileReadKey = null
    skipFileEvidence = null
    skipCreditsJob?.cancel()
    skipCreditsJob = null
    skipCreditsSearchedFor = null
    skipFoundCredits = null
}

/**
 * On every position tick: asks the databases again when the file's length changed, and starts what the file checks
 * that are on still need for the stream playing. The file is read once its length is known; credits are looked for
 * nearer the end, once.
 */
internal fun PlayerRuntimeController.ensureSkipFileChecks(positionMs: Long) {
    if (!skipIntroEnabled || _playbackTimeline.value.isLive) return
    val durationMs = currentPlaybackDurationMs().takeIf { it > 0 && !isShortPlaceholderDuration(it) } ?: return
    // A swap to another release (auto quality keeps the episode) changes the length the databases' times were
    // picked and checked for: they're asked again.
    val fetchedFor = skipTimesDurationMs
    if (fetchedFor != null && kotlin.math.abs(durationMs - fetchedFor) > SKIP_REFETCH_LENGTH_CHANGE_MS) {
        skipTimesDurationMs = null
        skipIntroFetchedKey = null
        fetchSkipIntervals(contentId, currentSeason, currentEpisode)
    }
    val checks = skipTimeFileChecks
    if (checks.isEmpty()) return
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return
    val keyframes = SkipTimeFileCheck.FIT in checks
    if (SkipTimeFileCheck.CHAPTERS in checks || keyframes) {
        val key = "$url|$keyframes"
        if (skipFileReadKey != key) {
            skipFileReadKey = key
            skipFileJob?.cancel()
            skipFileJob = scope.launch { readSkipFileEvidence(url, durationMs, keyframes) }
        }
    }
    if (SkipTimeFileCheck.CREDITS in checks && skipCreditsSearchedFor != url) {
        // The pictures come from the seek thumbnails; with those off there's nothing to look at.
        val grabber = switchFrameGrabber ?: return
        val movie = contentType.equals("movie", ignoreCase = true)
        if (positionMs < durationMs - CreditsDetector.windowMs(movie) - CREDITS_SEARCH_LEAD_MS) return
        if (_uiState.value.isBuffering) return
        skipCreditsSearchedFor = url
        skipCreditsJob?.cancel()
        skipCreditsJob = scope.launch { findCredits(url, durationMs, movie, grabber) }
    }
}

private suspend fun PlayerRuntimeController.readSkipFileEvidence(url: String, durationMs: Long, keyframes: Boolean) {
    // mpv has read the chapters already, whatever the container; the file is read for what mpv doesn't say.
    val mpvChapters = if (isUsingMpvEngine()) mpvChapterList() else null
    val headers = currentHeaders
    val read = if ((keyframes || mpvChapters.isNullOrEmpty()) && !isLocalStream(url)) {
        withContext(Dispatchers.IO) {
            val request = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
            FileSkipEvidence.read(
                client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                    .callTimeout(SKIP_FILE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build(),
                url = request.url,
                headers = request.headers,
                userAgent = PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
                durationMs = durationMs,
                keyframes = keyframes
            )
        }
    } else null
    if (currentStreamUrl != url) return
    val chapters = mpvChapters?.takeIf { it.isNotEmpty() } ?: read?.chapters.orEmpty()
    skipFileEvidence = SkipFileEvidence(url, chapters, read?.keyframesMs)
    Log.i(
        PlayerRuntimeController.TAG,
        "SKIP_FILE: ${chapters.size} chapters [${chapters.joinToString(" | ") { "${it.startMs / 1000}s ${it.title}" }}], " +
            "keyframes ${read?.keyframesMs?.size ?: "none"}"
    )
    refreshSkipIntervals()
}

private fun PlayerRuntimeController.mpvChapterList(): List<MediaChapter>? {
    val mpv = mpvView?.mpv ?: return null
    val count = runCatching { mpv.getPropertyInt("chapter-list/count") }.getOrNull() ?: return null
    return (0 until count).mapNotNull { i ->
        val seconds = runCatching { mpv.getPropertyDouble("chapter-list/$i/time") }.getOrNull() ?: return@mapNotNull null
        val title = runCatching { mpv.getPropertyString("chapter-list/$i/title") }.getOrNull().orEmpty().trim()
        MediaChapter((seconds * 1000).toLong(), null, title)
    }.sortedBy { it.startMs }
}

/** A torrent's local server: reading the file's index there would fetch pieces playback doesn't need yet. */
private fun isLocalStream(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return true
    return host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"
}

private suspend fun PlayerRuntimeController.findCredits(
    url: String,
    durationMs: Long,
    movie: Boolean,
    grabber: suspend (Long) -> Bitmap?
) {
    val found = CreditsDetector.find(
        durationMs = durationMs,
        movie = movie,
        grab = { positionMs ->
            delay(CREDITS_GRAB_PACE_MS)
            grabber(positionMs)?.let { bitmap -> withContext(Dispatchers.Default) { CreditsDetector.stats(bitmap) } }
        },
        log = { Log.i(PlayerRuntimeController.TAG, "SKIP_CREDITS: $it") }
    )
    if (currentStreamUrl != url) return
    skipFoundCredits = found?.let { SkipFoundCredits(url, it) }
    refreshSkipIntervals()
}
