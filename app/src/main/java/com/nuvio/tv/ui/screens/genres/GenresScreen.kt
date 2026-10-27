@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.genres

import com.nuvio.tv.ui.newlook.LocalNewLookChrome
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.ui.screens.browse.browseCardWidthToFit
import com.nuvio.tv.ui.screens.browse.browseGridWidth
import com.nuvio.tv.ui.screens.browse.BrowseHeader
import com.nuvio.tv.ui.screens.browse.BrowseInfoPanel
import com.nuvio.tv.ui.screens.browse.BrowseListRow
import com.nuvio.tv.ui.screens.browse.BrowsePill
import com.nuvio.tv.ui.screens.browse.BrowsePosterGrid
import com.nuvio.tv.ui.screens.browse.browseSortLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/** Which column has focus; it decides what slides in and out, like WuPlay's genre browser. */
private enum class GenresZone { GENRES, ENTRIES, GRID }

private val ScreenStart = 48.dp
/** How much nearer the edge the screen starts when there's no sidebar column (Home: 106 → 24 dp from the edge). */
private val FULL_WIDTH_SHIFT = 24.dp
private val GenreColumnWidth = 130.dp
/** Wide enough for the Movies / Shows / Anime / All pills. */
private val EntryColumnWidth = 236.dp
private val ColumnGap = 8.dp
private val GridGapFromList = 16.dp
private val GridStartWide = 280.dp
private val TopInset = 18.dp

