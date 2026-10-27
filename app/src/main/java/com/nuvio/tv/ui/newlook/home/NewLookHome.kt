@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.newlook.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import com.nuvio.tv.ui.newlook.Glide
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.core.home.NewLookDetails
import com.nuvio.tv.core.home.NewLookEnricher
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.newlook.LocalNewLookChrome
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernHomePresentationState
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.screens.home.MODERN_CONTINUE_WATCHING_ROW_KEY
import com.nuvio.tv.ui.screens.home.prefetchOnFocus
import com.nuvio.tv.core.streams.StreamPrefetcher
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.ui.screens.player.rememberBetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettings
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NewLookHomeEntryPoint {
    fun builtInRows(): BuiltInRowsRepository
    fun enricher(): NewLookEnricher
    /** Lazy so composing Home never builds the stream stack on the main thread. */
    fun streamPrefetcher(): dagger.Lazy<StreamPrefetcher>
    fun smartHighlights(): com.nuvio.tv.core.home.smart.SmartHighlightsRepository
    fun betaSettingsStore(): com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
}

/** What the user chose for a Smart Highlight from its long-press options. */
internal sealed interface SmartHighlightChoice {
    data class NotThisTitle(val themeId: String, val itemId: String) : SmartHighlightChoice
    data class Snooze(val themeId: String) : SmartHighlightChoice
    data class BlockName(val name: String) : SmartHighlightChoice
    data class Block(val themeId: String) : SmartHighlightChoice
}

private val NoSmartHighlights = kotlinx.coroutines.flow.MutableStateFlow(
    emptyList<com.nuvio.tv.core.home.smart.SmartHighlightRow>()
)

internal enum class HomeArea { HERO, ROWS }

/** What Home keeps while a details page is open, so coming back lands where you were. Held by the ViewModel. */
@Stable
class NewLookHomeMemory {
    internal var area: HomeArea = HomeArea.HERO
    internal var rowKey: String? = null
    internal val cols = HashMap<String, Int>()
    internal var heroButton = 0
    internal var slide = 0
    /** The featured titles, picked when Home first opens and again after a long time away (see [RESHUFFLE_AFTER_MS]). */
    internal var featuredOrder: List<String>? = null
    /** When Home last went to the background (elapsed realtime), or 0. */
    internal var stoppedAt = 0L
    internal var scrollIndex = 0
    internal var scrollOffset = 0
    /** Load more asked per row, at the row's size then, so each size asks once. */
    internal val loadMoreRequested = HashMap<String, Int>()
}

/** Back in the app after this long away, the carousel picks new titles. */
private const val RESHUFFLE_AFTER_MS = 20 * 60_000L

/** The carousel's pool takes this many of today's top movies and shows, and of each catalog row. */
private const val FEATURED_FROM_TOP = 10
private const val FEATURED_FROM_ROW = 4

/** How long a trailer may play before the carousel moves on regardless. */
private const val TRAILER_MAX_MS = 180_000L

/** With focus elsewhere, the hero shows each title's backdrop for this long. */
private const val ROTATE_MS = 8_000L
/** Home counts as settled once its row layout has held still this long after its sources came in. */
private const val SETTLE_QUIET_MS = 350L
/** The longest the first Home waits to settle (a slow network): it shows what it has then. */
private const val SETTLE_CAP_MS = 4_500L
/** A title without a trailer shows its backdrop for this long while focused. */
private const val NO_TRAILER_MS = 9_000L

/**
 * What the new look's Home needs from the rest of the app: the ViewModel and Hilt singletons in the app
 * ([viewModelSource]), stand-ins in the layout preview (src/test, NewLookPreviewTest), so both run the same Home code.
 */
internal interface NewLookHomeSource {
    val memory: NewLookHomeMemory
    val scrollToTop: StateFlow<Int>
    val trailerUrls: Map<String, String>
    val trailerAudioUrls: Map<String, String>
    val builtInRows: StateFlow<BuiltInRowsRepository.Rows>
    suspend fun refreshBuiltIns()
    fun cachedDetails(id: String): NewLookDetails?
    suspend fun details(item: MetaPreview, withTag: Boolean, topTen: Set<String>): NewLookDetails?
    /** [surfaceHeightPx]: how tall the trailer will play, so Auto trailer quality can match it. */
    fun requestTrailer(itemId: String, title: String, releaseInfo: String?, apiType: String, surfaceHeightPx: Int? = null)
    fun requestLazyCatalogLoad(catalogKey: String)
    fun onEvent(event: HomeEvent)
    /** A Continue Watching card has focus: warm the stream search its click will make. */
    fun prefetchContinueWatching(item: ContinueWatchingItem) {}
    /** A regular row's poster opened out (Fetch on expansion): warm the search its Play would make. */
    fun prefetchOnExpansion(target: ExpansionTarget, dwellMs: Long) {}
    val smartHighlights: StateFlow<List<com.nuvio.tv.core.home.smart.SmartHighlightRow>> get() = NoSmartHighlights
    suspend fun refreshSmartHighlights() {}
    /** Saves the choice; Home applies it from the settings at once. */
    suspend fun onSmartHighlightChoice(choice: SmartHighlightChoice) {}
    /**
     * Returns once what decides Home's rows has come in at least once: the settings file (row order, highlighted
     * and grand rows), the built-in rows and today's Smart Highlights.
     */
    suspend fun awaitStartupSources() {}
}

private fun viewModelSource(
    viewModel: HomeViewModel,
    builtIns: BuiltInRowsRepository,
    enricher: NewLookEnricher,
    streamPrefetcher: dagger.Lazy<StreamPrefetcher>,
    smart: com.nuvio.tv.core.home.smart.SmartHighlightsRepository,
    betaStore: com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
) = object : NewLookHomeSource {
    override val memory get() = viewModel.newLookMemory
    override val scrollToTop get() = viewModel.scrollToTopTrigger
    override val trailerUrls get() = viewModel.trailerPreviewUrls
    override val trailerAudioUrls get() = viewModel.trailerPreviewAudioUrls
    override val builtInRows get() = builtIns.rows
    override suspend fun refreshBuiltIns() {
        builtIns.refresh()
    }
    override fun cachedDetails(id: String) = enricher.cached(id)
    override suspend fun details(item: MetaPreview, withTag: Boolean, topTen: Set<String>) =
        enricher.details(item, withTag, topTen)
    override fun requestTrailer(itemId: String, title: String, releaseInfo: String?, apiType: String, surfaceHeightPx: Int?) {
        viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType, surfaceHeightPx)
    }
    override fun requestLazyCatalogLoad(catalogKey: String) {
        viewModel.requestLazyCatalogLoad(catalogKey)
    }
    override fun onEvent(event: HomeEvent) {
        viewModel.onEvent(event)
    }
    override fun prefetchContinueWatching(item: ContinueWatchingItem) {
        streamPrefetcher.prefetchOnFocus(item)
    }
    override fun prefetchOnExpansion(target: ExpansionTarget, dwellMs: Long) {
        streamPrefetcher.get().prefetchOnExpansion(target.type, target.videoId, target.season, target.episode, dwellMs)
    }
    override val smartHighlights get() = smart.rows
    override suspend fun refreshSmartHighlights() {
        smart.refresh()
    }
    override suspend fun awaitStartupSources() {
        betaStore.awaitLoaded()
        builtIns.firstLoadDone.first { it }
        smart.firstLoadDone.first { it }
    }
    override suspend fun onSmartHighlightChoice(choice: SmartHighlightChoice) {
        when (choice) {
            is SmartHighlightChoice.NotThisTitle -> betaStore.excludeFromHighlight(choice.themeId, choice.itemId)
            is SmartHighlightChoice.Snooze -> betaStore.snoozeHighlight(
                choice.themeId,
                java.time.LocalDate.now().toEpochDay() + com.nuvio.tv.core.home.smart.SmartHighlightPlanner.SNOOZE_DAYS
            )
            is SmartHighlightChoice.BlockName -> betaStore.blockHighlight(
                BetaPerformanceSettings.SMART_NAME_PREFIX + com.nuvio.tv.core.home.smart.HighlightNameFilter.normalise(choice.name)
            )
            is SmartHighlightChoice.Block -> betaStore.blockHighlight(choice.themeId)
        }
    }
}

/**
 * The new look's Home: a black/gray gradient page with a rounded hero window (a carousel of featured titles) and
 * rows below it that scroll with it as one page. Continue Watching cards are wide; posters open out when focused;
 * highlighted rows are mini carousels whose trailers play in turn. See scripts/ui/lab for the design and notes.
 */
