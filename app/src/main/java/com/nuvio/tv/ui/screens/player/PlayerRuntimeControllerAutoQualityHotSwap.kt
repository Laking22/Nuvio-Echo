package com.nuvio.tv.ui.screens.player

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.R
import com.nuvio.tv.core.intro.PlaybackIntroState
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.core.player.seekpreview.FileKeyframes
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.newlook.NewLookNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request
import kotlin.math.abs

/**
 * Time the new source gets to load before the countdown starts (or, with no countdown, before the swap).
 * A step down loads a lighter file and is wanted sooner.
 */
private const val HOT_SWAP_UP_LEAD_MS = 15_000L
private const val HOT_SWAP_DOWN_LEAD_MS = 10_000L

/** A step down swaps only within what the playing source has buffered, this much short of its end. */
private const val HOT_SWAP_DOWN_MARGIN_MS = 4_000L

/** The countdown never starts later than this before the swap, so the swap point is always scheduled ahead. */
private const val HOT_SWAP_ARM_MIN_MS = 500L

/** Loaded from the swap point onward: enough to swap without a stall. */
private const val HOT_SWAP_READY_MS = 3_000L

/** What's loaded ahead of the swap stays within these (by the file's bitrate), on top of the playing buffer. */
private const val HOT_SWAP_MAX_BUFFER_MS = 10_000L
private const val HOT_SWAP_MAX_BUFFER_BYTES = 24L * 1024 * 1024
private const val HOT_SWAP_DEFAULT_BUFFER_MS = 8_000L

/** Played past the swap point by this much without swapping (a seek, a missed message): called off. */
private const val HOT_SWAP_LATE_MS = 1_500L

/** After the swap, a failure of the new source this soon goes back to the old one. */
private const val HOT_SWAP_PROBATION_MS = 15_000L

private const val HOT_SWAP_FPS_PROBE_TIMEOUT_MS = 6_000L

/** Reading the new file's keyframe index; past this the swap point isn't put on a keyframe. */
private const val HOT_SWAP_INDEX_TIMEOUT_MS = 5_000L

/** The swap waits at most this much longer for the new file's next keyframe. */
private const val HOT_SWAP_MAX_KEYFRAME_WAIT_MS = 12_000L

private const val HOT_SWAP_POLL_MS = 100L

/** Playing this long after the swap with no sound to play counts as silent (the track list can lag a little). */
private const val HOT_SWAP_SILENT_GRACE_MS = 2_000L

private const val HOT_SWAP_LINK_CHECK_TIMEOUT_MS = 4_000L

private val DOLBY_VISION_TOKEN by lazy {
    Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)
}

/**
 * One of Auto's changes done as a hot swap: [pick] loads on the playing ExoPlayer while the old source
 * plays on, then the player moves onto it at [swapAtMs]. Only one decoder is ever used: the loading
 * reads and buffers the file but decodes nothing.
 */
internal class AutoQualityHotSwap(
    val player: ExoPlayer,
    val target: StreamQuality,
    val isUpgrade: Boolean,
    val from: StreamQuality?,
    val pick: Stream,
    val playback: StreamPlayback,
    /** What was playing, for the way back if the new source fails right after the swap. */
    val original: Stream?,
    /**
     * Where the swap happens, in the title's time: the new source is loaded from exactly here. Set
     * once more before loading starts, onto the new file's next keyframe when its index can be read.
     */
    var swapAtMs: Long,
    val countdownMs: Long,
    val bufferTargetMs: Long
) {
    val leadMs: Long get() = if (isUpgrade) HOT_SWAP_UP_LEAD_MS else HOT_SWAP_DOWN_LEAD_MS

    var source: PreloadMediaSource? = null
    var listener: Player.Listener? = null
    var message: PlayerMessage? = null
    /** The countdown has started and the swap is scheduled. */
    var armed = false
    /** When the player moved onto the new source (elapsed realtime); 0 until then. */
    var swappedAt = 0L
    var firstFrameLogged = false
    /** What played had sound: the new source must too (checked once it plays, as the usual switch does). */
    var requireAudio = false
    var probationJob: Job? = null

    // Written on the playback thread by the preload, read on the main thread.
    @Volatile var bufferedMs = 0L
    @Volatile var loadedToEnd = false
    @Volatile var preloadError: PreloadException? = null
    @Volatile var stopped = false
    @Volatile var loadStartedAt = 0L

    val readyMs: Long get() = minOf(HOT_SWAP_READY_MS, bufferTargetMs - 500L)
    fun isReady(): Boolean = loadedToEnd || bufferedMs >= readyMs
}

