@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val NdText2 = Color(0xFFB3B3B3)
internal val NdText3 = Color(0xFF808080)
internal val NdPill = RoundedCornerShape(999.dp)

internal fun ndIsSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

/** Focusable and clickable with OK, plus an optional hold-OK action, without the default indication. */
@Composable
internal fun Modifier.ndClickable(
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val tracker = rememberLongPressKeyTracker()
    val interaction = remember { MutableInteractionSource() }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .then(
            if (onLongClick == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                tracker.handle(event.nativeKeyEvent, ::ndIsSelectKey) { onLongClick() }
            }
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * For a menu opened by holding OK: the remote keeps sending OK repeats while it's held, then the release, and once
 * the menu has focus those would select its first item. They're ignored until the hold ends (the release, or a
 * fresh press if the release went elsewhere).
 */
@Composable
internal fun Modifier.ignoreHeldSelect(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (armed || !ndIsSelectKey(native.keyCode)) return@onPreviewKeyEvent false
        when {
            native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                armed = true
                false
            }
            native.action == AndroidKeyEvent.ACTION_UP -> {
                armed = true
                true
            }
            else -> true
        }
    }
}

internal fun Meta.isSeriesLike(): Boolean =
    type == ContentType.SERIES || type == ContentType.TV || apiType.equals("series", true) || apiType.equals("tv", true)

/**
 * The title's YouTube trailers, teasers and clips (TMDB's when enrichment is on, else the addon's), one per video:
 * trailers first, then teasers, then the rest, each in the order given.
 */
internal fun Meta.playableTrailers(): List<com.nuvio.tv.domain.model.MetaTrailer> {
    val seen = HashSet<String>()
    val listed = trailers.filter { t ->
        val id = t.ytId?.trim().orEmpty()
        id.isNotEmpty() && seen.add(id)
    }
    val bare = trailerYtIds.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it) }
        .map { com.nuvio.tv.domain.model.MetaTrailer(ytId = it, type = "Trailer") }
    fun rank(type: String?) = when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
    return (listed + bare).sortedBy { rank(it.type) }
}

/** The logo left over the trailer once the page fades: bottom-left, a little larger than in the page. */
@Composable
private fun NdCornerLogo(meta: Meta) {
    val context = LocalContext.current
    var failed by remember(meta.logo) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // A soft shade from the bottom-left corner so a bright frame never swallows the logo.
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.5f to Color.Black.copy(alpha = 0.25f),
                        1f to Color.Transparent,
                        center = androidx.compose.ui.geometry.Offset(0f, size.height),
                        radius = size.width * 0.55f
                    )
                )
            }
            .padding(start = 48.dp, bottom = 44.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        if (!meta.logo.isNullOrBlank() && !failed) {
            AsyncImage(
                model = remember(meta.logo) { ImageRequest.Builder(context).data(meta.logo).crossfade(true).build() },
                contentDescription = meta.name,
                onError = { failed = true },
                modifier = Modifier.height(80.dp).widthIn(max = 340.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart
            )
        } else {
            Text(meta.name, color = Color.White, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                modifier = Modifier.widthIn(max = 420.dp))
        }
    }
}

/** Cast without the lead credits, and the lead credits (creators, else directors, else writers). */
internal fun Meta.castAndCrew(): Pair<List<MetaCastMember>, List<MetaCastMember>> {
    val all = if (castMembers.isNotEmpty()) castMembers else cast.map { MetaCastMember(name = it) }
    fun role(m: MetaCastMember, r: String) = m.character.equals(r, ignoreCase = true)
    val creators = all.filter { role(it, "Creator") }
    val directors = all.filter { role(it, "Director") }
    val writers = all.filter { role(it, "Writer") }
    val crew = when {
        creators.isNotEmpty() -> creators
        directors.isNotEmpty() -> directors
        writers.isNotEmpty() -> writers
        else -> director.map { MetaCastMember(name = it, character = "Director") }
    }
    val crewNames = (creators + directors + writers).map { it.name.lowercase() }.toSet()
    val castOnly = all.filterNot { m ->
        (role(m, "Creator") || role(m, "Director") || role(m, "Writer")) && m.name.lowercase() in crewNames
    }
    return castOnly to crew
}

private enum class NdPanel { EPISODES, CAST }

/**
 * The new details page (Beta speed-ups → New details page). On the left, top to bottom: logo, details, synopsis
 * (focus it to open the full details sideways), credits, the rating row, then Play and a menu marked by a sliding
 * glass lens. Below: Similar titles, the collection, and Reviews. Episodes and Cast & Crew open full-screen panels.
 */