@Composable
internal fun NewLookHome(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    presentation: ModernHomePresentationState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onWatchNow: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onSettled: () -> Unit = {}
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, NewLookHomeEntryPoint::class.java)
    }
    val source = remember(viewModel, entryPoint) {
        viewModelSource(
            viewModel, entryPoint.builtInRows(), entryPoint.enricher(), entryPoint.streamPrefetcher(),
            entryPoint.smartHighlights(), entryPoint.betaSettingsStore()
        )
    }
    val settings by rememberBetaPerformanceSettings()
    NewLookHomeContent(
        source = source,
        settings = settings,
        uiState = uiState,
        presentation = presentation,
        onNavigateToDetail = onNavigateToDetail,
        onWatchNow = onWatchNow,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onSettled = onSettled
    )
}

@Composable
internal fun NewLookHomeContent(
    source: NewLookHomeSource,
    settings: BetaPerformanceSettings,
    uiState: HomeUiState,
    presentation: ModernHomePresentationState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onWatchNow: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    /**
     * Called once the rows have stopped moving: what decides them has come in and their layout has held still for
     * a moment (or [SETTLE_CAP_MS] has passed). The app keeps its splash up until then, so Home never opens on rows
     * that then shuffle as the rest arrives.
     */
    onSettled: () -> Unit = {}
) {
    val context = LocalContext.current
    val chrome = LocalNewLookChrome.current
    val menuOnTop = chrome?.menuOnTop == true
    val hideMenu = chrome?.hideMenu == true
    val contentStart = if (!menuOnTop && !hideMenu) NewLookLayout.ContentStartWithSidebar else NewLookLayout.ContentStartFullWidth
    // Grand cards are as wide as the hero window, which runs from contentStart to near the screen's right edge.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // Layout's poster card width sizes the posters only; its corner radius rounds every row's cards.
    val dims = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp, screenWidth, contentStart) {
        NewLookDimens.of(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp).withGrandWidth(screenWidth, contentStart)
    }
    val inset = chrome?.contentInset ?: 0.dp
    val memory = source.memory
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val muted = uiState.focusedPosterBackdropTrailerMuted

    // Built-in rows: refreshed when Home opens and whenever it comes back to the front.
    val builtIns by source.builtInRows.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Bumped to pick new featured titles: coming back to the app after a long time away gets a fresh carousel.
    var featuredRound by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { source.refreshBuiltIns() }
                scope.launch { source.refreshSmartHighlights() }
                val away = memory.stoppedAt.takeIf { it > 0 }?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L
                if (away >= RESHUFFLE_AFTER_MS) {
                    memory.featuredOrder = null
                    featuredRound++
                }
            }
            if (event == Lifecycle.Event.ON_STOP) memory.stoppedAt = android.os.SystemClock.elapsedRealtime()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val titles = BuiltInTitles(
        trending = stringResource(R.string.home_row_trending_week),
        becauseYouWatched = stringResource(R.string.home_row_because_you_watched),
        forYou = stringResource(R.string.home_row_for_you),
        topMovies = stringResource(R.string.home_row_top_movies_today),
        topShows = stringResource(R.string.home_row_top_shows_today),
        movie = stringResource(R.string.home_type_movie),
        show = stringResource(R.string.home_type_show)
    )
    // Smart Highlights: rebuilt when the Enrichment level changes, renamed or something is blocked (a block refills
    // the gap).
    val smartHighlights by source.smartHighlights.collectAsState()
    LaunchedEffect(settings.enrichment, settings.smartHighlightAiNames, settings.blockedHighlights, settings.snoozedHighlights) {
        source.refreshSmartHighlights()
    }
    val rows = remember(
        presentation.rows, builtIns, titles,
        settings.highlightedRows, settings.hiddenBuiltInRows, settings.plainBuiltInRows, settings.grandRows,
        settings.homeRowOrder, smartHighlights, settings.enrichment, settings.smartHighlightAiNames,
        settings.blockedHighlights, settings.snoozedHighlights, settings.highlightExclusions
    ) {
        buildNewLookRows(presentation.rows.list, builtIns, settings, titles, smartHighlights)
    }
    var sourcesIn by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.awaitStartupSources()
        sourcesIn = true
    }
    val rowLayout = remember(rows) { rows.map { it.key to it.kind } }
    val currentOnSettled by rememberUpdatedState(onSettled)
    LaunchedEffect(sourcesIn, rowLayout) {
        if (sourcesIn) {
            delay(SETTLE_QUIET_MS)
            currentOnSettled()
        }
    }
    LaunchedEffect(Unit) {
        delay(SETTLE_CAP_MS)
        currentOnSettled()
    }

    // Details (logo, genres, runtime) fetched on focus, by item id.
    val details = remember { mutableStateMapOf<String, NewLookDetails>() }
    fun loadDetails(item: MetaPreview?, withTag: Boolean = false, topTen: Set<String> = emptySet()) {
        item ?: return
        if (details[item.id]?.let { !withTag || it.tag != null } == true) return
        source.cachedDetails(item.id)?.let { if (!withTag || it.tag != null) { details[item.id] = it; return } }
        scope.launch { source.details(item, withTag, topTen)?.let { details[item.id] = it } }
    }

    // ---- Featured titles (hero carousel) ----
    // The hero catalogs picked in Layout when there are; otherwise a wide pool (this week's trending, today's top
    // movies and shows, and a few titles from each catalog row), so the carousel isn't always this week's trending.
    val featuredSource = remember(uiState.heroItems, uiState.heroSectionEnabled, builtIns, rows) {
        val base = if (uiState.heroSectionEnabled && uiState.heroItems.size >= 3) {
            uiState.heroItems
        } else {
            builtIns.trendingWeek + builtIns.topMoviesToday.take(FEATURED_FROM_TOP) +
                builtIns.topShowsToday.take(FEATURED_FROM_TOP) + uiState.heroItems +
                rows.filter { it.kind == RowKind.POSTER && !it.isPlaceholder }
                    .flatMap { row -> row.items.take(FEATURED_FROM_ROW).mapNotNull { it.metaPreview } }
        }
        base.filter { !it.backdropUrl.isNullOrBlank() }.distinctBy { it.id }
    }
    val featured = remember(featuredSource, featuredRound) {
        val byId = featuredSource.associateBy { it.id }
        val kept = memory.featuredOrder?.mapNotNull { byId[it] }.orEmpty()
        if (kept.size >= minOf(FeaturedPicker.SLIDES, featuredSource.size) && kept.isNotEmpty()) kept
        else {
            // Titles featured lately (kept across restarts) wait their turn.
            val recent = FeaturedPicker.loadRecent(context)
            FeaturedPicker.pick(featuredSource, recent).also { picked ->
                memory.featuredOrder = picked.map { it.id }
                FeaturedPicker.saveRecent(context, FeaturedPicker.remember(recent, picked))
            }
        }
    }
    val topTenIds = remember(builtIns) {
        (builtIns.topMoviesToday.take(10) + builtIns.topShowsToday.take(10)).map { it.id }.toSet()
    }
    LaunchedEffect(featured, topTenIds) {
        featured.forEach { item -> loadDetails(item, withTag = true, topTen = topTenIds) }
    }
    val movieLabel = stringResource(R.string.home_type_movie)
    val showLabel = stringResource(R.string.home_type_show)
    // Derived, so the slides are rebuilt only when the titles or their details change, not on every focus move.
    val slides by remember(featured, movieLabel, showLabel) {
        derivedStateOf {
            featured.map { item ->
                val d = details[item.id]
                val isSeries = item.type == ContentType.SERIES || item.apiType.equals("series", true)
                HeroSlide(
                    key = item.id,
                    title = item.name,
                    backdrop = item.backdropUrl ?: d?.backdrop,
                    logo = item.logo ?: d?.logo,
                    typeLabel = if (isSeries) showLabel else movieLabel,
                    genre = item.genres.firstOrNull() ?: d?.genres?.firstOrNull(),
                    year = yearOf(item.releaseInfo) ?: yearOf(item.released) ?: yearOf(d?.year),
                    rating = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    ratingIsImdb = item.id.startsWith("tt") || item.imdbId != null,
                    description = item.description ?: d?.overview,
                    tag = d?.tag,
                    tagSeason = d?.tagSeason
                )
            }
        }
    }

    // ---- Focus state ----
    var area by remember { mutableStateOf(memory.area) }
    var focusedRowKey by remember { mutableStateOf(memory.rowKey) }
    val cols = remember { mutableStateMapOf<String, Int>().apply { putAll(memory.cols) } }
    var heroButton by remember { mutableIntStateOf(memory.heroButton) }
    var slide by remember { mutableIntStateOf(memory.slide) }
    var heroHasFocus by remember { mutableStateOf(false) }
    /** The card that holds focus (row key to item key), or null when none does. */
    var focusedCard by remember { mutableStateOf<Pair<String, String>?>(null) }
    var cwOptions by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    /**
     * The poster that has opened out (row key to item key): focused for the expand delay. One state for the card's
     * opening and its trailer, so the trailer is asked for only once a poster opens, not for each one passed.
     */
    var openPoster by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(focusedCard, settings.posterOpenDelayMs) {
        openPoster = null
        val card = focusedCard ?: return@LaunchedEffect
        val row = rows.firstOrNull { it.key == card.first } ?: return@LaunchedEffect
        if (row.kind != RowKind.POSTER || row.isPlaceholder) return@LaunchedEffect
        if (settings.posterOpenDelayMs > 0) delay(settings.posterOpenDelayMs.toLong())
        openPoster = card
    }
    /** On a grand card, which pill is lit: 0 Watch Now, 1 More Info. Back to Watch Now on every new card. */
    var grandButton by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusedCard) { grandButton = 0 }
    /** The Smart Highlight row and title held down, while its options are open. */
    var smartOptions by remember { mutableStateOf<Pair<NewLookRow, ModernCarouselItem>?>(null) }
    if (slides.isNotEmpty() && slide >= slides.size) slide = 0

    LaunchedEffect(area, focusedRowKey, heroButton, slide) {
        memory.area = area
        memory.rowKey = focusedRowKey
        memory.heroButton = heroButton
        memory.slide = slide
    }

    val watchFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowRequester(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    // The card just left of each row's focused one, so Left moves there at once without a focus search: the search
    // could miss a card that's off screen to the left mid-scroll and fall through to the sidebar.
    val leftFocus = remember { HashMap<String, FocusRequester>() }
    fun leftRequester(key: String) = leftFocus.getOrPut(key) { FocusRequester() }
    val rowStates = remember { HashMap<String, LazyListState>() }

    fun tryFocus(requester: FocusRequester): Boolean = runCatching { requester.requestFocus() }.getOrDefault(false)

    fun focusHero(button: Int = heroButton) {
        area = HomeArea.HERO
        scope.launch {
            repeat(20) {
                if (tryFocus(if (button == 1) moreFocus else watchFocus)) return@launch
                withFrameNanos { }
            }
        }
    }

    fun focusRow(index: Int, col: Int? = null) {
        val row = rows.getOrNull(index) ?: return
        if (col != null) cols[row.key] = col
        area = HomeArea.ROWS
        focusedRowKey = row.key
        scope.launch {
            if (col != null) rowStates[row.key]?.let { state ->
                if (state.layoutInfo.visibleItemsInfo.none { it.index == col }) state.scrollToItem(col)
            }
            // At once when the row is on screen (moving to the next row nearly always is); otherwise it may still be
            // below the screen, so try each frame while the page scrolls to it.
            if (col == null && tryFocus(rowRequester(row.key))) return@launch
            repeat(40) {
                withFrameNanos { }
                if (tryFocus(rowRequester(row.key))) return@launch
            }
        }
    }

    // A loading row that has focus: once it loads, focus moves onto its real card (the skeleton it was on is gone).
    // If a row disappears while focused (a catalog that failed and was dropped), focus goes to the row now in its place.
    val focusedRowLoading = rows.firstOrNull { it.key == focusedRowKey }?.onlyPlaceholders == true
    var lastRowIndex by remember { mutableIntStateOf(0) }
    var wasLoading by remember(focusedRowKey) { mutableStateOf(focusedRowLoading) }
    LaunchedEffect(focusedRowKey, focusedRowLoading, rows) {
        val key = focusedRowKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) lastRowIndex = index
        if (area != HomeArea.ROWS) {
            wasLoading = focusedRowLoading
            return@LaunchedEffect
        }
        when {
            index < 0 && rows.isNotEmpty() -> focusRow(lastRowIndex.coerceAtMost(rows.lastIndex))
            // The skeleton's removal may already have cleared focusedCard; only another row having focus rules it out.
            wasLoading && !focusedRowLoading && (focusedCard == null || focusedCard?.first == key) -> {
                val row = rows[index]
                focusRow(index, (cols[key] ?: 0).coerceAtMost((row.items.size - 1).coerceAtLeast(0)))
            }
        }
        wasLoading = focusedRowLoading
    }

    fun nextSlide(step: Int) {
        if (slides.isEmpty()) return
        slide = (slide + step).mod(slides.size)
    }

    // First focus: the window's Watch Now, or wherever Home was left.
    var initialFocusDone by remember { mutableStateOf(false) }
    val contentReady = slides.isNotEmpty() || rows.isNotEmpty()
    LaunchedEffect(contentReady) {
        if (!contentReady || initialFocusDone) return@LaunchedEffect
        initialFocusDone = true
        val savedRow = memory.rowKey?.let { key -> rows.indexOfFirst { it.key == key } } ?: -1
        if (memory.area == HomeArea.ROWS && savedRow >= 0) focusRow(savedRow)
        else if (slides.isNotEmpty()) focusHero(memory.heroButton)
        else focusRow(0)
    }

    // Sidebar's Home pressed while on Home: back to the top.
    val scrollToTop by source.scrollToTop.collectAsState()
    var seenScrollToTop by remember { mutableIntStateOf(scrollToTop) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != seenScrollToTop) {
            seenScrollToTop = scrollToTop
            cols.clear()
            rowStates.values.forEach { it.scrollToItem(0) }
            focusHero(0)
        }
    }

    // ---- Page scrolling ----
    val listState = rememberLazyListState(memory.scrollIndex, memory.scrollOffset)
    LaunchedEffect(listState, chrome) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                memory.scrollIndex = index
                memory.scrollOffset = offset
                // Past the hero the avatar is long gone: any distance beyond the screen will do.
                chrome?.reportPageScroll(if (index == 0) offset.toFloat() else 100_000f)
            }
    }
    val headerHeightPx = with(density) { NewLookLayout.RowsTop.roundToPx() }
    // The screen in pixels, so the focused row rests 10 dp above the bottom whatever the interface size.
    val screenHeightPx = (LocalConfiguration.current.screenHeightDp * context.resources.displayMetrics.density).toInt()
    val rowHeightsPx = remember(rows, dims, density) { rows.map { with(density) { it.height(dims).roundToPx() } } }
    // On the window the page sits at the top. On a row, the page scrolls just enough for that row to show whole.
    val targetScroll = remember(area, focusedRowKey, rowHeightsPx, screenHeightPx) {
        val index = rows.indexOfFirst { it.key == focusedRowKey }
        if (area == HomeArea.HERO || index < 0) 0 else with(density) {
            val row = rows[index]
            val visiblePx = (dims.rowHead + row.cardHeight(dims) + row.metaHeight(dims) + 6.dp).roundToPx()
            NewLookScroll.target(
                headerPx = headerHeightPx,
                rowHeightsPx = rowHeightsPx,
                index = index,
                visiblePx = visiblePx,
                viewportPx = screenHeightPx,
                // A grand row sits in the middle of the screen rather than on its bottom edge, so the rows above and
                // below show a little either side of it.
                bottomMarginPx = if (row.kind == RowKind.GRAND) {
                    ((screenHeightPx - visiblePx) / 2).coerceAtLeast(10.dp.roundToPx())
                } else 10.dp.roundToPx()
            )
        }
    }
    // The page's speed when a glide is cut short by the next press, so the next one carries on from it.
    val pageVelocity = remember { floatArrayOf(0f) }
    LaunchedEffect(targetScroll) {
        val (index, offset) = NewLookScroll.toItem(targetScroll, headerHeightPx, rowHeightsPx)
        // One glide over the exact distance when the target is laid out (it nearly always is: the next row down or
        // up). animateScrollToItem stepped through items with a spring and ran longer for the same move.
        val laidOut = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (laidOut != null) {
            val distance = (laidOut.offset + offset).toFloat()
            if (distance != 0f) {
                var travelled = 0f
                val glide = AnimationState(initialValue = 0f, initialVelocity = pageVelocity[0])
                listState.scroll {
                    glide.animateTo(distance, Glide.scroll) {
                        travelled += scrollBy(value - travelled)
                        pageVelocity[0] = velocity
                    }
                }
            }
            pageVelocity[0] = 0f
        } else {
            pageVelocity[0] = 0f
            listState.animateScrollToItem(index, offset)
        }
    }

    // Rows that haven't loaded yet load as they come near the screen.
    val latestRows by rememberUpdatedState(rows)

    // Before a card is taken off Home, focus moves to the card beside it, or (its row going with it) to the next row.
    // Left on the card, focus fell out of Home as it went: the menu opened and closed and the page jumped up and
    // back down (seen removing Continue Watching cards quickly).
    suspend fun moveFocusOffCard(rowKey: String, wholeRow: Boolean) {
        val current = latestRows
        val rowIndex = current.indexOfFirst { it.key == rowKey }
        val row = current.getOrNull(rowIndex) ?: return
        val targetKey: String
        if (!wholeRow && row.items.size > 1) {
            val col = (cols[rowKey] ?: 0).coerceIn(0, row.items.lastIndex)
            cols[rowKey] = if (col < row.items.lastIndex) col + 1 else col - 1
            targetKey = rowKey
        } else {
            val target = current.getOrNull(rowIndex + 1) ?: current.getOrNull(rowIndex - 1)
            if (target == null) {
                focusHero()
                return
            }
            targetKey = target.key
        }
        area = HomeArea.ROWS
        focusedRowKey = targetKey
        // The row's requester moves to the new card when it recomposes: until then it's still on the one going.
        withFrameNanos { }
        // An options dialog may still be closing; focus comes back to Home once it has.
        for (attempt in 0 until 40) {
            withFrameNanos { }
            if (tryFocus(rowRequester(targetKey))) return
        }
    }

    // A focused card that moved within its row (the card before it was removed) keeps the row's place on it.
    LaunchedEffect(rows) {
        val (rowKey, itemKey) = focusedCard ?: return@LaunchedEffect
        val index = rows.firstOrNull { it.key == rowKey }?.items?.indexOfFirst { it.key == itemKey } ?: return@LaunchedEffect
        if (index >= 0 && cols[rowKey] != index) {
            cols[rowKey] = index
            memory.cols[rowKey] = index
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collectLatest { (first, last) ->
            delay(240)
            for (i in (first - 1).coerceAtLeast(0)..(last + 1)) {
                val row = latestRows.getOrNull(i) ?: continue
                val rowSource = row.source ?: continue
                if (!row.isPlaceholder) continue
                source.requestLazyCatalogLoad(
                    catalogRowLegacyKey(rowSource.addonId ?: continue, rowSource.apiType ?: continue, rowSource.catalogId ?: continue)
                )
            }
        }
    }

    // ---- Trailers: one at a time, only for what has focus ----
    // A loading row's skeletons have no title to play, show or open.
    val focusedRow = rows.firstOrNull { it.key == focusedCard?.first }?.takeIf { !it.onlyPlaceholders }
    val focusedItem = focusedRow?.items?.firstOrNull { it.key == focusedCard?.second }
    // Highlighted and grand rows play the focused card's trailer and move on when it ends.
    val highlightTrailerItem = focusedItem?.takeIf {
        (focusedRow.kind == RowKind.HIGHLIGHT || focusedRow.kind == RowKind.GRAND) && !focusedRow.collection
    }
    // A regular row's poster plays its trailer once it has opened out (and stays on it when the trailer ends).
    val posterTrailerItem = focusedItem?.takeIf { focusedRow.kind == RowKind.POSTER && openPoster == focusedCard }
    // Fetch on expansion: the opened poster's sources, so Play finds a search already running. The wait makes up the
    // usual focus dwell (with a short expand delay, focus may still be passing through the row).
    LaunchedEffect(posterTrailerItem?.key) {
        val payload = posterTrailerItem?.payload as? ModernPayload.Catalog ?: return@LaunchedEffect
        if (!settings.fetchOnExpansion) return@LaunchedEffect
        val target = expansionPrefetchTarget(payload.itemId, payload.itemType, uiState.continueWatchingItems)
            ?: return@LaunchedEffect
        // Waited here, so moving off the poster first (which ends this effect) fetches nothing.
        delay((com.nuvio.tv.core.streams.PREFETCH_FOCUS_DWELL_MS - settings.posterOpenDelayMs).coerceAtLeast(0L))
        source.prefetchOnExpansion(target, dwellMs = 0L)
    }
    val heroItem = featured.getOrNull(slide)
    val trailerTarget: Triple<String, String, Pair<String?, String>>? = when {
        heroHasFocus && heroItem != null -> Triple(heroItem.id, heroItem.name, heroItem.releaseInfo to heroItem.apiType)
        else -> (highlightTrailerItem ?: posterTrailerItem)?.let { item ->
            (item.payload as? ModernPayload.Catalog)?.let {
                Triple(it.itemId, it.trailerTitle, it.trailerReleaseInfo to it.trailerApiType)
            }
        }
    }
    // How tall the trailer will play: the hero, a grand card, a highlight, or an opened poster.
    val trailerSurface: Dp = when {
        heroHasFocus -> if (settings.heroEdgeToEdge) NewLookLayout.EdgeHeroHeight else NewLookLayout.WindowHeight
        focusedRow?.kind == RowKind.GRAND -> dims.grandHeight
        focusedRow?.kind == RowKind.HIGHLIGHT -> dims.highlightHeight
        else -> dims.posterHeight
    }
    val trailerSurfacePx = with(density) { trailerSurface.roundToPx() }
    val trailerUrls = source.trailerUrls
    val trailerAudioUrls = source.trailerAudioUrls
    val trailerUrl = trailerTarget?.let { trailerUrls[it.first] }
    LaunchedEffect(trailerTarget?.first, trailerSurfacePx) {
        val target = trailerTarget ?: return@LaunchedEffect
        // Asked again a few times: requests are dropped during the startup grace period. The first ask always goes
        // through, as a trailer fetched for a smaller surface is fetched again for this one.
        repeat(4) { attempt ->
            if (attempt > 0 && trailerUrls[target.first] != null) return@LaunchedEffect
            source.requestTrailer(target.first, target.second, target.third.first, target.third.second, trailerSurfacePx)
            delay(1_500)
        }
    }

    // The carousel always moves on: trailers in turn while the window is focused, else a new backdrop every 8 s.
    val heroTrailerReady = heroHasFocus && trailerUrl != null
    LaunchedEffect(heroHasFocus, slide, slides.size, heroTrailerReady) {
        if (slides.size <= 1) return@LaunchedEffect
        delay(
            when {
                !heroHasFocus -> ROTATE_MS
                heroTrailerReady -> TRAILER_MAX_MS
                else -> NO_TRAILER_MS
            }
        )
        nextSlide(1)
    }

    // A highlighted row moves on to its next card when the trailer ends (or after a while without one).
    fun advanceHighlight() {
        val row = focusedRow ?: return
        val index = row.items.indexOfFirst { it.key == focusedCard?.second }
        if (index < 0 || row.items.isEmpty()) return
        val next = (index + 1) % row.items.size
        focusRow(rows.indexOf(row), next)
    }
    // One lambda for every row, so a focus move doesn't hand each visible row a new callback (which made all of them
    // recompose on every key press).
    val latestAdvance by rememberUpdatedState(::advanceHighlight)
    val onHighlightTrailerEnded: () -> Unit = remember { { latestAdvance() } }
    val highlightTrailerReady = highlightTrailerItem != null && trailerUrl != null
    LaunchedEffect(highlightTrailerItem?.key, highlightTrailerReady) {
        if (highlightTrailerItem == null) return@LaunchedEffect
        delay(if (highlightTrailerReady) TRAILER_MAX_MS else NO_TRAILER_MS)
        advanceHighlight()
    }

    // Back: to the start of the row, then up to the window; from the window Back opens the menu.
    BackHandler(enabled = area == HomeArea.ROWS && focusedCard != null) {
        val key = focusedRowKey ?: return@BackHandler
        val col = cols[key] ?: 0
        if (col > 0) {
            val index = rows.indexOfFirst { it.key == key }
            scope.launch { rowStates[key]?.scrollToItem(0) }
            focusRow(index, 0)
        } else {
            focusHero(0)
        }
    }

    // ---- Layout ----
    val contentFocusRequester = LocalContentFocusRequester.current
    val pageDrop by animateFloatAsState(
        targetValue = if (menuOnTop && hideMenu && chrome?.topBarFocused == true) 40f else 0f,
        animationSpec = tween(240),
        label = "pageDrop"
    )
    // Home paints its own background over the whole screen, so the app's Surface under it (the theme's background,
    // another full screen of pixels every frame) is turned off while Home shows, as the player does.
    DisposableEffect(Unit) {
        com.nuvio.tv.core.player.PlayerWindowBackdrop.acquireTransparent()
        onDispose { com.nuvio.tv.core.player.PlayerWindowBackdrop.releaseTransparent() }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Draw across the column the scaffold leaves for the sidebar: the page uses the whole screen.
            .layout { measurable, constraints ->
                val extra = inset.roundToPx()
                val width = constraints.maxWidth + extra
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-extra, 0) }
            }
            .then(if (settings.homeAmbientBackground) Modifier.background(PageBase) else Modifier.background(PageGradient))
            .focusRequester(contentFocusRequester)
            // Coming back into Home (from the menu, a dialog or another screen's return): to the card or button
            // Home was on, from its own memory. A focusRestorer here brought focus back one row up (seen
            // in the JVM preview: Left into the menu, then Right, landed on the row above).
            .focusProperties {
                onEnter = {
                    val rowIndex = rows.indexOfFirst { it.key == focusedRowKey }
                    val restored = area == HomeArea.ROWS && rowIndex >= 0 && tryFocus(rowRequester(rows[rowIndex].key))
                    if (!restored) tryFocus(if (heroButton == 1) moreFocus else watchFocus)
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (chrome?.topBarFocused == true || cwOptions != null) return@onPreviewKeyEvent false
                if (heroHasFocus) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (heroButton == 1) tryFocus(watchFocus) else {
                                nextSlide(-1)
                                tryFocus(moreFocus)
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (heroButton == 0) tryFocus(moreFocus) else {
                                nextSlide(1)
                                tryFocus(watchFocus)
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (rows.isNotEmpty()) focusRow(0)
                            true
                        }
                        Key.DirectionUp -> {
                            if (menuOnTop) chrome?.focusTopBar()
                            true
                        }
                        else -> false
                    }
                } else if (focusedCard != null) {
                    val index = rows.indexOfFirst { it.key == focusedCard?.first }
                    val row = rows.getOrNull(index)
                    val col = row?.let { cols[it.key] } ?: 0
                    // A grand card's two pills: Right lights More Info before moving on, Left lights Watch Now.
                    val grand = row?.kind == RowKind.GRAND
                    if (grand && event.key == Key.DirectionRight && grandButton == 0) {
                        grandButton = 1
                        return@onPreviewKeyEvent true
                    }
                    if (grand && event.key == Key.DirectionLeft && grandButton == 1) {
                        grandButton = 0
                        return@onPreviewKeyEvent true
                    }
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (index in 0 until rows.lastIndex) focusRow(index + 1)
                            true
                        }
                        Key.DirectionUp -> {
                            if (index > 0) focusRow(index - 1) else if (slides.isNotEmpty()) focusHero(heroButton)
                            true
                        }
                        Key.DirectionRight -> row != null && col >= row.items.lastIndex
                        // Within a row Home moves focus itself; only Left from the first card reaches the sidebar,
                        // and with the menu on top there's no sidebar to go to.
                        Key.DirectionLeft -> when {
                            row != null && col > 0 -> {
                                if (!tryFocus(leftRequester(row.key))) focusRow(index, col - 1)
                                true
                            }
                            else -> menuOnTop
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        val screenWidth = maxWidth
        if (settings.homeAmbientBackground) {
            // The carousel's current title, blurred to its colours, behind the whole page (Appearance → Ambient colour).
            AmbientPageWash(url = slides.getOrNull(slide)?.backdrop, modifier = Modifier.fillMaxSize())
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView, LocalNewLookCardRadius provides dims.cardRadius) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(bottom = 240.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "hero", contentType = "hero") {
                    HeroBlock(
                        slides = slides,
                        slide = slide,
                        contentStart = contentStart,
                        screenWidth = screenWidth,
                        menuOnTop = menuOnTop,
                        hideMenu = hideMenu,
                        drop = { pageDrop },
                        heroButton = if (heroHasFocus) heroButton else null,
                        watchFocus = watchFocus,
                        moreFocus = moreFocus,
                        trailerUrl = if (heroHasFocus) trailerUrl else null,
                        trailerAudioUrl = if (heroHasFocus) trailerTarget?.let { trailerAudioUrls[it.first] } else null,
                        muted = muted,
                        onTrailerEnded = { nextSlide(1) },
                        onHeroFocus = { focused, button ->
                            heroHasFocus = focused
                            if (focused) {
                                area = HomeArea.HERO
                                heroButton = button
                                focusedCard = null
                            }
                        },
                        onWatchNow = { heroItem?.let { onWatchNow(it.id, it.apiType, "") } },
                        onMoreInfo = { heroItem?.let { onNavigateToDetail(it.id, it.apiType, "") } },
                        topBar = if (menuOnTop && chrome != null) {
                            { modifier -> chrome.TopBar(onDown = { focusHero(heroButton) }, modifier = modifier) }
                        } else null,
                        topBarVisible = !hideMenu || chrome?.topBarFocused == true,
                        edgeToEdge = settings.heroEdgeToEdge
                    )
                }
                itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.kind }) { rowIndex, row ->
                    val state = rememberLazyListState(initialFirstVisibleItemIndex = (cols[row.key] ?: 0).coerceAtMost((row.items.size - 1).coerceAtLeast(0)))
                    rowStates[row.key] = state
                    NewLookRowView(
                        row = row,
                        dims = dims,
                        contentStart = contentStart,
                        listState = state,
                        col = cols[row.key] ?: 0,
                        focusedItemKey = focusedCard?.takeIf { it.first == row.key }?.second,
                        // Only the focused row sees it change, so moving between pills recomposes one row.
                        grandButton = if (focusedCard?.first == row.key) grandButton else 0,
                        focusRequester = rowRequester(row.key),
                        leftFocusRequester = leftRequester(row.key),
                        details = details,
                        trailerUrl = if (focusedCard?.first == row.key) trailerUrl else null,
                        trailerAudioUrl = if (focusedCard?.first == row.key) trailerTarget?.let { trailerAudioUrls[it.first] } else null,
                        muted = muted,
                        isWatched = isCatalogItemWatched,
                        onTrailerEnded = onHighlightTrailerEnded,
                        onCardFocused = { index, item, focused ->
                            if (focused && row.onlyPlaceholders) {
                                // A skeleton: hold the place, and load this row first.
                                area = HomeArea.ROWS
                                focusedRowKey = row.key
                                cols[row.key] = index
                                focusedCard = row.key to item.key
                                row.source?.let { s ->
                                    val addon = s.addonId
                                    val type = s.apiType
                                    val catalog = s.catalogId
                                    if (addon != null && type != null && catalog != null) {
                                        source.requestLazyCatalogLoad(catalogRowLegacyKey(addon, type, catalog))
                                    }
                                }
                            } else if (focused) {
                                area = HomeArea.ROWS
                                focusedRowKey = row.key
                                cols[row.key] = index
                                memory.cols[row.key] = index
                                focusedCard = row.key to item.key
                                loadDetails(item.metaPreview)
                                loadDetails(row.items.getOrNull(index + 1)?.metaPreview)
                                maybeLoadMore(source, row, index)
                                // Continue Watching opens the Streams screen directly, so warm that search.
                                if (row.key == MODERN_CONTINUE_WATCHING_ROW_KEY) {
                                    (item.payload as? ModernPayload.ContinueWatching)?.let {
                                        source.prefetchContinueWatching(it.item)
                                    }
                                }
                            } else if (focusedCard == row.key to item.key) {
                                focusedCard = null
                            }
                        },
                        onPrefetch = { item -> loadDetails(item.metaPreview) },
                        openItemKey = openPoster?.takeIf { it.first == row.key }?.second,
                        onClick = { item ->
                            if (row.onlyPlaceholders) return@NewLookRowView
                            when (val payload = item.payload) {
                                is ModernPayload.ContinueWatching -> onContinueWatchingClick(payload.item)
                                is ModernPayload.Catalog -> if (row.kind == RowKind.GRAND && grandButton == 0) {
                                    onWatchNow(payload.itemId, payload.itemType, payload.addonBaseUrl)
                                } else {
                                    onNavigateToDetail(payload.itemId, payload.itemType, payload.addonBaseUrl)
                                }
                                is ModernPayload.CollectionFolder -> onNavigateToFolderDetail(payload.collectionId, payload.folderId)
                            }
                        },
                        onLongClick = { item ->
                            if (row.onlyPlaceholders) return@NewLookRowView
                            when (val payload = item.payload) {
                                is ModernPayload.ContinueWatching -> cwOptions = payload.item
                                is ModernPayload.Catalog -> if (row.smart != null) {
                                    smartOptions = row to item
                                } else {
                                    item.metaPreview?.let { onCatalogItemLongPress(it, payload.addonBaseUrl) }
                                }
                                is ModernPayload.CollectionFolder -> Unit
                            }
                        }
                    )
                }
            }
        }
    }

    smartOptions?.let { (row, item) ->
        val info = row.smart ?: return@let
        val rowIndex = rows.indexOfFirst { it.key == row.key }
        fun choose(choice: SmartHighlightChoice) {
            smartOptions = null
            if (choice is SmartHighlightChoice.NotThisTitle || choice is SmartHighlightChoice.Snooze ||
                choice is SmartHighlightChoice.Block
            ) {
                scope.launch {
                    moveFocusOffCard(row.key, wholeRow = choice !is SmartHighlightChoice.NotThisTitle)
                    source.onSmartHighlightChoice(choice)
                }
                return
            }
            scope.launch { source.onSmartHighlightChoice(choice) }
            // The row may be renamed or replaced: once Home has updated, focus what took its place.
            val col = cols[row.key] ?: 0
            scope.launch {
                delay(350)
                val current = latestRows
                val index = current.indexOfFirst { it.key == row.key }.takeIf { it >= 0 }
                    ?: rowIndex.coerceIn(0, (current.size - 1).coerceAtLeast(0))
                val target = current.getOrNull(index) ?: return@launch
                cols[target.key] = if (target.key == row.key) col.coerceAtMost((target.items.size - 1).coerceAtLeast(0)) else (cols[target.key] ?: 0)
                area = HomeArea.ROWS
                focusedRowKey = target.key
                repeat(40) {
                    withFrameNanos { }
                    if (tryFocus(rowRequester(target.key))) return@launch
                }
            }
        }
        SmartHighlightOptionsDialog(
            itemTitle = item.title,
            rowTitle = row.title,
            aiNamed = info.aiNamed,
            onDismiss = { smartOptions = null },
            onNotThisTitle = {
                item.metaPreview?.let { choose(SmartHighlightChoice.NotThisTitle(info.themeId, it.id)) } ?: run { smartOptions = null }
            },
            onSnooze = { choose(SmartHighlightChoice.Snooze(info.themeId)) },
            onBlockName = { choose(SmartHighlightChoice.BlockName(row.title)) },
            onBlockHighlight = { choose(SmartHighlightChoice.Block(info.themeId)) },
            onMoreOptions = {
                smartOptions = null
                item.metaPreview?.let { onCatalogItemLongPress(it, "") }
            },
            image = item.metaPreview?.let { it.background ?: it.poster }
        )
    }

    cwOptions?.let { item ->
        ContinueWatchingOptionsDialog(
            item = item,
            onDismiss = { cwOptions = null },
            onRemove = {
                cwOptions = null
                val rowKey = focusedRowKey
                scope.launch {
                    if (rowKey != null) moveFocusOffCard(rowKey, wholeRow = false)
                    source.onEvent(
                        HomeEvent.OnRemoveContinueWatching(
                            contentId = when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentId
                                is ContinueWatchingItem.NextUp -> item.info.contentId
                            },
                            season = when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.season
                                is ContinueWatchingItem.NextUp -> item.info.season
                            },
                            episode = when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.episode
                                is ContinueWatchingItem.NextUp -> item.info.episode
                            },
                            isNextUp = item is ContinueWatchingItem.NextUp
                        )
                    )
                }
            },
            onDetails = {
                cwOptions = null
                when (item) {
                    is ContinueWatchingItem.InProgress -> onNavigateToDetail(item.progress.contentId, item.progress.contentType, "")
                    is ContinueWatchingItem.NextUp -> onNavigateToDetail(item.info.contentId, item.info.contentType, "")
                }
            },
            onStartFromBeginning = {
                cwOptions = null
                onContinueWatchingStartFromBeginning(item)
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                cwOptions = null
                onContinueWatchingPlayManually(item)
            }
        )
    }
}

