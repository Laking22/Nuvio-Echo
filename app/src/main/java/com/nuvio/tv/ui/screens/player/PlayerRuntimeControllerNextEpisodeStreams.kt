package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The next episode's sources, fetched while this one plays (as Stremio does) so Next, its card and the episode list
 * start it without a search. [addonStreams] is the latest answer; [complete] once every source has answered.
 */
internal data class NextEpisodeStreams(
    val videoId: String,
    val addonStreams: List<AddonStreams>,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val complete: Boolean
) {
    /** Every addon has had its chance: the search finished, or ran long enough that only paused plugins are left. */
    fun settled(nowMs: Long): Boolean = complete || nowMs - startedAtMs >= NEXT_EPISODE_STREAMS_SETTLE_MS
}

/** How long after this episode's first frame the next one's sources are fetched, so they don't slow its start. */
private const val NEXT_EPISODE_STREAMS_DELAY_MS = 10_000L
/** After this long the search counts as answered: plugins wait while a video plays, addons have timed out by then. */
private const val NEXT_EPISODE_STREAMS_SETTLE_MS = 30_000L
/** Sources older than this are searched for again before the next episode's card picks one on its own. */
private const val NEXT_EPISODE_STREAMS_FRESH_MS = 15 * 60_000L

/**
 * Fetches the next episode's sources shortly after this one starts and keeps them for the rest of it ("Preload next
 * episode sources"). The search is the same one its source page, card and episode list ask for, so they all join it.
 */
internal fun PlayerRuntimeController.fetchNextEpisodeStreamsEarly() {
    if (!preloadNextEpisodeSourcesSetting) return
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    if (type.equals("cloud", ignoreCase = true)) return
    if (_uiState.value.nextEpisode?.hasAired != true) return
    if (nextEpisodeStreams?.videoId == nextVideo.id) return

    val queuedAt = SystemClock.elapsedRealtime()
    nextEpisodeStreams = NextEpisodeStreams(nextVideo.id, emptyList(), queuedAt, queuedAt, complete = false)
    nextEpisodeStreamsJob?.cancel()
    nextEpisodeStreamsJob = scope.launch {
        delay(NEXT_EPISODE_STREAMS_DELAY_MS)
        val startedAt = SystemClock.elapsedRealtime()
        fun hold(update: (NextEpisodeStreams) -> NextEpisodeStreams) {
            nextEpisodeStreams = nextEpisodeStreams?.takeIf { it.videoId == nextVideo.id }?.let(update)
        }
        hold { it.copy(startedAtMs = startedAt, updatedAtMs = startedAt) }
        Log.d(PlayerRuntimeController.TAG, "Fetching the next episode's sources: S${nextVideo.season}E${nextVideo.episode}")
        try {
            streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = nextVideo.id,
                season = nextVideo.season,
                episode = nextVideo.episode,
                speculative = true
            ).collect { result ->
                if (result is NetworkResult.Success && result.data.isNotEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    hold { it.copy(addonStreams = result.data, updatedAtMs = now) }
                }
            }
            hold { it.copy(complete = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Next episode's early source fetch failed", e)
            hold { it.copy(complete = true) }
        }
    }
}

/**
 * The next episode's ([videoId]) sources as fetched early, once they're in. Older than [NEXT_EPISODE_STREAMS_FRESH_MS]
 * they're left for a new search unless [allowStale] (a click: playing at once matters more, and a dead link falls back).
 */
internal fun PlayerRuntimeController.heldNextEpisodeStreams(videoId: String, allowStale: Boolean): List<AddonStreams>? {
    val held = nextEpisodeStreams?.takeIf { it.videoId == videoId && it.addonStreams.isNotEmpty() } ?: return null
    val now = SystemClock.elapsedRealtime()
    if (!held.settled(now)) return null
    if (!allowStale && now - held.updatedAtMs >= NEXT_EPISODE_STREAMS_FRESH_MS) return null
    return held.addonStreams
}

internal fun PlayerRuntimeController.clearNextEpisodeStreams() {
    nextEpisodeStreamsJob?.cancel()
    nextEpisodeStreamsJob = null
    nextEpisodeStreams = null
}