/** The details page takes its title's colours as its background (Appearance → Look & feel → Ambient colour). */
val LocalDetailsAmbient = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun NewDetailsContent(
    modifier: Modifier = Modifier,
    meta: Meta,
    heroBackdropUrl: String?,
    state: MetaDetailsUiState,
    extras: NewDetailsViewModel,
    isTrailerPaused: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (Int, Int, Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    onPlayEpisode: (Video) -> Unit,
    /** An episode in the Episodes panel has focus. */
    onEpisodeFocused: (Video) -> Unit,
    onPlayTitle: (String) -> Unit,
    onPlayEpisodeManually: (Video) -> Unit,
    onPlayTitleManually: (String) -> Unit,
    onPlayEpisodeFromStart: (Video) -> Unit,
    onPlayTitleFromStart: (String) -> Unit,
    onTrailerSelected: (com.nuvio.tv.domain.model.MetaTrailer) -> Unit,
    onDismissSharedTrailer: () -> Unit,
    onRetrySharedTrailer: () -> Unit,
    onPlayButtonFocused: () -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onBinPicked: (String) -> Unit,
    onBinPickerDismiss: () -> Unit,
    onCreateBin: suspend (String) -> String?,
    onToggleMovieWatched: () -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onMarkSeasonUnwatched: (Int) -> Unit,
    isSeasonFullyWatched: (Int) -> Boolean,
    onTrailerClick: () -> Unit,
    restorePlayFocusToken: Int,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit,
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String) -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onPosterLongPress: (MetaPreview) -> Unit
) {
    LaunchedEffect(meta.id) { extras.bind(meta) }
    val ratings by extras.ratings.collectAsStateWithLifecycle()
    val titleReviews by extras.titleReviews.collectAsStateWithLifecycle()
    val episodeReviews by extras.episodeReviews.collectAsStateWithLifecycle()
    val titleRating = ratings.title(meta.id)
    val episodeRatings = remember(ratings, meta.id) { ratings.episodesOf(meta.id) }
    val reminders by extras.reminders.collectAsStateWithLifecycle()
    val credits by extras.credits.collectAsStateWithLifecycle()

    val isSeries = meta.isSeriesLike()
    val heroVideo = remember(meta, state.nextToWatch, state.episodesForSeason) {
        resolveHeroPlaybackVideo(meta, state.nextToWatch, state.episodesForSeason)
    }
    // Not out yet (a movie, or a show whose next episode hasn't aired): Play becomes "Remind me".
    val upcoming: com.nuvio.tv.data.local.ReleaseReminder? = remember(meta, heroVideo, isSeries) {
        if (isSeries) heroVideo?.let { upcomingReminder(meta, it) } else upcomingReminder(meta)
    }
    val reminderOn = upcoming != null && upcoming.key in reminders
    val play: () -> Unit = {
        when {
            upcoming != null -> extras.toggleReminder(upcoming)
            heroVideo != null -> onPlayEpisode(heroVideo)
            else -> onPlayTitle(meta.id)
        }
    }
    // Holding OK on Play (not on "Remind me"): pick the source by hand, or start a Resume over.
    var playOptionsOpen by remember { mutableStateOf(false) }
    var playBounds by remember { mutableStateOf(Rect.Zero) }
    val binFocus = remember { FocusRequester() }
    var binBounds by remember { mutableStateOf(Rect.Zero) }
    var pageOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // However the list closes (a pick, Back, a new bin), focus goes back to the Bin button.
    var binPickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.showBinPicker) {
        if (state.showBinPicker) {
            binPickerWasOpen = true
        } else if (binPickerWasOpen) {
            binPickerWasOpen = false
            delay(40)
            runCatching { binFocus.requestFocus() }
        }
    }
    val playManually: () -> Unit = {
        if (heroVideo != null) onPlayEpisodeManually(heroVideo) else onPlayTitleManually(meta.id)
    }
    val playFromStart: () -> Unit = {
        if (heroVideo != null) onPlayEpisodeFromStart(heroVideo) else onPlayTitleFromStart(meta.id)
    }
    // Series list their trailers in the Episodes panel's own Trailers tab, under the seasons.
    val trailers = remember(meta.trailers, meta.trailerYtIds) { meta.playableTrailers() }
    val seasonsWithEpisodes = remember(meta.videos) {
        meta.videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
    }
    val allWatched = isSeries && seasonsWithEpisodes.filter { it > 0 }.let { s ->
        s.isNotEmpty() && s.all { isSeasonFullyWatched(it) }
    }
    val watched = if (isSeries) allWatched else state.isMovieWatched
    val toggleWatched: () -> Unit = {
        if (isSeries) {
            val seasons = seasonsWithEpisodes.filter { it > 0 }
            if (allWatched) seasons.forEach(onMarkSeasonUnwatched) else seasons.forEach(onMarkSeasonWatched)
        } else {
            onToggleMovieWatched()
        }
    }

    var panel by rememberSaveable { mutableStateOf<NdPanel?>(null) }
    var openReview by remember { mutableStateOf<Pair<List<TraktCommentReview>, Int>?>(null) }
    var reviewDirection by remember { mutableIntStateOf(0) }

    val playFocus = remember { FocusRequester() }
    val episodesItemFocus = remember { FocusRequester() }
    val castItemFocus = remember { FocusRequester() }
    var returnFocusTo by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(meta.id) {
        repeat(4) {
            if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(80)
        }
    }
    LaunchedEffect(restorePlayFocusToken) {
        if (restorePlayFocusToken > 0) {
            delay(60)
            runCatching { playFocus.requestFocus() }
        }
    }
    LaunchedEffect(panel) {
        if (panel == null) {
            returnFocusTo?.let { requester ->
                delay(40)
                runCatching { requester.requestFocus() }
            }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The trailer plays behind the page only while Play (or Resume) is focused; elsewhere it pauses on the backdrop.
    var playFocused by remember { mutableStateOf(false) }
    // Once the trailer has played a few seconds with no key pressed, the whole page fades away and leaves the logo
    // bottom-left over the video. Any key brings the page back (that key does nothing else) and restarts the wait.
    var trailerShowing by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }
    var swallowKeyUp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(trailerShowing, keyTick, panel, playOptionsOpen, state.showBinPicker) {
        immersive = false
        if (trailerShowing && panel == null && !playOptionsOpen && !state.showBinPicker) {
            delay(com.nuvio.tv.ui.newlook.home.TRAILER_FADE_DELAY_MS)
            immersive = true
        }
    }
    val rowSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val pageSpec = rememberMinimalPageScroll()
    val scrolledPast by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    // Backdrop, the same layer (and trailer player) as the original page.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val widthPx = remember(configuration, density) { with(density) { configuration.screenWidthDp.dp.roundToPx() } }
    val heightPx = remember(configuration, density) { with(density) { configuration.screenHeightDp.dp.roundToPx() } }
    val backdropUrl = meta.backdropUrl ?: meta.poster
    val seedUrl = heroBackdropUrl?.takeIf { it.isNotBlank() && it != backdropUrl }
    val backdropRequest = remember(context, backdropUrl, widthPx, heightPx) {
        ImageRequest.Builder(context).data(backdropUrl).crossfade(400).size(width = widthPx, height = heightPx).build()
    }
    val seedRequest = remember(context, seedUrl, widthPx, heightPx) {
        seedUrl?.let { ImageRequest.Builder(context).data(it).crossfade(false).size(width = widthPx, height = heightPx).build() }
    }
    val background = NuvioTheme.colors.Background
    val leftGradient = remember(background, widthPx) { detailLeftGradient(background, widthPx) }
    val emptyGradient = remember { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        keyTick++
                        if (immersive) {
                            immersive = false
                            swallowKeyUp = native.keyCode
                            true
                        } else {
                            swallowKeyUp == native.keyCode
                        }
                    }
                    android.view.KeyEvent.ACTION_UP -> if (swallowKeyUp == native.keyCode) {
                        swallowKeyUp = null
                        true
                    } else false
                    else -> false
                }
            }
    ) {
        // Ambient colour (Appearance → Look & feel): the title's colours, blurred, under the backdrop. They show where
        // the backdrop fades: past the hero, and through the left gradient's thinner part.
        if (LocalDetailsAmbient.current) {
            com.nuvio.tv.ui.newlook.home.AmbientPageWash(url = backdropUrl, modifier = Modifier.fillMaxSize())
        }
        BackdropLayer(
            backdropRequest = backdropRequest,
            heroBackdropRequest = seedRequest,
            trailerUrl = state.trailerUrl,
            trailerAudioUrl = state.trailerAudioUrl,
            isTrailerPlaying = state.isTrailerPlaying,
            isBackgroundTrailerPlaying = state.isBackgroundTrailerPlaying,
            pauseBackgroundTrailerOnScroll = state.pauseBackgroundTrailerOnScroll,
            isTrailerPaused = isTrailerPaused,
            showTrailerControls = state.showTrailerControls,
            trailerSeekToken = trailerSeekToken,
            trailerSeekDeltaMs = trailerSeekDeltaMs,
            onTrailerControlKey = onTrailerControlKey,
            onTrailerProgressChanged = onTrailerProgressChanged,
            onTrailerEnded = onTrailerEnded,
            isScrolledPastHero = scrolledPast,
            leftGradient = leftGradient,
            bottomGradient = emptyGradient,
            pauseBackgroundTrailer = !playFocused,
            onBackgroundTrailerShowing = { trailerShowing = it }
        )
        // A soft dark band behind the text column, so it reads over bright backdrops.
        val scrimAlpha by animateFloatAsState(
            if (state.isTrailerPlaying || scrolledPast || immersive) 0f else 0.7f,
            tween(if (scrolledPast) 300 else 800), label = "ndScrim"
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.9f),
                            0.30f to Color.Black.copy(alpha = 0.72f),
                            0.50f to Color.Black.copy(alpha = 0.35f),
                            0.66f to Color.Transparent
                        ),
                        alpha = scrimAlpha
                    )
                }
        )

        val contentAlpha by animateFloatAsState(
            if (state.isTrailerPlaying || immersive) 0f else 1f,
            tween(if (immersive) 900 else 400), label = "ndContent"
        )
        // What stays over the trailer once the page has faded: the logo, bottom-left.
        androidx.compose.animation.AnimatedVisibility(
            visible = immersive && !state.isTrailerPlaying,
            enter = androidx.compose.animation.fadeIn(tween(900, delayMillis = 300)),
            exit = androidx.compose.animation.fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            NdCornerLogo(meta)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pageSpec
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                // While a panel is open, focus can't move into the page behind it.
                .focusProperties { onEnter = { if (panel != null) cancelFocusChange() } }
                .focusGroup()
        ) {
            item(key = "nd_hero", contentType = "nd_hero") {
                NdHero(
                    meta = meta,
                    state = state,
                    isSeries = isSeries,
                    playLabel = when {
                        upcoming != null && reminderOn -> stringResource(R.string.nd_reminder_on)
                        upcoming != null -> stringResource(R.string.nd_remind_me)
                        else -> state.nextToWatch?.displayText ?: stringResource(R.string.nd_play)
                    },
                    playIcon = when {
                        upcoming != null && reminderOn -> Icons.Filled.NotificationsActive
                        upcoming != null -> Icons.Outlined.NotificationsNone
                        else -> Icons.Filled.PlayArrow
                    },
                    playAside = upcoming?.let { shortReleaseDate(it.releaseDate) },
                    playFocus = playFocus,
                    episodesItemFocus = episodesItemFocus,
                    castItemFocus = castItemFocus,
                    titleRating = titleRating,
                    watched = watched,
                    watchedPending = state.isMovieWatchedPending,
                    onRate = { extras.toggleTitleRating(meta, it) },
                    onToggleWatched = toggleWatched,
                    onToggleLibrary = onToggleLibrary,
                    onLibraryLongPress = onLibraryLongPress,
                    binFocus = binFocus,
                    onBinBounds = { binBounds = it },
                    onPlay = play,
                    onPlayHold = if (upcoming == null) ({ playOptionsOpen = true }) else null,
                    onPlayBounds = { playBounds = it },
                    hasTrailersTab = isSeries && trailers.isNotEmpty(),
                    onPlayFocused = onPlayButtonFocused,
                    onPlayFocusChanged = { playFocused = it },
                    onOpenEpisodes = { returnFocusTo = episodesItemFocus; panel = NdPanel.EPISODES },
                    onOpenCast = { returnFocusTo = castItemFocus; panel = NdPanel.CAST },
                    onTrailer = onTrailerClick,
                    onRandomEpisode = {
                        meta.watchableEpisodes().randomOrNull()?.let(onPlayEpisode)
                    },
                    onFocusedInHero = {
                        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }
                )
            }
            if (state.moreLikeThis.isNotEmpty()) {
                item(key = "nd_similar", contentType = "nd_row") {
                    RowScroll(rowSpec) { NdTitleRow(
                        title = stringResource(R.string.nd_row_similar),
                        items = state.moreLikeThis,
                        footer = when (state.moreLikeThisSource) {
                            MoreLikeThisSource.TMDB -> stringResource(R.string.detail_more_like_this_powered_by_tmdb)
                            MoreLikeThisSource.TRAKT -> stringResource(R.string.detail_more_like_this_powered_by_trakt)
                            MoreLikeThisSource.SIMKL -> stringResource(R.string.detail_more_like_this_powered_by_simkl)
                            null -> null
                        },
                        onClick = { onNavigateToDetail(it.id, it.apiType, null) },
                        onLongClick = onPosterLongPress
                    ) }
                }
            }
            if (state.collection.isNotEmpty()) {
                item(key = "nd_collection", contentType = "nd_row") {
                    RowScroll(rowSpec) { NdTitleRow(
                        title = state.collectionName ?: stringResource(R.string.tmdb_collections_title),
                        items = state.collection,
                        footer = null,
                        onClick = { onNavigateToDetail(it.id, it.apiType, null) },
                        onLongClick = onPosterLongPress
                    ) }
                }
            }
            item(key = "nd_reviews", contentType = "nd_reviews") {
                RowScroll(rowSpec) {
                    NdReviewsRow(
                        subtitle = null,
                        reviews = titleReviews,
                        onOpen = { list, index -> reviewDirection = 0; openReview = list to index }
                    )
                }
            }
        }
        }

        when (panel) {
            NdPanel.EPISODES -> EpisodesPanel(
                meta = meta,
                state = state,
                reminders = reminders,
                onToggleReminder = { video -> upcomingReminder(meta, video)?.let(extras::toggleReminder) },
                episodeRatings = episodeRatings,
                episodeReviews = episodeReviews,
                onPlayEpisode = { video -> panel = null; onPlayEpisode(video) },
                onRateEpisode = { video, rating -> extras.toggleEpisodeRating(meta, video, rating) },
                onLoadEpisodeReviews = { video -> extras.loadEpisodeReviews(meta, video) },
                onOpenReview = { list, index -> reviewDirection = 0; openReview = list to index },
                onAddToBin = onLibraryLongPress,
                trailers = trailers,
                onPlayTrailer = onTrailerSelected,
                onClose = { panel = null },
                onEpisodeFocused = onEpisodeFocused
            )
            NdPanel.CAST -> CastCrewPanel(
                meta = meta,
                credits = credits,
                onPerson = { member, preferCrew ->
                    member.tmdbId?.let { onNavigateToCastDetail(it, member.name, preferCrew) }
                },
                onCompany = { kind, company ->
                    company.tmdbId?.let { onNavigateToTmdbEntityBrowse(kind, it, company.name, meta.apiType) }
                },
                onClose = { panel = null }
            )
            null -> Unit
        }

        if (state.showBinPicker) {
            val bounds = binBounds.translate(-pageOrigin)
            com.nuvio.tv.ui.screens.library.BinPicker(
                // Under the button, so it doesn't cover the buttons beside it.
                anchor = bounds,
                title = stringResource(R.string.nd_bin_picker_title),
                bins = state.bins,
                holding = state.titleBinIds,
                openedByHold = true,
                onPick = onBinPicked,
                onCreate = onCreateBin,
                onDismiss = onBinPickerDismiss
            )
        }

        if (playOptionsOpen) {
            PlayOptionsMenu(
                anchor = playBounds,
                title = state.nextToWatch?.displayText ?: meta.name,
                showFromStart = state.nextToWatch?.isResume == true,
                onPlayManually = { playOptionsOpen = false; playManually() },
                onFromStart = { playOptionsOpen = false; playFromStart() },
                onDismiss = {
                    playOptionsOpen = false
                    scope.launch {
                        delay(40)
                        runCatching { playFocus.requestFocus() }
                    }
                }
            )
        }

        if (state.isSharedTrailerOverlayVisible) {
            SharedTrailerOverlay(
                title = state.selectedSharedTrailer?.name?.takeIf { it.isNotBlank() }
                    ?: state.selectedSharedTrailer?.type?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.detail_tab_trailer),
                trailerUrl = state.sharedTrailerUrl,
                trailerAudioUrl = state.sharedTrailerAudioUrl,
                isLoading = state.isSharedTrailerLoading,
                errorMessage = state.sharedTrailerErrorMessage,
                onDismiss = onDismissSharedTrailer,
                onRetry = onRetrySharedTrailer
            )
        }

        openReview?.let { (list, index) ->
            val review = list.getOrNull(index)
            if (review == null) {
                openReview = null
            } else {
                CommentOverlay(
                    review = review,
                    episode = null,
                    canNavigatePrevious = index > 0,
                    canNavigateNext = index < list.lastIndex,
                    isLoadingNext = false,
                    transitionDirection = reviewDirection,
                    onPrevious = { reviewDirection = -1; openReview = list to (index - 1).coerceAtLeast(0) },
                    onNext = { reviewDirection = 1; openReview = list to (index + 1).coerceAtMost(list.lastIndex) },
                    onDismiss = { reviewDirection = 0; openReview = null }
                )
            }
        }
    }
}

