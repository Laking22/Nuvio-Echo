package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.util.hasNoReleaseInfo
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.findCollectionCatalog
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.core.poster.withCustomPosterUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val posterCardCornerRadiusDp: Int = 12,
    val tabs: List<FolderTab> = emptyList(),
    /** Tabbed folders: the source being shown. */
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true,
    val movieWatchedStatus: Map<String, Boolean> = emptyMap()
)

/** Rows: where focus was, so coming back from a title lands on the same card, with the page and rows as they were. */
@androidx.compose.runtime.Immutable
data class FolderRowsFocus(
    val rowKey: String? = null,
    val itemIndex: Int = 0,
    val pageIndex: Int = 0,
    val pageOffset: Int = 0,
    /** Each row's first visible card. */
    val rowStarts: Map<String, Int> = emptyMap()
) {
    val saved: Boolean get() = rowKey != null
}

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val rawType: String = "",
    val source: CollectionSource? = null,
    val catalogRow: CatalogRow? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isAllTab: Boolean = false
)

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbMetadataService: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    private val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    private val traktPublicListSourceResolver: TraktPublicListSourceResolver,
    detailsLoader: com.nuvio.tv.core.browse.BrowseDetailsLoader,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    private var movieWatchedJob: Job? = null
    private var enrichFocusJob: Job? = null
    private val enrichedItemIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val backgroundMetaPrefetchedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Tabbed folders: the focused poster's details, for the panel beside the grid. */
    val focusedDetails = com.nuvio.tv.ui.screens.browse.FocusedDetailsTracker(viewModelScope, detailsLoader)

    /** Tabbed folders: the poster each tab last had focus on, so coming back from a title lands on it again. */
    val tabFocusIndex = mutableMapOf<Int, Int>()

    private val _rowsFocus = MutableStateFlow(FolderRowsFocus())
    val rowsFocus: StateFlow<FolderRowsFocus> = _rowsFocus.asStateFlow()


    init {
        posterOptions.bind(viewModelScope)
        loadFolder()
        observeWatchedStatusCombined()
    }

    private fun observeWatchedStatusCombined() {
        movieWatchedJob = viewModelScope.launch {
            combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds,
                _uiState.map { state -> state.tabs.flatMap { it.catalogRow?.items.orEmpty() } }
                    .distinctUntilChanged()
            ) { movieWatchedIds, seriesWatchedIds, allItems ->
                Triple(movieWatchedIds, seriesWatchedIds, allItems)
            }.collectLatest { (movieWatchedIds, seriesWatchedIds, allItems) ->
                val newStatus = mutableMapOf<String, Boolean>()
                allItems.forEach { item ->
                    val key = com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)
                    val isWatched = when (item.apiType) {
                        "movie" -> item.id in movieWatchedIds
                        "series", "tv" -> item.id in seriesWatchedIds
                        else -> false
                    }
                    newStatus[key] = isWatched
                }
                _uiState.update { s ->
                    if (s.movieWatchedStatus == newStatus) s else s.copy(movieWatchedStatus = newStatus)
                }
            }
        }
    }

    private val hasAllTab: Boolean
        get() {
            val state = _uiState.value
            val folder = state.folder ?: return false
            return state.tabs.firstOrNull()?.isAllTab == true && folder.sources.size >= 2
        }

    private fun loadFolder() {
        viewModelScope.launch {
            val collections = collectionsDataStore.collections.first()
            val collection = collections.find { it.id == collectionId }
            val folder = collection?.folders?.find { it.id == folderId }

            if (folder == null || folder.sources.isEmpty()) {
                _uiState.update {
                    it.copy(
                        folder = folder,
                        collectionTitle = collection?.title ?: "",
                        viewMode = collection?.viewMode ?: FolderViewMode.TABBED_GRID,
                        isLoading = false
                    )
                }
                return@launch
            }

            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val catalogTypeSuffixEnabled = layoutPreferenceDataStore.catalogTypeSuffixEnabled.first()
            val hideUnreleasedContent = layoutPreferenceDataStore.hideUnreleasedContent.first()
            val posterCardCornerRadiusDp = layoutPreferenceDataStore.posterCardCornerRadiusDp.first()
            val showAll = (collection?.showAllTab ?: true) && folder.sources.size >= 2

            val viewMode = collection?.viewMode ?: FolderViewMode.TABBED_GRID

            val sourceTabs = folder.sources.map { source ->
                val (name, typeLabel, rawType) = when (source) {
                    is AddonCatalogCollectionSource -> {
                        val addon = addons.find { it.id == source.addonId }
                        val catalog = addon?.catalogs?.findCollectionCatalog(source.type, source.catalogId)
                            ?: addon?.catalogs?.findCollectionCatalog(source.type, source.catalogId.substringBefore(","))
                            ?: addons.firstNotNullOfOrNull { a -> a.catalogs.findCollectionCatalog(source.type, source.catalogId) }
                        val labels = buildAddonTabLabels(source, catalog?.name)
                        Triple(labels.first, labels.second, catalog?.apiType ?: source.type)
                    }
                    is TmdbCollectionSource -> Triple(source.title, buildTmdbTypeLabel(source), source.mediaType.value.toCollectionRawType())
                    is TraktCollectionSource -> Triple(source.title, buildTraktTypeLabel(source), source.mediaType.value.toCollectionRawType())
                }
                FolderTab(label = name, typeLabel = typeLabel, rawType = rawType, source = source, isLoading = true)
            }

            val tabs = if (showAll) {
                listOf(
                    FolderTab(
                        label = appContext.getString(com.nuvio.tv.R.string.collections_tab_all),
                        isLoading = true,
                        isAllTab = true
                    )
                ) + sourceTabs
            } else {
                sourceTabs
            }

            _uiState.update {
                it.copy(
                    folder = folder,
                    collectionTitle = collection?.title ?: "",
                    viewMode = viewMode,
                    catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
                    hideUnreleasedContent = hideUnreleasedContent,
                    posterCardCornerRadiusDp = posterCardCornerRadiusDp,
                    tabs = tabs,
                    isLoading = false
                )
            }

            // The offset for source tab indices when "All" tab is present
            val tabOffset = if (showAll) 1 else 0

            folder.sources.forEachIndexed { index, source ->
                loadSourceForTab(index + tabOffset, source)
            }
        }
    }

    private fun rebuildAllTab() {
        val state = _uiState.value
        if (!hasAllTab) return
        val sourceTabs = state.tabs.drop(1) // skip the All tab
        val anyLoading = sourceTabs.any { tab ->
            tab.catalogRow?.isLoading == true || tab.isLoading
        }
        // Only include rows that have finished loading
        val loadedRows = sourceTabs.mapNotNull { tab ->
            tab.catalogRow?.takeIf { !it.isLoading }
        }

        if (loadedRows.isEmpty()) return

        val currentAllRow = state.tabs.getOrNull(0)?.catalogRow

        if (anyLoading && currentAllRow != null && currentAllRow.items.isNotEmpty()) {
            _uiState.update { s ->
                val tabs = s.tabs.toMutableList()
                tabs[0] = tabs[0].copy(
                    isLoading = false,
                    catalogRow = currentAllRow.copy(isLoading = true)
                )
                s.copy(tabs = tabs)
            }
            return
        }

        // All pending loads completed — compute the new batch of items via round-robin
        // and APPEND them to the existing ALL tab content (stable scroll position).
        val mergedItems = roundRobinMerge(loadedRows.map { it.items })
        val existingItems = currentAllRow?.items.orEmpty()
        val existingIds = existingItems.mapTo(mutableSetOf()) { it.id }
        val newItems = mergedItems.filter { it.id !in existingIds }
        val finalItems = existingItems + newItems

        val templateRow = loadedRows.first()
        val hasMore = sourceTabs.any { tab -> tab.catalogRow?.hasMore == true }
        val mergedRow = templateRow.copy(
            catalogName = "All",
            items = finalItems,
            hasMore = hasMore,
            isLoading = false
        )

        _uiState.update { s ->
            val tabs = s.tabs.toMutableList()
            tabs[0] = tabs[0].copy(
                catalogRow = mergedRow,
                isLoading = false
            )
            s.copy(tabs = tabs)
        }
    }

    private fun roundRobinMerge(lists: List<List<MetaPreview>>): List<MetaPreview> {
        val result = mutableListOf<MetaPreview>()
        val seen = mutableSetOf<String>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxSize) {
            for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                if (seen.add(item.id)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun loadSourceForTab(tabIndex: Int, source: CollectionSource) {
        when (source) {
            is AddonCatalogCollectionSource -> loadAddonCatalogForTab(tabIndex, source)
            is TmdbCollectionSource -> loadTmdbSourceForTab(tabIndex, source, page = 1, append = false)
            is TraktCollectionSource -> loadTraktSourceForTab(tabIndex, source, page = 1, append = false)
        }
    }

    private fun loadAddonCatalogForTab(tabIndex: Int, source: AddonCatalogCollectionSource) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val addon = addons.find { it.id == source.addonId }

            if (addon == null) {
                _uiState.update { state ->
                    val tabs = state.tabs.toMutableList()
                    if (tabIndex < tabs.size) {
                        tabs[tabIndex] = tabs[tabIndex].copy(
                            isLoading = false,
                            error = appContext.getString(R.string.addon_error_not_found)
                        )
                    }
                    state.copy(tabs = tabs)
                }
                return@launch
            }

            var catalog = addon.catalogs.findCollectionCatalog(source.type, source.catalogId)
                ?: addon.catalogs.findCollectionCatalog(source.type, source.catalogId.substringBefore(","))
            // If the catalog wasn't found in the declared addon, search all installed addons.
            var effectiveAddon: com.nuvio.tv.domain.model.Addon = addon
            if (catalog == null) {
                for (a in addons) {
                    val match = a.catalogs.findCollectionCatalog(source.type, source.catalogId)
                    if (match != null) {
                        effectiveAddon = a
                        catalog = match
                        break
                    }
                }
            }
            val tab = _uiState.value.tabs.getOrNull(tabIndex)
            val catalogName = catalog?.name
                ?: tab?.label?.takeIf { it.isNotBlank() }
                ?: source.catalogId

            val supportsSkip = catalog?.supportsExtra("skip") ?: false
            val skipStep = catalog?.skipStep() ?: 100
            val extraArgs = buildCatalogExtraArgs(source)

            catalogRepository.getCatalog(
                addonBaseUrl = effectiveAddon.baseUrl,
                addonId = effectiveAddon.id,
                addonName = effectiveAddon.displayName,
                catalogId = source.catalogId,
                catalogName = catalogName,
                type = catalog?.apiType ?: source.type,
                skip = 0,
                skipStep = skipStep,
                extraArgs = extraArgs,
                supportsSkip = supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.COLLECTIONS
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(
                                    catalogRow = result.data.filteredForRelease(state.hideUnreleasedContent),
                                    isLoading = false
                                )
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = result.message)
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun loadMoreItems(tabIndex: Int) {
        val state = _uiState.value
        val tab = state.tabs.getOrNull(tabIndex) ?: return

        // All tab: load more from all source tabs that still have more
        if (tab.isAllTab && hasAllTab) {
            val tabOffset = 1
            state.tabs.drop(tabOffset).forEachIndexed { index, sourceTab ->
                val sourceRow = sourceTab.catalogRow ?: return@forEachIndexed
                if (sourceRow.hasMore && !sourceRow.isLoading) {
                    loadMoreItems(index + tabOffset)
                }
            }
            return
        }

        val row = tab.catalogRow ?: return
        if (!row.hasMore || row.isLoading) return

        if (tab.source is TmdbCollectionSource) {
            loadTmdbSourceForTab(tabIndex, tab.source, page = row.currentPage + 1, append = true)
            return
        }

        if (tab.source is TraktCollectionSource) {
            loadTraktSourceForTab(tabIndex, tab.source, page = row.currentPage + 1, append = true)
            return
        }

        // Mark the tab's catalogRow as loading
        _uiState.update { s ->
            val tabs = s.tabs.toMutableList()
            if (tabIndex < tabs.size) {
                tabs[tabIndex] = tabs[tabIndex].copy(
                    catalogRow = row.copy(isLoading = true)
                )
            }
            s.copy(tabs = tabs)
        }
        rebuildAllTab()

        viewModelScope.launch {
            val nextSkip = row.nextCatalogSkip()

            catalogRepository.getCatalog(
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                addonName = row.addonName,
                catalogId = row.catalogId,
                catalogName = row.catalogName,
                type = row.apiType,
                skip = nextSkip,
                skipStep = row.skipStep,
                extraArgs = row.extraArgs,
                supportsSkip = row.supportsSkip,
                posterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.COLLECTIONS
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { s ->
                            val currentTab = s.tabs.getOrNull(tabIndex)
                            val currentRow = currentTab?.catalogRow ?: return@update s
                            val incomingFiltered = if (s.hideUnreleasedContent) {
                                val today = java.time.LocalDate.now()
                                result.data.items.filterNot { it.isUnreleased(today) }
                            } else {
                                result.data.items
                            }
                            val mergedRow = currentRow.mergeCatalogPage(result.data, incomingFiltered)

                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = mergedRow.copy(isLoading = false)
                            )
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val currentRow = s.tabs.getOrNull(tabIndex)?.catalogRow ?: return@update s
                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = currentRow.copy(isLoading = false)
                            )
                            s.copy(tabs = tabs)
                        }
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun loadMoreForCatalog(catalogId: String, addonId: String, type: String) {
        val state = _uiState.value
        val tabIndex = state.tabs.indexOfFirst { tab ->
            val row = tab.catalogRow ?: return@indexOfFirst false
            row.catalogId == catalogId && row.addonId == addonId && row.apiType == type
        }
        if (tabIndex >= 0) loadMoreItems(tabIndex)
    }

    fun selectTab(index: Int) {
        if (_uiState.value.selectedTabIndex != index) _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun saveRowsFocus(focus: FolderRowsFocus) {
        _rowsFocus.value = focus
    }

    private fun loadTmdbSourceForTab(tabIndex: Int, source: TmdbCollectionSource, page: Int, append: Boolean) {
        if (append) {
            _uiState.update { s ->
                val tabs = s.tabs.toMutableList()
                val row = tabs.getOrNull(tabIndex)?.catalogRow
                if (row != null) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(isLoading = true))
                s.copy(tabs = tabs)
            }
            rebuildAllTab()
        }
        viewModelScope.launch {
            tmdbCollectionSourceResolver.resolve(source, page).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val posterPattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                        val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val currentRow = tabs.getOrNull(tabIndex)?.catalogRow
                            val filteredData = result.data.filteredForRelease(
                                hideUnreleased = s.hideUnreleasedContent,
                                treatMissingDateAsUnreleased = true
                            )
                            val row = if (append && currentRow != null) {
                                val existingIds = currentRow.items.map { "${it.apiType}:${it.id}" }.toHashSet()
                                val newItems = filteredData.items.filter { "${it.apiType}:${it.id}" !in existingIds }
                                filteredData.copy(
                                    items = currentRow.items + newItems,
                                    hasMore = filteredData.hasMore && newItems.isNotEmpty(),
                                    isLoading = false
                                )
                            } else {
                                filteredData
                            }
                            if (tabIndex < tabs.size) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(items = row.items.withCustomPosterUrls(com.nuvio.tv.core.poster.patternForScreen(posterPattern, com.nuvio.tv.core.poster.CustomPosterScreen.COLLECTIONS, enabledScreens))), isLoading = false)
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val current = tabs.getOrNull(tabIndex)
                            if (current != null) {
                                tabs[tabIndex] = current.copy(
                                    isLoading = false,
                                    error = result.message,
                                    catalogRow = current.catalogRow?.copy(isLoading = false)
                                )
                            }
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    private fun loadTraktSourceForTab(tabIndex: Int, source: TraktCollectionSource, page: Int, append: Boolean) {
        if (append) {
            _uiState.update { s ->
                val tabs = s.tabs.toMutableList()
                val row = tabs.getOrNull(tabIndex)?.catalogRow
                if (row != null) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(isLoading = true))
                s.copy(tabs = tabs)
            }
            rebuildAllTab()
        }
        viewModelScope.launch {
            traktPublicListSourceResolver.resolve(source, page).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val posterPattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                        val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val currentRow = tabs.getOrNull(tabIndex)?.catalogRow
                            val filteredData = result.data.filteredForRelease(
                                hideUnreleased = s.hideUnreleasedContent,
                                treatMissingDateAsUnreleased = true
                            )
                            val row = if (append && currentRow != null) {
                                val existingIds = currentRow.items.map { "${it.apiType}:${it.id}" }.toHashSet()
                                val newItems = filteredData.items.filter { "${it.apiType}:${it.id}" !in existingIds }
                                filteredData.copy(
                                    items = currentRow.items + newItems,
                                    hasMore = filteredData.hasMore && newItems.isNotEmpty(),
                                    isLoading = false
                                )
                            } else {
                                filteredData
                            }
                            if (tabIndex < tabs.size) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(items = row.items.withCustomPosterUrls(com.nuvio.tv.core.poster.patternForScreen(posterPattern, com.nuvio.tv.core.poster.CustomPosterScreen.COLLECTIONS, enabledScreens))), isLoading = false)
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val current = tabs.getOrNull(tabIndex)
                            if (current != null) {
                                tabs[tabIndex] = current.copy(
                                    isLoading = false,
                                    error = result.message,
                                    catalogRow = current.catalogRow?.copy(isLoading = false)
                                )
                            }
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    private fun buildAddonTabLabels(source: AddonCatalogCollectionSource, catalogName: String?): Pair<String, String> {
        val typeLabel = when (source.type.lowercase()) {
            "movie" -> appContext.getString(R.string.type_movies)
            "series" -> appContext.getString(R.string.type_series_plural)
            else -> source.type.replaceFirstChar { it.uppercase() }
        }
        val baseName = if (!catalogName.isNullOrBlank()) {
            catalogName.replaceFirstChar { it.uppercase() }
        } else {
            typeLabel
        }
        val effectiveGenre = source.genre?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) }
        val name = effectiveGenre?.let { "$baseName · $it" } ?: baseName
        return name to typeLabel
    }

    private fun buildTmdbTypeLabel(source: TmdbCollectionSource): String {
        return when (source.sourceType.name) {
            "LIST" -> appContext.getString(R.string.collections_editor_tmdb_default_list)
            "COLLECTION" -> appContext.getString(R.string.collections_editor_tmdb_movie_collection)
            "COMPANY" -> appContext.getString(R.string.collections_editor_tmdb_mode_production)
            "NETWORK" -> appContext.getString(R.string.collections_editor_tmdb_mode_network)
            "PERSON" -> appContext.getString(R.string.collections_editor_tmdb_person_credits)
            "DIRECTOR" -> appContext.getString(R.string.collections_editor_tmdb_director_credits)
            else -> appContext.getString(R.string.collections_editor_tmdb_default_discover)
        }
    }

    private fun buildTraktTypeLabel(source: TraktCollectionSource): String {
        return when (source.mediaType) {
            com.nuvio.tv.domain.model.TmdbCollectionMediaType.MOVIE -> appContext.getString(R.string.collections_editor_trakt_movie_list)
            com.nuvio.tv.domain.model.TmdbCollectionMediaType.TV -> appContext.getString(R.string.collections_editor_trakt_series_list)
        }
    }

    private fun String.toCollectionRawType(): String {
        return if (lowercase() == "tv") "series" else this
    }

    private fun buildCatalogExtraArgs(source: AddonCatalogCollectionSource): Map<String, String> {
        val genre = source.genre?.takeIf { it.isNotBlank() && !it.equals("None", ignoreCase = true) } ?: return emptyMap()
        return mapOf("genre" to genre)
    }

    fun onItemFocused(item: MetaPreview) {
        if (item.id in enrichedItemIds) return

        enrichFocusJob?.cancel()
        enrichFocusJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(350)

            // Background-prefetch meta from addons so detail screen opens instantly.
            if (backgroundMetaPrefetchedIds.add(item.id)) {
                viewModelScope.launch {
                    metaRepository.getMetaFromAllAddons(
                        type = item.apiType,
                        id = item.id
                    ).first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
                }
            }

            val tmdbSettings = tmdbSettingsDataStore.settings.first()
            val tmdbEnabled = tmdbSettings.enabled && tmdbSettings.modernHomeEnabled
            val externalMetaEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()
            if (!tmdbEnabled && !externalMetaEnabled) return@launch

            var enrichment: com.nuvio.tv.core.tmdb.TmdbEnrichment? = null
            if (tmdbEnabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                if (tmdbId != null) {
                    enrichment = runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = tmdbSettings.language
                        )
                    }.getOrNull()
                }
            }

            if (enrichment == null && !externalMetaEnabled) return@launch
            enrichedItemIds.add(item.id)

            // Apply TMDB enrichment if available.
            if (enrichment != null) {
                val finalEnrichment = enrichment
                updateItemInTabs(item.id) { merged ->
                    var result = merged
                    if (tmdbSettings.useBasicInfo) {
                        result = result.copy(
                            name = finalEnrichment.localizedTitle ?: result.name,
                            description = finalEnrichment.description ?: result.description,
                            genres = if (finalEnrichment.genres.isNotEmpty()) finalEnrichment.genres else result.genres
                        )
                    }
                    if (tmdbSettings.useArtwork) {
                        result = result.copy(
                            background = finalEnrichment.backdrop ?: result.background,
                            logo = finalEnrichment.logo ?: result.logo
                        )
                    }
                    if (tmdbSettings.useDetails) {
                        result = result.copy(
                            runtime = finalEnrichment.runtimeMinutes?.toString() ?: result.runtime,
                            ageRating = finalEnrichment.ageRating ?: result.ageRating,
                            status = finalEnrichment.status ?: result.status
                        )
                    }
                    // Keep TMDB's localized trailer YT ids on the item for the trailer pipeline.
                    val enrichedYtIds = finalEnrichment.trailers.mapNotNull { it.ytId }.distinct()
                    if (enrichedYtIds.isNotEmpty() && result.trailerYtIds.isEmpty()) {
                        result = result.copy(trailerYtIds = enrichedYtIds)
                    }
                    result
                }
            }

            // External meta addon fallback:
            // 1. When TMDB didn't enrich at all, OR
            // 2. When TMDB enriched but useArtwork is off and the item still lacks a logo.
            val artworkStillMissing = enrichment != null && !tmdbSettings.useArtwork &&
                item.logo.isNullOrBlank()
            val needsExternalAddon = enrichment == null || artworkStillMissing
            if (needsExternalAddon && externalMetaEnabled) {
                val metaResult = metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }
                if (metaResult is NetworkResult.Success) {
                    val meta = metaResult.data
                    if (artworkStillMissing) {
                        // Only apply artwork — TMDB already provided the rest.
                        updateItemInTabs(item.id) { merged ->
                            merged.copy(
                                background = meta.background?.takeIf { it.isNotBlank() } ?: merged.background,
                                logo = meta.logo?.takeIf { it.isNotBlank() } ?: merged.logo
                            )
                        }
                    } else {
                        updateItemInTabs(item.id) { merged ->
                            merged.copy(
                                name = meta.name.takeIf { it.isNotBlank() } ?: merged.name,
                                description = meta.description?.takeIf { it.isNotBlank() } ?: merged.description,
                                background = meta.background?.takeIf { it.isNotBlank() } ?: merged.background,
                                logo = meta.logo?.takeIf { it.isNotBlank() } ?: merged.logo,
                                genres = meta.genres.takeIf { it.isNotEmpty() } ?: merged.genres,
                                imdbRating = meta.imdbRating ?: merged.imdbRating,
                                releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: merged.releaseInfo
                            )
                        }
                    }
                }
            }
        }
    }
    private fun updateItemInTabs(itemId: String, transform: (MetaPreview) -> MetaPreview) {
        _uiState.update { state ->
            var changed = false
            val updatedTabs = state.tabs.map { tab ->
                val row = tab.catalogRow ?: return@map tab
                val idx = row.items.indexOfFirst { it.id == itemId }
                if (idx < 0) return@map tab
                val merged = transform(row.items[idx])
                if (merged == row.items[idx]) return@map tab
                changed = true
                val items = row.items.toMutableList()
                items[idx] = merged
                tab.copy(catalogRow = row.copy(items = items))
            }
            if (changed) state.copy(tabs = updatedTabs) else state
        }
    }

}

/**
 * Drops unreleased items from a freshly-loaded row when the user toggle is on.
 * [treatMissingDateAsUnreleased] additionally drops items that have no release
 * information at all — used for TMDB- and Trakt-resolved rows, where a missing
 * release date means the title is unannounced (#2793). Addon rows keep the
 * lenient behavior because sparse addon metadata often omits dates for
 * released content.
 */
private fun CatalogRow.filteredForRelease(
    hideUnreleased: Boolean,
    treatMissingDateAsUnreleased: Boolean = false
): CatalogRow {
    if (!hideUnreleased) return this
    val today = java.time.LocalDate.now()
    val filtered = items.filterNot { item ->
        item.isUnreleased(today) || (treatMissingDateAsUnreleased && item.hasNoReleaseInfo())
    }
    return if (filtered.size == items.size) this else copy(items = filtered)
}
