package com.nuvio.tv.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.newlook.Glide
import com.nuvio.tv.ui.newlook.glassSurface
import com.nuvio.tv.ui.newlook.home.AmbientPageWash
import com.nuvio.tv.ui.newlook.home.Artwork
import com.nuvio.tv.ui.newlook.home.DetailsLine
import com.nuvio.tv.ui.newlook.home.HeroSlide
import com.nuvio.tv.ui.newlook.home.HeroWindow
import com.nuvio.tv.ui.newlook.home.LocalNewLookCardRadius
import com.nuvio.tv.ui.newlook.home.NewLookCard
import com.nuvio.tv.ui.newlook.home.NewLookDimens
import com.nuvio.tv.ui.newlook.home.PinToStart
import com.nuvio.tv.ui.newlook.home.PosterCard
import com.nuvio.tv.ui.newlook.home.SkeletonCard
import com.nuvio.tv.ui.newlook.home.WatchedMark
import com.nuvio.tv.ui.screens.browse.BrowseInfoPanel
import com.nuvio.tv.ui.screens.browse.BrowsePill
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.key
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.localizedContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The folder's sides: rows start here, as Home's do beside its sidebar, and the hero lines up with them. */
private val FolderStart = 48.dp
private val FolderEnd = 40.dp
private val FolderTop = 22.dp
private val FolderHeroHeight = 290.dp
/** Rows: where a focused row's title rests, so its cards and details are on screen with what's above peeking. */
private val FocusedRowTop = 214.dp
private const val HERO_SLIDES = 6
private const val HERO_ADVANCE_MS = 9_000L
/** Load the next page once focus is this close to a row's end. */
private const val LOAD_MORE_AHEAD = 6

/**
 * A collection folder in the new look, laid out as the collection's view mode says: rows (a small Home: a hero of
 * the folder's titles, then a row per source with Home's cards, which open out on focus) or tabs (a tab per source
 * over a grid of its posters, with the focused title's details beside it). The page takes on the colours of
 * whatever has focus.
 */
