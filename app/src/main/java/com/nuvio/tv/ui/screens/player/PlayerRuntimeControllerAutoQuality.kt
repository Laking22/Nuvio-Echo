package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.core.intro.PlaybackIntroState
import com.nuvio.tv.core.player.AudioTrackProbe
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.core.streams.AutoQualityDecision
import com.nuvio.tv.core.streams.AutoQualityPolicy
import com.nuvio.tv.core.streams.AutoQualitySample
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityMenu
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.newlook.NewLookNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.player.seekpreview.FileKeyframes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AUTO_TICK_MS = 1_000L

/** After the viewer cancels a change, Auto leaves the quality alone this long. */
private const val AUTO_CANCEL_SNOOZE_MS = 5 * 60_000L

/** Auto's announced change while its countdown runs. */
data class AutoQualityPendingUi(val target: StreamQuality, val isUpgrade: Boolean, val noticeId: Long)

/** The qualities Auto may pick from, for one source list and listening language. */
internal class AutoQualityAvailable(
    val streams: List<com.nuvio.tv.domain.model.Stream>,
    val language: com.nuvio.tv.core.streams.AudioLanguage?,
    val qualities: List<StreamQuality>
)

/** How long reading a source's track list may take before Auto goes ahead without knowing. */
private const val AUTO_AUDIO_PROBE_TIMEOUT_MS = 5_000L

/** Sources checked for sound per change; past that, the next one is taken on trust (the switch still checks). */
private const val AUTO_MAX_AUDIO_PROBES = 3

/** A source's own bitrate must fit the measured speed with this much to spare. */
private const val AUTO_SOURCE_HEADROOM = 1.25

/** Auto doesn't change quality this close to the end: the rest plays out as it is. */
private const val AUTO_END_GUARD_MS = 3 * 60_000L

/**
 * Auto quality: while it's picked in the quality menu (the default once it's switched on in Settings),
 * playback is checked every second and [com.nuvio.tv.core.streams.AutoQualityPolicy] decides when to
 * step up or down. A change is announced ("Upgrading to 1080p") the set number of seconds before it
 * happens, then runs as a quiet quality switch; if the new quality won't play, it goes back without a
 * word and leaves that quality alone for a while.
 */
internal fun PlayerRuntimeController.startAutoQualityMonitor() {
    if (autoQualityJob?.isActive == true) return
    autoQualityJob = scope.launch {
        while (isActive) {
            runCatching { autoQualityTick() }
                .onFailure { Log.w(PlayerRuntimeController.TAG, "AUTO_QUALITY: tick failed", it) }
            delay(AUTO_TICK_MS)
        }
    }
}

/** Auto picked in the quality menu. */
internal fun PlayerRuntimeController.selectAutoQuality() {
    dismissQualityOverlay()
    autoQualityUserChoice = true
    autoQualityPolicy.onSourceChanged(SystemClock.elapsedRealtime())
    _uiState.update { it.copy(autoQualityActive = autoQualityAvailable()) }
    Log.i(PlayerRuntimeController.TAG, "AUTO_QUALITY: picked")
}

/** A quality picked by hand: Auto stands aside until it's picked again. */
internal fun PlayerRuntimeController.leaveAutoQuality() {
    if (autoQualityUserChoice == false && !_uiState.value.autoQualityActive) return
    autoQualityUserChoice = false
    cancelPendingAutoQualityChange()
    _uiState.update { it.copy(autoQualityActive = false) }
}

private fun PlayerRuntimeController.autoQualityAvailable(): Boolean {
    return BetaFeatures.settings(context).autoQuality
}

private fun PlayerRuntimeController.cancelPendingAutoQualityChange() = cancelAutoQualityChange(byViewer = false)

/**
 * Runs [block] as Auto's change in progress ([autoQualityPendingJob]). Set before the block starts: on
 * the main thread it runs at once up to its first pause, and a step it hands on to (the countdown, a hot
 * swap) sets its own job, which must not then be overwritten by this one, already finished.
 */
internal fun PlayerRuntimeController.launchPendingAutoQualityJob(block: suspend CoroutineScope.() -> Unit): Job {
    val job = scope.launch(start = CoroutineStart.LAZY, block = block)
    autoQualityPendingJob = job
    job.start()
    return job
}

/**
 * Drops the announced change and folds its notice away. [byViewer] (OK pressed during the countdown):
 * Auto then leaves the quality alone for a few minutes rather than asking again straight away.
 */
