package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.core.streams.StreamFallbackSelector
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityMenu
import com.nuvio.tv.core.streams.StreamQualityParser
import com.nuvio.tv.core.streams.isInPlayerSwitchable
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_SOURCE_FALLBACK_ATTEMPTS = 6
private const val SOURCE_FALLBACK_WAIT_MS = 12_000L

// ── Quality menu ─────────────────────────────────────────────────────────────────────────────

internal fun PlayerRuntimeController.showQualityOverlay() {
    _uiState.update {
        it.copy(
            showQualityOverlay = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showSourcesPanel = false,
            showEpisodesPanel = false,
            currentStreamQuality = currentPlaybackQuality()
        )
    }
    // The source list comes from the same search the Streams screen used, so it's usually
    // instant; the current quality is re-read as sources arrive (a label beats a guess).
    qualityOverlayJob?.cancel()
    qualityOverlayJob = scope.launch {
        _uiState.map { it.sourceAllStreams }.distinctUntilChanged().collect { streams ->
            // Every quality is listed; one offered only in other languages says which, small, in its row.
            val notes = qualityLanguageNotes(streams, listeningLanguage())
            _uiState.update { state -> state.copy(currentStreamQuality = currentPlaybackQuality(), qualityLanguageNotes = notes) }
        }
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.dismissQualityOverlay() {
    qualityOverlayJob?.cancel()
    qualityOverlayJob = null
    _uiState.update { it.copy(showQualityOverlay = false) }
}

internal fun PlayerRuntimeController.selectStreamQuality(quality: StreamQuality) {
    val state = _uiState.value
    dismissQualityOverlay()
    // A quality picked by hand: Auto stands aside, even if it's the one already playing.
    leaveAutoQuality()
    if (quality == state.currentStreamQuality && !isSourceSwitching()) return
    val preferredAddon = currentAddonName ?: state.currentStreamAddonName
    // Sources in the language being listened to first; another language only when the quality has none in it.
    val language = listeningLanguage()
    fun ranked(tried: Set<String>) = preferLanguage(
        StreamQualityMenu.rankForQuality(
            streams = _uiState.value.sourceAllStreams,
            quality = quality,
            preferredAddon = preferredAddon,
            isCurrentStream = { isCurrentPlaybackStream(it) }
        ),
        language
    ).firstOrNull { it.switchKey() !in tried }
    val target = ranked(emptySet()) ?: return
    Log.i(PlayerRuntimeController.TAG, "QUALITY_SWITCH: to=${quality.label} addon=${target.addonName} audio=${language?.code}")
    val (original, resumeAt, duration) = sourceSwitchOrigin()
    beginSourceSwitch(
        SourceSwitchSession(
            label = quality.label,
            isAudio = false,
            original = original,
            resumeAtMs = resumeAt,
            durationMs = duration,
            next = ::ranked,
            onAttempt = { keepLanguageOnNextSource(language) }
        ),
        target
    )
}

/**
 * Switches source and resumes at the exact position. A plain switch resumes from saved watch
 * progress, which only exists past the first 2% of a title, so an early switch restarted at 0:00.
 * The pending seek is applied once the new source is ready, after any saved progress.
 */
internal fun PlayerRuntimeController.switchKeepingPosition(
    target: Stream,
    resumeAt: Long? = currentSwitchPositionMs(),
    startIntro: Boolean = true
) {
    switchToSourceStream(target, startIntro = startIntro)
    if (resumeAt != null) {
        _uiState.update { it.copy(pendingSeekPosition = resumeAt) }
    }
}

/**
 * Re-reads the playing quality for the label in the top corner of the minimal player. mpv never
 * reports its size through Exo's callbacks, so it's asked directly.
 */
internal fun PlayerRuntimeController.refreshCurrentStreamQuality() {
    if (isUsingMpvEngine() && currentVideoHeight == null) {
        val mpv = mpvView?.mpv
        if (mpv != null) {
            val width = runCatching { mpv.getPropertyInt("width") }.getOrNull()?.takeIf { it > 0 }
            val height = runCatching { mpv.getPropertyInt("height") }.getOrNull()?.takeIf { it > 0 }
            if (width != null && height != null) {
                currentVideoWidth = width
                currentVideoHeight = height
            }
        }
    }
    val quality = currentPlaybackQuality()
    if (quality != _uiState.value.currentStreamQuality) {
        _uiState.update { it.copy(currentStreamQuality = quality) }
    }
}

/**
 * Quality of what's playing: the decoded video size once known (labels can be wrong: a source
 * labeled 720p turned out to be 1920x960), else its source label, else its name.
 */
internal fun PlayerRuntimeController.currentPlaybackQuality(): StreamQuality? {
    val width = currentVideoWidth
    val height = currentVideoHeight
    if (width != null && height != null) {
        StreamQuality.fromVideoSize(width, height)?.let { return it }
    }
    _uiState.value.sourceAllStreams
        .firstOrNull { isCurrentPlaybackStream(it) }
        ?.let { StreamQualityParser.parse(it) }
        ?.let { return it }
    return StreamQualityParser.parse(currentFilename, _uiState.value.currentStreamName, currentStreamDescription)
}

internal fun PlayerRuntimeController.isCurrentPlaybackStream(stream: Stream): Boolean {
    val url = stream.getStreamUrl() ?: return false
    if (url == currentStreamUrl) return true
    // Normalizing only moves "user:pass@" into a header; a link without one is already in its played
    // form, so this skips a URI parse per listed source on every menu and switch check.
    if ('@' !in url) return false
    return stream.normalizedPlaybackUrl(url) == currentStreamUrl
}

private fun Stream.normalizedPlaybackUrl(url: String): String =
    PlayerMediaSourceFactory.normalizePlaybackRequest(
        url,
        PlayerMediaSourceFactory.sanitizeHeaders(behaviorHints?.proxyHeaders?.request)
    ).url

/** Where playback is right now, or null before the first frame (saved progress covers that). */
private fun PlayerRuntimeController.currentSwitchPositionMs(): Long? =
    if (hasRenderedFirstFrame) currentPlaybackPositionMs()?.takeIf { it > 0L } else null

// ── Source fallback ──────────────────────────────────────────────────────────────────────────

/**
 * A failure no retry of the same link can fix: the server refused or doesn't have the file.
 * These skip the retry/engine-switch ladder and go straight to the next source.
 */
internal fun PlaybackException.isDefinitiveSourceFailure(): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val code = findInvalidResponseCodeException()?.responseCode
        code != null && code in 400..499 && code !in setOf(408, 416, 429)
    }
    else -> false
}

