package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.ui.newlook.NewLookNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * What the controls show while a picked quality or audio loads: the spot it left, held still. [coverVideo]
 * once the old player is gone: until then its paused picture is still on screen.
 */
data class SourceSwitchUi(val positionMs: Long, val durationMs: Long, val coverVideo: Boolean = false)

/**
 * A quality or audio picked in the player (or Auto's change), from the click until the new source
 * plays. The player returns to its controls and runs a streak along the progress bar; the picture
 * holds the frame it left (taken from the stream by the seek-thumbnail decoder, never read back from
 * the screen) or, without one, the title's dimmed backdrop. The old source is released before the new
 * one loads, so only one player decoder is ever in use. A source that fails or doesn't start within the
 * timeout ([BetaPerformanceSettings.sourceSwitchTimeoutSeconds]) makes way for the next with the same
 * quality or audio ([next]); once none is left, playback goes back to [original] with a "Source failed
 * to play" notification (none when [silent]).
 */
internal class SourceSwitchSession(
    /** For the notification when nothing works: "1080p", "French". */
    val label: String,
    val isAudio: Boolean,
    val original: Stream?,
    val resumeAtMs: Long?,
    val durationMs: Long,
    /** The best source not in the keys given (see [switchKey]), or null. */
    val next: (Set<String>) -> Stream?,
    /** Runs before each pick plays (not on the way back to [original]). */
    val onAttempt: (Stream) -> Unit = {},
    /** Auto's own change: no notification when nothing plays. */
    val silent: Boolean = false,
    /** Every pick failed and playback is going back to [original]. */
    val onGiveUp: () -> Unit = {},
    /** A pick (not the way back) is playing. */
    val onPlaying: () -> Unit = {},
    /** A pick that starts without sound (no audio track, or none the device can play) counts as failed. */
    val requireAudio: Boolean = false
) {
    val tried = mutableSetOf<String>()
    var attempts = 0
    var returning = false
    /** Playing (not paused by the viewer) when the switch began: Back before the first attempt resumes it. */
    var wasPlaying = false
    /** What was playing when the current attempt started; the attempt counts once that changes. */
    var attemptFromUrl: String? = null
    /** The pick being tried ([switchKey]). */
    var attemptKey: String? = null
}

/** Picks tried for one switch before giving up. */
private const val MAX_SWITCH_ATTEMPTS = 6

/** A pick ready this long with no sound to play is taken as silent (the track list can lag readiness a little). */
private const val SILENT_GRACE_MS = 2_000L

/** How long the held frame may take to come from the thumbnail decoder before the backdrop is used. */
private const val FRAME_GRAB_TIMEOUT_MS = 1_200L

/** However the attempts go, the loading screen never outlasts this (beyond the attempts' own timeouts). */
private const val SWITCH_GUARD_SLACK_MS = 20_000L

/** The landing spot may be up to 20 s before the old position and 5 s after it (the nearest keyframe there). */
private const val SWITCH_EARLY_MARGIN_MS = 20_000L
private const val SWITCH_LATE_MARGIN_MS = 5_000L
internal val SOURCE_SWITCH_SEEK_PARAMETERS = SeekParameters(SWITCH_EARLY_MARGIN_MS * 1_000L, SWITCH_LATE_MARGIN_MS * 1_000L)

/** Identifies a source among the tried ones: its link, or for a debrid file not yet resolved, its listing. */
internal fun Stream.switchKey(): String =
    getStreamUrl()?.takeIf { it.isNotBlank() } ?: listOf(addonName, name, title, infoHash).joinToString("|")

internal fun PlayerRuntimeController.isSourceSwitching(): Boolean = sourceSwitch != null

/** Where playback is now, the way back if every pick fails (an earlier pick still loading keeps its own). */
internal fun PlayerRuntimeController.sourceSwitchOrigin(): Triple<Stream?, Long?, Long> {
    sourceSwitch?.let { return Triple(it.original, it.resumeAtMs, it.durationMs) }
    val state = _uiState.value
    // The listed source if it's there; otherwise the playing link itself, so a failed switch can always
    // go back (the old player is gone by then).
    val original = state.sourceAllStreams.firstOrNull { isCurrentPlaybackStream(it) } ?: currentPlaybackAsStream()
    val position = currentPlaybackPositionMs()?.takeIf { hasRenderedFirstFrame && it > 0L }
    val duration = _playbackTimeline.value.duration.takeIf { hasRenderedFirstFrame && it > 0L } ?: 0L
    return Triple(original, position, duration)
}

