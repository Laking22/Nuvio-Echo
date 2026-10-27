package com.nuvio.tv.ui.screens.genres

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.browse.BrowseCursor
import com.nuvio.tv.core.browse.BrowseDetailsLoader
import com.nuvio.tv.core.browse.BrowseGenre
import com.nuvio.tv.core.browse.BrowseKeyword
import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.core.browse.BrowsePage
import com.nuvio.tv.core.browse.BrowseRepository
import com.nuvio.tv.core.browse.BrowseRequest
import com.nuvio.tv.core.browse.BrowseSort
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.browse.FocusedDetailsTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line of the second column: a sort order or a keyword within the genre. */
@Immutable
sealed interface GenreEntry {
    val key: String

    data class Sort(val sort: BrowseSort) : GenreEntry {
        override val key: String get() = "sort:${sort.name}"
    }

    data class Keyword(val keyword: BrowseKeyword) : GenreEntry {
        override val key: String get() = "kw:${keyword.key}"
    }
}

@Immutable
data class GenresUiState(
    val genres: List<BrowseGenre> = emptyList(),
    val entries: List<GenreEntry> = emptyList(),
    val mode: BrowseMediaMode = BrowseMediaMode.ALL,
    val selectedGenreIndex: Int = 0,
    val selectedEntryIndex: Int = 0,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val hasError: Boolean = false,
    /** Bumped whenever the grid is replaced, so the screen can scroll back to the top. */
    val contentVersion: Int = 0,
    val usesTmdb: Boolean = true
) {
    val selectedGenre: BrowseGenre? get() = genres.getOrNull(selectedGenreIndex)
    val selectedEntry: GenreEntry? get() = entries.getOrNull(selectedEntryIndex)
}

