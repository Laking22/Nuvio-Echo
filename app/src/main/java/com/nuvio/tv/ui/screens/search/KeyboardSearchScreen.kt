@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.newlook.LocalNewLookChrome
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.browse.BrowseGridColumns
import com.nuvio.tv.ui.screens.browse.browseCardWidthToFit
import com.nuvio.tv.ui.screens.browse.browseGridWidth
import com.nuvio.tv.ui.screens.browse.BrowseHeader
import com.nuvio.tv.ui.screens.browse.BrowseInfoPanel
import com.nuvio.tv.ui.screens.browse.BrowseListRow
import com.nuvio.tv.ui.screens.browse.BrowsePill
import com.nuvio.tv.ui.screens.browse.BrowsePosterGrid
import com.nuvio.tv.ui.screens.browse.BrowseSubColumnWidth
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

private val ScreenStart = 48.dp
/** How much nearer the edge the screen starts when there's no sidebar column (Home: 106 → 24 dp from the edge). */
private val FULL_WIDTH_SHIFT = 24.dp
private val GridStart = 280.dp
private val TopInset = 16.dp
private val KeyHeight = 26.dp
private val KeyGap = 4.dp
private const val KEY_COLUMNS = 6
private const val MAX_KEYWORD_ROWS = 12

private sealed interface KeyboardKey {
    data class Character(val char: Char) : KeyboardKey
    data object Voice : KeyboardKey
    data object Space : KeyboardKey
    data object Backspace : KeyboardKey
    data object Options : KeyboardKey
}

/** Six columns: letters, then digits, then voice, a wide space bar, delete and options. */
private val keyboardRows: List<List<Pair<KeyboardKey, Int>>> = buildList {
    val characters = "abcdefghijklmnopqrstuvwxyz1234567890".toList()
    characters.chunked(KEY_COLUMNS).forEach { row -> add(row.map { KeyboardKey.Character(it) to 1 }) }
    add(
        listOf(
            KeyboardKey.Voice to 1,
            KeyboardKey.Space to 3,
            KeyboardKey.Backspace to 1,
            KeyboardKey.Options to 1
        )
    )
}

/**
 * WuPlay-style search: an on-screen keyboard with keyword suggestions on the left, recent searches
 * and a five-column grid on the right. The grid shows today's trending titles until something is
 * typed or a keyword is picked. Results come from the same addon search as the classic screen.
 */
