package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import android.os.SystemClock
import com.nuvio.tv.core.intro.PlaybackIntroState
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.parental.AgeRatings
import com.nuvio.tv.core.parental.ContentNote
import com.nuvio.tv.core.parental.ParentalCategory
import com.nuvio.tv.core.parental.ParentalLevel
import com.nuvio.tv.data.repository.ParentalGuideRequest
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.model.normalizeLanguageCode
import com.nuvio.tv.data.local.AudioLanguageOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    metaFetchJob = scope.launch {
        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                applyMetaDetails(result.data)
            }
            is NetworkResult.Error -> {
            }
            NetworkResult.Loading -> {
            }
        }
    }

    scope.launch {
        enrichDescriptionFromTmdb(id, type)
    }
}

internal fun PlayerRuntimeController.initializeCloudPlaybackSequence() {
    val playbackContext = cloudPlaybackContext ?: return
    metaVideos = playbackContext.asVideos()
    val currentFile = playbackContext.currentFile ?: return
    currentVideoId = playbackContext.videoId(currentFile)
    currentSeason = 1
    currentEpisode = playbackContext.currentIndex + 1
    currentEpisodeTitle = currentFile.name
    _uiState.update {
        it.copy(
            currentVideoId = currentVideoId,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    metaGenres = meta.genres
    metaCountry = meta.country
    // Fill in content language from meta if not provided via navigation args.
    if (contentLanguage == null) {
        contentLanguage = meta.resolveContentLanguage()
    }
    val description = resolveDescription(meta)

    recomputeNextEpisode(resetVisibility = false)
    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            castMembers = if (meta.castMembers.isNotEmpty()) meta.castMembers else state.castMembers,
            isNextEpisodeMetadataResolved = true
        )
    }
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.season == currentSeason && video.episode == currentEpisode
    }?.overview

    // Always update description when switching episodes - clear stale description
    _uiState.update { it.copy(description = overview) }

    // Push episode metadata to the MediaSession so Google Home shows the new episode.
    updateMediaSessionMetadata()

    // Cloud library IDs belong to the provider, not TMDB.
    if (!contentType.equals("cloud", ignoreCase = true)) {
        scope.launch {
            enrichDescriptionFromTmdb(contentId, contentType)
        }
    }
}

