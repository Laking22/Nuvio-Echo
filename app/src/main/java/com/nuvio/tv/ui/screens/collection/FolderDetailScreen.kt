package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaFeatures
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.newlook.home.AmbientPageWash
import com.nuvio.tv.ui.newlook.home.DetailsLine
import com.nuvio.tv.ui.newlook.home.HeroSlide
import com.nuvio.tv.ui.newlook.home.HeroWindow
import com.nuvio.tv.ui.newlook.home.LocalNewLookCardRadius
import com.nuvio.tv.ui.newlook.home.NewLookDimens
import com.nuvio.tv.ui.newlook.home.PinToStart
import com.nuvio.tv.ui.newlook.home.PosterCard
import com.nuvio.tv.ui.newlook.home.SkeletonCard
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.key
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.localizedContentType
import kotlinx.coroutines.delay

/** The folder's sides: rows start here, as Home's do beside its sidebar, and the hero lines up with them. */
private val FolderStart = 48.dp
private val FolderEnd = 40.dp
private val FolderHeroHeight = 290.dp
private const val HERO_SLIDES = 6
private const val HERO_ADVANCE_MS = 9_000L
/** Load the next page once focus is this close to a row's end. */
private const val LOAD_MORE_AHEAD = 6

/**
 * A collection folder as a small Home: the folder's name, a hero of its titles that turns every few seconds, then a
 * row per source with Home's cards, which open out to their backdrop and logo when focus rests on them. The page
 * takes on the colours of whatever has focus.
 */
@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onWatchNow: (String, String, String) -> Unit = onNavigateToDetail,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusState by viewModel.rowsFocusState.collectAsStateWithLifecycle()
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

    val context = LocalContext.current
    val isItemWatched: (MetaPreview) -> Boolean = remember(uiState.movieWatchedStatus) {
        { item -> uiState.movieWatchedStatus[com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val dims = remember(uiState.posterCardCornerRadiusDp) {
        NewLookDimens.of(cornerRadiusDp = uiState.posterCardCornerRadiusDp)
    }
    val openDelayMs = remember { BetaFeatures.settings(context).posterOpenDelayMs.toLong() }

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

    val watchFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val itemRequesters = remember { HashMap<String, FocusRequester>() }
    fun requester(rowKey: String, index: Int) = itemRequesters.getOrPut("$rowKey#$index") { FocusRequester() }
    val columnState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (focusState.hasSavedFocus) focusState.verticalScrollIndex else 0,
        initialFirstVisibleItemScrollOffset = if (focusState.hasSavedFocus) focusState.verticalScrollOffset else 0
    )
    val rowStates = remember { HashMap<String, LazyListState>() }

    // Opening: the hero's Watch Now (or the first card when nothing has art). Back from a title: the card it was on.
    val firstRowKey = rows.firstOrNull()?.catalogRow?.key()
    LaunchedEffect(Unit) {
        repeat(30) {
            withFrameNanos { }
            val target = when {
                focusState.hasSavedFocus && focusState.focusedRowKey != null ->
                    itemRequesters["${focusState.focusedRowKey}#${focusState.focusedItemIndex}"]
                slides.isNotEmpty() -> watchFocus
                firstRowKey != null -> itemRequesters["$firstRowKey#0"]
                else -> null
            }
            if (target != null && runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        AmbientPageWash(url = washUrl, modifier = Modifier.fillMaxSize())
        CompositionLocalProvider(LocalNewLookCardRadius provides dims.cardRadius) {
            LazyColumn(
                state = columnState,
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(top = 22.dp, bottom = 160.dp)
            ) {
                item(key = "header", contentType = "header") {
                    FolderTitle(folder = folder, collectionTitle = uiState.collectionTitle)
                }
                if (slides.isNotEmpty()) {
                    item(key = "hero", contentType = "hero") {
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
                            onButtonFocused = { heroButton = it; focusedItem = null },
                            onWatchNow = {
                                heroItems.getOrNull(slide)?.let { item ->
                                    HeroBackdropState.update(item.backdropUrl)
                                    onWatchNow(item.id, item.apiType, addonFor(rows, item))
                                }
                            },
                            onMoreInfo = {
                                heroItems.getOrNull(slide)?.let { item ->
                                    HeroBackdropState.update(item.backdropUrl)
                                    onNavigateToDetail(item.id, item.apiType, addonFor(rows, item))
                                }
                            },
                            modifier = Modifier
                                .padding(start = FolderStart, end = FolderEnd, bottom = 26.dp)
                                .fillMaxWidth()
                                .height(FolderHeroHeight)
                                // Focus leaving the pills: the hero turns again.
                                .onFocusChanged { if (!it.hasFocus) heroButton = null }
                        )
                    }
                }
                rows.forEachIndexed { rowIndex, tab ->
                    val catalogRow = tab.catalogRow
                    val rowKey = catalogRow?.key() ?: "row_${rowIndex}_${tab.label}"
                    item(key = "row_$rowKey", contentType = "row") {
                        val listState = rowStates.getOrPut(rowKey) {
                            LazyListState(firstVisibleItemIndex = focusState.catalogRowScrollStates[rowKey] ?: 0)
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
                                viewModel.onItemFocused(item)
                                viewModel.saveRowsFocusState(
                                    verticalScrollIndex = columnState.firstVisibleItemIndex,
                                    verticalScrollOffset = columnState.firstVisibleItemScrollOffset,
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
                            onClick = { item ->
                                HeroBackdropState.update(item.backdropUrl)
                                onNavigateToDetail(item.id, item.apiType, catalogRow?.addonBaseUrl.orEmpty())
                            },
                            onLongClick = { item ->
                                viewModel.posterOptions.show(item, catalogRow?.addonBaseUrl.orEmpty())
                            }
                        )
                    }
                }
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

/** The folder's cover (image or emoji) and name, with the collection it belongs to above it. */
@Composable
private fun FolderTitle(folder: CollectionFolder, collectionTitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = FolderStart, end = FolderEnd, bottom = 14.dp),
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
