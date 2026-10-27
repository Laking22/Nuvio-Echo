package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.components.LocalPlaybackAvailability
import com.nuvio.tv.ui.navigation.LocalDetailChildOverlayVisible
import android.widget.Toast

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.NuvioMotion

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.util.localizedGenreLabel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.MetaDetailsSkeleton
import com.nuvio.tv.ui.components.TrailerPlayer
import kotlinx.coroutines.delay

internal fun resolveVisibleEpisodeRestoreId(
    requestedId: String?,
    episodesForSeason: List<Video>,
    nextVideoId: String?
): String? {
    requestedId?.let { id ->
        if (episodesForSeason.any { it.id == id }) return id
    }
    nextVideoId?.let { id ->
        if (episodesForSeason.any { it.id == id }) return id
    }
    return episodesForSeason.firstOrNull()?.id
}

internal fun resolveReturnFocusSeason(
    playedSeason: Int?,
    selectedSeason: Int,
    nextSeason: Int?,
    availableSeasons: Collection<Int>,
    hasWaitedForSeasonAdvance: Boolean = false
): Int? {
    val next = nextSeason?.takeIf { it in availableSeasons }
    val played = playedSeason?.takeIf { it in availableSeasons }
    val selected = selectedSeason.takeIf { it in availableSeasons }
    return when {
        next != null && played != null && next > played -> next
        hasWaitedForSeasonAdvance &&
            selected != null &&
            played != null &&
            selected > played -> selected
        played != null -> played
        next != null -> next
        else -> selected
    }
}

internal fun isLastEpisodeOfSeason(
    allVideos: List<Video>,
    season: Int?,
    episode: Int?
): Boolean {
    if (season == null || episode == null) return false
    val lastEpisode = allVideos
        .filter { it.season == season }
        .mapNotNull { it.episode }
        .maxOrNull() ?: return false
    return episode >= lastEpisode
}

internal fun hasLaterAvailableSeason(
    playedSeason: Int?,
    availableSeasons: Collection<Int>
): Boolean = playedSeason != null && availableSeasons.any { it > playedSeason }

internal fun shouldWaitForReturnFocusSeasonAdvance(
    playedSeason: Int?,
    playedEpisode: Int?,
    nextSeason: Int?,
    allVideos: List<Video>,
    availableSeasons: Collection<Int>
): Boolean {
    if (!isLastEpisodeOfSeason(allVideos, playedSeason, playedEpisode)) return false
    if (!hasLaterAvailableSeason(playedSeason, availableSeasons)) return false
    return nextSeason == null || (playedSeason != null && nextSeason <= playedSeason)
}

internal sealed class ReturnFocusStep {
    data object WaitForSeasonAdvance : ReturnFocusStep()
    data class SelectSeason(val season: Int) : ReturnFocusStep()
    data class RestoreEpisode(val episodeId: String, val consumeRequest: Boolean) : ReturnFocusStep()
    data object Idle : ReturnFocusStep()
}

internal fun resolveImmediateNextEpisodeId(
    allVideos: List<Video>,
    fromEpisodeId: String?,
    nextVideoId: String?
): String? {
    if (fromEpisodeId == null || nextVideoId == null || fromEpisodeId == nextVideoId) return null
    val ordered = allVideos
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    val index = ordered.indexOfFirst { it.id == fromEpisodeId }
    if (index < 0 || index + 1 >= ordered.size) return null
    return ordered[index + 1].id.takeIf { it == nextVideoId }
}