internal fun PlayerRuntimeController.cancelAutoQualityChange(byViewer: Boolean) {
    val pending = _uiState.value.autoQualityPending
    cancelAutoQualityHotSwap(if (byViewer) "cancelled by the viewer" else "change dropped")
    autoQualityPendingJob?.cancel()
    autoQualityPendingJob = null
    pending?.let { NewLookNotifications.cancel(it.noticeId) }
    if (pending != null) _uiState.update { it.copy(autoQualityPending = null) }
    if (byViewer) {
        autoQualityPolicy.snooze(SystemClock.elapsedRealtime(), AUTO_CANCEL_SNOOZE_MS)
        Log.i(PlayerRuntimeController.TAG, "AUTO_QUALITY: change to ${pending?.target?.label} cancelled by the viewer")
    }
}

private fun PlayerRuntimeController.autoQualityTick() {
    val enabled = autoQualityAvailable()
    // Picked at the start of every playback while the setting is on, until a quality is picked by hand.
    val active = enabled && (autoQualityUserChoice ?: true)
    val state = _uiState.value
    if (state.autoQualityEnabled != enabled || state.autoQualityActive != active) {
        _uiState.update { it.copy(autoQualityEnabled = enabled, autoQualityActive = active) }
    }
    if (!active) {
        cancelPendingAutoQualityChange()
        return
    }

    // With Hot swap on, changes barely interrupt playback, so Auto acts sooner (see AutoQualityPolicy.SENSITIVE).
    autoQualityPolicy.config = if (BetaFeatures.settings(context).autoQualityHotSwap) {
        AutoQualityPolicy.SENSITIVE
    } else {
        AutoQualityPolicy.DEFAULT
    }
    val now = SystemClock.elapsedRealtime()
    // A new source (any switch, or the first one): judge it afresh.
    if (currentStreamUrl != autoQualitySeenStreamUrl) {
        autoQualitySeenStreamUrl = currentStreamUrl
        autoQualityPolicy.onSourceChanged(now, wasDowngrade = autoQualityLastWasDowngrade)
        autoQualityLastWasDowngrade = false
    }
    if (autoQualityPendingJob?.isActive == true || !autoQualityCanJudge()) return

    // The quality list comes from the title's source search, usually cached from the Streams screen.
    if (state.sourceAllStreams.isEmpty() && !state.isLoadingSourceStreams && !autoQualitySourcesRequested) {
        autoQualitySourcesRequested = true
        loadSourceStreams(forceRefresh = false)
    }
    // Stay loyal to language (Settings): only qualities offered in the language being listened to, so with
    // nothing else in that language it stays where it is. Off: any quality, whatever its language.
    val language = if (BetaFeatures.settings(context).autoQualityLanguageLoyal) listeningLanguage() else null
    val streams = state.sourceAllStreams
    // Worked out again only when the source list or the language changes, not every second.
    val cached = autoQualityAvailableCache
    val available = if (cached != null && cached.streams === streams && cached.language == language) {
        cached.qualities
    } else {
        val inLanguage = if (language == null) streams else streams.filter { keepsLanguage(it, language) }
        StreamQualityMenu.availableQualities(inLanguage).also {
            autoQualityAvailableCache = AutoQualityAvailable(streams, language, it)
        }
    }
    val current = currentPlaybackQuality()
    val sample = readAutoQualitySample(now) ?: return
    when (val decision = autoQualityPolicy.evaluate(sample, current, available, currentPlaybackBitrateBps())) {
        AutoQualityDecision.Stay -> Unit
        is AutoQualityDecision.Change -> prepareAutoQualityChange(decision.target, decision.isUpgrade, current, decision.early)
    }
}

/** Only while a direct stream plays normally: not during a switch, the intro, an error, live or the last minutes. */
private fun PlayerRuntimeController.autoQualityCanJudge(): Boolean {
    val state = _uiState.value
    if (isSourceSwitching() || !hasRenderedFirstFrame || state.error != null) return false
    if (state.isTorrentStream || contentType.equals("cloud", ignoreCase = true)) return false
    if (PlaybackIntroState.active.value || state.showLoadingOverlay || state.playbackEnded) return false
    val timeline = _playbackTimeline.value
    if (timeline.isLive) return false
    if (timeline.duration > 0L && timeline.duration - timeline.currentPosition < AUTO_END_GUARD_MS) return false
    return true
}

