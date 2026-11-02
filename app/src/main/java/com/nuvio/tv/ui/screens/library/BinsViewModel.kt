package com.nuvio.tv.ui.screens.library

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinEntry
import com.nuvio.tv.core.bins.BinNameResult
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.core.bins.BinsReducer
import com.nuvio.tv.core.bins.BinsRepository
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title in an open bin, ready to draw. */
@Immutable
data class BinTitle(val entry: BinEntry, val preview: MetaPreview)

@Immutable
data class BinsUiState(
    val loaded: Boolean = false,
    /** Every bin, in the chosen sort. */
    val bins: List<Bin> = emptyList(),
    val sort: BinSort = BinSort.RECENTLY_VIEWED,
    /** The bin being looked inside, if any. */
    val openBin: Bin? = null,
    val openTitles: List<BinTitle> = emptyList(),
    /** Custom order mode: pick one, then pick another to swap them. */
    val reordering: Boolean = false,
    /** The bin id or title key picked to move. */
    val picked: String? = null,
    val posterWidthDp: Int = 126,
    /** Layout's custom poster URL for the Library (blank when off), for the bins' own covers. */
    val posterPattern: String = "",
    val posterCornerDp: Int = 12,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet()
)

private data class Local(
    val openBinId: String? = null,
    val reordering: Boolean = false,
    val picked: String? = null
)

@HiltViewModel
class BinsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BinsRepository,
    private val layoutPreferences: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val metaRepository: MetaRepository,
    private val trackingLibrary: TrackingLibraryProviderRegistry
) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    private val posterLayout = combine(
        layoutPreferences.posterCardWidthDp,
        layoutPreferences.posterCardCornerRadiusDp,
        layoutPreferences.customPosterUrlPattern,
        layoutPreferences.customPosterEnabledScreens
    ) { width, corner, pattern, screens ->
        Triple(width, corner, patternForScreen(pattern, CustomPosterScreen.LIBRARY, screens))
    }

    private val watched = combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeriesStateHolder.fullyWatchedSeriesIds
    ) { movies, series -> movies to series }

    /** Every list on the connected trackers (Trakt, Simkl, MDBList), as read-only bins. */
    private val listBins: Flow<List<Bin>> = trackingLibrary.providers().let { providers ->
        if (providers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                providers.map { provider ->
                    provider.isAuthenticated.flatMapLatest { signedIn ->
                        if (!signedIn) flowOf(emptyList())
                        else combine(provider.tabs, provider.items) { tabs, items -> listBinsOf(provider.providerId, tabs, items) }
                    }
                }
            ) { perProvider -> perProvider.flatMap { it } }
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<BinsUiState> = combine(repository.state, local, posterLayout, watched, listBins) { state, local, layout, watched, lists ->
        val (width, corner, pattern) = layout
        // The viewer's own bins in the chosen sort, then the trackers' lists in the trackers' order.
        val bins = BinsReducer.sortedBins(state.bins, state.sort) + lists
        val open = local.openBinId?.let { id -> bins.firstOrNull { it.id == id } }
        val titles = open?.let { bin ->
            val sorted = BinsReducer.sortedEntries(bin.entries, state.sort)
            val previews = sorted.map { it.toPreview() }.withCustomPosterUrls(pattern)
            sorted.zip(previews) { entry, preview -> BinTitle(entry, preview) }
        }.orEmpty()
        BinsUiState(
            loaded = true,
            bins = bins,
            sort = state.sort,
            openBin = open,
            openTitles = titles,
            reordering = local.reordering,
            picked = local.picked,
            posterWidthDp = width,
            posterCornerDp = corner,
            posterPattern = pattern,
            watchedMovieIds = watched.first,
            watchedSeriesIds = watched.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BinsUiState())

    init {
        // The lists are only as fresh as the trackers' last sync: catch up when the Library opens.
        viewModelScope.launch {
            trackingLibrary.providers().forEach { provider ->
                if (provider.isAuthenticated.first()) {
                    runCatching { provider.refresh(TrackingRefreshIntent.AUTOMATIC) }
                }
            }
        }
    }

    fun openBin(bin: Bin) {
        local.update { Local(openBinId = bin.id) }
        if (bin.source == null) viewModelScope.launch { repository.markBinViewed(bin.id) }
    }

    private fun showReadOnly(bin: Bin) {
        showMessage(context.getString(R.string.bins_list_read_only, bin.source))
    }

    fun closeBin() {
        local.update { Local() }
    }

    fun selectSort(sort: BinSort) {
        local.update { it.copy(reordering = false, picked = null) }
        viewModelScope.launch { repository.setSort(sort) }
    }

    /** Custom order on (starting from what's shown) or off. */
    fun toggleReorder() {
        val state = uiState.value
        state.openBin?.takeIf { it.source != null }?.let { showReadOnly(it); return }
        if (state.reordering) {
            local.update { it.copy(reordering = false, picked = null) }
            showMessage(context.getString(R.string.bins_custom_order_saved))
            return
        }
        local.update { it.copy(reordering = true, picked = null) }
        viewModelScope.launch {
            val open = state.openBin
            if (open != null) {
                repository.useDisplayedEntryOrder(open.id, state.openTitles.map { it.entry.key })
            } else {
                repository.useDisplayedBinOrder(state.bins.map { it.id })
            }
        }
        showMessage(context.getString(if (state.openBin != null) R.string.bins_reorder_hint_titles else R.string.bins_reorder_hint_bins))
    }

    /** In custom order: the first pick is held; picking another swaps the two; picking it again lets go. */
    fun pick(target: String) {
        val current = local.value
        if (!current.reordering) return
        val held = current.picked
        when {
            held == null -> local.update { it.copy(picked = target) }
            held == target -> local.update { it.copy(picked = null) }
            else -> {
                local.update { it.copy(picked = null) }
                val openId = current.openBinId
                viewModelScope.launch {
                    if (openId != null) repository.swapEntries(openId, held, target) else repository.swapBins(held, target)
                }
            }
        }
    }

    /** Back while custom order is on: first drop the held pick, then leave custom order. Returns whether it was used. */
    fun backInReorder(): Boolean {
        val current = local.value
        if (!current.reordering) return false
        if (current.picked != null) local.update { it.copy(picked = null) } else toggleReorder()
        return true
    }

    fun toggleStar(bin: Bin) {
        if (bin.source != null) return showReadOnly(bin)
        viewModelScope.launch { repository.toggleStar(bin.id) }
        showMessage(
            if (bin.starred) context.getString(R.string.bins_unstarred)
            else context.getString(R.string.bins_starred, bin.name)
        )
    }

    /** Null once the bin exists; otherwise why not, for the dialog to show. */
    suspend fun createBin(name: String): String? = when (val result = repository.createBin(name)) {
        is BinNameResult.Ok -> {
            showMessage(context.getString(R.string.bins_created, com.nuvio.tv.core.bins.cleanBinName(name)))
            null
        }
        BinNameResult.Blank -> context.getString(R.string.bins_name_blank)
        BinNameResult.Duplicate -> context.getString(R.string.bins_name_taken, com.nuvio.tv.core.bins.cleanBinName(name))
    }

    suspend fun renameBin(bin: Bin, name: String): String? = when (repository.renameBin(bin.id, name)) {
        is BinNameResult.Ok -> null
        BinNameResult.Blank -> context.getString(R.string.bins_name_blank)
        BinNameResult.Duplicate -> context.getString(R.string.bins_name_taken, com.nuvio.tv.core.bins.cleanBinName(name))
    }

    fun deleteBin(bin: Bin) {
        if (local.value.openBinId == bin.id) closeBin()
        viewModelScope.launch { repository.deleteBin(bin.id) }
        showMessage(context.getString(R.string.bins_deleted, bin.name))
    }

    fun removeTitle(bin: Bin, title: BinTitle) {
        viewModelScope.launch { repository.remove(bin.id, title.entry.id, title.entry.type) }
        showMessage(context.getString(R.string.bins_removed_from, bin.name))
    }

    fun onTitleOpened(bin: Bin, title: BinTitle) {
        if (bin.source == null) viewModelScope.launch { repository.markEntryViewed(bin.id, title.entry.key) }
        prefetch(title.entry)
    }

    fun cachedBackdrop(title: BinTitle): String? = metaRepository.getCachedMeta(title.entry.type, title.entry.id)?.backdropUrl

    private var prefetchJob: Job? = null

    /** Warms the details page for the focused title, so opening it is instant. */
    fun prefetch(entry: BinEntry) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(150)
            // firstOrNull: a lookup that ends with no result must not crash the screen.
            runCatching {
                metaRepository.getMetaFromAllAddons(type = entry.type, id = entry.id)
                    .firstOrNull { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            }
        }
    }

    private fun showMessage(text: String) {
        _message.value = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(2_400)
            _message.value = null
        }
    }
}