internal fun resolveReturnFocusStep(
    playedSeason: Int?,
    playedEpisode: Int?,
    selectedSeason: Int,
    nextSeason: Int?,
    availableSeasons: Collection<Int>,
    allVideos: List<Video>,
    requestedEpisodeId: String?,
    episodesForSeason: List<Video>,
    nextVideoId: String?,
    alreadyRestoredId: String?,
    hasWaitedForSeasonAdvance: Boolean
): ReturnFocusStep {
    val advancedNextId = resolveImmediateNextEpisodeId(
        allVideos = allVideos,
        fromEpisodeId = requestedEpisodeId,
        nextVideoId = nextVideoId
    )
    val focusEpisodeId = advancedNextId ?: requestedEpisodeId
    val focusVideo = focusEpisodeId?.let { id -> allVideos.firstOrNull { it.id == id } }
    val focusSeason = focusVideo?.season ?: playedSeason
    val focusEpisode = focusVideo?.episode ?: playedEpisode

    val waitingForAdvance = advancedNextId == null &&
        shouldWaitForReturnFocusSeasonAdvance(
            playedSeason = playedSeason,
            playedEpisode = playedEpisode,
            nextSeason = nextSeason,
            allVideos = allVideos,
            availableSeasons = availableSeasons
        )
    val bingeAheadOfProgress = nextSeason != null &&
        playedSeason != null &&
        nextSeason < playedSeason
    if (waitingForAdvance && !hasWaitedForSeasonAdvance && !bingeAheadOfProgress) {
        return ReturnFocusStep.WaitForSeasonAdvance
    }

    val exitIsFinale = isLastEpisodeOfSeason(allVideos, focusSeason, focusEpisode)
    val seasonToShow = when {
        advancedNextId != null ->
            focusSeason?.takeIf { it in availableSeasons }
        requestedEpisodeId != null && !exitIsFinale ->
            playedSeason?.takeIf { it in availableSeasons }
                ?: resolveReturnFocusSeason(
                    playedSeason = playedSeason,
                    selectedSeason = selectedSeason,
                    nextSeason = nextSeason,
                    availableSeasons = availableSeasons,
                    hasWaitedForSeasonAdvance = hasWaitedForSeasonAdvance
                )
        else -> resolveReturnFocusSeason(
            playedSeason = playedSeason,
            selectedSeason = selectedSeason,
            nextSeason = nextSeason,
            availableSeasons = availableSeasons,
            hasWaitedForSeasonAdvance = hasWaitedForSeasonAdvance
        )
    }
    if (seasonToShow != null && seasonToShow != selectedSeason) {
        return ReturnFocusStep.SelectSeason(seasonToShow)
    }

    val restoreEpisodeId = resolveVisibleEpisodeRestoreId(
        requestedId = focusEpisodeId,
        episodesForSeason = episodesForSeason,
        nextVideoId = nextVideoId
    ) ?: return ReturnFocusStep.Idle

    if (restoreEpisodeId == alreadyRestoredId) {
        return ReturnFocusStep.Idle
    }

    return ReturnFocusStep.RestoreEpisode(
        episodeId = restoreEpisodeId,
        consumeRequest = !waitingForAdvance || bingeAheadOfProgress
    )
}

private const val USER_INTERACTION_DISPATCH_DEBOUNCE_MS = 120L

private fun formatDetailYearRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