private suspend fun PlayerRuntimeController.enrichDescriptionFromTmdb(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return
    val settings = tmdbSettingsDataStore.settings.first()
    if (!settings.enabled || !settings.useBasicInfo) return

    val tmdbId = runCatching { tmdbService.ensureTmdbId(id, type) }.getOrNull() ?: return
    val contentType = when (type.lowercase()) {
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
    val enrichment = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = contentType,
            language = settings.language
        )
    }.getOrNull() ?: return

    val isSeries = type.lowercase() in listOf("series", "tv")
    val season = currentSeason
    val episode = currentEpisode

    // For series, try to get episode-level overview and title from TMDB.
    val episodeEnrichment = if (isSeries && season != null && episode != null) {
        runCatching {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = settings.language
            )[season to episode]
        }.getOrNull()
    } else null

    val tmdbDescription = episodeEnrichment?.overview ?: enrichment.description
    if (settings.useBasicInfo && !tmdbDescription.isNullOrBlank()) {
        _uiState.update { it.copy(description = tmdbDescription) }
    }

    // Enrich title from TMDB (localized).
    if (settings.useBasicInfo) {
        val tmdbTitle = enrichment.localizedTitle
        if (!tmdbTitle.isNullOrBlank()) {
            _uiState.update { it.copy(title = tmdbTitle) }
        }
    }

    // Enrich logo from TMDB if artwork is enabled.
    if (settings.useArtwork) {
        val tmdbLogo = enrichment.logo
        if (!tmdbLogo.isNullOrBlank()) {
            _uiState.update { it.copy(logo = tmdbLogo) }
        }
    }

    // Also enrich episode title from TMDB if available.
    if (settings.useBasicInfo) {
        val tmdbEpisodeTitle = episodeEnrichment?.title
        if (!tmdbEpisodeTitle.isNullOrBlank()) {
            _uiState.update { it.copy(currentEpisodeTitle = tmdbEpisodeTitle) }
        }
    }

    // Enrich cast from TMDB if addon didn't provide any.
    if (settings.useBasicInfo && enrichment.castMembers.isNotEmpty()) {
        _uiState.update { state ->
            if (state.castMembers.isEmpty()) state.copy(castMembers = enrichment.castMembers)
            else state
        }
    }

    // Fill in content language from TMDB if still unknown, so "original
    // audio" can resolve correctly even when the addon meta lacks it.
    if (contentLanguage == null) {
        val tmdbLang = normalizeLanguageCode(enrichment.language)
        if (tmdbLang != null) {
            contentLanguage = tmdbLang
            val hasUserAudioSelection = persistedTrackPreference?.audio != null
            if (!hasUserAudioSelection) {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                if (playerSettings.preferredAudioLanguage == AudioLanguageOption.ORIGINAL) {
                    val resolved = resolvePreferredAudioLanguages(
                        preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                        secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                        deviceLanguages = resolveDeviceAudioLanguages(),
                        contentOriginalLanguage = tmdbLang
                    )
                    if (resolved.isNotEmpty()) {
                        _exoPlayer?.let { player ->
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setPreferredAudioLanguages(*resolved.toTypedArray())
                                .build()
                        }
                        if (isUsingMpvEngine()) {
                            mpvPreferredAudioLanguages = resolved
                            mpvView?.applyAudioLanguagePreferences(resolved)
                        }
                    }
                }
            }
        }
    }

    // Refresh MediaSession metadata with TMDB-enriched title / artwork.
    updateMediaSessionMetadata()
}

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv", "other", "cloud")) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    if (normalizedType == "other" || normalizedType == "cloud") {
        val currentId = currentVideoId
        val idx = if (currentId != null) metaVideos.indexOfFirst { it.id == currentId } else -1
        val resolvedNext = if (idx >= 0 && idx < metaVideos.size - 1) metaVideos[idx + 1] else null
        nextEpisodeVideo = resolvedNext
        if (resolvedNext == null) {
            clearNextEpisodeAndCancelPostPlay()
            return
        }
        val nextInfo = NextEpisodeInfo(
            videoId = resolvedNext.id,
            season = resolvedNext.season ?: 1,
            episode = resolvedNext.episode ?: (idx + 2),
            title = resolvedNext.title,
            thumbnail = resolvedNext.thumbnail,
            overview = resolvedNext.overview,
            released = resolvedNext.released,
            hasAired = true,
            unairedMessage = null,
            isOtherType = normalizedType == "other" || normalizedType == "cloud"
        )
        applyRecomputedNextEpisode(nextInfo, resetVisibility)
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val shuffleState = playbackShuffleState ?: run {
        clearNextEpisodeAndCancelPostPlay()
        return
    }
    val resolvedNext = episodeShufflePlayback.nextEpisode(
        profileId, contentId.orEmpty(), metaVideos, season, episode, shuffleState
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        available = resolvedNext.available,
        unairedMessage = if (hasAired) {
            null
        } else {
            context.getString(com.nuvio.tv.R.string.next_episode_not_aired_yet)
        }
    )
    applyRecomputedNextEpisode(nextInfo, resetVisibility)
}