/** The playing link as a source to switch back to; null for a torrent (its local link dies with it). */
private fun PlayerRuntimeController.currentPlaybackAsStream(): Stream? {
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return null
    if (_uiState.value.isTorrentStream) return null
    return Stream(
        name = _uiState.value.currentStreamName,
        title = null,
        description = currentStreamDescription,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = currentStreamBingeGroup,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = currentHeaders.takeIf { it.isNotEmpty() }, response = null),
            videoSize = currentVideoSize,
            filename = currentFilename
        ),
        addonName = currentAddonName ?: _uiState.value.currentStreamAddonName.orEmpty(),
        addonLogo = currentAddonLogo
    )
}

/** Starts [session] with [first]: back to the controls, the frame held, the streak running. */
internal fun PlayerRuntimeController.beginSourceSwitch(session: SourceSwitchSession, first: Stream) {
    sourceSwitchJob?.cancel()
    val previous = sourceSwitch
    previous?.let { session.tried += it.tried }
    session.wasPlaying = previous?.wasPlaying ?: _uiState.value.isPlaying
    // The old source stops where it is while the new one loads.
    if (previous == null) setPlaybackPaused(true)
    sourceSwitch = session
    currentStreamUrl.takeIf { it.isNotBlank() }?.let { session.tried += it }
    session.original?.let { session.tried += it.switchKey() }
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            sourceSwitch = SourceSwitchUi(session.resumeAtMs ?: 0L, session.durationMs),
            showControls = true,
            showAudioOverlay = false,
            showQualityOverlay = false,
            showPauseOverlay = false
        )
    }
    sourceSwitchJob = scope.launch {
        yield()
        // The frame it left, from the stream itself, while the paused old player still shows it.
        if (previous == null && _switchFrame.value == null) {
            val at = session.resumeAtMs
            val grab = switchFrameGrabber
            if (at != null && grab != null) {
                _switchFrame.value = withTimeoutOrNull(FRAME_GRAB_TIMEOUT_MS) {
                    try {
                        grab(at)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: no frame to hold (${e.message})")
                        null
                    }
                }
            }
        }
        if (sourceSwitch === session) attemptSourceSwitch(session, first)
    }
    guardSourceSwitch(session)
}

/**
 * The last line of defence: whatever happens to the attempts, the loading screen ends and the player
 * is left to its own loading and error handling, so a switch can never hold the player hostage.
 */
private fun PlayerRuntimeController.guardSourceSwitch(session: SourceSwitchSession) {
    sourceSwitchGuardJob?.cancel()
    val perAttemptMs = BetaFeatures.settings(context).sourceSwitchTimeoutSeconds * 1_000L
    sourceSwitchGuardJob = scope.launch {
        delay(perAttemptMs * (MAX_SWITCH_ATTEMPTS + 1) + FRAME_GRAB_TIMEOUT_MS + SWITCH_GUARD_SLACK_MS)
        if (sourceSwitch === session) {
            Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: guard ended a switch that ran too long")
            sourceSwitchJob?.cancel()
            endSourceSwitch(resumeControlsTimer = false)
        }
    }
}

private fun PlayerRuntimeController.attemptSourceSwitch(session: SourceSwitchSession, target: Stream) {
    session.attempts++
    session.tried += target.switchKey()
    session.attemptFromUrl = currentStreamUrl
    session.attemptKey = target.switchKey()
    if (session.returning) {
        // The original plays as it was, not on the language that was being tried.
        pendingAudioLanguageSwitch = null
        audioSwitchCheck = null
    } else {
        session.onAttempt(target)
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "SOURCE_SWITCH: ${if (session.returning) "back to" else "trying"} addon=${target.addonName} " +
            "attempt=${session.attempts} label=${session.label}"
    )
    try {
        switchKeepingPosition(target, session.resumeAtMs, startIntro = false)
    } catch (e: Exception) {
        // Never leave the loading screen up over a player that didn't start: hand back to the usual flow.
        Log.e(PlayerRuntimeController.TAG, "SOURCE_SWITCH: switch threw; ending it", e)
        endSourceSwitch(resumeControlsTimer = false)
        return
    }
    // The controls opened when the switch began; whether they stay up from here is the viewer's call.
    _uiState.update {
        it.copy(sourceSwitch = SourceSwitchUi(session.resumeAtMs ?: 0L, session.durationMs, coverVideo = true))
    }
    watchSourceSwitch(session)
}

