package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.core.streams.AudioLanguage
import com.nuvio.tv.core.streams.AudioLanguageMenu
import com.nuvio.tv.core.streams.AudioMenuOption
import com.nuvio.tv.core.streams.AudioTrackRef
import com.nuvio.tv.core.streams.StreamAudioParser
import com.nuvio.tv.core.streams.isInPlayerSwitchable
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A language picked in the audio menu, waiting for the next source's tracks. */
internal data class PendingAudioLanguageSwitch(
    val language: AudioLanguage?,
    val fromStreamUrl: String?,
    val requestedAtMs: Long
)

/** A pending pick is dropped if the new source hasn't reported its tracks by then. */
private const val PENDING_AUDIO_TIMEOUT_MS = 120_000L

private data class AudioMenuInputs(
    val visible: Boolean,
    val streams: List<Stream>,
    val tracks: List<TrackInfo>,
    val selectedIndex: Int
)

internal fun PlayerRuntimeController.isAudioLanguageMenuEnabled(): Boolean =
    BetaFeatures.settings(context).audioLanguageMenu

/**
 * The audio language a quality change has to keep: the one picked in this playback (audio menu or a
 * track of the file), else the playing track's, else what the playing source is tagged with. Null
 * when it can't be told, and then any source will do.
 */
internal fun PlayerRuntimeController.listeningLanguage(): AudioLanguage? {
    stickyAudioLanguage?.let { return it }
    val state = _uiState.value
    state.audioTracks.firstOrNull { it.index == state.selectedAudioTrackIndex || it.isSelected }
        ?.let { StreamAudioParser.trackLanguage(it.language, it.name) }
        ?.let { return it }
    val current = state.sourceAllStreams.firstOrNull { isCurrentPlaybackStream(it) } ?: return null
    val audio = StreamAudioParser.audio(current, originalAudioLanguage(), audioParsingTitles())
    return audio.languages.singleOrNull()?.takeIf { !audio.unknown }
}

/** Whether [stream] has [language] (null: no language to keep, so yes). */
internal fun PlayerRuntimeController.keepsLanguage(stream: Stream, language: AudioLanguage?): Boolean =
    language == null || AudioLanguageMenu.matches(stream, language, originalAudioLanguage(), audioParsingTitles())

/** [streams] with those in [language] first, each group keeping its order (null: as they are). */
internal fun PlayerRuntimeController.preferLanguage(streams: List<Stream>, language: AudioLanguage?): List<Stream> {
    if (language == null || streams.size < 2) return streams
    val (same, other) = streams.partition { keepsLanguage(it, language) }
    return same + other
}

/**
 * For each quality offered only in languages other than [language]: those languages ("Hindi",
 * "French, German +1"), shown small in the quality menu's row. Empty when there's no language to keep.
 */
internal fun PlayerRuntimeController.qualityLanguageNotes(
    streams: List<Stream>,
    language: AudioLanguage?
): Map<com.nuvio.tv.core.streams.StreamQuality, String> {
    if (language == null) return emptyMap()
    val original = originalAudioLanguage()
    val titles = audioParsingTitles()
    val byQuality = HashMap<com.nuvio.tv.core.streams.StreamQuality, MutableList<Stream>>()
    for (stream in streams) {
        if (!stream.isInPlayerSwitchable()) continue
        val quality = com.nuvio.tv.core.streams.StreamQualityParser.parse(stream) ?: continue
        byQuality.getOrPut(quality) { mutableListOf() } += stream
    }
    val notes = HashMap<com.nuvio.tv.core.streams.StreamQuality, String>()
    for ((quality, sources) in byQuality) {
        if (sources.any { AudioLanguageMenu.matches(it, language, original, titles) }) continue
        val languages = sources.flatMap { StreamAudioParser.audio(it, original, titles).languages }.distinct()
        notes[quality] = when {
            languages.isEmpty() -> context.getString(com.nuvio.tv.R.string.audio_menu_unknown)
            languages.size <= 2 -> languages.joinToString(", ") { audioLanguageDisplayName(it) }
            else -> languages.take(2).joinToString(", ") { audioLanguageDisplayName(it) } + " +${languages.size - 2}"
        }
    }
    return notes
}

/** A track of the playing file picked by hand: its language is the one to keep. */
internal fun PlayerRuntimeController.rememberListeningLanguage(trackIndex: Int) {
    val track = _uiState.value.audioTracks.firstOrNull { it.index == trackIndex } ?: return
    StreamAudioParser.trackLanguage(track.language, track.name)?.let { stickyAudioLanguage = it }
}