private fun PlayerRuntimeController.clearNextEpisodeAndCancelPostPlay() {
    val mode = _uiState.value.postPlayMode
    if (mode != null) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update {
        it.copy(
            nextEpisode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
}

private fun PlayerRuntimeController.applyRecomputedNextEpisode(
    nextInfo: NextEpisodeInfo,
    resetVisibility: Boolean,
) {
    val previousState = _uiState.value
    val previousNextEpisode = previousState.nextEpisode
    val previousMode = previousState.postPlayMode
    if (previousMode is PostPlayMode.StillWatching &&
        previousNextEpisode != null &&
        previousNextEpisode.videoId != nextInfo.videoId
    ) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        val updatedMode = if (shouldResetVisibility) {
            null
        } else {
            state.postPlayMode?.copyWithNextEpisode(nextInfo)
        }
        state.copy(
            nextEpisode = nextInfo,
            postPlayMode = updatedMode,
            postPlayDismissedForCurrentEpisode =
                if (shouldResetVisibility && !state.postPlayDismissedForCurrentEpisode) false
                else state.postPlayDismissedForCurrentEpisode,
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    nextEpisodeCardWaiting = false
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    if (_playbackTimeline.value.isLive) return
    if (!hasRenderedFirstFrame) return
    // Short debrid/error clips must never arm next-episode auto-play (see #2819).
    // Prefer the largest known duration; the per-poll value can drop transiently.
    val effectiveDurationEarly = maxOf(durationMs, lastKnownDuration)
    if (isShortPlaceholderDuration(effectiveDurationEarly)) return
    // Act only after this stream has reported a position away from its end.
    if (!endDetectionArmed) {
        if (!PlayerNextEpisodeRules.isAwayFromEnd(
                positionMs = positionMs,
                durationMs = effectiveDurationEarly,
                skipIntervals = skipIntervals,
                thresholdMode = nextEpisodeThresholdModeSetting,
                thresholdPercent = nextEpisodeThresholdPercentSetting,
                thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
            )
        ) return
        endDetectionArmed = true
    }
    if (!_uiState.value.error.isNullOrBlank()) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return

    val effectiveDuration = effectiveDurationEarly

    // The next episode's source is found ahead of its card, so the card offers Play only once there's something to
    // play. The search can take as long as auto-play's timeout, so it starts that much earlier again (with
    // a 30 s head start the card came about 10 s late); "Preload next episode sources" starts it earlier still.
    if (preparedForNextEpisode() == null) {
        val timeoutMs = streamAutoPlayTimeoutSecondsSetting.toLong().coerceIn(0L, MAX_TIMEOUT_LEAD_S) * 1_000L
        val preloadLeadMs = if (preloadNextEpisodeSourcesSetting) timeoutMs else 0L
        val shouldPrepare = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs + NEXT_EPISODE_PREPARE_LEAD_MS + timeoutMs + preloadLeadMs,
            durationMs = effectiveDuration,
            skipIntervals = skipIntervals,
            thresholdMode = nextEpisodeThresholdModeSetting,
            thresholdPercent = nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
        )
        if (shouldPrepare) {
            prepareNextEpisode()
        }
    }

    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (!shouldShow) return

    if (_uiState.value.postPlayDismissedForCurrentEpisode) return

    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else {
        val ne = state.nextEpisode
        val isUnplayable = !ne.hasAired ||
            (ne.released.isNullOrBlank() && ne.available == false)
        if (isUnplayable) return

        if (preparedForNextEpisode() == null && !contentType.equals("cloud", ignoreCase = true)) {
            // Its source is still being found: the card shows once it is.
            nextEpisodeCardWaiting = true
            prepareNextEpisode()
            return
        }
        showNextEpisodeCard()
    }
}

/** Shows the next episode's card, with the source found for it, and plays it at once when auto-play is on. */
internal fun PlayerRuntimeController.showNextEpisodeCard() {
    val state = _uiState.value
    val nextEpisode = state.nextEpisode ?: return
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return
    _uiState.update {
        it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextEpisode, sourceName = preparedForNextEpisode()?.sourceName))
    }
    if (streamAutoPlayNextEpisodeEnabledSetting) {
        playNextEpisode()
    }
}

