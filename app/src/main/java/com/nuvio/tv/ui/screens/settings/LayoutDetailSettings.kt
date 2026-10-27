package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.core.streams.StreamBadgePlacement

@Composable
internal fun LayoutDetailPageSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsSectionLabel(text = stringResource(R.string.layout_detail_group_episodes))
    SettingsToggleRow(
        title = stringResource(R.string.layout_blur_unwatched),
        subtitle = stringResource(R.string.layout_blur_unwatched_sub),
        checked = uiState.blurUnwatchedEpisodes,
        onToggle = { onEvent(LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)) }
    )

    SettingsSectionLabel(text = stringResource(R.string.layout_detail_group_trailers))
    if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.audio_autoplay_trailers),
            subtitle = stringResource(R.string.audio_autoplay_trailers_sub),
            checked = uiState.detailPageTrailerAutoplayEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled(!uiState.detailPageTrailerAutoplayEnabled))
            }
        )
        if (uiState.detailPageTrailerAutoplayEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_trailer_play_in_background),
                subtitle = stringResource(R.string.layout_trailer_play_in_background_sub),
                checked = uiState.detailPageTrailerPlayInBackground,
                onToggle = {
                    onEvent(LayoutSettingsEvent.SetDetailPageTrailerPlayInBackground(!uiState.detailPageTrailerPlayInBackground))
                }
            )
            if (uiState.detailPageTrailerPlayInBackground) {
                SettingsToggleRow(
                    title = stringResource(R.string.layout_trailer_pause_on_scroll),
                    subtitle = stringResource(R.string.layout_trailer_pause_on_scroll_sub),
                    checked = uiState.detailPageTrailerPauseOnScroll,
                    onToggle = {
                        onEvent(LayoutSettingsEvent.SetDetailPageTrailerPauseOnScroll(!uiState.detailPageTrailerPauseOnScroll))
                    }
                )
            }
            SliderSettingsItem(
                title = stringResource(R.string.audio_trailer_delay),
                value = uiState.detailPageTrailerAutoplayDelaySeconds,
                valueText = "${uiState.detailPageTrailerAutoplayDelaySeconds}s",
                minValue = 3,
                maxValue = 15,
                step = 1,
                onValueChange = { seconds ->
                    onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds(seconds))
                }
            )
        }
    }
    SettingsToggleRow(
        title = stringResource(R.string.layout_trailer_button),
        subtitle = stringResource(R.string.layout_trailer_button_sub),
        checked = uiState.detailPageTrailerButtonEnabled,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(!uiState.detailPageTrailerButtonEnabled))
        }
    )

    SettingsSectionLabel(text = stringResource(R.string.layout_detail_group_metadata))
    SettingsToggleRow(
        title = stringResource(R.string.layout_prefer_external_meta),
        subtitle = stringResource(R.string.layout_prefer_external_meta_sub),
        checked = uiState.preferExternalMetaAddonDetail,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(!uiState.preferExternalMetaAddonDetail))
        }
    )
    ParentalGuideRows()
}

@Composable
internal fun LayoutStreamsSection(
    streamBadgeUiState: StreamBadgeSettingsUiState,
    onShowFileSizeBadgesChange: (Boolean) -> Unit,
    onShowAddonLogoChange: (Boolean) -> Unit,
    onBadgePlacementSelected: (StreamBadgePlacement) -> Unit,
    onConfigureBadges: () -> Unit
) {
    var showPositionDialog by rememberSaveable { mutableStateOf(false) }

    SettingsSectionLabel(text = stringResource(R.string.settings_stream_badges_section))
    SettingsToggleRow(
        title = stringResource(R.string.settings_stream_size_badges_title),
        subtitle = stringResource(R.string.settings_stream_size_badges_description),
        checked = streamBadgeUiState.showFileSizeBadges,
        onToggle = { onShowFileSizeBadgesChange(!streamBadgeUiState.showFileSizeBadges) }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_stream_badge_position_title),
        subtitle = null,
        value = streamBadgePlacementLabel(streamBadgeUiState.badgePlacement),
        onClick = { showPositionDialog = true }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_stream_badge_urls_title),
        subtitle = streamBadgeRulesPreview(streamBadgeUiState),
        onClick = onConfigureBadges
    )

    SettingsSectionLabel(text = stringResource(R.string.settings_stream_display_section))
    SettingsToggleRow(
        title = stringResource(R.string.settings_stream_addon_logo_title),
        subtitle = stringResource(R.string.settings_stream_addon_logo_description),
        checked = streamBadgeUiState.showAddonLogo,
        onToggle = { onShowAddonLogoChange(!streamBadgeUiState.showAddonLogo) }
    )

    if (showPositionDialog) {
        StreamBadgePositionDialog(
            currentPlacement = streamBadgeUiState.badgePlacement,
            onPlacementSelected = { placement ->
                onBadgePlacementSelected(placement)
                showPositionDialog = false
            },
            onDismiss = { showPositionDialog = false }
        )
    }
}



@Composable
private fun streamBadgePlacementLabel(placement: StreamBadgePlacement): String =
    when (placement) {
        StreamBadgePlacement.TOP -> stringResource(R.string.settings_stream_badge_position_top)
        StreamBadgePlacement.BOTTOM -> stringResource(R.string.settings_stream_badge_position_bottom)
    }

@Composable
private fun streamBadgeRulesPreview(uiState: StreamBadgeSettingsUiState): String {
    val rules = uiState.rules.normalized()
    return if (rules.hasImport) {
        stringResource(
            R.string.settings_fusion_badges_summary,
            rules.imports.size,
            STREAM_BADGE_IMPORT_LIMIT,
            rules.enabledFilterCount
        )
    } else {
        stringResource(R.string.settings_fusion_badges_empty)
    }
}

@Composable
private fun StreamBadgePositionDialog(
    currentPlacement: StreamBadgePlacement,
    onPlacementSelected: (StreamBadgePlacement) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            StreamBadgePlacement.BOTTOM,
            stringResource(R.string.settings_stream_badge_position_bottom)
        ),
        SettingsPickerOption(
            StreamBadgePlacement.TOP,
            stringResource(R.string.settings_stream_badge_position_top)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_stream_badge_position_dialog_title),
        subtitle = stringResource(R.string.settings_stream_badge_position_dialog_description),
        options = options,
        selectedValue = currentPlacement,
        onOptionSelected = onPlacementSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 260.dp
    )
}

