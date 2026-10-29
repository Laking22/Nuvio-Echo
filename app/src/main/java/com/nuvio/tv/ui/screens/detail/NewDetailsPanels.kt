package com.nuvio.tv.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.delay

/** A full-screen panel: black, and focus stays inside it until it closes. */
@Composable
private fun PanelFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Properties first: they apply to the next focus target, so this one is the panel's own group.
            // (After focusGroup() they landed on each list inside, which then couldn't be left.)
            .trapDpadFocus()
            .focusGroup()
    ) { content() }
}

/**
 * Keeps the D-pad inside a panel or menu (it can't be walked out of), while a page opened over it can still take
 * focus. Cancelling every exit also refused the person page's own focus request: focus stayed on
 * the hidden Cast & Crew grid, so the filmography never got it and Right did nothing.
 */
internal fun Modifier.trapDpadFocus(): Modifier = focusProperties {
    onExit = {
        val d = requestedFocusDirection
        if (d == FocusDirection.Up || d == FocusDirection.Down || d == FocusDirection.Left ||
            d == FocusDirection.Right || d == FocusDirection.Next || d == FocusDirection.Previous
        ) cancelFocusChange()
    }
}

@Composable
private fun PanelLogo(meta: Meta) {
    val context = LocalContext.current
    var failed by remember(meta.logo) { mutableStateOf(false) }
    if (!meta.logo.isNullOrBlank() && !failed) {
        AsyncImage(
            model = remember(meta.logo) { ImageRequest.Builder(context).data(meta.logo).crossfade(true).build() },
            contentDescription = meta.name,
            onError = { failed = true },
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(200.dp).height(56.dp)
        )
    } else {
        Text(meta.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

private val RowShape = RoundedCornerShape(10.dp)
private val EpisodeShape = RoundedCornerShape(12.dp)

/** The Trailers tab's place in the season list (after the seasons and Specials). */
private const val TRAILERS_TAB = Int.MIN_VALUE

private fun trailerFocusId(index: Int) = "trailer:$index"

/**
 * Episodes: seasons down the left (moving onto one shows it), its episodes on the right. OK plays an episode;
 * holding OK opens Play, Liked, Loved, Not for me and Reviews for it. Under the seasons, a Trailers tab lists the
 * show's trailers, which play over the page.
 */
@Composable
internal fun EpisodesPanel(
    meta: Meta,
    state: MetaDetailsUiState,
    reminders: Map<String, com.nuvio.tv.data.local.ReleaseReminder>,
    onToggleReminder: (Video) -> Unit,
    episodeRatings: Map<Pair<Int, Int>, TitleRating>,
    episodeReviews: Map<Pair<Int, Int>, ReviewsState>,
    onPlayEpisode: (Video) -> Unit,
    onRateEpisode: (Video, TitleRating) -> Unit,
    onLoadEpisodeReviews: (Video) -> Unit,
    onOpenReview: (List<TraktCommentReview>, Int) -> Unit,
    onAddToBin: () -> Unit,
    trailers: List<com.nuvio.tv.domain.model.MetaTrailer>,
    onPlayTrailer: (com.nuvio.tv.domain.model.MetaTrailer) -> Unit,
    onClose: () -> Unit,
    /** Focus has landed on an episode (its sources are warmed once focus rests there). */
    onEpisodeFocused: (Video) -> Unit = {}
) {
    val seasons = remember(meta.videos, trailers.isNotEmpty()) {
        meta.videos.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it }) +
            listOfNotNull(TRAILERS_TAB.takeIf { trailers.isNotEmpty() })
    }
    val bySeason = remember(meta.videos) {
        meta.videos.filter { it.season != null }.groupBy { it.season!! }
            .mapValues { (_, eps) -> eps.sortedBy { it.episode ?: Int.MAX_VALUE } }
    }
    var season by remember { mutableStateOf(state.selectedSeason.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var reviewsFor by remember { mutableStateOf<Video?>(null) }
    var refocusEpisode by remember { mutableStateOf<String?>(null) }
    val episodeFocus = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = episodeFocus.getOrPut(id) { FocusRequester() }
    val totalEpisodes = remember(meta.videos) { meta.videos.count { (it.season ?: 0) > 0 } }
    val seasonCount = seasons.count { it > 0 }

    BackHandler(enabled = menuFor == null && reviewsFor == null) { onClose() }
    BackHandler(enabled = reviewsFor != null && menuFor == null) {
        refocusEpisode = reviewsFor?.id
        reviewsFor = null
    }
    BackHandler(enabled = menuFor != null) {
        refocusEpisode = menuFor?.id
        menuFor = null
    }

    val episodes = bySeason[season].orEmpty()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val seasonFocus = remember { HashMap<Int, FocusRequester>() }
    fun seasonRequester(s: Int) = seasonFocus.getOrPut(s) { FocusRequester() }
    val seasonListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) {
        // The next episode may be far down the list: bring it in first, since only built rows can take focus.
        val index = episodes.indexOfFirst { it.id == state.nextToWatch?.nextVideoId }.coerceAtLeast(0)
        if (index > 0) listState.scrollToItem(index)
        val target = episodes.getOrNull(index)
        repeat(8) {
            if (target != null && runCatching { requesterFor(target.id).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
        runCatching { seasonRequester(season).requestFocus() }
    }
    // Keeps the season being shown in view in the left list, so Left from an episode can always reach it.
    LaunchedEffect(season) {
        val i = seasons.indexOf(season)
        if (i < 0) return@LaunchedEffect
        val visible = seasonListState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == i && it.offset >= 0 &&
            it.offset + it.size <= seasonListState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) seasonListState.animateScrollToItem(maxOf(0, i - 2))
    }
    // Moving past the last episode goes on into the next season (and past the first, back into the previous one).
    var shownSeason by remember { mutableStateOf(season) }
    var pendingEdge by remember { mutableStateOf<Boolean?>(null) } // true = land on the last episode
    // While a step into the next (or previous) season moves focus, a season row that catches focus in passing must
    // not switch the season. Going between the Trailers tab and a season swaps the whole list, so the focused row
    // goes away and focus falls to the first season row before the step's own request lands.
    var steppingSeason by remember { mutableStateOf(false) }
    val orderedSeasons = seasons
    fun stepSeason(forward: Boolean): Boolean {
        val i = orderedSeasons.indexOf(season)
        val next = orderedSeasons.getOrNull(if (forward) i + 1 else i - 1) ?: return false
        if (next != TRAILERS_TAB && bySeason[next].isNullOrEmpty()) return false
        pendingEdge = !forward
        steppingSeason = true
        season = next
        return true
    }
    suspend fun stepIntoSeason(toLast: Boolean?) {
        val ids = if (season == TRAILERS_TAB) trailers.indices.map(::trailerFocusId) else bySeason[season].orEmpty().map { it.id }
        if (toLast == null) {
            listState.scrollToItem(0)
            return
        }
        val index = if (toLast) ids.lastIndex else 0
        if (index < 0) return
        listState.scrollToItem(index)
        repeat(8) {
            if (runCatching { requesterFor(ids[index]).requestFocus() }.getOrDefault(false)) return
            delay(40)
        }
    }
    LaunchedEffect(season) {
        if (season == shownSeason) return@LaunchedEffect
        shownSeason = season
        val toLast = pendingEdge
        pendingEdge = null
        try {
            stepIntoSeason(toLast)
        } finally {
            steppingSeason = false
        }
    }

    LaunchedEffect(refocusEpisode, reviewsFor) {
        val id = refocusEpisode ?: return@LaunchedEffect
        if (reviewsFor != null) return@LaunchedEffect
        repeat(5) {
            if (runCatching { requesterFor(id).requestFocus() }.getOrDefault(false)) {
                refocusEpisode = null
                return@LaunchedEffect
            }
            delay(60)
        }
        refocusEpisode = null
    }

    PanelFrame {
        Row(Modifier.fillMaxSize().alpha(if (menuFor != null) 0.3f else 1f)) {
            Column(
                modifier = Modifier.width(272.dp).fillMaxHeight().padding(start = 32.dp, top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PanelLogo(meta)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(
                        R.string.nd_panel_seasons_episodes,
                        pluralStringResource(R.plurals.nd_seasons_title, seasonCount, seasonCount),
                        totalEpisodes
                    ),
                    color = NdText2, fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    state = seasonListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(seasons, key = { _, s -> "season_$s" }) { _, s ->
                        var focused by remember { mutableStateOf(false) }
                        val selected = s == season
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .then(
                                    when {
                                        focused -> Modifier.detailGlassLens(RowShape)
                                        selected -> Modifier.clip(RowShape).background(Color(0xFF1C1C1C))
                                        else -> Modifier
                                    }
                                )
                                .ndClickable(
                                    focusRequester = seasonRequester(s),
                                    onFocusChanged = { f ->
                                        focused = f
                                        if (f && reviewsFor == null && !steppingSeason) season = s
                                    },
                                    onClick = {
                                        val first = if (s == TRAILERS_TAB) trailerFocusId(0).takeIf { trailers.isNotEmpty() }
                                            else bySeason[s]?.firstOrNull()?.id
                                        first?.let { runCatching { requesterFor(it).requestFocus() } }
                                    }
                                )
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when (s) {
                                    TRAILERS_TAB -> stringResource(R.string.nd_panel_trailers)
                                    0 -> stringResource(R.string.nd_panel_specials)
                                    else -> stringResource(R.string.nd_panel_season, s)
                                },
                                color = if (selected || focused) Color.White else Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                            )
                            if (s == TRAILERS_TAB) {
                                Text(pluralStringResource(R.plurals.nd_panel_videos_count, trailers.size, trailers.size), color = NdText2, fontSize = 12.sp)
                            } else {
                                val count = bySeason[s].orEmpty().size
                                Text(pluralStringResource(R.plurals.nd_panel_episodes_count, count, count), color = NdText2, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(32.dp))
            Box(Modifier.weight(1f).fillMaxHeight().padding(end = 32.dp)) {
                val showing = reviewsFor
                if (showing == null && season == TRAILERS_TAB) {
                    TrailerList(
                        listState = listState,
                        meta = meta,
                        trailers = trailers,
                        requesterFor = ::requesterFor,
                        seasonFocus = seasonRequester(season),
                        onPlay = onPlayTrailer,
                        onPastStart = { stepSeason(false) }
                    )
                } else if (showing != null) {
                    EpisodeReviewsList(
                        video = showing,
                        reviews = episodeReviews[(showing.season ?: 0) to (showing.episode ?: 0)] ?: ReviewsState(loading = true),
                        onOpenReview = onOpenReview
                    )
                } else {
                    EpisodeList(
                        listState = listState,
                        season = season,
                        episodes = episodes,
                        state = state,
                        episodeRatings = episodeRatings,
                        reminders = reminders,
                        meta = meta,
                        requesterFor = ::requesterFor,
                        seasonFocus = seasonRequester(season),
                        onPlay = { video ->
                            if (upcomingReminder(meta, video) != null) onToggleReminder(video) else onPlayEpisode(video)
                        },
                        onHold = { video, anchor -> menuAnchor = anchor; menuFor = video },
                        onPastEnd = { forward -> stepSeason(forward) },
                        onFocused = onEpisodeFocused
                    )
                }
            }
        }
        menuFor?.let { video ->
            val upcoming = upcomingReminder(meta, video)
            EpisodeHoldMenu(
                video = video,
                anchor = menuAnchor,
                current = episodeRatings[(video.season ?: 0) to (video.episode ?: 0)],
                upcoming = upcoming != null,
                reminderOn = upcoming != null && upcoming.key in reminders,
                onPlay = {
                    if (upcoming != null) {
                        onToggleReminder(video)
                        refocusEpisode = video.id
                        menuFor = null
                    } else {
                        menuFor = null
                        onPlayEpisode(video)
                    }
                },
                onRate = { rating -> onRateEpisode(video, rating); refocusEpisode = video.id; menuFor = null },
                onReviews = {
                    onLoadEpisodeReviews(video)
                    menuFor = null
                    reviewsFor = video
                },
                onAddToBin = {
                    menuFor = null
                    onAddToBin()
                }
            )
        }
    }
}

@Composable
private fun EpisodeList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    season: Int,
    episodes: List<Video>,
    state: MetaDetailsUiState,
    episodeRatings: Map<Pair<Int, Int>, TitleRating>,
    reminders: Map<String, com.nuvio.tv.data.local.ReleaseReminder>,
    meta: Meta,
    requesterFor: (String) -> FocusRequester,
    seasonFocus: FocusRequester,
    onPlay: (Video) -> Unit,
    onHold: (Video, Rect) -> Unit,
    onPastEnd: (forward: Boolean) -> Boolean,
    onFocused: (Video) -> Unit
) {
    val ratings = episodes.mapNotNull { v -> episodeRating(state, v) }
    val average = if (ratings.isEmpty()) null else ratings.average()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (season == 0) stringResource(R.string.nd_panel_specials) else stringResource(R.string.nd_panel_season, season),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.nd_panel_hold_hint), color = NdText3, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (average != null) {
                Text("★", color = Color(0xFFF5C518), fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(String.format(java.util.Locale.US, "%.1f", average), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 2.dp)
        ) {
            itemsIndexed(episodes, key = { _, v -> v.id }) { index, video ->
                val upcoming = upcomingReminder(meta, video)
                EpisodeRow(
                    video = video,
                    state = state,
                    rated = episodeRatings[(video.season ?: 0) to (video.episode ?: 0)],
                    upcomingDate = upcoming?.releaseDate,
                    reminderOn = upcoming != null && upcoming.key in reminders,
                    requester = requesterFor(video.id),
                    onPlay = { onPlay(video) },
                    onHold = { anchor -> onHold(video, anchor) },
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) onFocused(video) }
                        // Left goes to the season being shown, not whichever season row is level with this episode.
                        .focusProperties { left = seasonFocus }
                        .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (native.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> index == episodes.lastIndex && onPastEnd(true)
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> index == 0 && onPastEnd(false)
                            else -> false
                        }
                    }
                )
            }
        }
    }
}

/** The Trailers tab: one row per video, OK plays it. Up from the first goes back to the last season's end. */
@Composable
private fun TrailerList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    meta: Meta,
    trailers: List<com.nuvio.tv.domain.model.MetaTrailer>,
    requesterFor: (String) -> FocusRequester,
    seasonFocus: FocusRequester,
    onPlay: (com.nuvio.tv.domain.model.MetaTrailer) -> Unit,
    onPastStart: () -> Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nd_panel_trailers), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 2.dp)
        ) {
            itemsIndexed(trailers, key = { i, t -> "${t.ytId}|$i" }) { index, trailer ->
                TrailerRow(
                    trailer = trailer,
                    fallbackTitle = meta.name,
                    requester = requesterFor(trailerFocusId(index)),
                    onPlay = { onPlay(trailer) },
                    modifier = Modifier
                        .focusProperties { left = seasonFocus }
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            native.action == android.view.KeyEvent.ACTION_DOWN &&
                                native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && index == 0 && onPastStart()
                        }
                )
            }
        }
    }
}