/**
 * Before a quality change: the new source starts on [language] (a multi-audio file would otherwise
 * start on its default track).
 */
internal fun PlayerRuntimeController.keepLanguageOnNextSource(language: AudioLanguage?) {
    pendingAudioLanguageSwitch = language?.let {
        PendingAudioLanguageSwitch(language = it, fromStreamUrl = currentStreamUrl, requestedAtMs = SystemClock.elapsedRealtime())
    }
}

/** The title's original language, as the menu's "Original" row. */
internal fun PlayerRuntimeController.originalAudioLanguage(): AudioLanguage? = AudioLanguage.fromCode(contentLanguage)

/** Words of the title, so "Johnny English" or "The French Dispatch" aren't read as audio tags. */
internal fun PlayerRuntimeController.audioParsingTitles(): List<String?> =
    listOf(_uiState.value.title, _uiState.value.currentEpisodeTitle)

/**
 * Keeps the menu's rows up to date while it's open: sources arrive over several seconds and the
 * playing file's tracks can change. Parsing runs off the main thread.
 */
internal fun PlayerRuntimeController.startAudioLanguageMenu() {
    audioMenuJob?.cancel()
    audioMenuJob = scope.launch {
        _uiState
            .map { AudioMenuInputs(it.showAudioOverlay, it.sourceAllStreams, it.audioTracks, it.selectedAudioTrackIndex) }
            .distinctUntilChanged()
            .takeWhile { it.visible }
            .collectLatest { inputs ->
                val settings = BetaFeatures.settings(context)
                val original = originalAudioLanguage()
                val titles = audioParsingTitles()
                val options = withContext(Dispatchers.Default) {
                    AudioLanguageMenu.buildOptions(
                        streams = inputs.streams,
                        isCurrentStream = { isCurrentPlaybackStream(it) },
                        tracks = inputs.tracks.map { it.toAudioTrackRef() },
                        selectedTrackIndex = inputs.selectedIndex,
                        original = original,
                        shownKeys = settings.audioMenuLanguages,
                        showUnknown = settings.showUnknownAudio,
                        titles = titles
                    )
                }
                if (options != _uiState.value.audioMenuOptions) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "AUDIO_MENU: original=${original?.code} sources=${inputs.streams.size} tracks=${inputs.tracks.size} rows=" +
                            options.joinToString(",") { row ->
                                (row.language?.code ?: "unknown") + (if (row.isCurrent) "*" else "") +
                                    ":" + (row.trackIndex?.let { "track$it" } ?: row.sourceCount.toString())
                            }
                    )
                }
                _uiState.update { it.copy(audioMenuOptions = options) }
            }
        _uiState.update { it.copy(audioMenuOptions = null) }
    }
    loadSourceStreams(forceRefresh = false)
}

private fun TrackInfo.toAudioTrackRef() = AudioTrackRef(
    index = index,
    language = language,
    name = name,
    detail = listOfNotNull(codec?.uppercase(), channelCount?.let { "$it ch" }).joinToString(" · ").ifBlank { null }
)

/**
 * A switch from the audio menu waiting for the new source's first frame, to check it's the same
 * video: addons sometimes tag a movie or a whole-season file under an episode.
 */
internal data class AudioSwitchCheck(
    val language: AudioLanguage?,
    val fromStreamUrl: String?,
    val expectedDurationMs: Long
)

/**
 * A language the playing file doesn't have: play a source that does, at the same spot. If it fails,
 * stalls or turns out to be another video, the next source with that audio is tried (see
 * [SourceSwitchSession]).
 */