/**
 * Settings → Auto quality → Hot swap. Instead of stopping for the usual switch, the new quality's source
 * loads quietly from a point the head start ([HOT_SWAP_UP_LEAD_MS], [HOT_SWAP_DOWN_LEAD_MS]) plus the
 * countdown ahead. Once it has enough there, the usual "Upgrading to…" or "Downgrading to…" countdown
 * shows (OK cancels), and as playback reaches that point the same player moves onto it: a short blink
 * instead of a load. An upgrade that doesn't load in time, or fails, is dropped without a word and the
 * old source plays on; a step down that can't be hidden that way (the buffer running out first, a stall)
 * goes on as the usual switch.
 *
 * True when the hot swap has taken the change on; false leaves it to the usual switch (mpv, a link
 * still to resolve, a display mode change, a Dolby Vision file converted per file).
 */
internal fun PlayerRuntimeController.startAutoQualityHotSwap(
    target: StreamQuality,
    from: StreamQuality?,
    pick: Stream,
    isUpgrade: Boolean
): Boolean {
    val player = _exoPlayer ?: return false
    val url = pick.getStreamUrl()?.takeIf { it.isNotBlank() }
    val playback = url?.let {
        streamPlayback(pick, it, PlayerMediaSourceFactory.sanitizeHeaders(pick.behaviorHints?.proxyHeaders?.request))
    }
    val countdownMs = BetaFeatures.settings(context).autoQualityConfirmSeconds * 1_000L
    val leadMs = if (isUpgrade) HOT_SWAP_UP_LEAD_MS else HOT_SWAP_DOWN_LEAD_MS
    val blocker = hotSwapBlocker(player, pick, playback)
        ?: if (!isUpgrade && player.totalBufferedDuration < leadMs + countdownMs + HOT_SWAP_DOWN_MARGIN_MS) {
            "only ${player.totalBufferedDuration}ms buffered to step down in"
        } else {
            null
        }
    if (blocker != null || playback == null) {
        Log.i(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: usual switch instead ($blocker)")
        return false
    }
    val duration = _playbackTimeline.value.duration
    val swap = AutoQualityHotSwap(
        player = player,
        target = target,
        isUpgrade = isUpgrade,
        from = from,
        pick = pick,
        playback = playback,
        original = sourceSwitchOrigin().first,
        swapAtMs = player.currentPosition + leadMs + countdownMs,
        countdownMs = countdownMs,
        bufferTargetMs = hotSwapBufferTargetMs(pick, duration)
    )
    // Never two at once: one still under way (it shouldn't be) is let go first.
    autoQualityHotSwap?.let { endAutoQualityHotSwap(it) }
    autoQualityHotSwap = swap
    launchPendingAutoQualityJob {
        // A display mode change or a per-file Dolby Vision conversion needs the usual switch.
        hotSwapPipelineBlocker(pick, playback)?.let { reason ->
            if (autoQualityHotSwap === swap) hotSwapToUsualSwitch(swap, reason)
            return@launchPendingAutoQualityJob
        }
        if (autoQualityHotSwap !== swap) return@launchPendingAutoQualityJob
        // A link that refuses (PenguPlay hands out some that answer 428) fails here in a moment, rather than
        // after the player's retries use up the whole head start.
        hotSwapLinkProblem(playback)?.let { problem ->
            if (autoQualityHotSwap === swap) giveUpHotSwap(swap, problem)
            return@launchPendingAutoQualityJob
        }
        if (autoQualityHotSwap !== swap) return@launchPendingAutoQualityJob
        val swapAt = hotSwapPoint(swap)
        if (autoQualityHotSwap !== swap) return@launchPendingAutoQualityJob
        if (swapAt == null) {
            hotSwapToUsualSwitch(swap, "the buffer runs out before a swap point")
            return@launchPendingAutoQualityJob
        }
        swap.swapAtMs = swapAt
        if (!beginHotSwapLoad(swap)) return@launchPendingAutoQualityJob
        runHotSwap(swap)
    }
    return true
}

/** Why [pick] can't be hot-swapped to right now, or null when it can. */
private fun PlayerRuntimeController.hotSwapBlocker(player: ExoPlayer, pick: Stream, playback: StreamPlayback?): String? {
    if (isUsingMpvEngine()) return "mpv"
    if (playback == null || pick.isTorrent() || pick.youTubeIdToResolve() != null) return "link not resolved yet"
    if (isExperimentalDv7ToDv81ActiveForCurrentPlayback) return "Dolby Vision conversion playing"
    if (trackSelector == null || _loadControl == null) return "player not set up"
    if (player.playbackState != Player.STATE_READY || !player.playWhenReady) return "not playing"
    if (player.isCurrentMediaItemLive) return "live"
    return null
}

/**
 * What the player was set up with must suit the new source too. Dolby Vision is handled the same for
 * every file (base layer, passthrough or stripped) unless it's converted to DV8.1, which is decided
 * per file. With frame rate matching on, the new source must want the display mode already set: same
 * frame rate, and no resolution matching (an upgrade changes resolution). Null when it all fits.
 */
private suspend fun PlayerRuntimeController.hotSwapPipelineBlocker(pick: Stream, playback: StreamPlayback): String? {
    val settings = playerSettingsDataStore.playerSettings.first()
    val text = listOfNotNull(pick.name, pick.title, pick.description, playback.filename).joinToString(" ")
    if (DOLBY_VISION_TOKEN.containsMatchIn(text)) {
        val convertsDv = when (settings.dv7HandlingMode) {
            Dv7HandlingMode.DV81_LIBDOVI -> true
            // Reads the display's and decoders' capabilities: off the main thread.
            Dv7HandlingMode.AUTO -> withContext(Dispatchers.Default) {
                DolbyVisionBaseLayerPolicy.resolve(context, DoviBridge.isLibraryLoaded).decision
            } == DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81
            else -> false
        }
        if (convertsDv) return "Dolby Vision source, converted per file"
    }
    if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) return null
    if (settings.resolutionMatchingEnabled) return "resolution matching is on"
    val playing = _uiState.value.detectedFrameRate.takeIf { it > 0f } ?: return "frame rate playing unknown"
    val detection = FrameRateUtils.getCachedFrameRate(playback.url, playback.headers, playback.filename)
        ?: withTimeoutOrNull(HOT_SWAP_FPS_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val probeJob = coroutineContext[Job]
                FrameRateUtils.detectFrameRateWithOkHttpProbe(
                    context = context,
                    sourceUrl = playback.url,
                    headers = FrameRateUtils.streamHeadersForAfrProbe(playback.headers),
                    mimeType = playback.mimeType,
                    filename = playback.filename,
                    isCancelled = { probeJob?.isActive != true }
                )
            }
        }?.also { FrameRateUtils.cacheFrameRate(playback.url, playback.headers, it, playback.filename) }
        ?: return "new frame rate unknown"
    if (abs(detection.snapped - playing) > 0.01f) return "frame rate ${detection.snapped} vs $playing"
    return null
}