@Composable
fun KeyboardSearchScreen(
    searchViewModel: SearchViewModel,
    viewModel: KeyboardSearchViewModel = hiltViewModel(),
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val searchState by searchViewModel.uiState.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val watchedMovieIds by searchViewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by searchViewModel.watchedSeriesIds.collectAsState()
    val scope = rememberCoroutineScope()

    var gridHasFocus by rememberSaveable { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var lastKeyIndex by rememberSaveable { mutableIntStateOf(0) }
    /** Keyword row that last had focus, or -1 when it was a key; Left from the grid returns there. */
    var lastKeywordIndex by rememberSaveable { mutableIntStateOf(-1) }
    val keywordRequesters = remember { List(MAX_KEYWORD_ROWS) { FocusRequester() } }
    val keyRequesters = remember { List(keyboardRows.sumOf { it.size }) { FocusRequester() } }
    val gridFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val restoreItemFocus = remember { FocusRequester() }
    val firstRecentFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    val query = searchState.query
    val trimmedQuery = query.trim()
    val showingKeyword = state.selectedKeyword
    val showingResults = showingKeyword == null && trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH

    val rankedTitles = if (state.rankedForQuery == trimmedQuery) state.rankedTitles else emptyList()
    val results = remember(searchState.catalogRows, trimmedQuery, state.resultFilter, rankedTitles) {
        if (trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH) {
            SearchResultsMerger.merge(searchState.catalogRows, trimmedQuery, state.resultFilter, rankedTitles)
        } else {
            emptyList()
        }
    }
    val gridItems = if (showingResults) results else state.browseItems
    val gridLoading = if (showingResults) {
        results.isEmpty() && (searchState.isSearching || searchState.submittedQuery.trim() != trimmedQuery)
    } else {
        state.browseLoading
    }

    fun setQuery(newQuery: String) {
        searchViewModel.onEvent(SearchEvent.QueryChanged(newQuery))
        viewModel.onQueryChanged(newQuery)
    }

    // Keys can arrive faster than the screen recomposes (hardware keyboards, pasted text), so each
    // one builds on the view model's query rather than the copy this composition was drawn with.
    fun type(key: KeyboardKey) {
        val current = searchViewModel.uiState.value.query
        when (key) {
            is KeyboardKey.Character -> setQuery(current + key.char)
            KeyboardKey.Space -> if (current.isNotBlank() && !current.endsWith(' ')) setQuery("$current ")
            KeyboardKey.Backspace -> if (current.isNotEmpty()) setQuery(current.dropLast(1))
            KeyboardKey.Voice, KeyboardKey.Options -> Unit
        }
    }

    val voice = rememberVoiceSearch { recognized ->
        setQuery(recognized)
        searchViewModel.onEvent(SearchEvent.SubmitSearch)
    }

    // New grid contents start from the top.
    val gridVersion = if (showingResults) "q:$trimmedQuery:${state.resultFilter}" else "b:${state.browseVersion}"
    // The set focus last went into the grid for.
    var enteredGridVersion by rememberSaveable { mutableStateOf(gridVersion) }

    fun focusAfterLayout(requester: FocusRequester, fallback: FocusRequester? = null) {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (runCatching { requester.requestFocus() }.isFailure && fallback != null) {
                runCatching { fallback.requestFocus() }
            }
        }
    }

    fun enterGrid(): Boolean {
        // Not while a new set of titles is about to replace what's shown.
        if (gridItems.isEmpty() || gridLoading) return false
        if (showingResults) searchViewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
        // A set not entered before (a new query, filter or keyword) starts at its first title; coming back to the
        // same set returns to where focus was.
        if (gridVersion != enteredGridVersion) {
            enteredGridVersion = gridVersion
            viewModel.lastFocusedItemIndex = 0
            scope.launch {
                gridState.scrollToItem(0)
                withFrameNanos { }
                if (!runCatching { firstItemFocus.requestFocus() }.getOrDefault(false)) runCatching { gridFocus.requestFocus() }
            }
            return true
        }
        return runCatching { gridFocus.requestFocus() }.isSuccess
    }

    fun backToKeyboard() {
        gridHasFocus = false
        val keywordRequester = keywordRequesters.getOrNull(lastKeywordIndex)
        if (keywordRequester != null) {
            focusAfterLayout(keywordRequester, fallback = keyRequesters[lastKeyIndex])
        } else {
            focusAfterLayout(keyRequesters[lastKeyIndex])
        }
    }

    var seenGridVersion by rememberSaveable { mutableStateOf(gridVersion) }
    LaunchedEffect(gridVersion) {
        if (gridVersion != seenGridVersion) {
            seenGridVersion = gridVersion
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        if (gridHasFocus) {
            focusAfterLayout(restoreItemFocus, fallback = gridFocus)
        } else {
            backToKeyboard()
        }
    }

    BackHandler(enabled = gridHasFocus || query.isNotEmpty() || showingKeyword != null) {
        when {
            gridHasFocus -> {
                if (gridState.firstVisibleItemIndex > 0) {
                    scope.launch {
                        gridState.scrollToItem(0)
                        repeat(2) { withFrameNanos { } }
                        runCatching { firstItemFocus.requestFocus() }
                    }
                } else {
                    backToKeyboard()
                }
            }
            query.isNotEmpty() -> setQuery("")
            else -> viewModel.selectKeyword(null)
        }
    }

    val leftAlpha = animateFloatAsState(if (gridHasFocus) 0f else 1f, tween(200), label = "keyboardAlpha")

    // Without a sidebar column (the menu hidden, or on top) the screen starts nearer the edge, as Home does, and the
    // grid takes the width that frees up.
    val edgeShift = if (LocalNewLookChrome.current?.let { it.menuOnTop || it.hideMenu } == true) FULL_WIDTH_SHIFT else 0.dp
    val screenStart = ScreenStart - edgeShift
    val gridStart = GridStart - edgeShift
    val leftX = animateDpAsState(
        targetValue = if (gridHasFocus) screenStart - BrowseSubColumnWidth / 2 else screenStart,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "keyboardX"
    )
    val panelAlpha = animateFloatAsState(if (gridHasFocus) 1f else 0f, tween(200), label = "panelAlpha")

    val contentFocusRequester = LocalContentFocusRequester.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .focusRequester(contentFocusRequester)
            .focusRestorer()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                // Hardware keyboards and remotes with number keys type straight into the query.
                val native = event.nativeKeyEvent
                if (gridHasFocus || native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_DEL -> {
                        type(KeyboardKey.Backspace)
                        true
                    }
                    native.keyCode == AndroidKeyEvent.KEYCODE_SPACE -> {
                        type(KeyboardKey.Space)
                        true
                    }
                    else -> {
                        val char = native.unicodeChar.takeIf { it > 0 }?.toChar()?.lowercaseChar()
                        if (char != null && char.isLetterOrDigit()) {
                            type(KeyboardKey.Character(char))
                            true
                        } else {
                            false
                        }
                    }
                }
            }
    ) {
        // Left: query line, keyboard and keyword suggestions.
        Column(
            modifier = Modifier
                .offset { IntOffset(leftX.value.roundToPx(), 0) }
                .graphicsLayer { alpha = leftAlpha.value }
                .width(BrowseSubColumnWidth)
                .fillMaxHeight()
        ) {
            QueryLine(query = query, listening = voice.isListening)
            Spacer(modifier = Modifier.height(10.dp))
            var keyIndex = 0
            Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                keyboardRows.forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        var column = 0
                        row.forEach { (key, span) ->
                            val index = keyIndex++
                            val endsRow = column + span == KEY_COLUMNS
                            column += span
                            KeyboardKeyView(
                                key = key,
                                span = span,
                                focusRequester = keyRequesters[index],
                                canFocus = !gridHasFocus,
                                onFocused = {
                                    lastKeyIndex = index
                                    lastKeywordIndex = -1
                                    gridHasFocus = false
                                },
                                onRight = if (endsRow) {
                                    { enterGrid() || runCatching { firstRecentFocus.requestFocus() }.isSuccess }
                                } else {
                                    null
                                },
                                onUp = if (rowIndex == 0) ({ true }) else null,
                                onPress = {
                                    when (key) {
                                        KeyboardKey.Voice -> voice.start()
                                        KeyboardKey.Options -> showOptions = true
                                        else -> type(key)
                                    }
                                },
                                onLongPress = if (key == KeyboardKey.Backspace) ({ setQuery("") }) else null
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))

            val keywordLabels: List<Pair<String, () -> Unit>> = if (state.usesTmdb) {
                state.keywords.map { keyword ->
                    keyword.label to {
                        viewModel.selectKeyword(keyword)
                    }
                }
            } else {
                searchState.suggestions.map { title ->
                    title to {
                        setQuery(title)
                        searchViewModel.onEvent(SearchEvent.SubmitSearch)
                    }
                }
            }
            if (keywordLabels.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = NuvioTheme.spacing.sm, bottom = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(
                            if (state.usesTmdb) R.string.search_kb_suggested_keywords else R.string.search_kb_suggestions
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = NuvioTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    keywordLabels.take(MAX_KEYWORD_ROWS).forEachIndexed { index, (label, onPick) ->
                        val keyword = state.keywords.getOrNull(index)
                        BrowseListRow(
                            text = label,
                            selected = state.usesTmdb && keyword != null && keyword.key == showingKeyword?.key,
                            modifier = Modifier
                                .focusRequester(keywordRequesters[index])
                                .focusProperties { canFocus = !gridHasFocus }
                                .onPreviewKeyEvent { event ->
                                    val native = event.nativeKeyEvent
                                    if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                                    ) {
                                        enterGrid()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            onFocused = {
                                gridHasFocus = false
                                lastKeywordIndex = index
                            },
                            onClick = onPick
                        )
                    }
                }
            }
        }

        // Details of the focused poster while browsing the grid.
        BrowseInfoPanel(
            details = viewModel.focusedDetails.details,
            modifier = Modifier
                .offset { IntOffset(24.dp.roundToPx(), 0) }
                .graphicsLayer { alpha = panelAlpha.value }
                .padding(top = 28.dp, bottom = 28.dp)
                .fillMaxHeight()
        )

        // Right: recent searches, header and grid. Posters are as wide as fits: the usual 128 dp with the classic
        // sidebar, a little narrower in the new look, whose menu keeps its inset (the fifth column was cut off).
        val cardWidth = browseCardWidthToFit(maxWidth - gridStart - 8.dp)
        Column(
            modifier = Modifier
                .offset { IntOffset(gridStart.roundToPx(), 0) }
                .width(browseGridWidth(cardWidth) + 8.dp)
                .fillMaxHeight()
        ) {
            RecentSearchesRow(
                recent = searchState.recentSearches,
                firstFocusRequester = firstRecentFocus,
                onFocused = { gridHasFocus = false },
                onLeftFromFirst = { backToKeyboard() },
                onDown = { enterGrid() },
                onPick = { recent ->
                    viewModel.selectKeyword(null)
                    setQuery(recent)
                    searchViewModel.onEvent(SearchEvent.SubmitSearch)
                }
            )
            val header = when {
                showingKeyword != null -> showingKeyword.label
                showingResults -> "“$trimmedQuery”"
                state.usesTmdb -> stringResource(R.string.search_kb_trending_today)
                else -> stringResource(R.string.search_kb_popular)
            }
            BrowseHeader(
                title = null,
                subtitle = header,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
            )
            val browseFailed = !showingResults && state.browseError && state.browseItems.isEmpty()
            if (browseFailed) {
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
                        onClick = { viewModel.retryBrowse() }
                    )
                }
            } else {
                BrowsePosterGrid(
                    items = gridItems,
                    isLoading = gridLoading,
                    isLoadingMore = if (showingResults) false else state.browseLoadingMore,
                    canLoadMore = if (showingResults) false else state.browseCanLoadMore,
                    // Results are up but more catalogs are still answering (or the next page is loading).
                    loadingTail = if (showingResults) searchState.isSearching && results.isNotEmpty() else state.browseLoadingMore,
                    gridState = gridState,
                    focusRequester = gridFocus,
                    firstItemFocusRequester = firstItemFocus,
                    restoreIndex = viewModel.lastFocusedItemIndex,
                    restoreFocusRequester = restoreItemFocus,
                    onLoadMore = viewModel::loadMoreBrowse,
                    onItemClick = { item ->
                        if (showingResults) searchViewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
                        onNavigateToDetail(item.id, item.apiType, item.sourceAddonBaseUrl)
                    },
                    onItemFocused = { index, item ->
                        gridHasFocus = true
                        viewModel.onItemFocused(index, item)
                    },
                    onLeftFromFirstColumn = { backToKeyboard() },
                    // Cards keyed by position everywhere: as search results stream in and re-rank, focus stays where
                    // it is (the top stays the top). Keyed by title, focus followed its title down the grid as better
                    // matches arrived above it ("dun" then "dune": focus ended rows below Dune).
                    reuseCardsAcrossSets = true,
                    cardWidth = cardWidth,
                    emptyText = when {
                        showingResults -> stringResource(R.string.search_kb_no_results, trimmedQuery)
                        else -> stringResource(R.string.browse_empty)
                    },
                    isWatched = { item ->
                        if (item.apiType == "series" || item.apiType == "tv") item.id in watchedSeriesIds else item.id in watchedMovieIds
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .onPreviewKeyEvent { event ->
                            // Up from the first row goes to the recent searches.
                            val native = event.nativeKeyEvent
                            if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                                gridHasFocus &&
                                viewModel.lastFocusedItemIndex < BrowseGridColumns &&
                                searchState.recentSearches.isNotEmpty()
                            ) {
                                gridHasFocus = false
                                runCatching { firstRecentFocus.requestFocus() }
                                true
                            } else {
                                false
                            }
                        }
                )
            }
        }
    }

    if (showOptions) {
        SearchOptionsDialog(
            filter = state.resultFilter,
            hasRecent = searchState.recentSearches.isNotEmpty(),
            onFilter = viewModel::setResultFilter,
            onClearRecent = { searchViewModel.onEvent(SearchEvent.ClearRecentSearches) },
            onDismiss = { showOptions = false }
        )
    }
}

