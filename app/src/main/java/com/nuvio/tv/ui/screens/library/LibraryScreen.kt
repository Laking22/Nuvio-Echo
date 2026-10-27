package com.nuvio.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private enum class LibraryTab { Bins, Cloud }

private sealed interface LibraryOverlay {
    data class Sort(val anchor: Rect) : LibraryOverlay
    data class BinOptions(val bin: Bin, val anchor: Rect) : LibraryOverlay
    data class TitleOptions(val bin: Bin, val title: BinTitle, val anchor: Rect) : LibraryOverlay
    data object Create : LibraryOverlay
    data class Rename(val bin: Bin) : LibraryOverlay
    data class Delete(val bin: Bin) : LibraryOverlay
}

/** Stable focus requesters by key ("pill:sort", "bin:<id>", "star:<id>", "title:<key>"). */
private class FocusKeys {
    private val map = HashMap<String, FocusRequester>()
    operator fun get(key: String): FocusRequester = map.getOrPut(key) { FocusRequester() }
}

private val ScreenPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp)
private val GridGap = 24.dp

/**
 * The Library: Bins (folders the viewer makes and names) and Cloud. Bins sort by the Sort pill or by hand
 * (Custom order: pick one, then pick another to swap them); the star marks the default bin.
 */
@Composable
fun LibraryScreen(
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onCloudPlaybackResolved: (CloudLibraryPlaybackInfo) -> Unit = {},
    binsViewModel: BinsViewModel = hiltViewModel(),
    cloudViewModel: CloudLibraryViewModel = hiltViewModel()
) {
    val state by binsViewModel.uiState.collectAsState()
    val message by binsViewModel.message.collectAsState()
    val cloudState by cloudViewModel.uiState.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Bins) }
    var overlay by remember { mutableStateOf<LibraryOverlay?>(null) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var sortBounds by remember { mutableStateOf(Rect.Zero) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    // After a swap, focus follows the moved bin to where it was put.
    var refocusAfterSwap by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusKeys() }
    val binsGrid = rememberLazyGridState()
    val titlesGrid = remember(state.openBin?.id) { LazyGridState() }
    val contentFocus = LocalContentFocusRequester.current

    fun local(rect: Rect): Rect = rect.translate(-rootOffset)
    fun dismissOverlay(returnTo: String?) {
        overlay = null
        if (returnTo != null) pendingFocus = returnTo
    }

    BackHandler(enabled = overlay == null && tab == LibraryTab.Bins && (state.reordering || state.openBin != null)) {
        if (!binsViewModel.backInReorder()) {
            val closing = state.openBin
            binsViewModel.closeBin()
            if (closing != null) pendingFocus = "bin:${closing.id}"
        }
    }

    // First focus once the bins are in: where it was (coming back from a title), else the first bin, else +.
    LaunchedEffect(state.loaded) {
        if (!state.loaded || pendingFocus != null) return@LaunchedEffect
        pendingFocus = lastFocused?.takeIf { tab == LibraryTab.Bins }
            ?: when {
                tab == LibraryTab.Cloud -> "pill:cloud"
                state.openBin != null -> state.openTitles.firstOrNull()?.let { "title:${it.entry.key}" } ?: "pill:sort"
                else -> state.bins.firstOrNull()?.let { "bin:${it.id}" } ?: "pill:plus"
            }
    }

    // Moves focus to [pendingFocus], scrolling its row into view first when it's off screen.
    LaunchedEffect(pendingFocus, state.bins, state.openTitles, tab) {
        val key = pendingFocus ?: return@LaunchedEffect
        val (grid, index) = when {
            key.startsWith("bin:") || key.startsWith("star:") ->
                binsGrid to state.bins.indexOfFirst { it.id == key.substringAfter(':') }.let { if (it < 0) -1 else it + 1 }
            key.startsWith("title:") ->
                titlesGrid to state.openTitles.indexOfFirst { it.entry.key == key.removePrefix("title:") }.let { if (it < 0) -1 else it + 1 }
            else -> null to -1
        }
        if (grid != null && index > 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            runCatching { grid.scrollToItem(index) }
        }
        repeat(8) {
            if (runCatching { focus[key].requestFocus() }.getOrDefault(false)) {
                pendingFocus = null
                return@LaunchedEffect
            }
            delay(32)
        }
        pendingFocus = null
    }

    LaunchedEffect(state.bins, state.openTitles) {
        val key = refocusAfterSwap ?: return@LaunchedEffect
        refocusAfterSwap = null
        pendingFocus = key
    }

    val sortLabel = sortLabel(state.sort)
    val header: @Composable () -> Unit = {
        LibraryHeader(
            tab = tab,
            state = state,
            sortLabel = sortLabel,
            focus = focus,
            cloudRefreshing = cloudState.cloudLibrary.isRefreshing,
            onTab = { selected ->
                if (selected != tab) {
                    tab = selected
                    pendingFocus = if (selected == LibraryTab.Cloud) "pill:cloud" else "pill:bins"
                } else if (selected == LibraryTab.Bins && state.openBin != null) {
                    val closing = state.openBin
                    binsViewModel.closeBin()
                    pendingFocus = closing?.let { "bin:${it.id}" }
                }
            },
            onSortBounds = { sortBounds = it },
            onSort = { overlay = LibraryOverlay.Sort(local(sortBounds)) },
            onCustomOrder = { binsViewModel.toggleReorder() },
            onCreate = { overlay = LibraryOverlay.Create },
            onRefresh = cloudViewModel::refresh,
            onFocused = { lastFocused = it }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onGloballyPositioned { rootOffset = it.positionInRoot() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                // Entering the screen (from the menu, or one of our own requests) lands on what's asked for, else where
                // focus last was, else the first bin. Read when focus enters, not when composed: focusRestorer's fixed
                // fallback was a frame stale and sent the menu's hand-off to the Bins pill.
                .focusProperties {
                    onEnter = {
                        val key = pendingFocus ?: lastFocused?.takeIf { tab == LibraryTab.Bins } ?: when {
                            tab == LibraryTab.Cloud -> "pill:cloud"
                            state.openBin != null -> state.openTitles.firstOrNull()?.let { "title:${it.entry.key}" } ?: "pill:sort"
                            else -> state.bins.firstOrNull()?.let { "bin:${it.id}" } ?: "pill:plus"
                        }
                        runCatching { focus[key].requestFocus() }
                    }
                }
                .focusGroup()
        ) {
            when {
                tab == LibraryTab.Cloud -> CloudLibraryContent(
                    viewModel = cloudViewModel,
                    header = header,
                    upFocusRequester = focus["pill:cloud"],
                    onPlaybackResolved = onCloudPlaybackResolved,
                    contentPadding = ScreenPadding,
                    modifier = Modifier.fillMaxSize()
                )
                state.openBin != null -> {
                    val bin = state.openBin!!
                    BinTitlesGrid(
                        bin = bin,
                        state = state,
                        gridState = titlesGrid,
                        header = header,
                        focus = focus,
                        onFocused = { lastFocused = it },
                        onClick = { title ->
                            if (state.reordering) {
                                val held = state.picked
                                binsViewModel.pick(title.entry.key)
                                if (held != null && held != title.entry.key) refocusAfterSwap = "title:$held"
                            } else {
                                binsViewModel.onTitleOpened(bin, title)
                                HeroBackdropState.update(binsViewModel.cachedBackdrop(title))
                                onNavigateToDetail(title.entry.id, title.entry.type, title.entry.addonBaseUrl)
                            }
                        },
                        onLongClick = { title, anchor ->
                            if (!state.reordering) overlay = LibraryOverlay.TitleOptions(bin, title, local(anchor))
                        },
                        onPrefetch = { binsViewModel.prefetch(it.entry) }
                    )
                }
                else -> BinsGrid(
                    state = state,
                    gridState = binsGrid,
                    header = header,
                    focus = focus,
                    onFocused = { lastFocused = it },
                    onClick = { bin ->
                        if (state.reordering) {
                            val held = state.picked
                            binsViewModel.pick(bin.id)
                            if (held != null && held != bin.id) refocusAfterSwap = "bin:$held"
                        } else {
                            binsViewModel.openBin(bin)
                            pendingFocus = state.bins.firstOrNull { it.id == bin.id }?.entries?.let { entries ->
                                com.nuvio.tv.core.bins.BinsReducer.sortedEntries(entries, state.sort).firstOrNull()?.key?.let { "title:$it" }
                            } ?: "pill:sort"
                        }
                    },
                    onLongClick = { bin, anchor ->
                        if (!state.reordering) overlay = LibraryOverlay.BinOptions(bin, local(anchor))
                    },
                    onStar = { binsViewModel.toggleStar(it) }
                )
            }
        }

        when (val current = overlay) {
            is LibraryOverlay.Sort -> BinPillMenu(
                anchor = current.anchor,
                title = null,
                items = BinSort.Menu.map { sort ->
                    BinMenuItem(sortLabelText(sort), checked = sort == state.sort) {
                        binsViewModel.selectSort(sort)
                        dismissOverlay("pill:sort")
                    }
                },
                initialIndex = BinSort.Menu.indexOf(state.sort).coerceAtLeast(0),
                openedByHold = false,
                onDismiss = { dismissOverlay("pill:sort") }
            )
            is LibraryOverlay.BinOptions -> {
                val bin = current.bin
                BinPillMenu(
                    anchor = current.anchor,
                    title = bin.name,
                    items = listOf(
                        BinMenuItem(stringResource(if (bin.starred) R.string.bins_menu_unstar else R.string.bins_menu_star)) {
                            binsViewModel.toggleStar(bin)
                            dismissOverlay("bin:${bin.id}")
                        },
                        BinMenuItem(stringResource(R.string.bins_menu_rename)) { overlay = LibraryOverlay.Rename(bin) },
                        BinMenuItem(stringResource(R.string.bins_menu_delete)) { overlay = LibraryOverlay.Delete(bin) }
                    ),
                    initialIndex = 0,
                    openedByHold = true,
                    onDismiss = { dismissOverlay("bin:${bin.id}") }
                )
            }
            is LibraryOverlay.TitleOptions -> BinPillMenu(
                anchor = current.anchor,
                title = current.title.entry.name,
                items = listOf(
                    BinMenuItem(stringResource(R.string.bins_title_open)) {
                        dismissOverlay(null)
                        binsViewModel.onTitleOpened(current.bin, current.title)
                        onNavigateToDetail(current.title.entry.id, current.title.entry.type, current.title.entry.addonBaseUrl)
                    },
                    BinMenuItem(stringResource(R.string.bins_title_remove, current.bin.name)) {
                        val titles = state.openTitles
                        val at = titles.indexOfFirst { it.entry.key == current.title.entry.key }
                        val next = titles.getOrNull(at + 1) ?: titles.getOrNull(at - 1)
                        binsViewModel.removeTitle(current.bin, current.title)
                        dismissOverlay(next?.let { "title:${it.entry.key}" } ?: "pill:sort")
                    }
                ),
                initialIndex = 0,
                openedByHold = true,
                onDismiss = { dismissOverlay("title:${current.title.entry.key}") }
            )
            LibraryOverlay.Create -> BinNameDialog(
                title = stringResource(R.string.bins_name_title),
                initial = "",
                confirmLabel = stringResource(R.string.bins_create_button),
                onConfirm = { name -> binsViewModel.createBin(name) },
                onDismiss = { dismissOverlay("pill:plus") }
            )
            is LibraryOverlay.Rename -> BinNameDialog(
                title = stringResource(R.string.bins_rename_title),
                initial = current.bin.name,
                confirmLabel = stringResource(R.string.action_save),
                onConfirm = { name -> binsViewModel.renameBin(current.bin, name) },
                onDismiss = { dismissOverlay("bin:${current.bin.id}") }
            )
            is LibraryOverlay.Delete -> DeleteBinDialog(
                bin = current.bin,
                onConfirm = {
                    val bins = state.bins
                    val at = bins.indexOfFirst { it.id == current.bin.id }
                    val next = bins.getOrNull(at + 1) ?: bins.getOrNull(at - 1)
                    binsViewModel.deleteBin(current.bin)
                    dismissOverlay(next?.let { "bin:${it.id}" } ?: "pill:plus")
                },
                onDismiss = { dismissOverlay("bin:${current.bin.id}") }
            )
            null -> Unit
        }

        BinsToast(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@Composable
private fun LibraryHeader(
    tab: LibraryTab,
    state: BinsUiState,
    sortLabel: String,
    focus: FocusKeys,
    cloudRefreshing: Boolean,
    onTab: (LibraryTab) -> Unit,
    onSortBounds: (Rect) -> Unit,
    onSort: () -> Unit,
    onCustomOrder: () -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onFocused: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(stringResource(R.string.library_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        // Right stops at the last pill instead of dropping into the grid below.
        val endOfRow = Modifier.focusProperties { right = FocusRequester.Cancel }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BinsTextPill(
                stringResource(R.string.bins_tab), selected = tab == LibraryTab.Bins, requester = focus["pill:bins"],
                onClick = { onTab(LibraryTab.Bins) }
            )
            BinsTextPill(
                stringResource(R.string.library_source_cloud), selected = tab == LibraryTab.Cloud, requester = focus["pill:cloud"],
                onClick = { onTab(LibraryTab.Cloud) }
            )
            if (tab == LibraryTab.Bins) {
                BinsSortPill(sortLabel, focus["pill:sort"], onClick = onSort, onBounds = onSortBounds)
                BinsIconPill(
                    icon = CustomOrderIcon,
                    label = stringResource(if (state.reordering) R.string.bins_custom_order_done else R.string.bins_custom_order),
                    requester = focus["pill:custom"],
                    onClick = onCustomOrder,
                    armed = state.reordering,
                    modifier = if (state.openBin != null) endOfRow else Modifier
                )
                if (state.openBin == null) {
                    BinsIconPill(PlusIcon, stringResource(R.string.bins_create), focus["pill:plus"], onClick = onCreate, modifier = endOfRow)
                }
            } else {
                BinsTextPill(
                    stringResource(if (cloudRefreshing) R.string.library_syncing_btn else R.string.bins_refresh),
                    selected = false, requester = focus["pill:refresh"], onClick = onRefresh, modifier = endOfRow
                )
            }
        }
        val bin = state.openBin
        if (tab == LibraryTab.Bins && bin != null) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(bin.name, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    pluralStringResource(R.plurals.bins_title_count, bin.entries.size, bin.entries.size) + " · " + stringResource(R.string.bin_back_hint),
                    color = BinsMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        if (tab == LibraryTab.Bins && state.reordering) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    when {
                        state.picked != null -> R.string.bins_reorder_place
                        bin != null -> R.string.bins_reorder_pick_title
                        else -> R.string.bins_reorder_pick_bin
                    }
                ),
                color = BinsMuted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun BinsGrid(
    state: BinsUiState,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    focus: FocusKeys,
    onFocused: (String) -> Unit,
    onClick: (Bin) -> Unit,
    onLongClick: (Bin, Rect) -> Unit,
    onStar: (Bin) -> Unit
) {
    // Each bin is as wide as two poster cards side by side.
    val binWidth = (state.posterWidthDp * 2).dp
    val binHeight = binWidth * 0.595f
    LazyVerticalGrid(
        columns = GridCells.FixedSize(binWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGap)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (state.loaded && state.bins.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyScreenState(
                    title = stringResource(R.string.bins_empty_title),
                    subtitle = stringResource(R.string.bins_empty_subtitle),
                    icon = Icons.Outlined.Inventory2,
                    height = 260.dp
                )
            }
        }
        items(state.bins, key = { it.id }) { bin ->
            BinCard(
                bin = bin,
                posterPattern = state.posterPattern,
                width = binWidth,
                height = binHeight,
                index = state.bins.indexOf(bin),
                requester = focus["bin:${bin.id}"],
                starRequester = focus["star:${bin.id}"],
                picked = state.picked == bin.id,
                reordering = state.reordering,
                onFocused = { onFocused("bin:${bin.id}") },
                onClick = { onClick(bin) },
                onLongClick = { anchor -> onLongClick(bin, anchor) },
                onStar = { onStar(bin) },
                modifier = Modifier.animateItem(
                    fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                )
            )
        }
    }
}

@Composable
private fun BinTitlesGrid(
    bin: Bin,
    state: BinsUiState,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    focus: FocusKeys,
    onFocused: (String) -> Unit,
    onClick: (BinTitle) -> Unit,
    onLongClick: (BinTitle, Rect) -> Unit,
    onPrefetch: (BinTitle) -> Unit
) {
    val style = PosterCardDefaults.Style.copy(
        width = state.posterWidthDp.dp,
        height = (state.posterWidthDp * 3 / 2).dp,
        cornerRadius = state.posterCornerDp.dp
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = style.width),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (state.openTitles.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyScreenState(
                    title = stringResource(R.string.bin_empty_title),
                    subtitle = stringResource(R.string.bin_empty_subtitle),
                    icon = Icons.Outlined.Inventory2,
                    height = 260.dp
                )
            }
        }
        items(state.openTitles, key = { it.entry.key }) { title ->
            val key = title.entry.key
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val series = title.entry.type.equals("series", true) || title.entry.type.equals("tv", true)
            Box(
                Modifier
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .animateItem(
                        fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                    )
            ) {
                GridContentCard(
                    item = title.preview,
                    posterCardStyle = style,
                    isWatched = if (series) title.entry.id in state.watchedSeriesIds else title.entry.id in state.watchedMovieIds,
                    focusRequester = focus["title:$key"],
                    showLabel = true,
                    onFocused = {
                        onFocused("title:$key")
                        onPrefetch(title)
                    },
                    onClick = { onClick(title) },
                    onLongPress = { onLongClick(title, bounds) }
                )
                if (state.picked == key) {
                    Box(
                        Modifier
                            .size(style.width, style.height)
                            .border(3.dp, Color.White, RoundedCornerShape(style.cornerRadius))
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteBinDialog(bin: Bin, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    com.nuvio.tv.ui.components.NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.bins_delete_title, bin.name),
        subtitle = stringResource(R.string.bins_delete_subtitle),
        width = 460.dp
    ) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(6) {
                if (runCatching { cancel.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(40)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            BinsTextPill(stringResource(R.string.action_cancel), selected = true, requester = cancel, onClick = onDismiss)
            BinsTextPill(stringResource(R.string.action_delete), selected = false, requester = remember { FocusRequester() }, onClick = onConfirm)
        }
    }
}

@Composable
private fun sortLabel(sort: BinSort): String = sortLabelText(sort)

@Composable
private fun sortLabelText(sort: BinSort): String = stringResource(
    when (sort) {
        BinSort.CUSTOM -> R.string.bins_sort_custom
        BinSort.RECENTLY_VIEWED -> R.string.bins_sort_recent
        BinSort.ADDED_NEWEST -> R.string.library_sort_added_desc
        BinSort.ADDED_OLDEST -> R.string.library_sort_added_asc
        BinSort.TITLE_AZ -> R.string.library_sort_title_asc
        BinSort.TITLE_ZA -> R.string.library_sort_title_desc
    }
)