@Composable
private fun TrailerRow(
    trailer: com.nuvio.tv.domain.model.MetaTrailer,
    fallbackTitle: String,
    requester: FocusRequester,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val thumb = "https://img.youtube.com/vi/${trailer.ytId}/mqdefault.jpg"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focused) Modifier.detailGlassLens(EpisodeShape) else Modifier)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onPlay)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(192.dp).height(108.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF151515))) {
            AsyncImage(
                model = remember(thumb) { ImageRequest.Builder(context).data(thumb).crossfade(true).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                trailer.name?.takeIf { it.isNotBlank() } ?: "${trailer.type?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nd_trailer_untitled)}: $fallbackTitle",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val details = listOfNotNull(
                trailer.type?.takeIf { it.isNotBlank() },
                trailer.lang?.takeIf { it.isNotBlank() }?.let { code ->
                    java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()).takeIf { it.isNotBlank() } ?: code
                }
            )
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(details.joinToString(" · "), color = NdText2, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Hold-OK on the page's Play (or Resume): Play manually (choose the source from the list) and, for a Resume, Start
 * from beginning. Opens beside Play in the same glass as the episode menu.
 */
@Composable
internal fun PlayOptionsMenu(
    anchor: Rect,
    title: String,
    showFromStart: Boolean,
    onPlayManually: () -> Unit,
    onFromStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }
    val x = anchor.right + with(density) { 12.dp.toPx() }
    val y = anchor.top - with(density) { 34.dp.toPx() }
    Column(
        modifier = Modifier
            .offset { IntOffset(x.toInt(), y.toInt().coerceAtLeast(0)) }
            .width(220.dp)
            .detailGlassPanel(RoundedCornerShape(18.dp), strength = 1.6f)
            .ignoreHeldSelect()
            .trapDpadFocus()
            .focusGroup()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title, color = NdText3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)
        )
        HoldItem(stringResource(R.string.play_manually), false, first, onPlayManually) { c ->
            Icon(Icons.AutoMirrored.Filled.List, null, tint = c, modifier = Modifier.size(18.dp))
        }
        if (showFromStart) {
            HoldItem(stringResource(R.string.cw_action_start_from_beginning), false, null, onFromStart) { c ->
                Icon(Icons.Filled.Replay, null, tint = c, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun episodeRating(state: MetaDetailsUiState, video: Video): Double? {
    val key = (video.season ?: return null) to (video.episode ?: return null)
    return state.episodeImdbRatings[key] ?: video.rating?.takeIf { it > 0 }
}

@Composable
private fun EpisodeRow(
    video: Video,
    state: MetaDetailsUiState,
    rated: TitleRating?,
    upcomingDate: java.time.LocalDate?,
    reminderOn: Boolean,
    requester: FocusRequester,
    onPlay: () -> Unit,
    onHold: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val key = (video.season ?: 0) to (video.episode ?: 0)
    val progress = state.episodeProgressMap[key]
    val watched = key in state.watchedEpisodes || progress?.isCompleted() == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .then(if (focused) Modifier.detailGlassLens(EpisodeShape) else Modifier)
            .ndClickable(
                focusRequester = requester,
                onFocusChanged = { focused = it },
                onLongClick = { onHold(bounds) },
                onClick = onPlay
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.width(192.dp).height(108.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF151515))) {
            video.thumbnail?.let { thumb ->
                AsyncImage(
                    model = remember(thumb) { ImageRequest.Builder(context).data(thumb).crossfade(true).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            video.episode?.let { n ->
                Text(
                    stringResource(R.string.nd_panel_episode_tag, n),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (upcomingDate != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (reminderOn) Color.White else Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (reminderOn) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                        null, tint = if (reminderOn) Color.Black else Color.White, modifier = Modifier.size(14.dp)
                    )
                }
            } else if (watched) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp)) }
            } else if (progress != null && progress.progressPercentage > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.progressPercentage.coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    video.title.ifBlank { stringResource(R.string.nd_panel_episode_tag, video.episode ?: 0) },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (rated != null) {
                    Spacer(Modifier.width(8.dp))
                    DetailGlyphIcon(
                        when (rated) {
                            TitleRating.LIKE -> DetailGlyph.THUMB_UP
                            TitleRating.LOVE -> DetailGlyph.THUMBS_UP_DOUBLE
                            TitleRating.DISLIKE -> DetailGlyph.THUMB_DOWN
                        },
                        filled = true, color = Color.White, size = 15.dp
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parts = listOfNotNull(
                    video.runtime?.takeIf { it > 0 }?.let { stringResource(R.string.nd_panel_minutes, it) },
                    if (upcomingDate != null) stringResource(R.string.nd_coming, shortReleaseDate(upcomingDate)) else shortDate(video.released),
                    if (reminderOn) stringResource(R.string.nd_reminder_on) else null
                )
                Text(parts.joinToString(" · "), color = NdText2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                episodeRating(state, video)?.let {
                    Text("★", color = Color(0xFFF5C518), fontSize = 12.sp)
                    Spacer(Modifier.width(3.dp))
                    Text(String.format(java.util.Locale.US, "%.1f", it), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            video.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun shortDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val date = runCatching { java.time.OffsetDateTime.parse(raw).toLocalDate() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull()
        ?: return null
    return date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}

/** Hold-OK menu for one episode: Play, the three ratings (a check on the current one), then Reviews. */
@Composable
private fun EpisodeHoldMenu(
    video: Video,
    anchor: Rect,
    current: TitleRating?,
    upcoming: Boolean,
    reminderOn: Boolean,
    onPlay: () -> Unit,
    onRate: (TitleRating) -> Unit,
    onReviews: () -> Unit,
    onAddToBin: () -> Unit
) {
    val density = LocalDensity.current
    val first = remember { FocusRequester() }
    LaunchedEffect(video.id) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }
    val menuHeight = with(density) { 300.dp.toPx() }
    val screenHeight = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val x = anchor.left + with(density) { 214.dp.toPx() }
    val y = (anchor.top - with(density) { 4.dp.toPx() })
        .coerceIn(with(density) { 16.dp.toPx() }, (screenHeight - menuHeight - with(density) { 16.dp.toPx() }).coerceAtLeast(0f))
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .width(220.dp)
            .detailGlassPanel(shape, strength = 1.6f)
            .ignoreHeldSelect()
            .trapDpadFocus()
            .focusGroup()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            listOfNotNull(video.episode?.let { stringResource(R.string.nd_panel_episode_tag, it) }, video.title.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            color = NdText3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp)
        )
        if (upcoming) {
            // Not out yet: "Remind me" takes Play's place.
            HoldItem(stringResource(if (reminderOn) R.string.nd_reminder_on else R.string.nd_remind_me), reminderOn, first, onPlay) { c ->
                Icon(if (reminderOn) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone, null, tint = c,
                    modifier = Modifier.size(18.dp))
            }
        } else {
            HoldItem(stringResource(R.string.nd_hold_play), false, first, onPlay) { c ->
                Icon(Icons.Filled.PlayArrow, null, tint = c, modifier = Modifier.size(18.dp))
            }
        }
        HoldItem(stringResource(R.string.nd_hold_liked), current == TitleRating.LIKE, null, { onRate(TitleRating.LIKE) }) { c ->
            DetailGlyphIcon(DetailGlyph.THUMB_UP, current == TitleRating.LIKE, c, 17.dp)
        }
        HoldItem(stringResource(R.string.nd_hold_loved), current == TitleRating.LOVE, null, { onRate(TitleRating.LOVE) }) { c ->
            DetailGlyphIcon(DetailGlyph.THUMBS_UP_DOUBLE, current == TitleRating.LOVE, c, 18.dp)
        }
        HoldItem(stringResource(R.string.nd_hold_not_for_me), current == TitleRating.DISLIKE, null, { onRate(TitleRating.DISLIKE) }) { c ->
            DetailGlyphIcon(DetailGlyph.THUMB_DOWN, current == TitleRating.DISLIKE, c, 17.dp)
        }
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        HoldItem(stringResource(R.string.nd_bin_picker_title), false, null, onAddToBin) { c ->
            Icon(Icons.AutoMirrored.Filled.List, null, tint = c, modifier = Modifier.size(17.dp))
        }
        HoldItem(stringResource(R.string.nd_hold_reviews), false, null, onReviews) { c ->
            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = c, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun HoldItem(
    label: String,
    checked: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(if (focused) Modifier.detailGlassLens(NdPill) else Modifier)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon(Color.White)
        Text(label, color = Color.White.copy(alpha = if (focused) 1f else 0.88f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        if (checked) Text("✓", color = NdText2, fontSize = 12.sp)
    }
}

@Composable
private fun EpisodeReviewsList(
    video: Video,
    reviews: ReviewsState,
    onOpenReview: (List<TraktCommentReview>, Int) -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(video.id, reviews.items.isNotEmpty()) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(60)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.nd_episode_reviews_title, video.episode ?: 0, video.title),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(stringResource(R.string.nd_episode_reviews_back), color = NdText2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (reviews.items.isEmpty()) {
            Text(
                stringResource(if (reviews.loading || !reviews.loaded) R.string.nd_reviews_loading else R.string.nd_episode_reviews_empty),
                color = NdText2, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp).focusRequester(first).ndClickable(onClick = {})
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp, top = 2.dp)) {
                itemsIndexed(reviews.items, key = { i, r -> "${r.id}|$i" }) { index, review ->
                    Box(if (index == 0) Modifier.focusRequester(first) else Modifier) {
                        NdReviewCard(review = review, width = null, maxLines = 3, onClick = { onOpenReview(reviews.items, index) })
                    }
                }
            }
        }
    }
}

/** Cast & Crew: a Cast/Crew switch (moving onto it switches) over a grid of people; Crew ends with the companies. */
@Composable
internal fun CastCrewPanel(
    meta: Meta,
    credits: com.nuvio.tv.data.repository.TitleCredits,
    onPerson: (MetaCastMember, Boolean) -> Unit,
    onCompany: (String, MetaCompany) -> Unit,
    onClose: () -> Unit
) {
    val (castOnly, crew) = remember(meta, credits) { mergeCredits(meta, credits) }
    var tab by remember { mutableStateOf(if (castOnly.isNotEmpty()) 0 else 1) }
    val firstCard = remember { FocusRequester() }
    val toggleFocus = remember { listOf(FocusRequester(), FocusRequester()) }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        repeat(5) {
            val ok = runCatching { firstCard.requestFocus() }.getOrDefault(false) ||
                runCatching { toggleFocus[tab].requestFocus() }.getOrDefault(false)
            if (ok) return@LaunchedEffect
            delay(60)
        }
    }
    PanelFrame {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(272.dp).fillMaxHeight().padding(start = 32.dp, top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PanelLogo(meta)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.nd_panel_cast_crew), color = NdText2, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.nd_panel_cast_counts, castOnly.size, crew.size), color = NdText3, fontSize = 12.sp)
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 32.dp)) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (tab == 0) R.string.nd_panel_cast else R.string.nd_panel_crew),
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    Row(Modifier.clip(NdPill).background(Color(0xFF1A1A1A)).padding(3.dp)) {
                        listOf(R.string.nd_panel_cast, R.string.nd_panel_crew).forEachIndexed { i, label ->
                            var focused by remember { mutableStateOf(false) }
                            Text(
                                stringResource(label),
                                color = when {
                                    focused -> Color.Black
                                    tab == i -> Color.White
                                    else -> NdText2
                                },
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(NdPill)
                                    .background(
                                        when {
                                            focused -> Color.White
                                            tab == i -> Color(0xFF333333)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .ndClickable(
                                        focusRequester = toggleFocus[i],
                                        onFocusChanged = { f -> focused = f; if (f) tab = i },
                                        onClick = { runCatching { firstCard.requestFocus() } }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                val people = if (tab == 0) castOnly else crew
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp)
                ) {
                    if (people.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.nd_panel_no_cast), color = NdText2, fontSize = 13.sp)
                        }
                    }
                    items(people.size, key = { "p_${tab}_$it" }) { index ->
                        val member = people[index]
                        PersonCard(
                            member = member,
                            requester = if (index == 0) firstCard else null,
                            upTo = if (index < 3) toggleFocus[tab] else null,
                            onClick = { onPerson(member, tab == 1) }
                        )
                    }
                    if (tab == 1) {
                        companySection(R.string.nd_panel_network, meta.networks) { onCompany("network", it) }
                        companySection(R.string.nd_panel_production, meta.productionCompanies) { onCompany("company", it) }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.companySection(
    title: Int,
    companies: List<MetaCompany>,
    onClick: (MetaCompany) -> Unit
) {
    if (companies.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }, key = "h_$title") {
        Text(
            stringResource(title).uppercase(),
            color = NdText3, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    items(companies, key = { "c_${title}_${it.tmdbId ?: it.name}" }) { company ->
        CompanyCard(company = company, onClick = { onClick(company) })
    }
}

@Composable
private fun PersonCard(member: MetaCastMember, requester: FocusRequester?, upTo: FocusRequester?, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .then(if (focused) Modifier.detailGlassLens(shape) else Modifier.clip(shape).background(Color(0xFF121212)))
            // The first row's Up goes to the Cast/Crew switch, which the grid's own search doesn't reach.
            .focusProperties { if (upTo != null) up = upTo }
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onClick)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
            if (member.photo != null) {
                AsyncImage(
                    model = remember(member.photo) { ImageRequest.Builder(context).data(member.photo).crossfade(true).build() },
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(member.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(member.name, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            member.character?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = NdText2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CompanyCard(company: MetaCompany, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(56.dp)
            .then(if (focused) Modifier.detailGlassLens(shape) else Modifier)
            .padding(if (focused) 2.dp else 0.dp)
            .clip(shape)
            .background(Color.White)
            .ndClickable(onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (company.logo != null) {
            AsyncImage(
                model = remember(company.logo) { ImageRequest.Builder(context).data(company.logo).crossfade(true).build() },
                contentDescription = company.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(company.name, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The addon's cast with TMDB's ids and photos filled in by name (so everyone opens a filmography), or TMDB's cast
 * when the addon has none; the crew is the addon's lead credits followed by TMDB's whole crew, each person once.
 */
internal fun mergeCredits(
    meta: Meta,
    credits: com.nuvio.tv.data.repository.TitleCredits
): Pair<List<MetaCastMember>, List<MetaCastMember>> {
    val (addonCast, addonCrew) = meta.castAndCrew()
    fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    val tmdbByName = (credits.cast + credits.crew).groupBy { key(it.name) }.mapValues { it.value.first() }
    fun fill(m: MetaCastMember): MetaCastMember {
        if (m.tmdbId != null && m.photo != null) return m
        val match = tmdbByName[key(m.name)] ?: return m
        return m.copy(tmdbId = m.tmdbId ?: match.tmdbId, photo = m.photo ?: match.photo)
    }
    val cast = if (addonCast.isNotEmpty()) addonCast.map(::fill) else credits.cast
    val leads = addonCrew.map(::fill)
    val leadNames = leads.map { key(it.name) }.toSet()
    val crew = leads + credits.crew.filter { key(it.name) !in leadNames }
    return cast to crew
}