@Composable
private fun QueryLine(query: String, listening: Boolean) {
    val colors = NuvioTheme.colors
    Row(
        modifier = Modifier
            .padding(top = TopInset)
            .fillMaxWidth()
            .height(32.dp)
            .glassKey(RoundedCornerShape(NuvioTheme.radii.md), focused = false)
            .padding(horizontal = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (listening) Icons.Default.Mic else Icons.Default.Search,
            contentDescription = null,
            tint = if (listening) Color.White else colors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        val text = when {
            listening -> stringResource(R.string.search_kb_listening)
            query.isEmpty() -> stringResource(R.string.search_kb_placeholder)
            else -> query
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (query.isEmpty() || listening) colors.TextTertiary else colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (query.isNotEmpty() && !listening) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .background(Color.White.copy(alpha = 0.85f))
            )
        }
    }
}

@Composable
private fun KeyboardKeyView(
    key: KeyboardKey,
    span: Int,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    onFocused: () -> Unit,
    onRight: (() -> Boolean)?,
    onUp: (() -> Boolean)?,
    onPress: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    var focused by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val unitWidth = (BrowseSubColumnWidth - KeyGap * (KEY_COLUMNS - 1)) / KEY_COLUMNS
    val width: Dp = unitWidth * span + KeyGap * (span - 1)
    val content = if (focused) Color.White else colors.TextPrimary
    Box(
        modifier = Modifier
            .size(width, KeyHeight)
            .glassKey(RoundedCornerShape(NuvioTheme.radii.sm), focused)
            .focusRequester(focusRequester)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val isSelect = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    isSelect && native.action == AndroidKeyEvent.ACTION_DOWN -> {
                        // Holding OK repeats the key down; keys with a long-press action fire it once.
                        if (native.repeatCount > 0 && onLongPress != null && !longPressFired) {
                            longPressFired = true
                            onLongPress()
                        }
                        true
                    }
                    isSelect && native.action == AndroidKeyEvent.ACTION_UP -> {
                        if (!longPressFired) onPress()
                        longPressFired = false
                        true
                    }
                    native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
                        onRight?.invoke() ?: false
                    native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ->
                        onUp?.invoke() ?: false
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        when (key) {
            is KeyboardKey.Character -> Text(
                text = key.char.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = content
            )
            KeyboardKey.Space -> Text(
                text = stringResource(R.string.search_kb_space),
                style = MaterialTheme.typography.labelLarge,
                color = content
            )
            KeyboardKey.Voice -> KeyIcon(Icons.Default.Mic, R.string.search_kb_voice, content)
            KeyboardKey.Backspace -> KeyIcon(Icons.AutoMirrored.Filled.Backspace, R.string.search_kb_delete, content)
            KeyboardKey.Options -> KeyIcon(Icons.Default.Settings, R.string.search_kb_options, content)
        }
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, descriptionRes: Int, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = tint,
        modifier = Modifier.size(15.dp)
    )
}