private fun PlayerRuntimeController.readAutoQualitySample(now: Long): AutoQualitySample? {
    if (isUsingMpvEngine()) {
        val mpv = mpvView?.mpv ?: return null
        val pausedForCache = runCatching { mpv.getPropertyBoolean("paused-for-cache") }.getOrNull() == true
        val cacheSeconds = runCatching { mpv.getPropertyDouble("demuxer-cache-duration") }.getOrNull()
        val cacheSpeed = runCatching { mpv.getPropertyInt("cache-speed") }.getOrNull()
        return AutoQualitySample(
            nowMs = now,
            positionMs = currentPlaybackPositionMs() ?: 0L,
            wantsToPlay = !userPausedManually && (_uiState.value.isPlaying || pausedForCache),
            stalled = pausedForCache,
            bufferedAheadMs = cacheSeconds?.takeIf { it >= 0.0 }?.let { (it * 1000).toLong() },
            throughputBps = cacheSpeed?.takeIf { it > 0 }?.let { it.toLong() * 8L }
        )
    }
    val player = _exoPlayer ?: return null
    return AutoQualitySample(
        nowMs = now,
        positionMs = player.currentPosition,
        wantsToPlay = player.playWhenReady && !userPausedManually,
        stalled = player.playbackState == Player.STATE_BUFFERING,
        bufferedAheadMs = player.totalBufferedDuration.coerceAtLeast(0L),
        throughputBps = lastBandwidthEstimateBps.takeIf { it > 0L }
    )
}

/** What the playing file needs: its video bitrate, or its size over its length. */
private fun PlayerRuntimeController.currentPlaybackBitrateBps(): Long? =
    currentVideoBitrate?.takeIf { it > 0 }?.toLong()
        ?: PlayerBitrateEstimator.fileBitrateBps(currentVideoSize, _playbackTimeline.value.duration)?.toLong()

/**
 * Before anything is announced: picks the source to move to and checks it has sound (its track list,
 * read from the file). A silent one is passed over for the next; with none that has sound, the quality
 * is left alone for a while and nothing is said.
 */
private fun PlayerRuntimeController.prepareAutoQualityChange(
    target: StreamQuality,
    isUpgrade: Boolean,
    from: StreamQuality?,
    early: Boolean
) {
    val requireAudio = hasPlayableAudio()
    launchPendingAutoQualityJob {
        var pick: Stream? = null
        var probes = 0
        while (pick == null) {
            val candidate = autoQualityCandidate(target, emptySet()) ?: break
            if (!requireAudio || probes >= AUTO_MAX_AUDIO_PROBES) {
                pick = candidate
                break
            }
            probes++
            when (probeAudio(candidate)) {
                AudioTrackProbe.Result.NO_AUDIO -> {
                    Log.i(PlayerRuntimeController.TAG, "AUTO_QUALITY: ${candidate.addonName} ${target.label} has no sound; skipped")
                    silentSourceKeys += candidate.switchKey()
                }
                else -> pick = candidate
            }
        }
        if (pick == null) {
            Log.i(PlayerRuntimeController.TAG, "AUTO_QUALITY: no ${target.label} source that plays and fits; staying")
            autoQualityPolicy.block(target, SystemClock.elapsedRealtime())
            return@launchPendingAutoQualityJob
        }
        // The check took a moment: still wanted, and still possible?
        if (!_uiState.value.autoQualityActive || isSourceSwitching() || !autoQualityCanJudge()) return@launchPendingAutoQualityJob
        if (currentPlaybackQuality() == target) return@launchPendingAutoQualityJob
        scheduleAutoQualityChange(target, isUpgrade, from, pick, early = early)
    }
}

/** The file's own track list, read with the headers it plays with; UNKNOWN when it can't be read in time. */
private suspend fun PlayerRuntimeController.probeAudio(stream: Stream): AudioTrackProbe.Result {
    val url = stream.getStreamUrl() ?: return AudioTrackProbe.Result.UNKNOWN
    val request = PlayerMediaSourceFactory.normalizePlaybackRequest(url, stream.behaviorHints?.proxyHeaders?.request)
    val durationMs = _playbackTimeline.value.duration
    return AudioTrackProbe.probe(request.url, request.headers, AUTO_AUDIO_PROBE_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            FileKeyframes.read(
                client = PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                    .callTimeout(AUTO_AUDIO_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build(),
                url = request.url,
                headers = request.headers,
                userAgent = PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
                durationMs = durationMs
            )?.hasAudio
        }
    }
}

/**
 * Announces the change, waits the set countdown, then switches to [pick] if Auto still wants it. With
 * Hot swap on, an upgrade or an early step down ([early], with buffer left to hide it in) goes that way
 * instead where it can ([startAutoQualityHotSwap]).
 */