/** The left gradient the original page draws over its backdrop: full background at the edge, clear at 78%. */
internal fun detailLeftGradient(background: Color, widthPx: Int): ImageBitmap {
    val w = widthPx.coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, 2, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val alphas = floatArrayOf(1f, 0.95f, 0.84f, 0.70f, 0.52f, 0.34f, 0.18f, 0.07f, 0f)
    val shader = android.graphics.LinearGradient(
        0f, 0f, w * 0.78f, 0f,
        IntArray(alphas.size) { background.copy(alpha = alphas[it]).toArgb() },
        floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f),
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, w.toFloat(), 2f, android.graphics.Paint().apply { this.shader = shader })
    return bmp.asImageBitmap()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NdHero(
    meta: Meta,
    state: MetaDetailsUiState,
    isSeries: Boolean,
    playLabel: String,
    playIcon: ImageVector,
    playAside: String?,
    playFocus: FocusRequester,
    episodesItemFocus: FocusRequester,
    castItemFocus: FocusRequester,
    titleRating: TitleRating?,
    watched: Boolean,
    watchedPending: Boolean,
    onRate: (TitleRating) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    binFocus: FocusRequester,
    onBinBounds: (Rect) -> Unit,
    onPlay: () -> Unit,
    onPlayHold: (() -> Unit)?,
    onPlayBounds: (Rect) -> Unit,
    hasTrailersTab: Boolean,
    onPlayFocused: () -> Unit,
    onPlayFocusChanged: (Boolean) -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenCast: () -> Unit,
    onTrailer: () -> Unit,
    onRandomEpisode: () -> Unit,
    onFocusedInHero: () -> Unit
) {
    val context = LocalContext.current
    var synopsisOpen by remember { mutableStateOf(false) }
    var logoFailed by remember(meta.logo) { mutableStateOf(false) }
    val (castOnly, crew) = remember(meta) { meta.castAndCrew() }
    val dim by animateFloatAsState(if (synopsisOpen) 0.1f else 1f, tween(300), label = "ndDim")
    val likeFocus = remember { FocusRequester() }
    // The column fits on the first screen: focus moving inside it never scrolls the page (it scrolls back to the
    // top instead, see onFocusedInHero).
    val noScroll = remember {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    Box(
        modifier = Modifier
            .bringIntoViewResponder(noScroll)
            .fillMaxWidth()
            .heightIn(min = 488.dp)
            .padding(start = 48.dp, end = 48.dp, top = 44.dp)
            .onFocusChanged { if (it.hasFocus) onFocusedInHero() }
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.46f)) {
            if (!meta.logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(
                    model = remember(meta.logo) { ImageRequest.Builder(context).data(meta.logo).crossfade(true).build() },
                    contentDescription = meta.name,
                    onError = { logoFailed = true },
                    modifier = Modifier.height(64.dp).fillMaxWidth(0.8f),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
                Spacer(Modifier.height(14.dp))
            } else {
                Text(meta.name, color = Color.White, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(10.dp))
            }
            NdMetaLines(meta = meta, isSeries = isSeries, tmdbRating = state.tmdbRating)
            Spacer(Modifier.height(10.dp))
            NdSynopsis(
                meta = meta,
                isSeries = isSeries,
                castOnly = castOnly,
                crew = crew,
                open = synopsisOpen,
                onOpenChange = { synopsisOpen = it },
                modifier = Modifier.zIndex(2f)
            )
            Column(Modifier.alpha(dim)) {
                Spacer(Modifier.height(8.dp))
                NdCredits(isSeries = isSeries, castOnly = castOnly, crew = crew)
                Spacer(Modifier.height(14.dp))
                val holding = state.bins.filter { it.id in state.titleBinIds }
                NdRatingRow(
                    firstFocus = likeFocus,
                    titleRating = titleRating,
                    inBin = holding.isNotEmpty(),
                    inBinLabel = when (holding.size) {
                        0 -> null
                        1 -> stringResource(R.string.nd_bin_in, holding.single().name)
                        else -> stringResource(R.string.nd_bin_in_many, holding.size)
                    },
                    binFocus = binFocus,
                    onBinBounds = onBinBounds,
                    watched = watched,
                    watchedPending = watchedPending,
                    onRate = onRate,
                    onToggleLibrary = onToggleLibrary,
                    onLibraryLongPress = onLibraryLongPress,
                    onToggleWatched = onToggleWatched
                )
                Spacer(Modifier.height(12.dp))
                val items = buildList {
                    if (isSeries) add(NdMenuItem(Icons.Outlined.Tv, stringResource(R.string.nd_menu_episodes),
                        pluralStringResource(R.plurals.nd_seasons_count, state.seasons.count { it > 0 }, state.seasons.count { it > 0 }),
                        episodesItemFocus, onOpenEpisodes))
                    add(NdMenuItem(Icons.Outlined.Group, stringResource(R.string.nd_menu_cast), null, castItemFocus, onOpenCast))
                    // A series' trailers are in the Episodes panel's Trailers tab instead.
                    if (state.trailerButtonEnabled && !state.trailerUrl.isNullOrBlank() && !hasTrailersTab) {
                        add(NdMenuItem(Icons.Outlined.Movie, stringResource(R.string.nd_menu_trailer), null, null, onTrailer))
                    }
                    if (isSeries && meta.videos.isNotEmpty()) {
                        add(NdMenuItem(Icons.Outlined.Shuffle, stringResource(R.string.nd_menu_random), null, null, onRandomEpisode))
                    }
                }
                NdPlayMenu(
                    upFocus = likeFocus,
                    playLabel = playLabel,
                    playIcon = playIcon,
                    playAside = playAside,
                    playFocus = playFocus,
                    onPlay = onPlay,
                    onPlayHold = onPlayHold,
                    onPlayBounds = onPlayBounds,
                    onPlayFocused = onPlayFocused,
                    onPlayFocusChanged = onPlayFocusChanged,
                    items = items
                )
            }
        }
    }
}