/** How long before the next episode's card is due its source search starts, on top of auto-play's timeout. */
private const val NEXT_EPISODE_PREPARE_LEAD_MS = 30_000L
/** Most of auto-play's timeout (seconds) added to that head start; longer ones wait for every addon anyway. */
private const val MAX_TIMEOUT_LEAD_S = 90L

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
        }
        return
    }

    // Don't evaluate skip intervals until player settings are loaded from DataStore.
    // Without this, autoSkipSegmentTypes is empty on first iterations, causing the
    // skip button to appear instead of auto-skipping.
    if (!playerSettingsInitialized) return

    val active = nextActiveSkipInterval(skipIntervals, positionMs)
    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        val targetsPostCredits = active.followingPostCreditsScene(skipIntervals, currentPlaybackDurationMs()) != null
        if (currentActive != active || targetsPostCredits != _uiState.value.activeSkipTargetsPostCredits) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(
                activeSkipInterval = active,
                activeSkipTargetsPostCredits = targetsPostCredits,
                skipIntervalDismissed = false
            ) }
        }
        val segmentType = AutoSkipSegmentType.fromSkipIntervalType(active.type)
        val activeKey = active.autoSkipKey()
        if (
            segmentType != null &&
            segmentType in autoSkipSegmentTypes &&
            activeKey !in autoSkippedIntervalKeys
        ) {
            autoSkippedIntervalKeys.add(activeKey)
            skipInterval(active)
        }
    } else if (currentActive != null) {
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

private fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    // Shown once the intro is over and playback really begins.
    if (introHold) return
    val state = _uiState.value
    if (parentalGuideFirstPlayAtMs == 0L) parentalGuideFirstPlayAtMs = SystemClock.elapsedRealtime()
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?, type: String?, season: Int?, episode: Int?) {
    if (!parentalGuideEnabled) return
    if (id.isNullOrBlank()) return
    parentalGuideFirstPlayAtMs = 0L

    val cachedMeta = type?.let { metaRepository.getCachedMeta(it, id) }
    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: cachedMeta?.imdbId?.takeIf { it.startsWith("tt") }

    val prefix = id.substringBefore(':', "").lowercase()
    val state = _uiState.value
    val request = ParentalGuideRequest(
        imdbId = imdbId,
        tmdbId = if (prefix == "tmdb") id.split(":").getOrNull(1)?.toIntOrNull() else null,
        isSeries = season != null || type.equals("series", true) || type.equals("tv", true),
        title = (state.contentName ?: state.title).takeIf { it.isNotBlank() } ?: cachedMeta?.name,
        year = (state.releaseYear ?: cachedMeta?.releaseInfo)?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() },
        badgeRating = cachedMeta?.ageRating,
        isAnime = when {
            prefix in ANIME_ID_PREFIXES -> true
            cachedMeta?.genres?.any { it.equals("anime", true) } == true -> true
            else -> null
        },
        malId = if (prefix == "mal") id.split(":").getOrNull(1)?.toIntOrNull() else null
    )
    if (request.imdbId == null && request.tmdbId == null && request.isAnime != true) return

    scope.launch {
        val summary = parentalGuideRepository.getDetailedGuide(request) ?: return@launch
        val warnings = buildList {
            summary.rating?.let { rating ->
                // "PG-13" + "13+"; a bare number ("16") is already the age, so it becomes "16+".
                val age = AgeRatings.label(rating)
                val numeric = AgeRatings.isNumericOnly(rating)
                add(
                    ParentalWarning(
                        label = if (numeric && age != null) age else rating,
                        severity = age.takeUnless { numeric },
                        kind = ParentalWarningKind.RATING
                    )
                )
            }
            summary.categories.take(5).forEach { (category, level) ->
                add(ParentalWarning(label = context.getString(category.labelRes()), severity = level.labelRes()?.let(context::getString)))
            }
            if (summary.notes.isNotEmpty()) {
                add(
                    ParentalWarning(
                        label = context.getString(R.string.parental_also),
                        severity = summary.notes.joinToString(", ") { context.getString(it.labelRes()) },
                        kind = ParentalWarningKind.NOTES
                    )
                )
            }
        }
        publishParentalWarnings(warnings)
    }
}

