package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object SubmitSearch : SearchEvent
    /** Moving from the text input to the results confirms the current search (see #2928 review). */
    data object RememberSearchFromTextInput : SearchEvent
    data object ClearRecentSearches : SearchEvent
    data class RemoveRecentSearch(val query: String) : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data object Retry : SearchEvent
}