/**
 * When the playing source fails, switch to the next addon's source (closest quality, same
 * position) instead of showing an error. Returns true when it has taken over; [onExhausted]
 * then runs if no untried source is left, so the caller's usual error screen still appears.
 */
internal fun PlayerRuntimeController.tryFallbackToNextSource(
    detailedError: String,
    onExhausted: () -> Unit
): Boolean {
    // A quality or audio picked in the player: the next source with the same quality or audio.
    if (handleSourceSwitchFailure(detailedError)) return true
    if (!BetaFeatures.settings(context).sourceFallback) return false
    if (sourceFallbackJob?.isActive == true) return true
    if (_uiState.value.isTorrentStream || contentType.equals("cloud", ignoreCase = true)) return false

    val videoKey = listOf(contentType, currentVideoId ?: contentId, currentSeason, currentEpisode).joinToString("|")
    if (videoKey != sourceFallbackVideoKey) {
        sourceFallbackVideoKey = videoKey
        sourceFallbackTriedUrls.clear()
        sourceFallbackAttempts = 0
    }
    if (sourceFallbackAttempts >= MAX_SOURCE_FALLBACK_ATTEMPTS) return false
    sourceFallbackAttempts++

    currentStreamUrl.takeIf { it.isNotBlank() }?.let { sourceFallbackTriedUrls += it }
    val failedAddon = currentAddonName ?: _uiState.value.currentStreamAddonName
    val resumeAt = currentSwitchPositionMs()
    Log.w(
        PlayerRuntimeController.TAG,
        "SOURCE_FALLBACK: addon=$failedAddon failed " +
            "(${detailedError.lineSequence().firstOrNull().orEmpty()}); looking for the next source"
    )

    sourceFallbackJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_source_fallback_searching),
                showPauseOverlay = false
            )
        }
        val streams = awaitFallbackCandidates()
        // Read after the source list has loaded: the failed source's quality is often only in
        // its list entry (an addon's title such as "Broken source 1080p"), not in what the
        // player was handed.
        val failedQuality = currentPlaybackQuality()
        // A source in the language being listened to first; another language only if none is left.
        val language = listeningLanguage()
        val next = StreamFallbackSelector.next(
            streams = streams,
            failedAddon = failedAddon,
            failedQuality = failedQuality,
            isTried = { isTriedOrUnplayable(it) || !keepsLanguage(it, language) }
        ) ?: StreamFallbackSelector.next(
            streams = streams,
            failedAddon = failedAddon,
            failedQuality = failedQuality,
            isTried = { isTriedOrUnplayable(it) }
        )
        if (next != null && keepsLanguage(next, language)) keepLanguageOnNextSource(language)
        if (next == null) {
            Log.w(PlayerRuntimeController.TAG, "SOURCE_FALLBACK: no untried sources left")
            onExhausted()
            return@launch
        }
        next.getStreamUrl()?.let { url ->
            sourceFallbackTriedUrls += url
            sourceFallbackTriedUrls += next.normalizedPlaybackUrl(url)
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "SOURCE_FALLBACK: switching to addon=${next.addonName} quality=${StreamQualityParser.parse(next)?.label} " +
                "(failed quality=${failedQuality?.label})"
        )
        switchKeepingPosition(next, resumeAt)
    }
    return true
}

/** The error screen the player shows once every source has been tried. */
internal fun PlayerRuntimeController.showPlaybackErrorAfterFallback(detailedError: String) {
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = detailedError,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            isBuffering = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            playbackEnded = false,
            postPlayMode = null
        )
    }
}

/** Only direct links: a fallback can't stop to resolve a debrid file or ask for P2P consent. */
private fun PlayerRuntimeController.isTriedOrUnplayable(stream: Stream): Boolean {
    val url = stream.getStreamUrl()?.takeIf { it.isNotBlank() } ?: return true
    return url in sourceFallbackTriedUrls || stream.normalizedPlaybackUrl(url) in sourceFallbackTriedUrls
}

/** Loads the source list (normally cached) and waits for an untried source or the search's end. */
private suspend fun PlayerRuntimeController.awaitFallbackCandidates(): List<Stream> {
    loadSourceStreams(forceRefresh = false)
    withTimeoutOrNull(SOURCE_FALLBACK_WAIT_MS) {
        _uiState.first { state ->
            state.sourceAllStreams.any { it.isInPlayerSwitchable() && !isTriedOrUnplayable(it) } ||
                (!state.isLoadingSourceStreams && sourceStreamsFetchCompleted)
        }
    }
    return _uiState.value.sourceAllStreams
}