private fun applyDither(bmp: android.graphics.Bitmap) {
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val rng = java.util.Random(0)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val noise = rng.nextInt(3) - 1
        pixels[i] = ((a shl 24) or
            ((r + noise).coerceIn(0, 255) shl 16) or
            ((g + noise).coerceIn(0, 255) shl 8) or
            (b + noise).coerceIn(0, 255))
    }
    bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    returnFocusSeason: Int? = null,
    returnFocusEpisode: Int? = null,
    heroRestoreToken: Int = 0,
    heroBackdropUrl: String? = null,
    playOnLoad: Boolean = false,
    playOnLoadManually: Boolean = false,
    onBackPress: () -> Unit,
    onReturnFocusConsumed: () -> Unit = {},
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String) -> Unit = { _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onPlayClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?,
        contentLanguage: String?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayManuallyClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?,
        contentLanguage: String?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayStartFromBeginningClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?,
        contentLanguage: String?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val playbackAvailability = LocalPlaybackAvailability.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val newDetails: NewDetailsViewModel = hiltViewModel()
    val posterCardCornerRadiusDp by viewModel.posterCardCornerRadiusDp.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val selectedComment = uiState.selectedComment
    var commentOverlayDirection by remember { mutableIntStateOf(0) }
    var restorePlayFocusAfterTrailerBackToken by rememberSaveable { mutableIntStateOf(0) }
    var restoreSharedTrailerFocusToken by rememberSaveable { mutableIntStateOf(0) }
    var isTrailerPaused by remember { mutableStateOf(false) }
    val playOnLoadConsumed = rememberSaveable { mutableStateOf(false) }
    val playOnLoadHandoffDispatched = rememberSaveable { mutableStateOf(false) }
    val playOnLoadReturnObserved = rememberSaveable { mutableStateOf(false) }
    val suppressInitialPlayOnLoadContent = playOnLoad && !playOnLoadReturnObserved.value
    val playOnLoadReturnContentReady = !uiState.isLoading && uiState.meta != null
    var playOnLoadReturnRevealRequested by remember(playOnLoad) { mutableStateOf(!playOnLoad) }
    var playOnLoadReturnContentRevealed by remember(playOnLoad) { mutableStateOf(!playOnLoad) }

    LaunchedEffect(playOnLoad, playOnLoadReturnObserved.value, playOnLoadReturnContentReady) {
        if (!playOnLoad || (playOnLoadReturnObserved.value && playOnLoadReturnContentReady)) {
            playOnLoadReturnRevealRequested = true
        }
    }
    val playOnLoadReturnContentAlpha by animateFloatAsState(
        targetValue = if (playOnLoadReturnRevealRequested) 1f else 0f,
        animationSpec = NuvioMotion.mediumTween(),
        label = "playOnLoadReturnContentAlpha",
        finishedListener = { playOnLoadReturnContentRevealed = it == 1f }
    )

    val childOverlayVisible = LocalDetailChildOverlayVisible.current
    BackHandler(enabled = !childOverlayVisible) {
        if (selectedComment != null) {
            commentOverlayDirection = 0
            viewModel.onEvent(MetaDetailsEvent.OnDismissCommentOverlay)
        } else if (uiState.isSharedTrailerOverlayVisible) {
            restoreSharedTrailerFocusToken += 1
            viewModel.onEvent(MetaDetailsEvent.OnDismissSharedTrailer)
        } else if (uiState.isTrailerPlaying) {
            restorePlayFocusAfterTrailerBackToken += 1
            isTrailerPaused = false
            viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded)
        } else {
            onBackPress()
        }
    }

    val currentIsTrailerPlaying by rememberUpdatedState(uiState.isTrailerPlaying)
    val currentShowTrailerControls by rememberUpdatedState(uiState.showTrailerControls)
    var trailerSeekOverlayVisible by remember { mutableStateOf(false) }
    val trailerSeekOverlayState = remember { TrailerSeekOverlayState() }
    var trailerSeekToken by remember { mutableIntStateOf(0) }
    var trailerSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var lastUserInteractionDispatchMs by remember { mutableLongStateOf(0L) }
    val onTrailerProgressChanged = remember(trailerSeekOverlayState) {
        { position: Long, duration: Long ->
            trailerSeekOverlayState.positionMs = position
            trailerSeekOverlayState.durationMs = duration
        }
    }

    LaunchedEffect(uiState.userMessage) {
        if (uiState.userMessage != null) {
            delay(2500)
            viewModel.onEvent(MetaDetailsEvent.OnClearMessage)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        if (
            playOnLoad &&
            playOnLoadHandoffDispatched.value &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            playOnLoadReturnObserved.value = true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onEvent(MetaDetailsEvent.OnLifecyclePause)
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (playOnLoad && playOnLoadHandoffDispatched.value) {
                        playOnLoadReturnObserved.value = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onPreviewKeyEvent { keyEvent ->
                if (currentIsTrailerPlaying) {
                    if (currentShowTrailerControls) {
                        if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                isTrailerPaused = !isTrailerPaused
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                isTrailerPaused = !isTrailerPaused
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                isTrailerPaused = true
                                true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                isTrailerPaused = false
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                trailerSeekOverlayVisible = false
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> -12_000L
                                    repeatCount >= 6 -> -8_000L
                                    repeatCount >= 2 -> -5_000L
                                    else -> -3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> 12_000L
                                    repeatCount >= 6 -> 8_000L
                                    repeatCount >= 2 -> 5_000L
                                    else -> 3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            else -> false
                        }
                    }
                    // During auto trailer preview, consume all keys except back/ESC so content doesn't scroll.
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val shouldDispatch =
                        nativeEvent.repeatCount == 0 &&
                            (nativeEvent.eventTime - lastUserInteractionDispatchMs) >=
                            USER_INTERACTION_DISPATCH_DEBOUNCE_MS
                    if (shouldDispatch) {
                        lastUserInteractionDispatchMs = nativeEvent.eventTime
                        viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
                    }
                }
                false
            }
    ) {
        when {
            uiState.isLoading -> {
                if (suppressInitialPlayOnLoadContent) {
                    // On the way to the intro: black, not the title's cover (see below).
                    Box(Modifier.fillMaxSize().background(Color.Black))
                } else if (playOnLoad && !playOnLoadReturnContentRevealed) {
                    PlaybackHandoffBackdrop(backdropUrl = heroBackdropUrl)
                } else {
                    if (!heroBackdropUrl.isNullOrBlank()) {
                        val localContext = LocalContext.current
                        val localDensity = LocalDensity.current
                        val configuration = LocalConfiguration.current
                        val loadingBackdropWidthPx = remember(configuration, localDensity) {
                            with(localDensity) { configuration.screenWidthDp.dp.roundToPx() }
                        }
                        val loadingBackdropHeightPx = remember(configuration, localDensity) {
                            with(localDensity) { configuration.screenHeightDp.dp.roundToPx() }
                        }
                        val loadingBackdropRequest = remember(localContext, heroBackdropUrl, loadingBackdropWidthPx, loadingBackdropHeightPx) {
                            ImageRequest.Builder(localContext)
                                .data(heroBackdropUrl)
                                .crossfade(false)
                                .size(width = loadingBackdropWidthPx, height = loadingBackdropHeightPx)
                                .build()
                        }
                        AsyncImage(
                            model = loadingBackdropRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopEnd
                        )
                    }
                    MetaDetailsSkeleton(backdropAware = !heroBackdropUrl.isNullOrBlank())
                }
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
                )
            }
            uiState.meta != null -> {
                val meta = uiState.meta!!
                val context = LocalContext.current
                val genresString = remember(meta.genres) {
                    meta.genres.takeIf { it.isNotEmpty() }
                        ?.joinToString(" • ") { localizedGenreLabel(context, it) }
                }
                val yearString = remember(meta.releaseInfo) {
                    formatDetailYearRange(meta.releaseInfo)
                }
                val playEpisode: (Video) -> Unit = playEpisode@{ video ->
                    if (!playbackAvailability.canStream(meta.apiType, video.id, meta.id, video)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playEpisode
                    }
                    onPlayClick(
                        video.id,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        video.thumbnail ?: meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        video.season,
                        video.episode,
                        video.title,
                        null,
                        null,
                        video.runtime,
                        meta.resolveContentLanguage()
                    )
                }
                val playEpisodeManually: (Video) -> Unit = playEpisodeManually@{ video ->
                    if (!playbackAvailability.canStream(meta.apiType, video.id, meta.id, video)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playEpisodeManually
                    }
                    onPlayManuallyClick(
                        video.id,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        video.thumbnail ?: meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        video.season,
                        video.episode,
                        video.title,
                        null,
                        null,
                        video.runtime,
                        meta.resolveContentLanguage()
                    )
                }
                val playTitle: (String) -> Unit = playTitle@{ videoId ->
                    if (!playbackAvailability.canStream(meta.apiType, videoId, meta.id)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playTitle
                    }
                    onPlayClick(
                        videoId,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        null,
                        null,
                        null,
                        genresString,
                        yearString,
                        null,
                        meta.resolveContentLanguage()
                    )
                }
                val playTitleManually: (String) -> Unit = playTitleManually@{ videoId ->
                    if (!playbackAvailability.canStream(meta.apiType, videoId, meta.id)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playTitleManually
                    }
                    onPlayManuallyClick(
                        videoId,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        null,
                        null,
                        null,
                        genresString,
                        yearString,
                        null,
                        meta.resolveContentLanguage()
                    )
                }
                val playEpisodeFromStart: (Video) -> Unit = playEpisodeFromStart@{ video ->
                    if (!playbackAvailability.canStream(meta.apiType, video.id, meta.id, video)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playEpisodeFromStart
                    }
                    onPlayStartFromBeginningClick(
                        video.id,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        video.thumbnail ?: meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        video.season,
                        video.episode,
                        video.title,
                        null,
                        null,
                        video.runtime,
                        meta.resolveContentLanguage()
                    )
                }
                val playTitleFromStart: (String) -> Unit = playTitleFromStart@{ videoId ->
                    if (!playbackAvailability.canStream(meta.apiType, videoId, meta.id)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@playTitleFromStart
                    }
                    onPlayStartFromBeginningClick(
                        videoId,
                        meta.apiType,
                        meta.id,
                        meta.name,
                        meta.poster,
                        meta.backdropUrl,
                        meta.logo,
                        null,
                        null,
                        null,
                        genresString,
                        yearString,
                        null,
                        meta.resolveContentLanguage()
                    )
                }
                val isSeries = remember(meta.type, meta.videos) {
                    meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
                }
                val playOnLoadVideo = remember(meta, uiState.nextToWatch, uiState.episodesForSeason) {
                    resolveHeroPlaybackVideo(
                        meta = meta,
                        nextToWatch = uiState.nextToWatch,
                        episodesForSeason = uiState.episodesForSeason
                    )
                }

                LaunchedEffect(
                    playOnLoad,
                    playOnLoadManually,
                    playOnLoadConsumed.value,
                    isSeries,
                    uiState.nextToWatch,
                    playOnLoadVideo?.id,
                    playbackAvailability
                ) {
                    if (!playOnLoad || playOnLoadConsumed.value || (isSeries && uiState.nextToWatch == null)) {
                        return@LaunchedEffect
                    }
                    if (!playbackAvailability.isLoaded) return@LaunchedEffect
                    playOnLoadConsumed.value = true
                    if (uiState.shufflePoolEmpty) {
                        playOnLoadReturnObserved.value = true
                        return@LaunchedEffect
                    }
                    if (!playbackAvailability.canStream(meta.apiType, playOnLoadVideo?.id ?: meta.id, meta.id, playOnLoadVideo)) {
                        playOnLoadReturnObserved.value = true
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@LaunchedEffect
                    }
                    playOnLoadHandoffDispatched.value = true
                    if (playOnLoadVideo != null) {
                        if (playOnLoadManually) {
                            playEpisodeManually(playOnLoadVideo)
                        } else {
                            playEpisode(playOnLoadVideo)
                        }
                    } else if (playOnLoadManually) {
                        playTitleManually(meta.id)
                    } else {
                        playTitle(meta.id)
                    }
                }

                if (suppressInitialPlayOnLoadContent) {
                    // Straight to the intro: black until it starts. The backdrop here flashed the title's cover for a
                    // moment between a grand card's Watch Now and the intro.
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    return@Box
                }
                if (playOnLoad && !playOnLoadReturnContentRevealed) {
                    PlaybackHandoffBackdrop(backdropUrl = heroBackdropUrl ?: meta.backdropUrl)
                }
                NewDetailsContent(
                    modifier = Modifier.graphicsLayer { alpha = playOnLoadReturnContentAlpha },
                    meta = meta,
                    heroBackdropUrl = heroBackdropUrl,
                    state = uiState,
                    extras = newDetails,
                    isTrailerPaused = isTrailerPaused,
                    trailerSeekToken = trailerSeekToken,
                    trailerSeekDeltaMs = trailerSeekDeltaMs,
                    onTrailerControlKey = { keyCode, action, repeatCount ->
                        if (!uiState.showTrailerControls || !uiState.isTrailerPlaying || action != KeyEvent.ACTION_DOWN) {
                            false
                        } else {
                            val seekStepMs = when {
                                repeatCount >= 12 -> 12_000L
                                repeatCount >= 6 -> 8_000L
                                repeatCount >= 2 -> 5_000L
                                else -> 3_000L
                            }
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    isTrailerPaused = !isTrailerPaused
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    trailerSeekDeltaMs = -seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    trailerSeekDeltaMs = seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                else -> false
                            }
                        }
                    },
                    onTrailerProgressChanged = onTrailerProgressChanged,
                    onTrailerEnded = { viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded) },
                    onPlayEpisode = playEpisode,
                    // The classic page's episode focus does the same: the ViewModel warms the episode's sources
                    // once focus has rested on it.
                    onEpisodeFocused = { video ->
                        video.season?.let { viewModel.lastFocusedEpisodeIdBySeason[it] = video.id }
                    },
                    onPlayTitle = playTitle,
                    onPlayEpisodeManually = playEpisodeManually,
                    onPlayTitleManually = playTitleManually,
                    onPlayEpisodeFromStart = playEpisodeFromStart,
                    onPlayTitleFromStart = playTitleFromStart,
                    onTrailerSelected = { viewModel.onEvent(MetaDetailsEvent.OnSharedTrailerSelected(it)) },
                    onDismissSharedTrailer = { viewModel.onEvent(MetaDetailsEvent.OnDismissSharedTrailer) },
                    onRetrySharedTrailer = { viewModel.onEvent(MetaDetailsEvent.OnRetrySharedTrailer) },
                    onPlayButtonFocused = { viewModel.onEvent(MetaDetailsEvent.OnPlayButtonFocused) },
                    onToggleLibrary = { viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary) },
                    onLibraryLongPress = { viewModel.onEvent(MetaDetailsEvent.OnLibraryLongPress) },
                    onBinPicked = { viewModel.onEvent(MetaDetailsEvent.OnBinPicked(it)) },
                    onBinPickerDismiss = { viewModel.onEvent(MetaDetailsEvent.OnBinPickerDismiss) },
                    onCreateBin = { name -> viewModel.createBinAndAdd(name) },
                    onToggleMovieWatched = { viewModel.onEvent(MetaDetailsEvent.OnToggleMovieWatched) },
                    onMarkSeasonWatched = { viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(it)) },
                    onMarkSeasonUnwatched = { viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonUnwatched(it)) },
                    isSeasonFullyWatched = { viewModel.isSeasonFullyWatched(it) },
                    onTrailerClick = { viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick) },
                    restorePlayFocusToken = restorePlayFocusAfterTrailerBackToken,
                    onNavigateToCastDetail = onNavigateToCastDetail,
                    onNavigateToTmdbEntityBrowse = onNavigateToTmdbEntityBrowse,
                    onNavigateToDetail = onNavigateToDetail,
                    onPosterLongPress = { item -> viewModel.posterOptions.show(item, null) }
                )
            }
        }

        val message = uiState.userMessage
        if (!message.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(
                        color = if (uiState.userMessageIsError) {
                            Color(0xFF5A1C1C)
                        } else {
                            NuvioTheme.colors.BackgroundElevated
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }

        TrailerSeekOverlayHost(
            visible = uiState.isTrailerPlaying && uiState.showTrailerControls && trailerSeekOverlayVisible,
            overlayState = trailerSeekOverlayState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    LaunchedEffect(trailerSeekOverlayVisible, uiState.isTrailerPlaying, uiState.showTrailerControls, trailerSeekToken) {
        if (trailerSeekOverlayVisible && uiState.isTrailerPlaying && uiState.showTrailerControls) {
            delay(3000)
            trailerSeekOverlayVisible = false
        }
    }

    LaunchedEffect(uiState.isTrailerPlaying, uiState.showTrailerControls) {
        if (!uiState.isTrailerPlaying || !uiState.showTrailerControls) {
            trailerSeekOverlayVisible = false
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        }
    )
}

@Composable
private fun PlaybackHandoffBackdrop(backdropUrl: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            val context = LocalContext.current
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            val widthPx = remember(configuration, density) {
                with(density) { configuration.screenWidthDp.dp.roundToPx() }
            }
            val heightPx = remember(configuration, density) {
                with(density) { configuration.screenHeightDp.dp.roundToPx() }
            }
            val request = remember(context, backdropUrl, widthPx, heightPx) {
                ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .crossfade(false)
                    .size(width = widthPx, height = heightPx)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
    }
}

@Composable
internal fun BackdropLayer(
    backdropRequest: ImageRequest,
    heroBackdropRequest: ImageRequest? = null,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isTrailerPlaying: Boolean,
    isBackgroundTrailerPlaying: Boolean,
    pauseBackgroundTrailerOnScroll: Boolean,
    isTrailerPaused: Boolean = false,
    showTrailerControls: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    isScrolledPastHero: Boolean,
    leftGradient: ImageBitmap,
    bottomGradient: ImageBitmap,
    /** Pauses a background trailer and shows the backdrop again (the new page: while Play isn't focused). */
    pauseBackgroundTrailer: Boolean = false,
    /** True while a background trailer's picture is on screen (its first frame is in and it isn't paused). */
    onBackgroundTrailerShowing: (Boolean) -> Unit = {},
) {
    var showHeroBackdropUnderlay by remember(heroBackdropRequest, backdropRequest) {
        mutableStateOf(heroBackdropRequest != null)
    }
    val isBackgroundTrailerPaused = isBackgroundTrailerPlaying &&
        (pauseBackgroundTrailer || pauseBackgroundTrailerOnScroll && isScrolledPastHero)
    val isBackgroundTrailerVisible = isBackgroundTrailerPlaying && !isBackgroundTrailerPaused
    var isBackgroundTrailerRendered by remember(isBackgroundTrailerPlaying) { mutableStateOf(false) }
    val backgroundTrailerShowing = isBackgroundTrailerVisible && isBackgroundTrailerRendered
    LaunchedEffect(backgroundTrailerShowing) { onBackgroundTrailerShowing(backgroundTrailerShowing) }
    val backdropAlphaState = animateFloatAsState(
        targetValue = when {
            isTrailerPlaying -> 0f
            isBackgroundTrailerVisible && isBackgroundTrailerRendered -> 0f
            isScrolledPastHero -> 0.15f
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
        label = "backdropFade"
    )
    val backgroundTrailerAlphaState = animateFloatAsState(
        targetValue = when {
            !isBackgroundTrailerVisible -> 0f
            isScrolledPastHero -> 0.15f
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
        label = "backgroundTrailerFade"
    )
    val gradientAlphaState = animateFloatAsState(
        targetValue = if (isTrailerPlaying || isScrolledPastHero) 0f else 1f,
        animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
        label = "gradientFade"
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // Show hero backdrop from previous screen as persistent underlay
        // to prevent flash/re-render during navigation transition
        if (showHeroBackdropUnderlay && heroBackdropRequest != null) {
            AsyncImage(
                model = heroBackdropRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alpha = backdropAlphaState.value,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        AsyncImage(
            model = backdropRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alpha = backdropAlphaState.value,
            onSuccess = { showHeroBackdropUnderlay = false },
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd
        )
        TrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = isTrailerPlaying || isBackgroundTrailerPlaying,
            isPaused = isTrailerPaused || isBackgroundTrailerPaused,
            seekRequestToken = if (showTrailerControls) trailerSeekToken else 0,
            seekDeltaMs = if (showTrailerControls) trailerSeekDeltaMs else 0L,
            onRemoteKey = onTrailerControlKey,
            onProgressChanged = onTrailerProgressChanged,
            onEnded = onTrailerEnded,
            onFirstFrameRendered = { isBackgroundTrailerRendered = true },
            cropToFill = isBackgroundTrailerPlaying,
            autoCropLetterbox = isBackgroundTrailerPlaying,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (isBackgroundTrailerPlaying) backgroundTrailerAlphaState.value else 1f
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {
                        if (gradientAlphaState.value > 0f) {
                            drawImage(
                                leftGradient,
                                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                                alpha = gradientAlphaState.value,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                            )
                        }
                    }
                }
        )
    }
}