private fun maybeLoadMore(home: NewLookHomeSource, row: NewLookRow, index: Int) {
    val memory = home.memory
    val source = row.source ?: return
    if (!source.supportsSkip || !source.hasMore || source.isLoading) return
    if (index < row.items.size - 5) return
    val catalogId = source.catalogId ?: return
    val addonId = source.addonId ?: return
    val apiType = source.apiType ?: return
    if (memory.loadMoreRequested[row.key] == row.items.size) return
    memory.loadMoreRequested[row.key] = row.items.size
    home.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, apiType))
}

/** Near-black, so the cards stand out (#080808 rather than #2c2c2c). */
private val PageGradient = Brush.verticalGradient(
    0f to Color(0xFF121212),
    0.38f to Color(0xFF0A0A0A),
    0.70f to Color(0xFF050505),
    1f to Color(0xFF020202)
)

/** Under the ambient wash until its first image is in. */
private val PageBase = Color(0xFF0A0A0A)

/** The page only scrolls where Home says; focus moving never drags it. */
private val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** How long a row waits after focus moves before checking its focused card is at its start. */
private const val ROW_PIN_CHECK_DELAY_MS = 300L

/** Rows pin the focused card to their start, so a row always starts with its focused card. */
private class PinToStart(private val startPx: Float) : BringIntoViewSpec {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = Glide.scroll

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val distance = offset - startPx
        return if (abs(distance) < 1f) 0f else distance
    }
}