internal fun BinEntry.toPreview(): MetaPreview = MetaPreview(
    id = id,
    type = ContentType.fromString(type),
    rawType = type,
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres
)

/** A tracker's lists as bins: one per list that has titles, named for the list (the card names the tracker). */
internal fun listBinsOf(provider: TrackingProviderId, tabs: List<LibraryListTab>, items: List<LibraryEntry>): List<Bin> {
    val label = when (provider) {
        TrackingProviderId.TRAKT -> "Trakt"
        TrackingProviderId.SIMKL -> "Simkl"
        TrackingProviderId.MDBLIST -> "MDBList"
    }
    return tabs.mapIndexedNotNull { index, tab ->
        val entries = items
            .filter { tab.key in it.listKeys }
            .mapIndexed { position, item ->
                BinEntry(
                    id = item.id,
                    type = item.type,
                    name = item.name,
                    poster = item.poster,
                    background = item.background,
                    logo = item.logo,
                    description = item.description,
                    releaseInfo = item.releaseInfo,
                    imdbRating = item.imdbRating,
                    genres = item.genres,
                    addonBaseUrl = item.addonBaseUrl,
                    addedAt = item.listedAt,
                    order = item.listRanks[tab.key] ?: position
                )
            }
            .distinctBy { it.key }
        if (entries.isEmpty()) return@mapIndexedNotNull null
        Bin(
            id = "list:${provider.storageId}:${tab.key}",
            name = tab.title,
            order = LIST_BIN_ORDER_BASE + index,
            entries = entries,
            source = label
        )
    }
}

private const val LIST_BIN_ORDER_BASE = 100_000