/**
 * Where to swap: past the head start and countdown, on the new file's next keyframe when its index
 * (MKV Cues, MP4 sync samples) can be read. Started on a keyframe, the new source's first picture is
 * that keyframe itself; anywhere else it decodes from the keyframe before, which held the last picture
 * a few hundred ms longer.
 */
private suspend fun PlayerRuntimeController.hotSwapPoint(swap: AutoQualityHotSwap): Long? {
    val playback = swap.playback
    val startedAt = SystemClock.elapsedRealtime()
    val mime = playback.mimeType?.lowercase().orEmpty()
    val keyframes = if ("mpegurl" in mime || "m3u8" in mime || "dash" in mime) {
        null
    } else {
        val durationMs = _playbackTimeline.value.duration
        withTimeoutOrNull(HOT_SWAP_INDEX_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                FileKeyframes.read(
                    client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(HOT_SWAP_INDEX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build(),
                    url = playback.url,
                    headers = playback.headers,
                    userAgent = PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
                    durationMs = durationMs
                )?.keyframesUs
            }
        }
    }
    val position = swap.player.currentPosition
    val earliestMs = position + swap.leadMs + swap.countdownMs
    // A step down must swap while the playing source still has buffer to play.
    val latestMs = if (swap.isUpgrade) {
        earliestMs + HOT_SWAP_MAX_KEYFRAME_WAIT_MS
    } else {
        position + swap.player.totalBufferedDuration - HOT_SWAP_DOWN_MARGIN_MS
    }
    if (earliestMs > latestMs) return null
    val readMs = SystemClock.elapsedRealtime() - startedAt
    val keyframeUs = keyframes?.let { times ->
        val i = times.binarySearch(earliestMs * 1000L).let { if (it >= 0) it else -it - 1 }
        times.getOrNull(i)?.takeIf { it <= latestMs * 1000L }
    }
    if (keyframeUs == null) {
        Log.i(
            PlayerRuntimeController.TAG,
            "AUTO_HOTSWAP: no keyframe to swap on (${if (keyframes == null) "index unread" else "none near"}, ${readMs}ms)"
        )
        return earliestMs
    }
    Log.i(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: swap point on a keyframe at ${keyframeUs / 1000}ms (index read in ${readMs}ms)")
    // In whole ms, at or just past the keyframe: the player starts there, never on the keyframe before.
    return (keyframeUs + 999L) / 1000L
}

/** How much to load ahead: [HOT_SWAP_MAX_BUFFER_BYTES] of the file at its bitrate, within the limits. */
private fun hotSwapBufferTargetMs(pick: Stream, durationMs: Long): Long {
    val bps = PlayerBitrateEstimator.fileBitrateBps(pick.behaviorHints?.videoSize, durationMs)
        ?: return HOT_SWAP_DEFAULT_BUFFER_MS
    val ms = HOT_SWAP_MAX_BUFFER_BYTES * 8L * 1000L / bps.coerceAtLeast(1)
    return ms.coerceIn(HOT_SWAP_READY_MS + 1_000L, HOT_SWAP_MAX_BUFFER_MS)
}

/** Starts loading the new source from the swap point; false (and the change dropped) if it can't. */
private fun PlayerRuntimeController.beginHotSwapLoad(swap: AutoQualityHotSwap): Boolean {
    val player = swap.player
    val selector = trackSelector
    val allocator = _loadControl?.allocator
    if (player !== _exoPlayer || selector == null || allocator == null) {
        endAutoQualityHotSwap(swap)
        return false
    }
    val playback = swap.playback
    val source = try {
        val mediaSource = mediaSourceFactory.createMediaSource(
            context = context,
            url = playback.url,
            headers = playback.headers,
            subtitleConfigurations = attachedAddonSubtitles().distinctBy { "${it.id}|${it.url}" }.map(::toSubtitleConfiguration),
            subtitleRoutes = subtitleRoutes(attachedAddonSubtitles()),
            filename = playback.filename,
            responseHeaders = playback.responseHeaders,
            mimeTypeOverride = playback.mimeType,
            audioDelayUsProvider = audioDelayUs::get,
            mediaMetadata = buildMediaSessionMetadata(),
            cacheKey = playback.cacheKey
        )
        // On the player's own thread, track selector and memory, so the player can take the loaded
        // period over as it is.
        PreloadMediaSource.Factory(
            UnusedMediaSourceFactory,
            HotSwapPreloadControl(swap),
            selector,
            playerBandwidthMeter ?: DefaultBandwidthMeter.getSingletonInstance(context),
            Array(player.rendererCount) { player.getRenderer(it).capabilities },
            allocator,
            player.playbackLooper
        ).createMediaSource(mediaSource)
    } catch (e: Exception) {
        Log.w(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: couldn't build the source (${e.message})")
        endAutoQualityHotSwap(swap)
        return false
    }
    // Building a file's source holds back background prefetch until a player starts it, which would
    // also hold back the playing source's: it starts at the swap here, so release the hold now.
    mediaSourceFactory.unlockStartupPrefetch()
    swap.source = source
    val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (swap.swappedAt == 0L && reason == Player.DISCONTINUITY_REASON_SEEK && autoQualityHotSwap === swap) {
                cancelAutoQualityHotSwap("seek")
                cancelAutoQualityChange(byViewer = false)
            }
        }

        override fun onRenderedFirstFrame() {
            if (swap.swappedAt == 0L || swap.firstFrameLogged) return
            swap.firstFrameLogged = true
            Log.i(
                PlayerRuntimeController.TAG,
                "AUTO_HOTSWAP: picture back ${SystemClock.elapsedRealtime() - swap.swappedAt}ms after the swap"
            )
        }
    }
    swap.listener = listener
    player.addListener(listener)
    swap.loadStartedAt = SystemClock.elapsedRealtime()
    source.preload(Util.msToUs(swap.swapAtMs))
    Log.i(
        PlayerRuntimeController.TAG,
        "AUTO_HOTSWAP: loading ${swap.pick.addonName} ${swap.target.label} (${if (swap.isUpgrade) "up" else "down"}) " +
            "from ${swap.swapAtMs / 1000}s " +
            "(now ${player.currentPosition / 1000}s, up to ${swap.bufferTargetMs / 1000}s ahead)"
    )
    return true
}

