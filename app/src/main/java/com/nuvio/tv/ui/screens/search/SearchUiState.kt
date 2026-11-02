package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow

internal const val MIN_SEARCH_QUERY_LENGTH = 2

internal fun submittedSearchQuery(rawQuery: String): String =
    rawQuery.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val installedAddons: List<Addon> = emptyList(),
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val recentSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)
