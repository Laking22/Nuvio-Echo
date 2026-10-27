package com.nuvio.tv.ui.screens.search

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.browse.BrowseCatalog
import com.nuvio.tv.core.browse.BrowseCursor
import com.nuvio.tv.core.browse.BrowseDetailsLoader
import com.nuvio.tv.core.browse.BrowseKeyword
import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.core.browse.BrowsePage
import com.nuvio.tv.core.browse.BrowseRepository
import com.nuvio.tv.core.browse.BrowseRequest
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

@Immutable
data class KeyboardSearchUiState(
    val usesTmdb: Boolean = true,
    /** Keywords under the keyboard: today's picks when nothing is typed, TMDB matches otherwise. */
    val keywords: List<BrowseKeyword> = emptyList(),
    val keywordsForQuery: String = "",
    /** A keyword picked from the list; its titles replace Trending Today until something is typed. */
    val selectedKeyword: BrowseKeyword? = null,
    val resultFilter: BrowseMediaMode = BrowseMediaMode.ALL,
    val browseItems: List<MetaPreview> = emptyList(),
    val browseLoading: Boolean = true,
    val browseLoadingMore: Boolean = false,
    val browseCanLoadMore: Boolean = false,
    val browseError: Boolean = false,
    val browseVersion: Int = 0,
    /** TMDB's popularity-ranked matches for [rankedForQuery]; orders the addon results. */
    val rankedTitles: List<MetaPreview> = emptyList(),
    val rankedForQuery: String = ""
)

/**
 * Everything on the keyboard search screen that isn't the addon search itself, which stays in
 * [SearchViewModel]: the trending grid, keyword suggestions and keyword grids, and the details
 * panel for the focused poster.
 */
@HiltViewModel
class KeyboardSearchViewModel @Inject constructor(
    private val repository: BrowseRepository,
    private val detailsLoader: BrowseDetailsLoader
) : ViewModel() {

    private val defaultKeywords = if (repository.supportsKeywords()) BrowseCatalog.suggestedKeywords() else emptyList()

    private val _uiState = MutableStateFlow(
        KeyboardSearchUiState(usesTmdb = repository.hasTmdb, keywords = defaultKeywords)
    )
    val uiState: StateFlow<KeyboardSearchUiState> = _uiState.asStateFlow()

    var lastFocusedItemIndex: Int = 0

    /** Panel details for the focused poster, collected by the panel alone. */
    val focusedDetails = FocusedDetailsTracker(viewModelScope, detailsLoader)

    private var cursor: BrowseCursor? = null
    private var browseJob: Job? = null
    private var browseMoreJob: Job? = null
    private var keywordJob: Job? = null
    private var titleJob: Job? = null

    init {
        reloadBrowse()
    }

    /** Typing replaces a picked keyword and refreshes the keyword list for what was typed. */
    fun onQueryChanged(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty() && _uiState.value.selectedKeyword != null) {
            _uiState.update { it.copy(selectedKeyword = null) }
            reloadBrowse()
        }
        keywordJob?.cancel()
        titleJob?.cancel()
        if (!repository.supportsKeywords()) return
        if (trimmed.length >= 2) {
            titleJob = viewModelScope.launch {
                delay(TITLE_SEARCH_DEBOUNCE_MS)
                val ranked = runCatching { repository.searchTitles(trimmed) }.getOrElse {
                    if (it is CancellationException) throw it
                    emptyList()
                }
                _uiState.update { it.copy(rankedTitles = ranked, rankedForQuery = trimmed) }
            }
        }
        if (trimmed.length < 2) {
            _uiState.update { it.copy(keywords = defaultKeywords, keywordsForQuery = "") }
            return
        }
        keywordJob = viewModelScope.launch {
            delay(KEYWORD_DEBOUNCE_MS)
            val matches = runCatching { repository.searchKeywords(trimmed) }.getOrElse {
                if (it is CancellationException) throw it
                emptyList()
            }
            _uiState.update { it.copy(keywords = matches, keywordsForQuery = trimmed) }
        }
    }

    fun selectKeyword(keyword: BrowseKeyword?) {
        if (_uiState.value.selectedKeyword?.key == keyword?.key) return
        _uiState.update { it.copy(selectedKeyword = keyword) }
        reloadBrowse()
    }

    fun setResultFilter(filter: BrowseMediaMode) {
        if (_uiState.value.resultFilter == filter) return
        _uiState.update { it.copy(resultFilter = filter) }
        if (_uiState.value.selectedKeyword != null) reloadBrowse()
    }

    fun retryBrowse() = reloadBrowse()

    fun onItemFocused(index: Int, item: MetaPreview) {
        lastFocusedItemIndex = index
        focusedDetails.onFocused(item)
    }

    fun loadMoreBrowse() {
        val state = _uiState.value
        val from = cursor ?: return
        if (from.exhausted || state.browseLoading || browseMoreJob?.isActive == true) return
        val request = currentRequest()
        browseMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(browseLoadingMore = true) }
            try {
                val page = repository.load(request, from)
                if (currentRequest() != request) return@launch
                cursor = page.next
                _uiState.update { current ->
                    val seen = current.browseItems.mapTo(HashSet()) { it.id }
                    current.copy(
                        browseItems = current.browseItems + page.items.filter { seen.add(it.id) },
                        browseLoadingMore = false,
                        browseCanLoadMore = !page.next.exhausted
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Loading more failed: ${error.message}")
                _uiState.update { it.copy(browseLoadingMore = false, browseCanLoadMore = false) }
            }
        }
    }

    private fun currentRequest(): BrowseRequest {
        val state = _uiState.value
        val keyword = state.selectedKeyword
        return if (keyword != null) {
            BrowseRequest(mode = state.resultFilter, keyword = keyword)
        } else {
            BrowseRequest(trending = true)
        }
    }

    private fun reloadBrowse() {
        browseJob?.cancel()
        browseMoreJob?.cancel()
        val request = currentRequest()
        repository.cachedPage(request, null)?.let {
            applyFirstPage(it)
            return
        }
        cursor = null
        // The current posters stay up, dimmed, until the new ones arrive.
        _uiState.update {
            it.copy(
                browseLoading = true,
                browseLoadingMore = false,
                browseCanLoadMore = false,
                browseError = false
            )
        }
        browseJob = viewModelScope.launch {
            try {
                val page = repository.load(request, null)
                if (currentRequest() == request) applyFirstPage(page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Loading ${request.key} failed: ${error.message}")
                _uiState.update { it.copy(browseItems = emptyList(), browseLoading = false, browseError = true) }
            }
        }
    }

    private fun applyFirstPage(page: BrowsePage) {
        cursor = page.next
        lastFocusedItemIndex = 0
        _uiState.update {
            it.copy(
                browseItems = page.items,
                browseLoading = false,
                browseLoadingMore = false,
                browseCanLoadMore = !page.next.exhausted,
                browseError = false,
                browseVersion = it.browseVersion + 1
            )
        }
    }

    private companion object {
        const val TAG = "KeyboardSearchVM"
        const val KEYWORD_DEBOUNCE_MS = 300L
        const val TITLE_SEARCH_DEBOUNCE_MS = 300L
    }
}