/** The addon subtitles the playing source carries, so the new one carries them too. */
private fun PlayerRuntimeController.attachedAddonSubtitles() =
    _uiState.value.addonSubtitles.filter { addonSubtitleKey(it) in attachedAddonSubtitleKeys }

/**
 * Waits for the countdown's start (the swap point less the countdown): by then the new source must
 * have loaded enough, or it's dropped. Then the countdown shows and the swap is set for the swap point.
 */
private suspend fun PlayerRuntimeController.runHotSwap(swap: AutoQualityHotSwap) {
    val startedAt = SystemClock.elapsedRealtime()
    val armAtMs = swap.swapAtMs - maxOf(swap.countdownMs, HOT_SWAP_ARM_MIN_MS)
    while (kotlin.coroutines.coroutineContext.isActive && autoQualityHotSwap === swap && swap.swappedAt == 0L) {
        val state = _uiState.value
        if (!state.autoQualityActive || isSourceSwitching() || PlaybackIntroState.active.value || state.error != null) {
            cancelAutoQualityHotSwap("Auto no longer free to change")
            return
        }
        swap.preloadError?.let { error ->
            giveUpHotSwap(swap, "the new source failed to load (${error.cause?.message ?: error.message})")
            return
        }
        val position = swap.player.currentPosition
        if (!swap.isUpgrade) {
            // A step down is only worth hiding while the playing source holds out until the swap.
            if (swap.player.playbackState == Player.STATE_BUFFERING) {
                hotSwapToUsualSwitch(swap, "stalled while the lower quality loaded")
                return
            }
            if (swap.player.totalBufferedDuration < swap.swapAtMs - position + 1_000L) {
                hotSwapToUsualSwitch(swap, "the buffer won't last to the swap point")
                return
            }
        }
        if (position > swap.swapAtMs + HOT_SWAP_LATE_MS) {
            cancelAutoQualityHotSwap("played past the swap point")
            cancelAutoQualityChange(byViewer = false)
            return
        }
        if (!swap.armed && position >= armAtMs) {
            if (!swap.isReady()) {
                giveUpHotSwap(swap, "only ${swap.bufferedMs}ms loaded after ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s")
                return
            }
            armHotSwap(swap, position)
        }
        if (swap.armed && position >= swap.swapAtMs) {
            // The scheduled message should have done it; this catches one that didn't arrive.
            performHotSwap(swap)
            return
        }
        delay(if (swap.armed && swap.swapAtMs - position < 3 * HOT_SWAP_POLL_MS) 20L else HOT_SWAP_POLL_MS)
    }
}