@Composable
private fun HeroBlock(
    slides: List<HeroSlide>,
    slide: Int,
    contentStart: Dp,
    screenWidth: Dp,
    menuOnTop: Boolean,
    hideMenu: Boolean,
    drop: () -> Float,
    heroButton: Int?,
    watchFocus: FocusRequester,
    moreFocus: FocusRequester,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    muted: Boolean,
    onTrailerEnded: () -> Unit,
    onHeroFocus: (Boolean, Int) -> Unit,
    onWatchNow: () -> Unit,
    onMoreInfo: () -> Unit,
    topBar: (@Composable (Modifier) -> Unit)?,
    topBarVisible: Boolean,
    edgeToEdge: Boolean = false
) {
    val underBar = menuOnTop && !hideMenu
    val top = if (underBar) NewLookLayout.WindowTopUnderBar else NewLookLayout.WindowTop
    val height = if (underBar) NewLookLayout.WindowHeightUnderBar else NewLookLayout.WindowHeight
    val width = screenWidth - contentStart - NewLookLayout.WindowRight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Room for the hidden menu's bar while it has focus: the hero item grows by [drop] and its contents move
            // down inside it. A layer translation of the whole list moved the list's clip down with the page, which
            // cut off the bar and the glow above the new edge (a dark strip over the bar).
            .layout { measurable, constraints ->
                val dropPx = drop().dp.roundToPx()
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height + dropPx) { placeable.place(0, dropPx) }
            }
            .height(NewLookLayout.RowsTop)
    ) {
        // The ambient tint: the current title's colour glowing around the window, following the carousel. An
        // edge-to-edge hero has no window to glow around.
        if (!edgeToEdge) {
            val tint = rememberArtworkTint(slides.getOrNull(slide)?.backdrop)
            AmbientGlow(
                color = tint,
                modifier = Modifier
                    .offset { IntOffset(contentStart.roundToPx(), top.roundToPx()) }
                    .size(width, height)
            )
        }
        if (slides.isNotEmpty()) {
            HeroWindow(
                slides = slides,
                index = slide,
                focusedButton = heroButton,
                watchFocus = watchFocus,
                moreFocus = moreFocus,
                trailerUrl = trailerUrl,
                trailerAudioUrl = trailerAudioUrl,
                muted = muted,
                onTrailerEnded = onTrailerEnded,
                onButtonFocused = { onHeroFocus(true, it) },
                onWatchNow = onWatchNow,
                onMoreInfo = onMoreInfo,
                modifier = if (edgeToEdge) {
                    // The whole screen from the top, past this block's bottom: the first row draws over its fade. While
                    // the hidden bar drops in, it stays at the screen's top and grows by the drop, so the bar sits on
                    // the picture instead of over an empty strip.
                    Modifier
                        .layout { measurable, constraints ->
                            val dropPx = drop().dp.roundToPx()
                            val placeable = measurable.measure(
                                androidx.compose.ui.unit.Constraints.fixed(
                                    screenWidth.roundToPx(),
                                    NewLookLayout.EdgeHeroHeight.roundToPx() + dropPx
                                )
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -dropPx) }
                        }
                        .onFocusChanged { if (!it.hasFocus) onHeroFocus(false, 0) }
                } else {
                    Modifier
                        .offset(x = contentStart, y = top)
                        .size(width, height)
                        .onFocusChanged { if (!it.hasFocus) onHeroFocus(false, 0) }
                },
                edgeToEdge = edgeToEdge,
                textStart = if (edgeToEdge) contentStart else 24.dp,
                textBottom = if (edgeToEdge) {
                    NewLookLayout.EdgeHeroHeight - NewLookLayout.RowsTop + NewLookLayout.EdgeHeroTextAboveRows
                } else 20.dp
            )
        }
        // After the window: an edge-to-edge hero runs under the bar.
        if (topBar != null) {
            val alpha by animateFloatAsState(if (topBarVisible) 1f else 0f, tween(240), label = "topBarAlpha")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = if (hideMenu) (-28).dp else 12.dp)
                    .graphicsLayer { this.alpha = alpha },
                contentAlignment = Alignment.TopCenter
            ) {
                topBar(Modifier)
            }
        }
    }
}