@Composable
private fun NdMetaLines(meta: Meta, isSeries: Boolean, tmdbRating: Float?) {
    val context = LocalContext.current
    val line1 = remember(meta, isSeries) {
        buildList {
            formatYearRange(meta.releaseInfo)?.let(::add)
            meta.genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ") { localizedGenreLabel(context, it) }?.let(::add)
            if (isSeries) {
                val seasons = meta.videos.mapNotNull { it.season }.filter { it > 0 }.distinct().size
                if (seasons > 0) add(context.resources.getQuantityString(R.plurals.nd_seasons_title, seasons, seasons))
            } else {
                meta.runtime?.let { formatRuntime(it) }?.let(::add)
            }
        }
    }
    val text = Color(0xFFD6D6D6)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        line1.forEachIndexed { i, part ->
            if (i > 0) Box(Modifier.size(3.dp).clip(CircleShape).background(text.copy(alpha = 0.6f)))
            Text(part, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    val cert = meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    // A score of 0 means nobody has rated it yet (an unreleased title): leave it out.
    val imdb = meta.imdbRating?.takeIf { it > 0f }
    @Suppress("NAME_SHADOWING") val tmdbRating = tmdbRating?.takeIf { it > 0f }
    if (cert != null || imdb != null || tmdbRating != null) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cert != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Transparent)
                        .drawBehind {
                            drawRoundRect(
                                text.copy(alpha = 0.55f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                            )
                        }
                        .padding(horizontal = 5.dp)
                ) { Text(cert, color = text, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium) }
            }
            if (imdb != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ImdbRatingSourceLabel(
                        logoModifier = Modifier.size(26.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        textColor = text
                    )
                    Text(String.format(java.util.Locale.US, "%.1f", imdb), color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (tmdbRating != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AsyncImage(
                        model = remember(context) { ImageRequest.Builder(context).data(R.raw.mdblist_tmdb).build() },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text((tmdbRating * 10).toInt().toString(), color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun NdCredits(isSeries: Boolean, castOnly: List<MetaCastMember>, crew: List<MetaCastMember>) {
    val castLine = castOnly.take(3).joinToString(", ") { it.name }
    val crewLabel = when {
        crew.isEmpty() -> null
        crew.first().character.equals("Director", true) -> stringResource(R.string.nd_credit_director)
        isSeries && crew.size > 1 -> stringResource(R.string.nd_credit_creators)
        isSeries -> stringResource(R.string.nd_credit_creator)
        else -> stringResource(R.string.nd_credit_director)
    }
    Column {
        if (castLine.isNotBlank()) CreditLine(stringResource(R.string.nd_credit_cast), castLine)
        if (crewLabel != null) CreditLine(crewLabel, crew.joinToString(", ") { it.name })
    }
}

@Composable
private fun CreditLine(label: String, value: String) {
    Text(
        text = androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = NdText3, fontWeight = FontWeight.Medium))
            append(label)
            pop()
            append(" ")
            append(value)
        },
        color = NdText2,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * The synopsis. Focused, a glass panel grows sideways from it (the column's width out to about 840 dp) with the
 * full text and the title's facts; it doesn't push anything down, and shrinks back when focus moves on.
 */
@Composable
private fun NdSynopsis(
    meta: Meta,
    isSeries: Boolean,
    castOnly: List<MetaCastMember>,
    crew: List<MetaCastMember>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val description = meta.description?.trim().orEmpty()
    val density = LocalDensity.current
    var collapsed by remember { mutableStateOf(IntSize.Zero) }
    var innerHeight by remember { mutableIntStateOf(0) }
    val padH = with(density) { 16.dp.roundToPx() }
    val padV = with(density) { 12.dp.roundToPx() }
    val expandedWidth = with(density) { 840.dp.roundToPx() }
    val easing = FastOutSlowInEasing
    val width by animateIntAsState(
        if (open) expandedWidth else collapsed.width + padH * 2, tween(460, easing = easing), label = "ndSynW"
    )
    val height by animateIntAsState(
        if (open) maxOf(innerHeight + padV * 2, collapsed.height + padV * 2) else collapsed.height + padV * 2,
        tween(460, easing = easing), label = "ndSynH"
    )
    val panelAlpha by animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 160 else 220, delayMillis = if (open) 0 else 300), label = "ndSynPanel"
    )
    val contentAlpha by animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 280 else 140, delayMillis = if (open) 180 else 0), label = "ndSynContent"
    )
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ndClickable(onFocusChanged = onOpenChange, onClick = {})
    ) {
        Text(
            text = description,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .onSizeChanged { collapsed = it }
                .alpha(1f - contentAlpha)
        )
        // Takes no space: drawn from the text's corner, over what's below and to the right. Kept (invisible) while
        // closed, so its height is known before it opens.
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(1), height.coerceAtLeast(1)))
                    layout(0, 0) { placeable.place(-padH, -padV) }
                }
                .alpha(panelAlpha)
                .detailGlassPanel(shape)
                .clip(shape)
        ) {
            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val innerWidth = expandedWidth - padH * 2
                        val placeable = measurable.measure(Constraints(minWidth = innerWidth, maxWidth = innerWidth))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(padH, padV) }
                    }
                    .alpha(contentAlpha)
            ) {
                NdSynopsisDetails(
                    meta = meta,
                    isSeries = isSeries,
                    castOnly = castOnly,
                    crew = crew,
                    modifier = Modifier.onSizeChanged { innerHeight = it.height }
                )
            }
        }
    }
}