internal fun PlayerRuntimeController.scheduleAutoQualityChange(
    target: StreamQuality,
    isUpgrade: Boolean,
    from: StreamQuality?,
    pick: Stream,
    early: Boolean = false,
    allowHotSwap: Boolean = true
) {
    val settings = BetaFeatures.settings(context)
    if (allowHotSwap && (isUpgrade || early) && settings.autoQualityHotSwap &&
        startAutoQualityHotSwap(target, from, pick, isUpgrade)
    ) return
    val countdownSeconds = settings.autoQualityConfirmSeconds
    Log.i(
        PlayerRuntimeController.TAG,
        "AUTO_QUALITY: ${if (isUpgrade) "up" else "down"} ${from?.label} -> ${target.label} in ${countdownSeconds}s"
    )
    val kind = if (isUpgrade) NewLookNotifications.Kind.QUALITY_UP else NewLookNotifications.Kind.QUALITY_DOWN
    val title = context.getString(if (isUpgrade) R.string.notif_quality_up else R.string.notif_quality_down, target.label)
    if (countdownSeconds <= 0) {
        // Instant: said as it happens.
        NewLookNotifications.post(kind, title, "")
        startAutoQualitySwitch(target, isUpgrade, pick)
        return
    }
    // Up for the whole countdown, with the seconds left and "Press OK to cancel".
    val noticeId = NewLookNotifications.postCountdown(
        kind,
        title,
        countdownSeconds * 1_000L,
        context.getString(R.string.quality_cancel_hint)
    )
    _uiState.update { it.copy(autoQualityPending = AutoQualityPendingUi(target, isUpgrade, noticeId)) }
    launchPendingAutoQualityJob {
        delay(countdownSeconds * 1_000L)
        _uiState.update { it.copy(autoQualityPending = null) }
        // Picked by hand meanwhile, another switch under way, or already there: nothing to do.
        if (!_uiState.value.autoQualityActive || isSourceSwitching()) return@launchPendingAutoQualityJob
        if (currentPlaybackQuality() == target) return@launchPendingAutoQualityJob
        startAutoQualitySwitch(target, isUpgrade, pick)
    }
}

/**
 * The best source of [target] not in [tried]: the playing addon's first, the language being listened to
 * first (only that language when Stay loyal is on), never one found to have no sound, and never a file
 * heavier than the measured connection carries (a 60 Mbps remux labelled 4K on a 35 Mbps line would
 * only stall and step back down).
 */
private fun PlayerRuntimeController.autoQualityCandidate(target: StreamQuality, tried: Set<String>): Stream? {
    val state = _uiState.value
    val preferredAddon = currentAddonName ?: state.currentStreamAddonName
    val language = listeningLanguage()
    val loyal = BetaFeatures.settings(context).autoQualityLanguageLoyal
    val maxBitrateBps = autoQualityPolicy.throughputEstimateBps?.let { (it / AUTO_SOURCE_HEADROOM).toLong() }
    val durationMs = _playbackTimeline.value.duration
    return preferLanguage(
        StreamQualityMenu.rankForQuality(
            streams = state.sourceAllStreams,
            quality = target,
            preferredAddon = preferredAddon,
            isCurrentStream = { isCurrentPlaybackStream(it) }
        ),
        language
    ).firstOrNull {
        val key = it.switchKey()
        key !in tried && key !in silentSourceKeys && key !in hotSwapFailedSourceKeys &&
            (!loyal || keepsLanguage(it, language)) &&
            fitsConnection(it, maxBitrateBps, durationMs)
    }
}

/** The file's own bitrate (its size over the title's length) within [maxBitrateBps]; true when either is unknown. */
private fun fitsConnection(stream: Stream, maxBitrateBps: Long?, durationMs: Long): Boolean {
    maxBitrateBps ?: return true
    val bps = PlayerBitrateEstimator.fileBitrateBps(stream.behaviorHints?.videoSize, durationMs) ?: return true
    return bps <= maxBitrateBps
}

private fun PlayerRuntimeController.startAutoQualitySwitch(target: StreamQuality, isUpgrade: Boolean, pick: Stream) {
    val language = listeningLanguage()
    fun ranked(tried: Set<String>) = autoQualityCandidate(target, tried)
    // The source checked before the announcement, unless it has since turned out silent.
    val first = pick.takeIf { it.switchKey() !in silentSourceKeys } ?: ranked(emptySet())
    if (first == null) {
        autoQualityPolicy.block(target, SystemClock.elapsedRealtime())
        return
    }
    val (original, resumeAt, duration) = sourceSwitchOrigin()
    autoQualityLastWasDowngrade = !isUpgrade
    beginSourceSwitch(
        SourceSwitchSession(
            label = target.label,
            isAudio = false,
            original = original,
            resumeAtMs = resumeAt,
            durationMs = duration,
            next = ::ranked,
            onAttempt = { keepLanguageOnNextSource(language) },
            // Auto's own change: if nothing plays, back to what was playing without a word.
            silent = true,
            onGiveUp = {
                autoQualityLastWasDowngrade = false
                autoQualityPolicy.block(target, SystemClock.elapsedRealtime())
            },
            // Moving to a source without sound is worse than staying: one that starts silent is skipped.
            requireAudio = hasPlayableAudio()
        ),
        first
    )
}