@Composable
private fun NewLookRowView(
    row: NewLookRow,
    dims: NewLookDimens,
    contentStart: Dp,
    listState: LazyListState,
    col: Int,
    focusedItemKey: String?,
    grandButton: Int,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    details: Map<String, NewLookDetails>,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    muted: Boolean,
    isWatched: (MetaPreview) -> Boolean,
    onTrailerEnded: () -> Unit,
    onCardFocused: (Int, ModernCarouselItem, Boolean) -> Unit,
    onPrefetch: (ModernCarouselItem) -> Unit,
    onClick: (ModernCarouselItem) -> Unit,
    onLongClick: (ModernCarouselItem) -> Unit,
    /** The poster in this row that has opened out, if any. */
    openItemKey: String?
) {
    val density = LocalDensity.current
    val rowActive = focusedItemKey != null
    val cardTop = dims.rowHead + 3.dp
    val focusedIndex = row.items.indexOfFirst { it.key == focusedItemKey }
    val shownItem = row.items.getOrNull(if (focusedIndex >= 0) focusedIndex else col)

    // Highlighted rows show logos on every card: fetch the first few as the row comes on screen.
    if ((row.kind == RowKind.HIGHLIGHT || row.kind == RowKind.GRAND) && !row.collection) {
        LaunchedEffect(row.key, col) {
            row.items.drop(col).take(4).forEach(onPrefetch)
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(row.height(dims))) {
        // The row with focus leads; the others' titles step back a little. Faded as a layer, read while drawing, so
        // moving between rows doesn't recompose both titles for every frame of the fade.
        val titleAlpha = animateFloatAsState(if (rowActive) 1f else 0.72f, tween(200), label = "rowTitle")
        Text(
            text = row.title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = contentStart, end = 24.dp)
                .graphicsLayer {
                    alpha = titleAlpha.value
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                }
        )
        // A grand row's focused card glows in its title's colours, as the hero window does.
        if (row.kind == RowKind.GRAND) {
            val tint = rememberArtworkTint(if (rowActive) shownItem?.heroPreview?.backdrop ?: shownItem?.imageUrl else null)
            AmbientGlow(
                color = tint,
                visible = rowActive,
                radius = dims.cardRadius,
                modifier = Modifier
                    .offset { IntOffset(contentStart.roundToPx(), cardTop.roundToPx()) }
                    .size(dims.grandWidth, dims.grandHeight)
            )
        }
        // The focused card of a highlighted row tints its surroundings, only while the row has focus.
        if (row.kind == RowKind.HIGHLIGHT) {
            val tint = rememberArtworkTint(if (rowActive) shownItem?.heroPreview?.backdrop ?: shownItem?.imageUrl else null)
            val cardStart = contentStart + if (row.ranked) dims.rankPad else 0.dp
            AmbientGlow(
                color = tint,
                visible = rowActive,
                radius = dims.cardRadius,
                modifier = Modifier
                    .offset { IntOffset(cardStart.roundToPx(), cardTop.roundToPx()) }
                    .size(dims.highlightWidth, dims.highlightHeight)
            )
        }
        // The focused card is what asks to be scrolled into view; a ranked card sits rankPad into its item.
        val startPx = with(density) { (contentStart + if (row.ranked) dims.rankPad else 0.dp).toPx() }
        // Now and then that scroll falls short (seen two cards short after quick moves), and the passed cards,
        // faded out, leave a gap before the focused one. Once the row is still, put its card back at the start.
        LaunchedEffect(listState, col) {
            delay(ROW_PIN_CHECK_DELAY_MS)
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == col }
            if (col < row.items.size && (item == null || abs(item.offset) > 2)) listState.animateScrollToItem(col)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(startPx) { PinToStart(startPx) }) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(start = contentStart, end = 720.dp),
                horizontalArrangement = Arrangement.spacedBy(dims.gap),
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = cardTop)
                    .height(row.cardHeight(dims))
                    .focusRestorer(focusRequester)
                    .focusGroup()
            ) {
                itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                    val passed by animateFloatAsState(if (index < col) 0f else 1f, tween(200), label = "passed")
                    val modifier = Modifier
                        .graphicsLayer { alpha = passed }
                        .then(
                            when (index) {
                                col -> Modifier.focusRequester(focusRequester)
                                col - 1 -> Modifier.focusRequester(leftFocusRequester)
                                else -> Modifier
                            }
                        )
                    RowCard(
                        row = row,
                        item = item,
                        index = index,
                        dims = dims,
                        focused = item.key == focusedItemKey,
                        grandButton = grandButton,
                        details = item.metaPreview?.id?.let { details[it] },
                        trailerUrl = if (item.key == focusedItemKey) trailerUrl else null,
                        trailerAudioUrl = if (item.key == focusedItemKey) trailerAudioUrl else null,
                        muted = muted,
                        isWatched = isWatched,
                        onTrailerEnded = onTrailerEnded,
                        onFocusChanged = { onCardFocused(index, item, it) },
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) },
                        modifier = modifier,
                        open = item.key == focusedItemKey && item.key == openItemKey
                    )
                }
            }
        }
        if (row.kind != RowKind.WIDE && row.kind != RowKind.GRAND && shownItem != null) {
            val metaAlpha = animateFloatAsState(if (rowActive) 1f else 0f, tween(200), label = "rowMeta")
            // Only the focused row (and one fading out) has its details composed: rows coming on screen as the page
            // scrolls don't lay out a synopsis nobody sees.
            val fading by remember { derivedStateOf { metaAlpha.value > 0f } }
            if (rowActive || fading) {
                Box(
                    modifier = Modifier
                        .offset(x = contentStart + if (row.ranked) dims.rankPad else 0.dp, y = dims.rowHead + row.cardHeight(dims) + 10.dp)
                        .graphicsLayer { alpha = metaAlpha.value }
                ) {
                    RowMeta(row, shownItem, shownItem.metaPreview?.id?.let { details[it] }, dims)
                }
            }
        }
    }
}

