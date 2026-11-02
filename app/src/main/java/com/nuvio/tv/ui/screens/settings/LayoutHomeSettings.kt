@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility

@Composable
internal fun LayoutHomeLayoutSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_show_hero),
        subtitle = stringResource(R.string.layout_show_hero_sub),
        checked = uiState.heroSectionEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)) }
    )
    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
        Text(
            text = stringResource(R.string.layout_hero_catalogs),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )
        Text(
            text = stringResource(R.string.layout_hero_catalogs_sub),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
        val firstHeroCatalogFocusRequester = remember { FocusRequester() }
        LazyRow(
            modifier = Modifier.settingsOptionRow(firstHeroCatalogFocusRequester),
            contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(
                items = uiState.availableCatalogs,
                key = { _, catalog -> catalog.key }
            ) { catalogIndex, catalog ->
                CatalogChip(
                    catalogInfo = catalog,
                    isSelected = catalog.key in uiState.heroCatalogKeys,
                    onClick = { onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key)) },
                    modifier = if (catalogIndex == 0) {
                        Modifier.focusRequester(firstHeroCatalogFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
    HomeMotionRows()
}

@Composable
internal fun LayoutHomeContentSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_poster_labels),
        subtitle = stringResource(R.string.layout_poster_labels_sub),
        checked = uiState.posterLabelsEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_addon_name),
        subtitle = stringResource(R.string.layout_addon_name_sub),
        checked = uiState.catalogAddonNameEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_catalog_type),
        subtitle = stringResource(R.string.layout_catalog_type_sub),
        checked = uiState.catalogTypeSuffixEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_hide_unreleased),
        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
        checked = uiState.hideUnreleasedContent,
        onToggle = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_overall_ratings),
        subtitle = stringResource(
            if (uiState.homeImdbRatingsVisibility.showRatings) {
                R.string.layout_overall_ratings_sub_on
            } else {
                R.string.layout_overall_ratings_sub_off
            }
        ),
        checked = uiState.homeImdbRatingsVisibility.showRatings,
        onToggle = {
            val visibility = if (uiState.homeImdbRatingsVisibility.showRatings) {
                HomeImdbRatingsVisibility.HIDE_ALL
            } else {
                HomeImdbRatingsVisibility.SHOW_ALL
            }
            onEvent(LayoutSettingsEvent.SetHomeImdbRatingsVisibility(visibility))
        }
    )
}

@Composable
internal fun LayoutSidebarSection() {
    MenuPlacementRows()
}

@Composable
private fun CatalogChip(
    catalogInfo: CatalogInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsChoiceChip(
        modifier = modifier,
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick
    )
}