private fun PlayerRuntimeController.publishParentalWarnings(warnings: List<ParentalWarning>) {
    if (warnings.isEmpty()) return
    _uiState.update {
        it.copy(
            parentalWarnings = warnings,
            showParentalGuide = false,
            parentalGuideHasShown = false
        )
    }

    // A guide that arrives well after playback began would pop up mid-scene; skip it then.
    val firstPlayAt = parentalGuideFirstPlayAtMs
    val late = firstPlayAt != 0L && SystemClock.elapsedRealtime() - firstPlayAt > PARENTAL_GUIDE_LATE_MS
    if (_uiState.value.isPlaying && !late) {
        tryShowParentalGuide()
    }
}

private const val PARENTAL_GUIDE_LATE_MS = 12_000L
private val YEAR_REGEX = Regex("\\d{4}")
private val ANIME_ID_PREFIXES = setOf("kitsu", "mal", "anilist", "anidb")

private fun ParentalCategory.labelRes(): Int = when (this) {
    ParentalCategory.NUDITY -> R.string.parental_nudity
    ParentalCategory.VIOLENCE -> R.string.parental_violence
    ParentalCategory.PROFANITY -> R.string.parental_profanity
    ParentalCategory.ALCOHOL -> R.string.parental_alcohol
    ParentalCategory.FRIGHTENING -> R.string.parental_frightening
}

private fun ParentalLevel.labelRes(): Int? = when (this) {
    ParentalLevel.SEVERE -> R.string.parental_severity_severe
    ParentalLevel.MODERATE -> R.string.parental_severity_moderate
    ParentalLevel.MILD -> R.string.parental_severity_mild
    ParentalLevel.PRESENT, ParentalLevel.NONE -> null
}

private fun ContentNote.labelRes(): Int = when (this) {
    ContentNote.GORE -> R.string.parental_note_gore
    ContentNote.SEXUAL_VIOLENCE -> R.string.parental_note_sexual_violence
    ContentNote.SUICIDE -> R.string.parental_note_suicide
    ContentNote.SELF_HARM -> R.string.parental_note_self_harm
    ContentNote.TORTURE -> R.string.parental_note_torture
    ContentNote.ABUSE -> R.string.parental_note_abuse
    ContentNote.CANNIBALISM -> R.string.parental_note_cannibalism
    ContentNote.ANIMAL_HARM -> R.string.parental_note_animal_harm
    ContentNote.BODY_HORROR -> R.string.parental_note_body_horror
}

// ── Playback intro ────────────────────────────────────────────────────────────────────────

/**
 * Holds playback while the intro plays: if the stream is ready first, it's paused the moment it
 * starts and resumed by [releaseIntroHold]. Only watches the player while an intro is running.
 */
internal fun PlayerRuntimeController.startIntroHold() {
    if (introHold) return
    introHold = true
    introHeldPlayback = false
    introHoldJob?.cancel()
    introHoldJob = scope.launch {
        _uiState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
            if (playing && introHold) {
                introHeldPlayback = true
                setPlaybackPaused(true)
            }
        }
    }
}

/** Anything that starts a stream in the player plays the intro too, while it's switched on. */
internal fun PlayerRuntimeController.startIntroForNewStream() {
    if (playbackIntroEnabled) PlaybackIntroState.start()
}

/** Ends the hold; playback held back during the intro starts now. */
internal fun PlayerRuntimeController.releaseIntroHold() {
    if (!introHold) return
    introHold = false
    introHoldJob?.cancel()
    introHoldJob = null
    if (introHeldPlayback) {
        introHeldPlayback = false
        setPlaybackPaused(false)
    }
}