@Composable
private fun RowCard(
    row: NewLookRow,
    item: ModernCarouselItem,
    index: Int,
    dims: NewLookDimens,
    focused: Boolean,
    grandButton: Int,
    details: NewLookDetails?,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    muted: Boolean,
    isWatched: (MetaPreview) -> Boolean,
    onTrailerEnded: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    open: Boolean
) {
    val preview = item.heroPreview
    val backdrop = preview.backdrop ?: details?.backdrop ?: item.imageUrl
    val logo = preview.logo ?: details?.logo
    when {
        row.onlyPlaceholders -> SkeletonCard(row.cardWidth(dims), row.cardHeight(dims), onFocusChanged, modifier)
        row.kind == RowKind.GRAND -> {
            val meta = item.metaPreview
            val isSeries = preview.isSeries || meta?.apiType.equals("series", true)
            val typeLabel = preview.contentTypeText
                ?: stringResource(if (isSeries) R.string.home_type_show else R.string.home_type_movie)
            val slide = remember(item, details, backdrop, logo, typeLabel) {
                HeroSlide(
                    key = item.key,
                    title = item.title,
                    backdrop = backdrop,
                    logo = logo,
                    typeLabel = typeLabel,
                    genre = preview.genres.firstOrNull() ?: meta?.genres?.firstOrNull() ?: details?.genres?.firstOrNull(),
                    year = yearOf(preview.yearText) ?: yearOf(meta?.releaseInfo) ?: yearOf(details?.year),
                    rating = preview.imdbText ?: meta?.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                    ratingIsImdb = meta?.id?.startsWith("tt") == true || meta?.imdbId != null,
                    description = preview.description ?: meta?.description ?: details?.overview,
                    tag = details?.tag,
                    tagSeason = details?.tagSeason
                )
            }
            GrandCard(
                slide = slide,
                focused = focused,
                button = grandButton,
                trailerUrl = trailerUrl,
                trailerAudioUrl = trailerAudioUrl,
                muted = muted,
                dims = dims,
                onTrailerEnded = onTrailerEnded,
                onClick = onClick,
                onLongClick = onLongClick,
                onFocusChanged = onFocusChanged,
                modifier = modifier
            )
        }
        row.kind == RowKind.WIDE -> {
            val payload = item.payload as? ModernPayload.ContinueWatching
            val cw = payload?.item
            val image = when (cw) {
                is ContinueWatchingItem.InProgress -> cw.episodeThumbnail ?: cw.progress.backdrop ?: cw.progress.poster
                is ContinueWatchingItem.NextUp -> cw.info.thumbnail ?: cw.info.backdrop ?: cw.info.poster
                null -> item.imageUrl
            }
            // In progress: the episode (no time left; the bar along the bottom shows how far in). Next up: its
            // label ("New episode"...).
            val episode = when (cw) {
                is ContinueWatchingItem.InProgress -> item.subtitle?.takeIf { it.isNotBlank() && !it.equals(item.title, true) }
                is ContinueWatchingItem.NextUp -> preview.secondaryHighlightText?.lowercase()?.replaceFirstChar { it.titlecase() }
                null -> null
            }
            ContinueWatchingCard(
                image = image,
                title = item.title,
                episode = episode,
                progress = (cw as? ContinueWatchingItem.InProgress)?.progress?.progressPercentage,
                dims = dims,
                onClick = onClick,
                onLongClick = onLongClick,
                onFocusChanged = onFocusChanged,
                modifier = modifier
            )
        }
        row.kind == RowKind.POSTER -> PosterCard(
            poster = preview.poster ?: item.imageUrl,
            backdrop = backdrop,
            logo = logo,
            title = item.title,
            watched = item.metaPreview?.let(isWatched) == true,
            open = open,
            dims = dims,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = onFocusChanged,
            modifier = modifier,
            trailerUrl = if (open) trailerUrl else null,
            trailerAudioUrl = if (open) trailerAudioUrl else null,
            muted = muted
        )
        row.kind == RowKind.COLLECTION -> CollectionTile(
            item = item,
            width = row.cardWidth(dims),
            height = row.cardHeight(dims),
            focused = focused,
            onClick = onClick,
            onFocusChanged = onFocusChanged,
            modifier = modifier
        )
        row.collection -> CollectionTile(
            item = item,
            width = dims.highlightWidth,
            height = dims.highlightHeight,
            focused = focused,
            onClick = onClick,
            onFocusChanged = onFocusChanged,
            modifier = modifier,
            highlighted = true
        )
        row.ranked -> Box(modifier = modifier.size(dims.rankPad + dims.highlightWidth, dims.highlightHeight)) {
            RankNumber(
                rank = index + 1,
                dims = dims,
                focused = focused,
                // The digits' foot sits on the card's bottom edge. It used to be pushed 0.16 × the font
                // size lower, past the row's clip 30 dp under the cards, which cut the numbers off flat (seen
                // in the JVM preview). RankNumber keeps the round digits whole.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = dims.highlightWidth - (dims.rankFontSize * 0.07f).dp)
            )
            HighlightCard(
                backdrop = backdrop,
                logo = logo,
                title = item.title,
                trailerUrl = trailerUrl,
                trailerAudioUrl = trailerAudioUrl,
                playing = focused,
                muted = muted,
                dims = dims,
                onTrailerEnded = onTrailerEnded,
                onClick = onClick,
                onLongClick = onLongClick,
                onFocusChanged = onFocusChanged,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        else -> HighlightCard(
            backdrop = backdrop,
            logo = logo,
            title = item.title,
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            playing = focused,
            muted = muted,
            dims = dims,
            onTrailerEnded = onTrailerEnded,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChanged = onFocusChanged,
            modifier = modifier
        )
    }
}

/** Under the focused card: the details line, and for highlighted rows the synopsis under it. */
@Composable
private fun RowMeta(row: NewLookRow, item: ModernCarouselItem, details: NewLookDetails?, dims: NewLookDimens) {
    val preview = item.heroPreview
    if (row.collection || row.kind == RowKind.COLLECTION) {
        val folder = item.payload as? ModernPayload.CollectionFolder
        val animated = folder != null && (!folder.heroVideoUrl.isNullOrBlank() || (folder.focusGifEnabled && !folder.focusGifUrl.isNullOrBlank()))
        DetailsLine(
            type = stringResource(R.string.home_collection),
            genres = listOfNotNull(folder?.collectionTitle?.takeIf { it != item.title }),
            year = if (animated) stringResource(R.string.home_animated_chip) else null,
            runtime = null,
            rating = null,
            ratingIsImdb = false
        )
        return
    }
    val meta = item.metaPreview
    val isSeries = preview.isSeries
    val genres = preview.genres.list.ifEmpty { details?.genres.orEmpty() }
    val rating = preview.imdbText ?: details?.rating?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }
    val ratingIsImdb = preview.imdbText != null && (meta?.id?.startsWith("tt") == true || meta?.imdbId != null)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailsLine(
            type = stringResource(if (isSeries) R.string.home_type_show else R.string.home_type_movie),
            genres = genres,
            year = yearOf(preview.yearText) ?: yearOf(meta?.released) ?: yearOf(details?.year),
            runtime = preview.runtimeText ?: formatRuntime(details?.runtimeMinutes),
            rating = rating,
            ratingIsImdb = ratingIsImdb
        )
        if (row.kind == RowKind.HIGHLIGHT) {
            val synopsis = preview.description ?: details?.overview
            if (!synopsis.isNullOrBlank()) {
                Text(
                    text = synopsis,
                    color = Color(0xFFB8B8B8),
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(dims.highlightWidth)
                )
            }
        }
    }
}