@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onWatchNow: (String, String, String) -> Unit = onNavigateToDetail,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val folder = uiState.folder

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        return
    }
    if (folder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.folder_detail_not_found), color = NuvioTheme.colors.TextSecondary)
        }
        return
    }

    val isItemWatched: (MetaPreview) -> Boolean = remember(uiState.movieWatchedStatus) {
        { item -> uiState.movieWatchedStatus[com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val dims = remember(uiState.posterCardCornerRadiusDp) {
        NewLookDimens.of(cornerRadiusDp = uiState.posterCardCornerRadiusDp)
    }
    val open: (MetaPreview, String) -> Unit = { item, addon ->
        HeroBackdropState.update(item.backdropUrl)
        onNavigateToDetail(item.id, item.apiType, addon)
    }

    var washUrl by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        AmbientPageWash(url = washUrl, modifier = Modifier.fillMaxSize())
        CompositionLocalProvider(LocalNewLookCardRadius provides dims.cardRadius) {
            if (uiState.viewMode == FolderViewMode.TABBED_GRID) {
                FolderTabs(
                    uiState = uiState,
                    folder = folder,
                    viewModel = viewModel,
                    dims = dims,
                    isItemWatched = isItemWatched,
                    onWash = { washUrl = it },
                    onOpen = open
                )
            } else {
                FolderRows(
                    uiState = uiState,
                    folder = folder,
                    viewModel = viewModel,
                    dims = dims,
                    isItemWatched = isItemWatched,
                    onWash = { washUrl = it },
                    onOpen = open,
                    onWatchNow = { item, addon ->
                        HeroBackdropState.update(item.backdropUrl)
                        onWatchNow(item.id, item.apiType, addon)
                    }
                )
            }
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl -> onNavigateToDetail(id, type, addonBaseUrl) }
    )
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRows(
    uiState: FolderDetailUiState,
    folder: CollectionFolder,
    viewModel: FolderDetailViewModel,
    dims: NewLookDimens,
    isItemWatched: (MetaPreview) -> Boolean,
    onWash: (String?) -> Unit,
    onOpen: (MetaPreview, String) -> Unit,
    onWatchNow: (MetaPreview, String) -> Unit
) {
    val context = LocalContext.current
    val focusState by viewModel.rowsFocusState.collectAsStateWithLifecycle()
    val openDelayMs = remember { BetaFeatures.settings(context).posterOpenDelayMs.toLong() }
    val scope = rememberCoroutineScope()

    // One row per source; a source that came back empty is left out.
    val rows = uiState.tabs.filter { tab ->
        !tab.isAllTab && (tab.isLoading || tab.error != null || tab.catalogRow?.items?.isNotEmpty() == true)
    }

    // The hero: the first few titles with art, taken in turn from each source so every source has a say.
    val heroItems = remember(rows.map { it.catalogRow?.items?.size }) {
        val lists = rows.mapNotNull { row -> row.catalogRow?.items?.filter { !it.background.isNullOrBlank() } }
        buildList<MetaPreview> {
            var i = 0
            while (size < HERO_SLIDES && lists.any { i < it.size }) {
                for (list in lists) {
                    val item = list.getOrNull(i) ?: continue
                    if (size < HERO_SLIDES && none { it.id == item.id }) add(item)
                }
                i++
            }
        }
    }
    val slides = remember(heroItems) { heroItems.map { it.toHeroSlide(context) } }
    var slide by remember { mutableIntStateOf(0) }
    if (slide >= slides.size) slide = 0
    var heroButton by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(slides.size, heroButton) {
        // Turns by itself, but holds still while one of its pills has focus.
        if (slides.size < 2 || heroButton != null) return@LaunchedEffect
        while (true) {
            delay(HERO_ADVANCE_MS)
            slide = (slide + 1) % slides.size
        }
    }

    var focusedItem by remember { mutableStateOf<MetaPreview?>(null) }
    val washUrl = focusedItem?.backdropUrl ?: slides.getOrNull(slide)?.backdrop
    LaunchedEffect(washUrl) { onWash(washUrl) }

    val watchFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val itemRequesters = remember { HashMap<String, FocusRequester>() }
    fun requester(rowKey: String, index: Int) = itemRequesters.getOrPut("$rowKey#$index") { FocusRequester() }
    val columnState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (focusState.hasSavedFocus) focusState.verticalScrollIndex else 0,
        initialFirstVisibleItemScrollOffset = if (focusState.hasSavedFocus) focusState.verticalScrollOffset else 0
    )
    val rowStates = remember { HashMap<String, LazyListState>() }
    val itemsBeforeRows = 1 + if (slides.isNotEmpty()) 1 else 0
    val rowTopPx = with(LocalDensity.current) { FocusedRowTop.roundToPx() }

    /** The page scrolls only where the folder says, as Home's does: the top for the hero, a set place for a row. */
    fun pinRow(rowIndex: Int) {
        val lazyIndex = itemsBeforeRows + rowIndex
        scope.launch {
            val info = columnState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
            if (info != null) {
                val distance = (info.offset - rowTopPx).toFloat()
                if (kotlin.math.abs(distance) > 1f) columnState.animateScrollBy(distance, Glide.scroll)
            } else {
                columnState.scrollToItem(lazyIndex, -rowTopPx)
            }
        }
    }
    fun showTop() {
        scope.launch {
            if (columnState.firstVisibleItemIndex > 0 || columnState.firstVisibleItemScrollOffset > 0) {
                columnState.animateScrollToItem(0)
            }
        }
    }

    // Opening: the hero's Watch Now (or the first card when nothing has art). Back from a title: the card it was on.
    val firstRowKey = rows.firstOrNull()?.catalogRow?.key()
    LaunchedEffect(Unit) {
        val saved = focusState.focusedRowKey?.takeIf { focusState.hasSavedFocus }
        repeat(40) {
            withFrameNanos { }
            val target = when {
                saved != null -> itemRequesters["$saved#${focusState.focusedItemIndex}"]
                slides.isNotEmpty() -> watchFocus
                firstRowKey != null -> itemRequesters["$firstRowKey#0"]
                else -> null
            }
            if (target != null && runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        // The saved card is gone (the source changed): start from the top instead.
        runCatching {
            if (slides.isNotEmpty()) watchFocus.requestFocus() else firstRowKey?.let { itemRequesters["$it#0"]?.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
        LazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(top = FolderTop, bottom = 320.dp)
        ) {
            item(key = "header", contentType = "header") {
                FolderTitle(
                    folder = folder,
                    collectionTitle = uiState.collectionTitle,
                    modifier = Modifier.padding(start = FolderStart, end = FolderEnd, bottom = 14.dp)
                )
            }
            if (slides.isNotEmpty()) {
                item(key = "hero", contentType = "hero") {
                    val heroItem = heroItems.getOrNull(slide)
                    HeroWindow(
                        slides = slides,
                        index = slide,
                        focusedButton = heroButton,
                        watchFocus = watchFocus,
                        moreFocus = moreFocus,
                        trailerUrl = null,
                        trailerAudioUrl = null,
                        muted = true,
                        onTrailerEnded = {},
                        onButtonFocused = {
                            heroButton = it
                            focusedItem = null
                            showTop()
                        },
                        onWatchNow = { heroItem?.let { onWatchNow(it, addonFor(rows, it)) } },
                        onMoreInfo = { heroItem?.let { onOpen(it, addonFor(rows, it)) } },
                        modifier = Modifier
                            .padding(start = FolderStart, end = FolderEnd, bottom = 26.dp)
                            .fillMaxWidth()
                            .height(FolderHeroHeight)
                            // Focus leaving the pills: the hero turns again.
                            .onFocusChanged { if (!it.hasFocus) heroButton = null }
                            // Left from Watch Now and Right from More Info turn the carousel, as on Home.
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || slides.size < 2) return@onPreviewKeyEvent false
                                when {
                                    event.key == Key.DirectionLeft && heroButton == 0 -> {
                                        slide = (slide - 1 + slides.size) % slides.size
                                        true
                                    }
                                    event.key == Key.DirectionRight && heroButton == 1 -> {
                                        slide = (slide + 1) % slides.size
                                        true
                                    }
                                    else -> false
                                }
                            }
                    )
                }
            }
            rows.forEachIndexed { rowIndex, tab ->
                val catalogRow = tab.catalogRow
                val rowKey = catalogRow?.key() ?: "row_${rowIndex}_${tab.label}"
                item(key = "row_$rowKey", contentType = "row") {
                    val listState = rowStates.getOrPut(rowKey) {
                        // Back from a title: the row it came from starts on its card, so the card can take focus.
                        val start = if (focusState.hasSavedFocus && focusState.focusedRowKey == rowKey) {
                            focusState.focusedItemIndex
                        } else {
                            focusState.catalogRowScrollStates[rowKey] ?: 0
                        }
                        LazyListState(firstVisibleItemIndex = start)
                    }
                    FolderRow(
                        title = rowTitle(tab, uiState.catalogTypeSuffixEnabled, context),
                        tab = tab,
                        rowKey = rowKey,
                        dims = dims,
                        openDelayMs = openDelayMs,
                        listState = listState,
                        requester = ::requester,
                        isItemWatched = isItemWatched,
                        onFocused = { index, item ->
                            focusedItem = item
                            pinRow(rowIndex)
                            viewModel.onItemFocused(item)
                            viewModel.saveRowsFocusState(
                                verticalScrollIndex = itemsBeforeRows + rowIndex,
                                verticalScrollOffset = -rowTopPx,
                                focusedRowKey = rowKey,
                                focusedItemKeyByRow = mapOf(rowKey to item.id),
                                catalogRowScrollStates = rowStates.mapValues { it.value.firstVisibleItemIndex },
                                catalogRowScrollAnchors = emptyMap(),
                                focusedRowIndex = rowIndex,
                                focusedItemIndex = index
                            )
                            val row = tab.catalogRow
                            if (row != null && row.hasMore && !row.isLoading && index >= row.items.size - LOAD_MORE_AHEAD) {
                                viewModel.loadMoreForCatalog(row.catalogId, row.addonId, row.apiType)
                            }
                        },
                        onClick = { item -> onOpen(item, catalogRow?.addonBaseUrl.orEmpty()) },
                        onLongClick = { item -> viewModel.posterOptions.show(item, catalogRow?.addonBaseUrl.orEmpty()) }
                    )
                }
            }
        }
    }
}

/** The page only scrolls where the folder says; focus moving never drags it. */
@OptIn(ExperimentalFoundationApi::class)
private val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * One source as a Home row: its name, then posters that open out after the expand delay while focused, with the
 * focused title's details under the row. The focused card is kept at the row's start, as on Home.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    title: String,
    tab: FolderTab,
    rowKey: String,
    dims: NewLookDimens,
    openDelayMs: Long,
    listState: LazyListState,
    requester: (String, Int) -> FocusRequester,
    isItemWatched: (MetaPreview) -> Boolean,
    onFocused: (Int, MetaPreview) -> Unit,
    onClick: (MetaPreview) -> Unit,
    onLongClick: (MetaPreview) -> Unit
) {
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(focusedIndex) {
        openIndex = null
        val index = focusedIndex ?: return@LaunchedEffect
        if (openDelayMs > 0) delay(openDelayMs)
        openIndex = index
    }
    val titles = tab.catalogRow?.items.orEmpty()
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = dims.rowSpace)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = FolderStart, end = FolderEnd, bottom = 10.dp)
        )
        val startPx = with(LocalDensity.current) { FolderStart.toPx() }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(startPx) { PinToStart(startPx) }) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(dims.posterHeight).focusRestorer(),
                contentPadding = PaddingValues(start = FolderStart, end = FolderEnd),
                horizontalArrangement = Arrangement.spacedBy(dims.gap)
            ) {
                when {
                    tab.isLoading && titles.isEmpty() -> items(6) { index ->
                        SkeletonCard(
                            width = dims.posterWidth,
                            height = dims.posterHeight,
                            onFocusChanged = {},
                            modifier = if (index == 0) Modifier.focusRequester(requester(rowKey, 0)) else Modifier
                        )
                    }
                    tab.error != null && titles.isEmpty() -> item {
                        Box(modifier = Modifier.height(dims.posterHeight), contentAlignment = Alignment.CenterStart) {
                            Text(text = tab.error, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                    else -> itemsIndexed(titles, key = { index, item -> "${item.id}_$index" }) { index, item ->
                        PosterCard(
                            poster = item.poster,
                            posterFallback = item.rawPosterUrl,
                            backdrop = item.background ?: item.landscapePoster,
                            logo = item.logo,
                            title = item.name,
                            watched = isItemWatched(item),
                            open = openIndex == index && focusedIndex == index,
                            dims = dims,
                            onClick = { onClick(item) },
                            onLongClick = { onLongClick(item) },
                            onFocusChanged = { focused ->
                                if (focused) {
                                    focusedIndex = index
                                    onFocused(index, item)
                                } else if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                            },
                            modifier = Modifier.focusRequester(requester(rowKey, index))
                        )
                    }
                }
            }
        }
        // The focused title's details, under its row; the space is kept so rows don't jump.
        Box(modifier = Modifier.height(dims.posterMeta).padding(start = FolderStart, top = 8.dp)) {
            val item = focusedIndex?.let { titles.getOrNull(it) }
            if (item != null) {
                val context = LocalContext.current
                DetailsLine(
                    type = localizedContentType(context, item.apiType),
                    genres = item.genres,
                    year = item.releaseInfo,
                    runtime = item.runtime,
                    rating = item.imdbRating?.takeIf { it > 0f }?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    ratingIsImdb = true
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------------------------

/**
 * Tabbed: the folder's name over the focused title's details on the left; on the right a tab per source (and All,
 * when the collection has it) in a glass capsule, over a grid of that source's posters in Home's cards.
 */
@Composable
private fun FolderTabs(
    uiState: FolderDetailUiState,
    folder: CollectionFolder,
    viewModel: FolderDetailViewModel,
    dims: NewLookDimens,
    isItemWatched: (MetaPreview) -> Boolean,
    onWash: (String?) -> Unit,
    onOpen: (MetaPreview, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = uiState.tabs
    val selected = uiState.selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val tab = tabs.getOrNull(selected)
    val titles = tab?.catalogRow?.items.orEmpty()

    val tabRequesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val itemRequesters = remember(selected) { HashMap<Int, FocusRequester>() }
    fun requester(index: Int) = itemRequesters.getOrPut(index) { FocusRequester() }
    val restoreIndex = viewModel.tabFocusIndex[selected] ?: 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = restoreIndex)
    var gridHasFocus by remember { mutableStateOf(false) }
    var focusedArt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedArt, titles.firstOrNull()?.id) { onWash(focusedArt ?: titles.firstOrNull()?.backdropUrl) }

    // Another tab: its grid starts where it was left (the top for one not visited yet).
    var shownTab by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (shownTab != selected) {
            shownTab = selected
            gridState.scrollToItem(viewModel.tabFocusIndex[selected] ?: 0)
        }
    }

    // Opening, or back from a title: the poster last focused in this tab (the tab itself while it loads).
    LaunchedEffect(Unit) {
        repeat(40) {
            withFrameNanos { }
            val target = itemRequesters[restoreIndex] ?: tabRequesters.getOrNull(selected)
            if (target != null && runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    // Back from the grid goes to the tabs first, then leaves.
    BackHandler(enabled = gridHasFocus && tabs.size > 1) {
        runCatching { tabRequesters.getOrNull(selected)?.requestFocus() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = 230.dp
        val gap = 24.dp
        val gridWidth = maxWidth - FolderStart - panelWidth - gap - FolderEnd
        val columns = ((gridWidth + dims.gap) / (dims.posterWidth + dims.gap)).toInt().coerceAtLeast(3)
        val cardWidth = (gridWidth - dims.gap * (columns - 1)) / columns
        Row(modifier = Modifier.fillMaxSize().padding(start = FolderStart, end = FolderEnd, top = FolderTop)) {
            Column(modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                FolderTitle(folder = folder, collectionTitle = uiState.collectionTitle, modifier = Modifier.padding(bottom = 4.dp))
                BrowseInfoPanel(
                    details = viewModel.focusedDetails.details,
                    modifier = Modifier.padding(bottom = 24.dp).fillMaxHeight()
                )
            }
            Spacer(modifier = Modifier.width(gap))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (tabs.size > 1) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = gridWidth)
                            .glassSurface(RoundedCornerShape(18.dp), glints = false, shadow = false)
                            .padding(3.dp)
                            .horizontalScroll(rememberScrollState())
                            .focusRestorer(tabRequesters.getOrNull(selected) ?: FocusRequester.Default),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        tabs.forEachIndexed { index, t ->
                            BrowsePill(
                                text = if (t.isAllTab) stringResource(R.string.collections_tab_all) else rowTitle(t, uiState.catalogTypeSuffixEnabled, context),
                                selected = index == selected,
                                outlined = false,
                                modifier = Modifier.focusRequester(tabRequesters[index]),
                                // Resting on a tab shows it, as a TV tab row does.
                                onFocused = {
                                    focusedArt = null
                                    viewModel.selectTab(index)
                                },
                                onClick = {
                                    viewModel.tabFocusIndex[index] = 0
                                    scope.launch {
                                        gridState.scrollToItem(0)
                                        withFrameNanos { }
                                        runCatching { requester(0).requestFocus() }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
                when {
                    tab == null -> Unit
                    tab.isLoading && titles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    tab.error != null && titles.isEmpty() -> Text(text = tab.error, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { gridHasFocus = it.hasFocus }
                            .focusRestorer(requester(viewModel.tabFocusIndex[selected] ?: 0)),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(dims.gap),
                        verticalArrangement = Arrangement.spacedBy(dims.gap + 4.dp)
                    ) {
                        items(titles.size, key = { index -> "${titles[index].id}_$index" }) { index ->
                            val item = titles[index]
                            FolderGridCard(
                                item = item,
                                width = cardWidth,
                                watched = isItemWatched(item),
                                modifier = Modifier.focusRequester(requester(index)),
                                onClick = { onOpen(item, tab.catalogRow?.addonBaseUrl.orEmpty()) },
                                onLongClick = { viewModel.posterOptions.show(item, tab.catalogRow?.addonBaseUrl.orEmpty()) },
                                onFocused = {
                                    viewModel.tabFocusIndex[selected] = index
                                    focusedArt = item.backdropUrl
                                    viewModel.focusedDetails.onFocused(item)
                                    viewModel.onItemFocused(item)
                                    val row = tab.catalogRow
                                    if (row != null && row.hasMore && !row.isLoading && index >= titles.size - columns * 2) {
                                        viewModel.loadMoreItems(selected)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A poster in the tabbed grid: Home's card, without opening out (a grid has no room for it). */
@Composable
private fun FolderGridCard(
    item: MetaPreview,
    width: Dp,
    watched: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit
) {
    NewLookCard(
        width = width,
        height = width * 1.5f,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocusChanged = { if (it) onFocused() },
        modifier = modifier,
        depth = com.nuvio.tv.domain.model.CardDepthSurface.POSTERS
    ) {
        Artwork(item.poster ?: item.backdropUrl, Modifier.fillMaxSize(), fallbackTitle = item.name, fallbackUrl = item.rawPosterUrl)
        if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------

/** The folder's cover (image or emoji) and name, with the collection it belongs to above it. */
@Composable
private fun FolderTitle(folder: CollectionFolder, collectionTitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else if (!folder.coverEmoji.isNullOrBlank()) {
            Text(text = folder.coverEmoji, fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column {
            if (collectionTitle.isNotBlank()) {
                Text(
                    text = collectionTitle.uppercase(),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    maxLines = 1
                )
            }
            Text(
                text = folder.title,
                style = androidx.compose.ui.text.TextStyle(textDirection = folder.title.contentTextDirection()),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A source's name, with its type when the collection shows types. */
private fun rowTitle(tab: FolderTab, typeSuffix: Boolean, context: android.content.Context): String {
    val type = localizedContentType(context, tab.rawType)
    return if (typeSuffix && tab.label != tab.typeLabel && type.isNotEmpty()) "${tab.label} · $type" else tab.label
}

/** The addon a hero title came from, for its details page to ask first. */
private fun addonFor(rows: List<FolderTab>, item: MetaPreview): String =
    rows.firstOrNull { tab -> tab.catalogRow?.items?.any { it.id == item.id } == true }?.catalogRow?.addonBaseUrl.orEmpty()

private fun MetaPreview.toHeroSlide(context: android.content.Context): HeroSlide = HeroSlide(
    key = id,
    title = name,
    backdrop = backdropUrl,
    logo = logo,
    typeLabel = localizedContentType(context, apiType),
    genre = genres.firstOrNull(),
    year = releaseInfo,
    rating = imdbRating?.takeIf { it > 0f }?.let { String.format(java.util.Locale.US, "%.1f", it) },
    ratingIsImdb = true,
    description = description,
    tag = null,
    tagSeason = null
)