internal fun PlayerRuntimeController.switchSourceForAudio(option: AudioMenuOption) {
    _uiState.update { it.copy(showAudioOverlay = false) }
    val language = option.language
    fun ranked(tried: Set<String>): Stream? {
        val state = _uiState.value
        return AudioLanguageMenu.rankForLanguage(
            streams = state.sourceAllStreams,
            language = language,
            original = originalAudioLanguage(),
            preferredAddon = currentAddonName ?: state.currentStreamAddonName,
            currentQuality = currentPlaybackQuality(),
            isCurrentStream = { isCurrentPlaybackStream(it) },
            titles = audioParsingTitles(),
            season = currentSeason,
            episode = currentEpisode
        ).firstOrNull { it.switchKey() !in tried }
    }
    val target = ranked(emptySet())
    if (target == null) {
        scheduleHideControls()
        return
    }
    val (original, resumeAt, duration) = sourceSwitchOrigin()
    val languageName = language?.let { audioLanguageDisplayName(it) }
        ?: context.getString(com.nuvio.tv.R.string.audio_menu_unknown)
    Log.i(PlayerRuntimeController.TAG, "AUDIO_SWITCH: to=${language?.code ?: "unknown"} addon=${target.addonName}")
    beginSourceSwitch(
        SourceSwitchSession(
            label = languageName,
            isAudio = true,
            original = original,
            resumeAtMs = resumeAt,
            durationMs = duration,
            next = ::ranked,
            onAttempt = {
                // The new source starts on the picked language, once checked to be the same video.
                pendingAudioLanguageSwitch = PendingAudioLanguageSwitch(
                    language = language,
                    fromStreamUrl = currentStreamUrl,
                    requestedAtMs = SystemClock.elapsedRealtime()
                )
                audioSwitchCheck = AudioSwitchCheck(
                    language = language,
                    fromStreamUrl = currentStreamUrl,
                    expectedDurationMs = duration
                )
            },
            // Playing in the picked language: quality changes keep to it from now on.
            onPlaying = { language?.let { stickyAudioLanguage = it } }
        ),
        target
    )
}

/**
 * Called once a source's duration is known. After a switch from the audio menu, a source far longer
 * or shorter than the one it replaced is the wrong video: the switch moves on to the next source.
 */
internal fun PlayerRuntimeController.verifyAudioSwitchDuration(durationMs: Long) {
    val check = audioSwitchCheck ?: return
    if (currentStreamUrl == check.fromStreamUrl) return
    audioSwitchCheck = null
    if (AudioLanguageMenu.durationFits(check.expectedDurationMs, durationMs)) return
    Log.w(
        PlayerRuntimeController.TAG,
        "AUDIO_SWITCH: source runs ${durationMs / 1000}s, expected ${check.expectedDurationMs / 1000}s; not this video"
    )
    pendingAudioLanguageSwitch = null
    rejectSourceSwitchAttempt()
}

/** Opens the Sources panel with only the sources that have [option]'s audio. */
internal fun PlayerRuntimeController.showSourcesForAudio(option: AudioMenuOption) {
    _uiState.update {
        it.copy(
            showAudioOverlay = false,
            sourceAudioFilter = SourceAudioFilter(option.language, option.isOriginal)
        )
    }
    showSourcesPanel()
    filterSourceStreamsByAddon(_uiState.value.sourceSelectedAddonFilter)
}

/** The Sources panel's list: [addonName]'s sources (null: all), narrowed to [audio] when set. */
internal fun PlayerRuntimeController.filterSourceList(
    streams: List<Stream>,
    addonName: String?,
    audio: SourceAudioFilter?
): List<Stream> {
    val byAddon = if (addonName == null) streams else streams.filter { it.addonName == addonName }
    if (audio == null) return byAddon
    val original = originalAudioLanguage()
    val titles = audioParsingTitles()
    return byAddon.filter { stream ->
        isCurrentPlaybackStream(stream) || AudioLanguageMenu.matches(stream, audio.language, original, titles)
    }
}

/**
 * After a switch from the audio menu, starts the new source on the picked language (a dual-audio
 * file would otherwise start on its default track). Called whenever the track list updates.
 */
internal fun PlayerRuntimeController.applyPendingAudioLanguage(audioTracks: List<TrackInfo>) {
    val pending = pendingAudioLanguageSwitch ?: return
    if (SystemClock.elapsedRealtime() - pending.requestedAtMs > PENDING_AUDIO_TIMEOUT_MS) {
        pendingAudioLanguageSwitch = null
        return
    }
    if (currentStreamUrl == pending.fromStreamUrl || audioTracks.isEmpty()) return
    pendingAudioLanguageSwitch = null
    val language = pending.language ?: return
    val track = audioTracks.firstOrNull { StreamAudioParser.trackLanguage(it.language, it.name) == language }
    if (track == null) {
        Log.i(PlayerRuntimeController.TAG, "AUDIO_SWITCH: new source has no ${language.code} track (${audioTracks.size} tracks)")
        return
    }
    if (track.isSelected || _uiState.value.selectedAudioTrackIndex == track.index) return
    Log.i(PlayerRuntimeController.TAG, "AUDIO_SWITCH: selecting ${language.code} track ${track.index}")
    rememberAudioSelection(track.index)
    selectAudioTrack(track.index)
    _uiState.update { it.copy(selectedAudioTrackIndex = track.index) }
}