@HiltViewModel
class GenresViewModel @Inject constructor(
    private val repository: BrowseRepository,
    private val detailsLoader: BrowseDetailsLoader
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenresUiState())
    val uiState: StateFlow<GenresUiState> = _uiState.asStateFlow()

    /** Index of the poster that had focus, restored when coming back from a details page. */
    var lastFocusedItemIndex: Int = 0

    /** Panel details for the focused poster, collected by the panel alone. */
    val focusedDetails = FocusedDetailsTracker(viewModelScope, detailsLoader)

    private var cursor: BrowseCursor? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var prefetchJob: Job? = null

    // Declared above init: with a cached first page, init's reload() reaches prefetchNeighbours()
    // during construction, before any property declared below init is initialised.
    /** Which list the user is moving through, so the next step there is usually already loaded. */
    private enum class PrefetchAxis { GENRES, ENTRIES }

    private var prefetchAxis = PrefetchAxis.GENRES

    init {
        val genres = repository.availableGenres()
        _uiState.update {
            it.copy(
                genres = genres,
                entries = entriesFor(genres.firstOrNull()),
                usesTmdb = repository.hasTmdb
            )
        }
        reload(debounceMs = 0L)
    }

    fun selectGenre(index: Int) {
        val state = _uiState.value
        if (index == state.selectedGenreIndex || index !in state.genres.indices) return
        val genre = state.genres[index]
        // Keep the same sort when moving between genres; keywords belong to the old genre.
        val keepEntry = (state.selectedEntry as? GenreEntry.Sort)
        val entries = entriesFor(genre)
        val entryIndex = keepEntry?.let { entries.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        _uiState.update { it.copy(selectedGenreIndex = index, entries = entries, selectedEntryIndex = entryIndex) }
        prefetchAxis = PrefetchAxis.GENRES
        reload(debounceMs = SELECTION_DEBOUNCE_MS)
    }

    fun selectEntry(index: Int) {
        val state = _uiState.value
        if (index == state.selectedEntryIndex || index !in state.entries.indices) return
        _uiState.update { it.copy(selectedEntryIndex = index) }
        prefetchAxis = PrefetchAxis.ENTRIES
        reload(debounceMs = SELECTION_DEBOUNCE_MS)
    }

    fun selectMode(mode: BrowseMediaMode) {
        val state = _uiState.value
        if (mode == state.mode) return
        // Anime hides genres that have no anime; keep the selected genre if it's still listed.
        val genres = repository.availableGenres(mode)
        val genreIndex = genres.indexOfFirst { it.id == state.selectedGenre?.id }
        if (genreIndex >= 0) {
            _uiState.update { it.copy(mode = mode, genres = genres, selectedGenreIndex = genreIndex) }
        } else {
            _uiState.update {
                it.copy(mode = mode, genres = genres, selectedGenreIndex = 0, entries = entriesFor(genres.firstOrNull()), selectedEntryIndex = 0)
            }
        }
        reload(debounceMs = 0L)
    }

    /** What to open for a poster: Kitsu titles may open as their TMDB match (see [BrowseRepository.resolveForDetails]). */
    suspend fun resolveForDetails(item: MetaPreview): MetaPreview = repository.resolveForDetails(item)

    fun retry() = reload(debounceMs = 0L)

    /**
     * Applies a selection still waiting out its debounce when its page is already cached, so
     * moving into the grid never lands on the previous genre's posters. True if the grid changed.
     */
    fun commitPendingSelection(): Boolean {
        if (loadJob?.isActive != true) return false
        val cached = repository.cachedPage(currentRequest(_uiState.value), null) ?: return false
        loadJob?.cancel()
        applyFirstPage(cached)
        return true
    }

    fun onItemFocused(index: Int, item: MetaPreview) {
        lastFocusedItemIndex = index
        focusedDetails.onFocused(item)
    }

    fun loadMore() {
        val state = _uiState.value
        val from = cursor ?: return
        if (from.exhausted || state.isLoading || loadMoreJob?.isActive == true) return
        val request = currentRequest(state)
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                val page = repository.load(request, from)
                if (currentRequest(_uiState.value) != request) return@launch
                cursor = page.next
                _uiState.update { current ->
                    val seen = current.items.mapTo(HashSet()) { it.id }
                    current.copy(
                        items = current.items + page.items.filter { seen.add(it.id) },
                        isLoadingMore = false,
                        canLoadMore = !page.next.exhausted
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Loading more failed: ${error.message}")
                _uiState.update { it.copy(isLoadingMore = false, canLoadMore = false) }
            }
        }
    }

    private fun entriesFor(genre: BrowseGenre?): List<GenreEntry> = buildList {
        repository.supportedSorts().forEach { add(GenreEntry.Sort(it)) }
        if (genre != null && repository.supportsKeywords()) {
            genre.keywords.forEach { add(GenreEntry.Keyword(it)) }
        }
    }

    private fun currentRequest(state: GenresUiState): BrowseRequest {
        val entry = state.selectedEntry
        return BrowseRequest(
            mode = state.mode,
            genre = state.selectedGenre,
            sort = (entry as? GenreEntry.Sort)?.sort ?: BrowseSort.POPULAR,
            keyword = (entry as? GenreEntry.Keyword)?.keyword
        )
    }

    private fun reload(debounceMs: Long) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val request = currentRequest(_uiState.value)
        val cached = repository.cachedPage(request, null)
        if (cached != null && debounceMs == 0L) {
            applyFirstPage(cached)
            return
        }
        cursor = null
        // The old posters stay up until the new ones are ready, dimmed only while a page actually
        // loads. Even a cached page waits out the debounce, so holding the D-pad down the genre
        // list swaps the grid once, where it stops.
        _uiState.update {
            it.copy(isLoading = cached == null, isLoadingMore = false, canLoadMore = false, hasError = false)
        }
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                val page = repository.load(request, null)
                if (currentRequest(_uiState.value) == request) applyFirstPage(page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Loading ${request.key} failed: ${error.message}")
                _uiState.update { it.copy(items = emptyList(), isLoading = false, hasError = true) }
            }
        }
    }

    private fun applyFirstPage(page: BrowsePage) {
        cursor = page.next
        lastFocusedItemIndex = 0
        _uiState.update {
            it.copy(
                items = page.items,
                isLoading = false,
                isLoadingMore = false,
                canLoadMore = !page.next.exhausted,
                hasError = false,
                contentVersion = it.contentVersion + 1
            )
        }
        prefetchNeighbours()
    }

    /** First pages of the rows either side of the selection in the list the user is moving through. */
    private fun prefetchNeighbours() {
        prefetchJob?.cancel()
        val state = _uiState.value
        val keptSort = (state.selectedEntry as? GenreEntry.Sort)?.sort ?: BrowseSort.POPULAR
        val requests = when (prefetchAxis) {
            PrefetchAxis.GENRES -> listOf(state.selectedGenreIndex + 1, state.selectedGenreIndex - 1)
                .mapNotNull { state.genres.getOrNull(it) }
                .map { genre -> BrowseRequest(mode = state.mode, genre = genre, sort = keptSort) }
            PrefetchAxis.ENTRIES -> listOf(state.selectedEntryIndex + 1, state.selectedEntryIndex - 1)
                .mapNotNull { state.entries.getOrNull(it) }
                .map { entry ->
                    BrowseRequest(
                        mode = state.mode,
                        genre = state.selectedGenre,
                        sort = (entry as? GenreEntry.Sort)?.sort ?: BrowseSort.POPULAR,
                        keyword = (entry as? GenreEntry.Keyword)?.keyword
                    )
                }
        }.filter { repository.cachedPage(it, null) == null }
        if (requests.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_DELAY_MS)
            for (request in requests) {
                try {
                    repository.load(request, null)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.i(TAG, "Prefetch ${request.key} failed: ${error.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "GenresViewModel"
        const val SELECTION_DEBOUNCE_MS = 220L
        const val PREFETCH_DELAY_MS = 350L
    }
}