@Composable
private fun RecentSearchesRow(
    recent: List<String>,
    firstFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onLeftFromFirst: () -> Unit,
    onDown: () -> Boolean,
    onPick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(top = TopInset, start = 4.dp)
            .height(32.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (recent.isEmpty()) return@Row
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.search_kb_recent),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(end = 4.dp)
        )
        recent.forEachIndexed { index, item ->
            BrowsePill(
                text = item,
                selected = false,
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) {
                                onLeftFromFirst()
                                true
                            } else {
                                false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                onDown()
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> true
                            else -> false
                        }
                    },
                onFocused = onFocused,
                onClick = { onPick(item) }
            )
        }
    }
}

@Composable
private fun SearchOptionsDialog(
    filter: BrowseMediaMode,
    hasRecent: Boolean,
    onFilter: (BrowseMediaMode) -> Unit,
    onClearRecent: () -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.search_kb_options),
        width = 420.dp
    ) {
        Text(
            text = stringResource(R.string.search_kb_show_results_for),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary
        )
        val firstFocus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            listOf(
                BrowseMediaMode.ALL to R.string.browse_mode_all,
                BrowseMediaMode.MOVIES to R.string.browse_mode_movies,
                BrowseMediaMode.SHOWS to R.string.browse_mode_shows
            ).forEachIndexed { index, (mode, label) ->
                BrowsePill(
                    text = stringResource(label),
                    selected = filter == mode,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onFilter(mode) }
                )
            }
        }
        if (hasRecent) {
            BrowsePill(
                text = stringResource(R.string.search_kb_clear_recent),
                selected = false,
                onClick = {
                    onClearRecent()
                    onDismiss()
                }
            )
        }
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            runCatching { firstFocus.requestFocus() }
        }
    }
}

/**
 * The keyboard's keys are clear liquid glass whatever colour theme is picked: a faint body with a lit top rim, and on
 * focus a brighter body and rim. No blur, so it costs the same on every TV.
 */
private fun Modifier.glassKey(shape: Shape, focused: Boolean): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val body = if (focused) {
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 1f to Color.White.copy(alpha = 0.22f))
    } else {
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f), 1f to Color.White.copy(alpha = 0.05f))
    }
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (focused) 0.85f else 0.22f),
        0.5f to Color.White.copy(alpha = if (focused) 0.30f else 0.05f),
        1f to Color.White.copy(alpha = if (focused) 0.55f else 0.12f)
    )
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        drawOutline(outline, body)
        drawOutline(outline, rim, style = stroke)
    }
}