@Composable
private fun NdSynopsisDetails(
    meta: Meta,
    isSeries: Boolean,
    castOnly: List<MetaCastMember>,
    crew: List<MetaCastMember>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val episodes = remember(meta.videos) { meta.videos.count { (it.season ?: 0) > 0 } }
    val seasons = remember(meta.videos) { meta.videos.mapNotNull { it.season }.filter { it > 0 }.distinct().size }
    val facts = buildList {
        meta.genres.takeIf { it.isNotEmpty() }?.let {
            add(stringResource(R.string.nd_fact_genres) to it.joinToString(", ") { g -> localizedGenreLabel(context, g) })
        }
        castOnly.take(4).takeIf { it.isNotEmpty() }?.let {
            add(stringResource(R.string.nd_fact_starring) to it.joinToString(", ") { m -> m.name })
        }
        if (crew.isNotEmpty()) {
            val label = if (crew.first().character.equals("Director", true) || !isSeries) R.string.nd_fact_director else R.string.nd_fact_created_by
            add(stringResource(label) to crew.joinToString(", ") { it.name })
        }
        if (isSeries) {
            if (seasons > 0) add(
                stringResource(R.string.nd_fact_seasons) to stringResource(
                    R.string.nd_fact_seasons_value,
                    context.resources.getQuantityString(R.plurals.nd_seasons_count, seasons, seasons),
                    episodes
                )
            )
            meta.status?.trim()?.takeIf { it.isNotBlank() }?.let {
                add(stringResource(R.string.nd_fact_status) to it.replaceFirstChar { c -> c.uppercase() })
            }
            meta.runtime?.let { formatRuntime(it) }?.let { add(stringResource(R.string.nd_fact_episode_length) to it) }
        } else {
            meta.runtime?.let { formatRuntime(it) }?.let { add(stringResource(R.string.nd_fact_runtime) to it) }
            formatYearRange(meta.releaseInfo)?.let { add(stringResource(R.string.nd_fact_released) to it) }
            meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.nd_fact_rated) to it) }
        }
        meta.language?.trim()?.takeIf { it.isNotBlank() }?.let {
            val code = normalizeLanguageCodeForDisplay(it)
            add(stringResource(R.string.nd_fact_language) to code)
        }
        meta.country?.trim()?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.nd_fact_country) to normalizeCountryLabel(it))
        }
    }
    val half = (facts.size + 1) / 2
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(
            text = meta.description?.trim().orEmpty(),
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.2.sp,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.35f)
        )
        FactColumn(facts.take(half), Modifier.weight(1f))
        FactColumn(facts.drop(half), Modifier.weight(1f))
    }
}