/** Shows the countdown (none when it's set to Instant) and schedules the swap at the swap point. */
private fun PlayerRuntimeController.armHotSwap(swap: AutoQualityHotSwap, position: Long) {
    swap.armed = true
    Log.i(
        PlayerRuntimeController.TAG,
        "AUTO_HOTSWAP: ready (${swap.bufferedMs}ms loaded${if (swap.loadedToEnd) ", to the end" else ""}); " +
            "swapping in ${swap.swapAtMs - position}ms"
    )
    if (swap.countdownMs > 0L) {
        val noticeId = NewLookNotifications.postCountdown(
            hotSwapNoticeKind(swap),
            hotSwapNoticeTitle(swap),
            (swap.swapAtMs - position).coerceAtLeast(0L),
            context.getString(R.string.quality_cancel_hint)
        )
        _uiState.update { it.copy(autoQualityPending = AutoQualityPendingUi(swap.target, swap.isUpgrade, noticeId)) }
    }
    swap.message = swap.player.createMessage { _, _ ->
        if (autoQualityHotSwap === swap) performHotSwap(swap)
    }
        .setLooper(Looper.getMainLooper())
        .setPosition(swap.swapAtMs)
        .setDeleteAfterDelivery(true)
        .send()
}

/** The swap: the player moves onto the loaded source at the swap point, and the player's state follows. */
private fun PlayerRuntimeController.performHotSwap(swap: AutoQualityHotSwap) {
    if (swap.swappedAt != 0L || autoQualityHotSwap !== swap) return
    val player = swap.player
    val source = swap.source
    if (player !== _exoPlayer || source == null || !_uiState.value.autoQualityActive || isSourceSwitching()) {
        cancelAutoQualityHotSwap("player changed before the swap")
        return
    }
    swap.message?.cancel()
    swap.message = null
    swap.swappedAt = SystemClock.elapsedRealtime()
    val playedTo = player.currentPosition
    // The language being listened to carries over (picked once the new track list arrives).
    keepLanguageOnNextSource(listeningLanguage())
    swap.requireAudio = hasPlayableAudio()
    // The last picture stays up until the new source's first, rather than black.
    exoPlayerView?.setKeepContentOnPlayerReset(true)
    player.setMediaSource(source, swap.swapAtMs)

    val pick = swap.pick
    applySelectedStreamState(pick, swap.playback.url, swap.playback.headers)
    persistSelectedStreamForReuse(stream = pick, url = currentStreamUrl, headers = currentHeaders)
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    autoQualityLastWasDowngrade = !swap.isUpgrade
    _uiState.update {
        it.copy(
            error = null,
            currentStreamName = pick.name ?: pick.addonName,
            currentStreamUrl = currentStreamUrl,
            currentStreamInfoHash = pick.infoHash ?: pick.clientResolve?.infoHash,
            currentStreamFileIdx = pick.clientResolve?.fileIdx,
            currentStreamAddonName = pick.addonName,
            autoQualityPending = null
        )
    }
    if (swap.countdownMs <= 0L) {
        // Instant: said as it happens.
        NewLookNotifications.post(hotSwapNoticeKind(swap), hotSwapNoticeTitle(swap), "")
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AUTO_HOTSWAP: swapped to ${pick.addonName} ${swap.target.label} at ${swap.swapAtMs}ms (played to ${playedTo}ms)"
    )
    // Auto leaves the new source alone for a while; a failure meanwhile goes back to the old one.
    swap.probationJob = scope.launch {
        val until = SystemClock.elapsedRealtime() + HOT_SWAP_PROBATION_MS
        var silentSince: Long? = null
        while (SystemClock.elapsedRealtime() < until) {
            delay(200L)
            if (autoQualityHotSwap !== swap) return@launch
            // Playing without sound (no audio track, or none the device plays) counts as failed.
            if (!swap.requireAudio || !swap.firstFrameLogged) continue
            if (hasPlayableAudio()) {
                swap.requireAudio = false
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val since = silentSince ?: now.also { silentSince = it }
            if (now - since >= HOT_SWAP_SILENT_GRACE_MS) {
                silentSourceKeys += swap.pick.switchKey()
                hotSwapBackToOriginal(swap, "no sound")
                return@launch
            }
        }
        if (autoQualityHotSwap === swap) endAutoQualityHotSwap(swap)
    }
    autoQualityPendingJob = swap.probationJob
}

/**
 * The new source can't be swapped to, and isn't tried again. An upgrade is dropped without a word: Auto
 * goes for the next source of that quality (once none is left, the quality is left alone a while). A
 * step down is still needed, so it goes on as the usual switch.
 */
private fun PlayerRuntimeController.giveUpHotSwap(swap: AutoQualityHotSwap, reason: String) {
    hotSwapFailedSourceKeys += swap.pick.switchKey()
    if (!swap.isUpgrade) {
        hotSwapToUsualSwitch(swap, reason)
        return
    }
    Log.i(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: dropped ${swap.pick.addonName} ${swap.target.label}: $reason")
    endAutoQualityHotSwap(swap)
}

/** A quick look at the link (its first byte): the HTTP status when it refuses, else null (unknown counts as fine). */
private suspend fun hotSwapLinkProblem(playback: StreamPlayback): String? = withContext(Dispatchers.IO) {
    val request = runCatching {
        Request.Builder()
            .url(playback.url)
            .apply { playback.headers.forEach { (name, value) -> header(name, value) } }
            .header("User-Agent", playback.headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            .header("Range", "bytes=0-0")
            .get()
            .build()
    }.getOrNull() ?: return@withContext null
    val client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .callTimeout(HOT_SWAP_LINK_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    try {
        client.newCall(request).execute().use { response ->
            if (response.code >= 400 && response.code != 416) "the link answers HTTP ${response.code}" else null
        }
    } catch (e: IOException) {
        null
    }
}

/** Hands the change to the usual switch (with its own notice and countdown). */
private fun PlayerRuntimeController.hotSwapToUsualSwitch(swap: AutoQualityHotSwap, reason: String) {
    Log.i(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: usual switch instead ($reason)")
    endAutoQualityHotSwap(swap)
    scheduleAutoQualityChange(swap.target, swap.isUpgrade, swap.from, swap.pick, allowHotSwap = false)
}

private fun hotSwapNoticeKind(swap: AutoQualityHotSwap) =
    if (swap.isUpgrade) NewLookNotifications.Kind.QUALITY_UP else NewLookNotifications.Kind.QUALITY_DOWN

private fun PlayerRuntimeController.hotSwapNoticeTitle(swap: AutoQualityHotSwap): String =
    context.getString(if (swap.isUpgrade) R.string.notif_quality_up else R.string.notif_quality_down, swap.target.label)

/** Calls off the hot swap under way, if any (before the swap: the loaded source is let go). */
internal fun PlayerRuntimeController.cancelAutoQualityHotSwap(reason: String) {
    val swap = autoQualityHotSwap ?: return
    if (swap.swappedAt == 0L) Log.i(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: called off ($reason)")
    endAutoQualityHotSwap(swap)
}

private fun PlayerRuntimeController.endAutoQualityHotSwap(swap: AutoQualityHotSwap) {
    if (autoQualityHotSwap === swap) autoQualityHotSwap = null
    swap.stopped = true
    swap.message?.cancel()
    swap.message = null
    swap.listener?.let { swap.player.removeListener(it) }
    swap.listener = null
    swap.probationJob?.cancel()
    swap.probationJob = null
    if (swap.swappedAt == 0L) {
        // Before the swap the loaded source is still ours (after it, the player's).
        swap.source?.releasePreloadMediaSource()
        swap.source = null
        val pending = _uiState.value.autoQualityPending
        if (swap.armed && pending != null) {
            NewLookNotifications.cancel(pending.noticeId)
            _uiState.update { it.copy(autoQualityPending = null) }
        }
    }
}

/**
 * The new source failed soon after the swap: back to what was playing, quietly, through the usual
 * switch. That source isn't tried again, and the quality is left alone a while. False when there's no
 * hot swap to answer for.
 */
internal fun PlayerRuntimeController.handleAutoQualityHotSwapFailure(): Boolean {
    val swap = autoQualityHotSwap ?: return false
    // Before the swap, a failure is the playing source's own.
    if (swap.swappedAt == 0L) return false
    hotSwapFailedSourceKeys += swap.pick.switchKey()
    return hotSwapBackToOriginal(swap, "failed")
}

/** Back to what played before the swap, quietly, through the usual switch; false when there's no way back. */
private fun PlayerRuntimeController.hotSwapBackToOriginal(swap: AutoQualityHotSwap, reason: String): Boolean {
    endAutoQualityHotSwap(swap)
    val original = swap.original ?: return false
    Log.w(PlayerRuntimeController.TAG, "AUTO_HOTSWAP: ${swap.pick.addonName} $reason after the swap; back to ${original.addonName}")
    autoQualityPolicy.block(swap.target, SystemClock.elapsedRealtime())
    _uiState.update { it.copy(error = null) }
    val resumeAt = swap.player.currentPosition.takeIf { it > 0L } ?: swap.swapAtMs
    val session = SourceSwitchSession(
        label = swap.target.label,
        isAudio = false,
        original = original,
        resumeAtMs = resumeAt,
        durationMs = _playbackTimeline.value.duration,
        next = { null },
        silent = true
    )
    session.returning = true
    scope.launch {
        // Out of the player's own error callback before the player is released.
        yield()
        beginSourceSwitch(session, original)
    }
    return true
}

/** Follows the preload on the playback thread: loads up to the buffer target, notes how far it got. */
private class HotSwapPreloadControl(private val swap: AutoQualityHotSwap) : PreloadMediaSource.PreloadControl {
    private var loggedFirstData = false

    private fun log(stage: String) = Log.i(
        PlayerRuntimeController.TAG,
        "AUTO_HOTSWAP: $stage after ${SystemClock.elapsedRealtime() - swap.loadStartedAt}ms"
    )

    override fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean {
        log("source prepared")
        return !swap.stopped
    }

    override fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean {
        log("file read, tracks picked")
        return !swap.stopped
    }

    override fun onContinueLoadingRequested(mediaSource: PreloadMediaSource, bufferedDurationUs: Long): Boolean {
        swap.bufferedMs = Util.usToMs(bufferedDurationUs)
        if (!loggedFirstData && swap.bufferedMs > 0L) {
            loggedFirstData = true
            log("first video past the swap point")
        }
        val more = !swap.stopped && swap.bufferedMs < swap.bufferTargetMs
        if (!more && !swap.stopped) log("loaded ${swap.bufferedMs}ms ahead")
        return more
    }

    override fun onUsedByPlayer(mediaSource: PreloadMediaSource) = Unit

    override fun onLoadedToTheEndOfSource(mediaSource: PreloadMediaSource) {
        swap.loadedToEnd = true
    }

    override fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource) {
        swap.preloadError = error
    }
}

/** The preload factory needs one, but the hot swap hands it ready-built sources only. */
private object UnusedMediaSourceFactory : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = IntArray(0)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        throw UnsupportedOperationException("hot swap sources are built by PlayerMediaSourceFactory")
}