/** Waits for the attempt to play; past the timeout it counts as failed. */
private fun PlayerRuntimeController.watchSourceSwitch(session: SourceSwitchSession) {
    sourceSwitchWatchJob?.cancel()
    sourceSwitchWatchJob = scope.launch {
        val timeoutMs = BetaFeatures.settings(context).sourceSwitchTimeoutSeconds * 1_000L
        // True: playing. False: playing without sound. Null: not playing within the timeout.
        val outcome: Boolean? = withTimeoutOrNull(timeoutMs) {
            var silentSince: Long? = null
            var result: Boolean? = null
            while (result == null) {
                if (sourceSwitchAttemptPlaying(session)) {
                    if (!session.requireAudio || session.returning || hasPlayableAudio()) {
                        result = true
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        val since = silentSince ?: now.also { silentSince = it }
                        if (now - since >= SILENT_GRACE_MS) result = false
                    }
                } else {
                    silentSince = null
                }
                if (result == null) delay(100)
            }
            result
        }
        if (sourceSwitch !== session) return@launch
        if (outcome == false) {
            Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: no sound (attempt=${session.attempts}); next")
            session.attemptKey?.let { silentSourceKeys += it }
            moveOnFromSourceSwitch(session)
            return@launch
        }
        val started = outcome == true
        if (started) {
            Log.i(PlayerRuntimeController.TAG, "SOURCE_SWITCH: playing (attempt=${session.attempts})")
            if (!session.returning) session.onPlaying()
            endSourceSwitch(resumeControlsTimer = true)
        } else {
            Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: no playback after ${timeoutMs / 1000}s")
            if (session.returning) {
                // Still loading the original: let it carry on as a normal load.
                endSourceSwitch(resumeControlsTimer = false)
            } else {
                moveOnFromSourceSwitch(session)
            }
        }
    }
}

/**
 * The new source is ready at the old spot (within the margins), with any audio check passed. Ready, not
 * playing: a viewer who pauses while it loads mustn't have the source counted as failed.
 */
private fun PlayerRuntimeController.sourceSwitchAttemptPlaying(session: SourceSwitchSession): Boolean {
    if (currentStreamUrl == session.attemptFromUrl || !hasRenderedFirstFrame) return false
    val state = _uiState.value
    if (state.pendingSeekPosition != null || state.error != null) return false
    val ready = if (isUsingMpvEngine()) !state.isBuffering else _exoPlayer?.playbackState == Player.STATE_READY
    if (!ready) return false
    if (audioSwitchCheck != null) return false
    val resumeAt = session.resumeAtMs ?: return true
    val position = currentPlaybackPositionMs() ?: return false
    return position in (resumeAt - SWITCH_EARLY_MARGIN_MS - 1_000L)..(resumeAt + SWITCH_LATE_MARGIN_MS + 1_000L)
}

/** What's playing has an audio track the device plays (one is listed and selected). */
internal fun PlayerRuntimeController.hasPlayableAudio(): Boolean {
    val state = _uiState.value
    return state.audioTracks.isNotEmpty() && state.selectedAudioTrackIndex >= 0
}