/** "en" → "English" in the device's language; anything else is shown as given. */
private fun normalizeLanguageCodeForDisplay(raw: String): String {
    val code = raw.trim()
    if (code.length in 2..3 && code.all { it.isLetter() }) {
        val name = java.util.Locale(code.lowercase()).getDisplayLanguage(java.util.Locale.getDefault())
        if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) return name
    }
    return code
}

@Composable
private fun FactColumn(facts: List<Pair<String, String>>, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.forEach { (label, value) ->
            Column {
                Text(label, color = NdText3, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(value, color = Color(0xFFEEEEEE), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class NdRateButton(
    val key: String,
    val label: String,
    val activeLabel: String?,
    val active: Boolean,
    val enabled: Boolean,
    val icon: @Composable (Boolean, Color) -> Unit,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null
)

/** Like, love, not for me, watchlist, watched: one row of glass circles with the focused one's meaning beside it. */
@Composable
private fun NdRatingRow(
    firstFocus: FocusRequester,
    titleRating: TitleRating?,
    inBin: Boolean,
    inBinLabel: String?,
    binFocus: FocusRequester,
    onBinBounds: (Rect) -> Unit,
    watched: Boolean,
    watchedPending: Boolean,
    onRate: (TitleRating) -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleWatched: () -> Unit
) {
    val likeLabel = stringResource(R.string.nd_rate_like)
    val loveLabel = stringResource(R.string.nd_rate_love)
    val dislikeLabel = stringResource(R.string.nd_rate_dislike)
    val buttons = listOf(
        NdRateButton("like", likeLabel, stringResource(R.string.nd_rate_saved, likeLabel), titleRating == TitleRating.LIKE, true,
            { on, c -> DetailGlyphIcon(DetailGlyph.THUMB_UP, on, c, 18.dp) }, { onRate(TitleRating.LIKE) }),
        NdRateButton("love", loveLabel, stringResource(R.string.nd_rate_saved, loveLabel), titleRating == TitleRating.LOVE, true,
            { on, c -> DetailGlyphIcon(DetailGlyph.THUMBS_UP_DOUBLE, on, c, 19.dp) }, { onRate(TitleRating.LOVE) }),
        NdRateButton("dislike", dislikeLabel, stringResource(R.string.nd_rate_saved, dislikeLabel), titleRating == TitleRating.DISLIKE, true,
            { on, c -> DetailGlyphIcon(DetailGlyph.THUMB_DOWN, on, c, 18.dp) }, { onRate(TitleRating.DISLIKE) }),
        // Bin (was Watchlist): press for the starred bin, hold for the list of bins.
        NdRateButton("bin", stringResource(R.string.nd_bin_add), inBinLabel, inBin, true,
            { on, c -> DetailGlyphIcon(DetailGlyph.STAR, on, c, 18.dp) }, onToggleLibrary, onLibraryLongPress),
        NdRateButton("watched", stringResource(R.string.nd_mark_watched), stringResource(R.string.nd_watched), watched, !watchedPending,
            { on, c -> Icon(if (on) Icons.Filled.Visibility else Icons.Outlined.Visibility, null, tint = c, modifier = Modifier.size(19.dp)) },
            onToggleWatched)
    )
    var focusedKey by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        buttons.forEach { button ->
            val focused = focusedKey == button.key
            val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "ndRateScale")
            Box(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(32.dp)
                    .then(if (focused) Modifier.detailGlassLens(CircleShape) else Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
                    .then(if (button.key == "bin") Modifier.onGloballyPositioned { onBinBounds(it.boundsInRoot()) } else Modifier)
                    .ndClickable(
                        focusRequester = when (button.key) {
                            "like" -> firstFocus
                            "bin" -> binFocus
                            else -> null
                        },
                        onFocusChanged = { f ->
                            if (f) focusedKey = button.key else if (focusedKey == button.key) focusedKey = null
                        },
                        onLongClick = button.onLongClick,
                        onClick = { if (button.enabled) button.onClick() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                button.icon(button.active, if (focused) Color.White else Color.White.copy(alpha = 0.88f))
            }
        }
        val label = buttons.firstOrNull { it.key == focusedKey }?.let { if (it.active) it.activeLabel ?: it.label else it.label }
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(label, color = NdText2, fontSize = 12.sp, maxLines = 1)
        }
    }
}

internal data class NdMenuItem(
    val icon: ImageVector,
    val label: String,
    val aside: String?,
    val focusRequester: FocusRequester?,
    val onClick: () -> Unit
)

/** Play and the menu under it share one glass lens that glides to whatever is focused. */
@Composable
private fun NdPlayMenu(
    upFocus: FocusRequester,
    playLabel: String,
    playIcon: ImageVector,
    playAside: String?,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onPlayHold: (() -> Unit)?,
    onPlayBounds: (Rect) -> Unit,
    onPlayFocused: () -> Unit,
    onPlayFocusChanged: (Boolean) -> Unit,
    items: List<NdMenuItem>
) {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var focused by remember { mutableIntStateOf(-1) }
    val target = bounds[focused]
    val lensY by animateIntAsState(target?.top?.toInt() ?: 0, tween(260, easing = FastOutSlowInEasing), label = "ndLensY")
    val lensH by animateIntAsState(target?.height?.toInt() ?: 0, tween(260, easing = FastOutSlowInEasing), label = "ndLensH")
    val lensW by animateIntAsState(target?.width?.toInt() ?: 0, tween(260, easing = FastOutSlowInEasing), label = "ndLensW")
    val lensAlpha by animateFloatAsState(if (target != null) 1f else 0f, tween(180), label = "ndLensAlpha")
    Box {
        if (lensAlpha > 0f && lensW > 0) {
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val p = measurable.measure(Constraints.fixed(lensW, lensH.coerceAtLeast(1)))
                        layout(0, 0) { p.place(IntOffset(0, lensY)) }
                    }
                    .alpha(lensAlpha)
                    .detailGlassLens(NdPill)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier
                    .onGloballyPositioned {
                        bounds[0] = it.boundsInParent()
                        onPlayBounds(it.boundsInRoot())
                    }
                    .width(208.dp)
                    .height(36.dp)
                    // Up from Play goes to the first rating button, not whichever is nearest.
                    .focusProperties { up = upFocus }
                    .ndClickable(
                        focusRequester = playFocus,
                        onFocusChanged = { f ->
                            onPlayFocusChanged(f)
                            if (f) { focused = 0; onPlayFocused() } else if (focused == 0) focused = -1
                        },
                        onLongClick = onPlayHold,
                        onClick = onPlay
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(playIcon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(playLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f))
                if (playAside != null) {
                    Text(playAside, color = NdText2, fontSize = 11.sp, maxLines = 1)
                }
            }
            items.forEachIndexed { i, item ->
                val index = i + 1
                Row(
                    modifier = Modifier
                        .onGloballyPositioned { bounds[index] = it.boundsInParent() }
                        .width(208.dp)
                        .height(30.dp)
                        .ndClickable(
                            focusRequester = item.focusRequester,
                            onFocusChanged = { f -> if (f) focused = index else if (focused == index) focused = -1 },
                            onClick = item.onClick
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val color = if (focused == index) Color.White else Color.White.copy(alpha = 0.84f)
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(16.dp))
                    Text(item.label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    if (item.aside != null) {
                        Text(item.aside, color = if (focused == index) NdText2 else NdText3, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The page scrolls only as far as it must to show what's focused (with room for a row's title above and its
 * captions below), instead of the TV default of moving the focused item to a fixed spot, which shifted the
 * whole page on every step.
 */
@Composable
private fun rememberMinimalPageScroll(): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val top = with(density) { 56.dp.toPx() }
        val bottom = with(density) { 60.dp.toPx() }
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                com.nuvio.tv.ui.newlook.Glide.scroll

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val leading = offset - top
                val trailing = offset + size - (containerSize - bottom)
                return when {
                    size + top + bottom > containerSize -> leading
                    leading < 0f -> leading
                    trailing > 0f -> trailing
                    else -> 0f
                }
            }
        }
    }
}

/** Gives a row back the app's usual sideways scrolling inside the page's minimal vertical scrolling. */
@Composable
private fun RowScroll(spec: androidx.compose.foundation.gestures.BringIntoViewSpec, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec,
        content = content
    )
}

/** A "Remind me" for [video] when it hasn't aired yet; null when it's out or its date is unknown. */
internal fun upcomingReminder(meta: Meta, video: Video): com.nuvio.tv.data.local.ReleaseReminder? {
    if (com.nuvio.tv.core.util.isEpisodeReleaseAired(video.released) != false) return null
    val date = com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate(video.released) ?: return null
    return com.nuvio.tv.data.local.ReleaseReminder(
        meta.id, meta.apiType, meta.name, video.season ?: return null, video.episode ?: return null,
        video.title.takeIf { it.isNotBlank() }, date
    )
}

/**
 * A "Remind me" for a movie that isn't out: its release date is still ahead, or (with no date) its year is.
 * A year alone reminds on the first of that year.
 */
internal fun upcomingReminder(meta: Meta): com.nuvio.tv.data.local.ReleaseReminder? {
    val today = java.time.LocalDate.now()
    val date = when (com.nuvio.tv.core.util.isEpisodeReleaseAired(meta.released)) {
        false -> com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate(meta.released)
        true -> null
        null -> meta.releaseInfo?.trim()?.take(4)?.toIntOrNull()
            ?.takeIf { it > today.year }?.let { java.time.LocalDate.of(it, 1, 1) }
    } ?: return null
    return com.nuvio.tv.data.local.ReleaseReminder(meta.id, meta.apiType, meta.name, null, null, null, date)
}

/** "Jul 30" this year, "Jul 30, 2027" otherwise. */
internal fun shortReleaseDate(date: java.time.LocalDate): String {
    val pattern = if (date.year == java.time.LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
}