@Composable
fun GenresScreen(
    viewModel: GenresViewModel = hiltViewModel(),
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var zone by rememberSaveable { mutableStateOf(GenresZone.GENRES) }
    val scope = rememberCoroutineScope()
    // A Kitsu title may need a quick lookup before it opens; ignore repeat presses meanwhile.
    var openingItem by remember { mutableStateOf(false) }

    val genreFocus = remember { FocusRequester() }
    val entryFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val restoreItemFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val genreScroll = rememberScrollState()
    val entryScroll = rememberScrollState()

    fun focusAfterLayout(requester: FocusRequester, fallback: FocusRequester? = null) {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (runCatching { requester.requestFocus() }.isFailure && fallback != null) {
                runCatching { fallback.requestFocus() }
            }
        }
    }

    // Content version the grid last had focus in: coming back to the same titles returns to the
    // poster you left, new titles start at the first one.
    var gridVisitVersion by rememberSaveable { mutableIntStateOf(-1) }
    fun enterGrid() {
        val committed = viewModel.commitPendingSelection()
        val current = viewModel.uiState.value
        if (current.items.isEmpty() || current.isLoading) return
        val sameTitles = !committed && current.contentVersion == gridVisitVersion
        gridVisitVersion = current.contentVersion
        val target = if (sameTitles) gridFocus else firstItemFocus
        if (committed) {
            // The new posters are composed on the next frame; move in once they are.
            focusAfterLayout(target, fallback = gridFocus)
        } else if (runCatching { target.requestFocus() }.isFailure) {
            runCatching { gridFocus.requestFocus() }
        }
    }

    // Scroll the grid back to the top whenever its contents are replaced, but not when the screen
    // is merely recomposed after coming back from a details page.
    var seenContentVersion by rememberSaveable { mutableIntStateOf(state.contentVersion) }
    LaunchedEffect(state.contentVersion) {
        if (state.contentVersion != seenContentVersion) {
            seenContentVersion = state.contentVersion
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        when (zone) {
            GenresZone.GENRES -> focusAfterLayout(genreFocus)
            GenresZone.ENTRIES -> focusAfterLayout(entryFocus)
            GenresZone.GRID -> focusAfterLayout(restoreItemFocus, fallback = gridFocus)
        }
    }

    BackHandler(enabled = zone != GenresZone.GENRES) {
        when (zone) {
            GenresZone.GRID -> {
                if (gridState.firstVisibleItemIndex > 0 || viewModel.lastFocusedItemIndex > 0) {
                    scope.launch {
                        gridState.scrollToItem(0)
                        repeat(2) { withFrameNanos { } }
                        runCatching { firstItemFocus.requestFocus() }
                    }
                } else {
                    zone = GenresZone.ENTRIES
                    focusAfterLayout(entryFocus)
                }
            }
            GenresZone.ENTRIES -> {
                if (state.selectedEntryIndex != 0) {
                    viewModel.selectEntry(0)
                    scope.launch { entryScroll.animateScrollTo(0) }
                    focusAfterLayout(entryFocus)
                } else {
                    zone = GenresZone.GENRES
                    focusAfterLayout(genreFocus)
                }
            }
            GenresZone.GENRES -> Unit
        }
    }

    val slide = tween<androidx.compose.ui.unit.Dp>(durationMillis = 240, easing = FastOutSlowInEasing)
    val fade = tween<Float>(durationMillis = 200)

    // Without a sidebar column (the menu hidden, or on top) the screen starts nearer the edge, as Home does, and the
    // grid takes the width that frees up.
    val edgeShift = if (LocalNewLookChrome.current?.let { it.menuOnTop || it.hideMenu } == true) FULL_WIDTH_SHIFT else 0.dp
    val screenStart = ScreenStart - edgeShift
    val gridStartWide = GridStartWide - edgeShift
    val genresX = animateDpAsState(
        targetValue = if (zone == GenresZone.GENRES) screenStart else screenStart - GenreColumnWidth,
        animationSpec = slide, label = "genresX"
    )
    val genresAlpha = animateFloatAsState(if (zone == GenresZone.GENRES) 1f else 0f, fade, label = "genresAlpha")
    val entriesX = animateDpAsState(
        targetValue = when (zone) {
            GenresZone.GENRES -> screenStart + GenreColumnWidth + ColumnGap
            GenresZone.ENTRIES -> screenStart
            GenresZone.GRID -> screenStart - EntryColumnWidth / 2
        },
        animationSpec = slide, label = "entriesX"
    )
    val entriesAlpha = animateFloatAsState(if (zone == GenresZone.GRID) 0f else 1f, fade, label = "entriesAlpha")
    val panelAlpha = animateFloatAsState(if (zone == GenresZone.GRID) 1f else 0f, fade, label = "panelAlpha")
    val gridX = animateDpAsState(
        targetValue = if (zone == GenresZone.GENRES) {
            screenStart + GenreColumnWidth + ColumnGap + EntryColumnWidth + GridGapFromList
        } else {
            gridStartWide
        },
        animationSpec = slide, label = "gridX"
    )

    val contentFocusRequester = LocalContentFocusRequester.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .focusRequester(contentFocusRequester)
            .focusRestorer()
            .focusGroup()
    ) {
        // Column 1: genres.
        Column(
            modifier = Modifier
                .offset { IntOffset(genresX.value.roundToPx(), 0) }
                .graphicsLayer { alpha = genresAlpha.value }
                .width(GenreColumnWidth)
                .fillMaxHeight()
        ) {
            Text(
                text = stringResource(R.string.browse_genres_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = TopInset, bottom = 12.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(genreScroll)
                    .padding(bottom = NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                state.genres.forEachIndexed { index, genre ->
                    val selected = index == state.selectedGenreIndex
                    BrowseListRow(
                        text = stringResource(genre.labelRes),
                        selected = selected,
                        modifier = Modifier
                            .then(if (selected) Modifier.focusRequester(genreFocus) else Modifier)
                            .focusProperties { canFocus = zone == GenresZone.GENRES }
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                                    native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                                ) {
                                    runCatching { entryFocus.requestFocus() }
                                    zone = GenresZone.ENTRIES
                                    true
                                } else {
                                    false
                                }
                            },
                        onFocused = {
                            zone = GenresZone.GENRES
                            viewModel.selectGenre(index)
                        },
                        onClick = {
                            runCatching { entryFocus.requestFocus() }
                            zone = GenresZone.ENTRIES
                        }
                    )
                }
            }
        }

        // Column 2: Movies / Shows / Anime / All, then sorts and keywords.
        Column(
            modifier = Modifier
                .offset { IntOffset(entriesX.value.roundToPx(), 0) }
                .graphicsLayer { alpha = entriesAlpha.value }
                .width(EntryColumnWidth)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.padding(top = TopInset - 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val modes = buildList {
                    add(BrowseMediaMode.MOVIES to R.string.browse_mode_movies)
                    add(BrowseMediaMode.SHOWS to R.string.browse_mode_shows)
                    // Anime narrows by original language, which only TMDB can do.
                    if (state.usesTmdb) add(BrowseMediaMode.ANIME to R.string.browse_mode_anime)
                    add(BrowseMediaMode.ALL to R.string.browse_mode_all)
                }
                modes.forEachIndexed { index, (mode, label) ->
                    BrowsePill(
                        text = stringResource(label),
                        selected = state.mode == mode,
                        modifier = Modifier
                            .focusProperties { canFocus = zone != GenresZone.GRID }
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                when (native.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        runCatching { entryFocus.requestFocus() }
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) {
                                        zone = GenresZone.GENRES
                                        focusAfterLayout(genreFocus)
                                        true
                                    } else {
                                        false
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> index == modes.lastIndex
                                    else -> false
                                }
                            },
                        onFocused = { zone = GenresZone.ENTRIES },
                        onClick = { viewModel.selectMode(mode) }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(entryScroll)
                    .padding(bottom = NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                state.entries.forEachIndexed { index, entry ->
                    val selected = index == state.selectedEntryIndex
                    BrowseListRow(
                        text = when (entry) {
                            is GenreEntry.Sort -> browseSortLabel(entry.sort)
                            is GenreEntry.Keyword -> entry.keyword.label
                        },
                        selected = selected,
                        modifier = Modifier
                            .then(if (selected) Modifier.focusRequester(entryFocus) else Modifier)
                            .focusProperties { canFocus = zone != GenresZone.GRID }
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                when (native.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        zone = GenresZone.GENRES
                                        focusAfterLayout(genreFocus)
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        enterGrid()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        onFocused = {
                            zone = GenresZone.ENTRIES
                            viewModel.selectEntry(index)
                        },
                        onClick = { enterGrid() }
                    )
                }
                if (!state.usesTmdb) {
                    Text(
                        text = stringResource(R.string.browse_tmdb_key_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.md)
                    )
                }
            }
        }

        // Details of the focused poster, in place of the lists while browsing the grid.
        BrowseInfoPanel(
            details = viewModel.focusedDetails.details,
            modifier = Modifier
                .offset { IntOffset(24.dp.roundToPx(), 0) }
                .graphicsLayer { alpha = panelAlpha.value }
                .padding(top = 28.dp, bottom = 28.dp)
                .fillMaxHeight()
        )

        // Grid: header and posters. Wider than what is left of the screen while all three
        // columns show, so it slides rather than reflowing. Posters are as wide as fits at the grid's
        // own place, like Search: 128 dp with the classic sidebar, a little narrower in the new look.
        val cardWidth = browseCardWidthToFit(maxWidth - gridStartWide - 8.dp)
        Column(
            modifier = Modifier
                .offset { IntOffset(gridX.value.roundToPx(), 0) }
                .width(browseGridWidth(cardWidth) + 8.dp)
                .fillMaxHeight()
        ) {
            val genreLabel = state.selectedGenre?.let { stringResource(it.labelRes) }
            val entryLabel = when (val entry = state.selectedEntry) {
                is GenreEntry.Sort -> browseSortLabel(entry.sort)
                is GenreEntry.Keyword -> entry.keyword.label
                null -> null
            }
            BrowseHeader(
                title = entryLabel,
                subtitle = genreLabel,
                modifier = Modifier.padding(start = 4.dp, top = TopInset - 4.dp, bottom = 4.dp)
            )
            if (state.hasError && state.items.isEmpty()) {
                Column(modifier = Modifier.padding(start = 4.dp, top = NuvioTheme.spacing.xl)) {
                    Text(
                        text = stringResource(R.string.browse_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                    BrowsePill(
                        text = stringResource(R.string.browse_retry),
                        selected = false,
                        onClick = { viewModel.retry() }
                    )
                }
            } else {
                BrowsePosterGrid(
                    items = state.items,
                    isLoading = state.isLoading,
                    isLoadingMore = state.isLoadingMore,
                    canLoadMore = state.canLoadMore,
                    gridState = gridState,
                    focusRequester = gridFocus,
                    firstItemFocusRequester = firstItemFocus,
                    restoreIndex = viewModel.lastFocusedItemIndex,
                    restoreFocusRequester = restoreItemFocus,
                    onLoadMore = viewModel::loadMore,
                    onItemClick = { item ->
                        if (!openingItem) {
                            openingItem = true
                            scope.launch {
                                try {
                                    val target = viewModel.resolveForDetails(item)
                                    onNavigateToDetail(target.id, target.apiType, target.sourceAddonBaseUrl)
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    // A failed lookup or navigation must not take the app down with it.
                                    android.util.Log.w("Browse", "Opening a title failed: ${error.javaClass.simpleName}")
                                } finally {
                                    openingItem = false
                                }
                            }
                        }
                    },
                    onItemFocused = { index, item ->
                        zone = GenresZone.GRID
                        viewModel.onItemFocused(index, item)
                    },
                    onLeftFromFirstColumn = {
                        zone = GenresZone.ENTRIES
                        focusAfterLayout(entryFocus)
                    },
                    emptyText = if (state.isLoading) null else stringResource(R.string.browse_empty),
                    cardWidth = cardWidth,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}