/** The next pick with the same quality or audio; once none is left, back to the original. */
private fun PlayerRuntimeController.moveOnFromSourceSwitch(session: SourceSwitchSession) {
    val next = if (session.attempts < MAX_SWITCH_ATTEMPTS) session.next(session.tried) else null
    if (next != null) {
        attemptSourceSwitch(session, next)
        return
    }
    val original = session.original
    Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: nothing left for ${session.label}; back=${original?.addonName}")
    session.onGiveUp()
    if (!session.silent) {
        NewLookNotifications.post(
            NewLookNotifications.Kind.PLAYER,
            context.getString(R.string.notif_source_failed_title),
            when {
                original != null -> context.getString(R.string.notif_source_failed_back)
                session.isAudio -> context.getString(R.string.notif_source_failed_audio, session.label)
                else -> context.getString(R.string.notif_source_failed_quality, session.label)
            }
        )
    }
    if (original == null) {
        endSourceSwitch(resumeControlsTimer = false)
        return
    }
    session.returning = true
    attemptSourceSwitch(session, original)
}

/**
 * The playing source failed while a switch was loading it. True when the switch has taken over (the
 * next pick, or the way back); false lets the usual error handling run.
 */
internal fun PlayerRuntimeController.handleSourceSwitchFailure(detailedError: String): Boolean {
    // Auto's hot-swapped source failing just after the swap: back to the old one.
    if (handleAutoQualityHotSwapFailure()) return true
    val session = sourceSwitch ?: return false
    // Before the first attempt the old (paused) player is still the one playing: its errors are its own.
    if (session.attempts == 0 || currentStreamUrl == session.attemptFromUrl) return false
    sourceSwitchWatchJob?.cancel()
    if (session.returning) {
        endSourceSwitch(resumeControlsTimer = false)
        return false
    }
    Log.w(PlayerRuntimeController.TAG, "SOURCE_SWITCH: failed (${detailedError.lineSequence().firstOrNull().orEmpty()})")
    _uiState.update { it.copy(error = null) }
    sourceSwitchJob = scope.launch {
        // Out of the player's own error callback before the player is released.
        yield()
        if (sourceSwitch === session) moveOnFromSourceSwitch(session)
    }
    return true
}

/** A pick that turned out to be the wrong video (see [verifyAudioSwitchDuration]): on to the next. */
internal fun PlayerRuntimeController.rejectSourceSwitchAttempt(): Boolean {
    val session = sourceSwitch ?: return false
    if (session.returning) return false
    sourceSwitchWatchJob?.cancel()
    sourceSwitchJob = scope.launch {
        yield()
        if (sourceSwitch === session) moveOnFromSourceSwitch(session)
    }
    return true
}

/**
 * Back while a pick loads: quietly back to what was playing. False when there's nothing to go back to
 * (or it's already on its way back), so Back does what it usually does.
 */
internal fun PlayerRuntimeController.abortSourceSwitch(): Boolean {
    val session = sourceSwitch ?: return false
    if (session.returning) return false
    Log.i(PlayerRuntimeController.TAG, "SOURCE_SWITCH: cancelled with Back (attempt=${session.attempts})")
    sourceSwitchWatchJob?.cancel()
    sourceSwitchJob?.cancel()
    if (session.attempts == 0) {
        // Not started yet: the old source was only paused.
        endSourceSwitch(resumeControlsTimer = true)
        if (session.wasPlaying) setPlaybackPaused(false)
        return true
    }
    val original = session.original ?: return false
    session.returning = true
    sourceSwitchJob = scope.launch {
        yield()
        if (sourceSwitch === session) attemptSourceSwitch(session, original)
    }
    return true
}

/** Ends the switch: the frame and streak go, the controls hide again as usual. */
internal fun PlayerRuntimeController.endSourceSwitch(resumeControlsTimer: Boolean) {
    if (sourceSwitch == null && _switchFrame.value == null) return
    sourceSwitch = null
    sourceSwitchWatchJob?.cancel()
    sourceSwitchWatchJob = null
    sourceSwitchGuardJob?.cancel()
    sourceSwitchGuardJob = null
    _switchFrame.value = null
    _uiState.update { it.copy(sourceSwitch = null) }
    if (resumeControlsTimer) scheduleHideControls()
}

/** Something else took over (another source or episode picked, the player closed). */
internal fun PlayerRuntimeController.cancelSourceSwitch() {
    if (sourceSwitch == null) return
    sourceSwitchJob?.cancel()
    sourceSwitchJob = null
    endSourceSwitch(resumeControlsTimer = false)
}