private val YearPattern = Regex("""\b(19|20)\d{2}\b""")

/** "2026" from "July 4, 2026", "2022–" or "2026-07-04". */
internal fun yearOf(text: String?): String? = text?.let { YearPattern.find(it)?.value }

/** A single movie or episode to search sources for. */
internal data class ExpansionTarget(val type: String, val videoId: String, val season: Int?, val episode: Int?)

/**
 * What an opened poster's Play would search for: the episode its Continue Watching card holds (in progress or next
 * up), a movie itself, or a show's first episode, as its details page offers "Play S1 E1". Never a whole season.
 */
internal fun expansionPrefetchTarget(
    itemId: String,
    apiType: String,
    continueWatching: List<ContinueWatchingItem>
): ExpansionTarget? {
    if (itemId.isBlank() || itemId.startsWith("__placeholder_")) return null
    for (cw in continueWatching) {
        when (cw) {
            is ContinueWatchingItem.InProgress -> if (cw.progress.contentId == itemId) {
                return ExpansionTarget(cw.progress.contentType, cw.progress.videoId, cw.progress.season, cw.progress.episode)
            }
            is ContinueWatchingItem.NextUp -> if (cw.info.contentId == itemId) {
                return ExpansionTarget(cw.info.contentType, cw.info.videoId, cw.info.season, cw.info.episode)
            }
        }
    }
    val series = apiType.equals("series", true) || apiType.equals("tv", true)
    return if (series) ExpansionTarget(apiType, "$itemId:1:1", 1, 1) else ExpansionTarget(apiType, itemId, null, null)
}
