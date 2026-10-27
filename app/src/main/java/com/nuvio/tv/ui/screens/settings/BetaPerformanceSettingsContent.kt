package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.NotificationStyle
import com.nuvio.tv.data.local.ViewedThresholdUnit
import com.nuvio.tv.core.home.BuiltInRow
import com.nuvio.tv.core.streams.AudioLanguage
import com.nuvio.tv.ui.screens.player.audioLanguageDisplayName
import com.nuvio.tv.ui.navigation.LocalOpenSpeedTest
import com.nuvio.tv.core.intro.PlaybackIntroState
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BetaPerformanceSettingsViewModel @Inject constructor(
    private val dataStore: BetaPerformanceSettingsDataStore,
    private val imagePerformancePreferences: ImagePerformancePreferences,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val playerSettingsDataStore: com.nuvio.tv.data.local.PlayerSettingsDataStore
) : ViewModel() {
    val settings: StateFlow<BetaPerformanceSettings> = dataStore.current

    /** The player's parental guide (the old Content Warnings switch), now always the detailed one. */
    val parentalGuide: StateFlow<Boolean> = playerSettingsDataStore.playerSettings
        .map { it.parentalGuideEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setParentalGuide(enabled: Boolean) = viewModelScope.launch { playerSettingsDataStore.setParentalGuideEnabled(enabled) }

    // Read once when the app starts (the image loader is built then), so changes need a restart.
    private val _hardwareBitmaps = MutableStateFlow(imagePerformancePreferences.hardwareBitmapsEnabled)
    val hardwareBitmaps: StateFlow<Boolean> = _hardwareBitmaps.asStateFlow()

    /** Trailers on Home (hero and highlighted cards) play with sound; the same setting as the old "Trailer muted". */
    val trailerSound: StateFlow<Boolean> = layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTrailerSound(enabled: Boolean) = viewModelScope.launch {
        layoutPreferenceDataStore.setFocusedPosterBackdropTrailerMuted(!enabled)
    }

    fun setHardwareBitmaps(enabled: Boolean) {
        imagePerformancePreferences.setHardwareBitmapsEnabled(enabled)
        _hardwareBitmaps.value = enabled
    }

    fun setPrefetchOnOpen(enabled: Boolean) = viewModelScope.launch { dataStore.setPrefetchOnOpen(enabled) }
    fun setPrefetchOnFocus(enabled: Boolean) = viewModelScope.launch { dataStore.setPrefetchOnFocus(enabled) }
    fun setFetchOnExpansion(enabled: Boolean) = viewModelScope.launch { dataStore.setFetchOnExpansion(enabled) }
    fun setWarmUpAddonConnections(enabled: Boolean) = viewModelScope.launch { dataStore.setWarmUpAddonConnections(enabled) }
    fun setFastPlaybackStart(enabled: Boolean) = viewModelScope.launch { dataStore.setFastPlaybackStart(enabled) }
    fun setSourceFallback(enabled: Boolean) = viewModelScope.launch { dataStore.setSourceFallback(enabled) }
    fun setPosterColourMesh(enabled: Boolean) = viewModelScope.launch { dataStore.setPosterColourMesh(enabled) }
    fun setHomeAmbientBackground(enabled: Boolean) = viewModelScope.launch { dataStore.setHomeAmbientBackground(enabled) }
    fun setDetailsAmbientBackground(enabled: Boolean) = viewModelScope.launch { dataStore.setDetailsAmbientBackground(enabled) }
    fun setHeroEdgeToEdge(enabled: Boolean) = viewModelScope.launch { dataStore.setHeroEdgeToEdge(enabled) }
    fun setQuietFocus(enabled: Boolean) = viewModelScope.launch { dataStore.setQuietFocus(enabled) }
    fun setTrailerQuality(height: Int) = viewModelScope.launch { dataStore.setTrailerQuality(height) }
    fun setTrailerVideoLayer(enabled: Boolean) = viewModelScope.launch { dataStore.setTrailerVideoLayer(enabled) }
    fun setEnrichment(level: Int) = viewModelScope.launch { dataStore.setEnrichment(level) }
    fun setSmartHighlightAiNames(enabled: Boolean) = viewModelScope.launch { dataStore.setSmartHighlightAiNames(enabled) }
    fun resetBlockedHighlights() = viewModelScope.launch { dataStore.resetBlockedHighlights() }
    fun setMinimalPlayer(enabled: Boolean) = viewModelScope.launch { dataStore.setMinimalPlayer(enabled) }
    fun setPlaybackIntro(enabled: Boolean) = viewModelScope.launch { dataStore.setPlaybackIntro(enabled) }
    fun setSeekThumbnails(enabled: Boolean) = viewModelScope.launch { dataStore.setSeekThumbnails(enabled) }
    fun setSeekPreviewBudget(mb: Int) = viewModelScope.launch { dataStore.setSeekPreviewBudget(mb) }
    fun setSeekPreviewQuality(height: Int) = viewModelScope.launch { dataStore.setSeekPreviewQuality(height) }
    fun setSeekPreviewFilmstrip(enabled: Boolean) = viewModelScope.launch { dataStore.setSeekPreviewFilmstrip(enabled) }
    fun setIntroLength(seconds: Int) = viewModelScope.launch { dataStore.setIntroLength(seconds) }
    fun setAudioLanguageMenu(enabled: Boolean) = viewModelScope.launch { dataStore.setAudioLanguageMenu(enabled) }
    fun setShowUnknownAudio(enabled: Boolean) = viewModelScope.launch { dataStore.setShowUnknownAudio(enabled) }
    fun setAudioMenuLanguage(key: String, shown: Boolean) = viewModelScope.launch { dataStore.setAudioMenuLanguage(key, shown) }
    fun setHideMenu(enabled: Boolean) = viewModelScope.launch { dataStore.setHideMenuUntilSelected(enabled) }
    fun setPosterOpenDelay(ms: Int) = viewModelScope.launch { dataStore.setPosterOpenDelay(ms) }
    fun setSourceSwitchTimeout(seconds: Int) = viewModelScope.launch { dataStore.setSourceSwitchTimeout(seconds) }
    fun setAutoQuality(enabled: Boolean) = viewModelScope.launch { dataStore.setAutoQuality(enabled) }
    fun setAutoQualityConfirm(seconds: Int) = viewModelScope.launch { dataStore.setAutoQualityConfirm(seconds) }
    fun setAutoQualityLanguageLoyal(enabled: Boolean) = viewModelScope.launch { dataStore.setAutoQualityLanguageLoyal(enabled) }
    fun setAutoQualityHotSwap(enabled: Boolean) = viewModelScope.launch { dataStore.setAutoQualityHotSwap(enabled) }
    fun setMenuOnTop(enabled: Boolean) = viewModelScope.launch { dataStore.setMenuOnTop(enabled) }
    fun setMenuGlass(level: Int) = viewModelScope.launch { dataStore.setMenuGlass(level) }
    fun cycleNotificationStyle(current: NotificationStyle) = viewModelScope.launch {
        val styles = NotificationStyle.entries
        dataStore.setNotificationStyle(styles[(styles.indexOf(current) + 1).mod(styles.size)])
    }
    fun cycleViewedUnit(current: ViewedThresholdUnit) = viewModelScope.launch {
        dataStore.setViewedThresholdUnit(
            if (current == ViewedThresholdUnit.MINUTES) ViewedThresholdUnit.PERCENT else ViewedThresholdUnit.MINUTES
        )
    }
    fun setViewedMinutes(minutes: Int) = viewModelScope.launch { dataStore.setViewedThresholdMinutes(minutes) }
    fun setViewedPercent(percent: Int) = viewModelScope.launch { dataStore.setViewedThresholdPercent(percent) }
    fun setBuiltInRowShown(row: BuiltInRow, shown: Boolean) = viewModelScope.launch { dataStore.setBuiltInRowShown(row.key, shown) }

    private val _validatingOmdbKey = MutableStateFlow(false)
    val validatingOmdbKey: StateFlow<Boolean> = _validatingOmdbKey.asStateFlow()

    /** Blank clears the key; anything else is saved only if OMDb accepts it. */
    fun saveOmdbApiKey(key: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) {
                dataStore.setOmdbApiKey("")
                onResult(true)
                return@launch
            }
            _validatingOmdbKey.value = true
            val valid = parentalGuideRepository.validateOmdbKey(trimmed)
            _validatingOmdbKey.value = false
            if (valid) dataStore.setOmdbApiKey(trimmed)
            onResult(valid)
        }
    }
}

/*
 * The beta's settings, each group placed with the settings it belongs to: Appearance, Layout, Content & Discovery,
 * Playback and Advanced. There's no Beta page of its own any more.
 */

/** Layout → Detail page: the parental guide on the details page, and the OMDb key it can fall back on. */
@Composable
internal fun ParentalGuideRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val parentalGuide by viewModel.parentalGuide.collectAsStateWithLifecycle()
    var showOmdbKeyDialog by remember { mutableStateOf(false) }
    SettingsSectionLabel(text = stringResource(R.string.beta_settings_group_parental))
    SettingsToggleRow(
        title = stringResource(R.string.beta_detailed_parental_title),
        subtitle = stringResource(R.string.beta_detailed_parental_subtitle),
        checked = parentalGuide,
        onToggle = { viewModel.setParentalGuide(!parentalGuide) }
    )
    if (parentalGuide) {
        SettingsActionRow(
            title = stringResource(R.string.beta_omdb_key_title),
            subtitle = stringResource(R.string.beta_omdb_key_subtitle),
            value = maskOmdbKey(settings.omdbApiKey, stringResource(R.string.beta_omdb_key_not_set)),
            onClick = { showOmdbKeyDialog = true }
        )
    }
    if (showOmdbKeyDialog) {
        OmdbApiKeyDialog(
            currentValue = settings.omdbApiKey,
            viewModel = viewModel,
            onDismiss = { showOmdbKeyDialog = false }
        )
    }
}

/** Appearance: how Home and the details panels take on the artwork's colours, and how notifications come in. */
@Composable
internal fun LookAndFeelSettingsGroup(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.settings_group_look_and_feel)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.home_ambient_background_title),
            subtitle = stringResource(R.string.home_ambient_background_subtitle),
            checked = settings.homeAmbientBackground,
            onToggle = { viewModel.setHomeAmbientBackground(!settings.homeAmbientBackground) }
        )
        // Where else the colour shows: only while it's on.
        if (settings.homeAmbientBackground) {
            SettingsChipRow(
                items = listOf(AMBIENT_DETAILS to stringResource(R.string.ambient_details_page)),
                selected = setOfNotNull(AMBIENT_DETAILS.takeIf { settings.detailsAmbientBackground }),
                onToggle = { viewModel.setDetailsAmbientBackground(!settings.detailsAmbientBackground) }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.quiet_focus_title),
            subtitle = stringResource(R.string.quiet_focus_subtitle),
            checked = settings.quietFocus,
            onToggle = { viewModel.setQuietFocus(!settings.quietFocus) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.beta_poster_colour_mesh_title),
            subtitle = stringResource(R.string.beta_poster_colour_mesh_subtitle),
            checked = settings.posterColourMesh,
            onToggle = { viewModel.setPosterColourMesh(!settings.posterColourMesh) }
        )
        SettingsActionRow(
            title = stringResource(R.string.beta_notification_style_title),
            subtitle = null,
            value = stringResource(
                when (settings.notificationStyle) {
                    NotificationStyle.ISLAND -> R.string.beta_notification_style_island
                    NotificationStyle.PROFILE -> R.string.beta_notification_style_profile
                    NotificationStyle.OFF -> R.string.beta_notification_style_off
                }
            ),
            onClick = { viewModel.cycleNotificationStyle(settings.notificationStyle) }
        )
        if (settings.notificationStyle != NotificationStyle.OFF) {
            SettingsActionRow(
                title = stringResource(R.string.beta_notification_test),
                subtitle = null,
                onClick = { com.nuvio.tv.ui.newlook.NewLookNotifications.sample() }
            )
        }
    }
}

/** Layout → Menu & Navigation: where the menu sits and whether it hides. */
@Composable
internal fun MenuPlacementRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.beta_menu_on_top_title),
        subtitle = stringResource(R.string.beta_menu_on_top_subtitle),
        checked = settings.menuOnTop,
        onToggle = { viewModel.setMenuOnTop(!settings.menuOnTop) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_hide_menu_title),
        subtitle = stringResource(R.string.beta_hide_menu_subtitle),
        checked = settings.hideMenuUntilSelected,
        onToggle = { viewModel.setHideMenu(!settings.hideMenuUntilSelected) }
    )
    SliderSettingsItem(
        title = stringResource(R.string.menu_glass_title),
        subtitle = stringResource(R.string.menu_glass_subtitle),
        values = BetaPerformanceSettings.MENU_GLASS_LEVELS,
        selected = settings.menuGlass,
        valueText = stringResource(
            when (settings.menuGlass) {
                0 -> R.string.menu_glass_low
                1 -> R.string.menu_glass_mid
                else -> R.string.menu_glass_high
            }
        ),
        onValueChange = viewModel::setMenuGlass
    )
}

/** Layout → Home Layout: how Home's posters open out and its trailers sound. */
@Composable
internal fun HomeMotionRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trailerSound by viewModel.trailerSound.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.hero_edge_to_edge_title),
        subtitle = stringResource(R.string.hero_edge_to_edge_subtitle),
        checked = settings.heroEdgeToEdge,
        onToggle = { viewModel.setHeroEdgeToEdge(!settings.heroEdgeToEdge) }
    )
    SliderSettingsItem(
        title = stringResource(R.string.beta_poster_delay_title),
        subtitle = stringResource(R.string.beta_poster_delay_subtitle),
        values = BetaPerformanceSettings.POSTER_OPEN_DELAYS,
        selected = settings.posterOpenDelayMs,
        valueText = if (settings.posterOpenDelayMs == 0) {
            stringResource(R.string.beta_poster_delay_none)
        } else {
            stringResource(R.string.beta_poster_delay_value, settings.posterOpenDelayMs / 1000f)
        },
        onValueChange = viewModel::setPosterOpenDelay
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_trailer_sound_title),
        subtitle = stringResource(R.string.beta_trailer_sound_subtitle),
        checked = trailerSound,
        onToggle = { viewModel.setTrailerSound(!trailerSound) }
    )
    SettingsSectionLabel(
        text = stringResource(R.string.trailer_quality_title),
        description = stringResource(R.string.trailer_quality_subtitle)
    )
    // One pick of several, as chips: the selected one is the quality.
    val auto = stringResource(R.string.trailer_quality_auto)
    SettingsChipRow(
        items = BetaPerformanceSettings.TRAILER_QUALITIES.map { height ->
            height.toString() to when (height) {
                BetaPerformanceSettings.TRAILER_QUALITY_AUTO -> auto
                2160 -> "4K"
                else -> "${height}p"
            }
        },
        selected = setOf(settings.trailerQuality.toString()),
        onToggle = { key -> viewModel.setTrailerQuality(key.toInt()) }
    )
}

/** Advanced → Performance: how Home's trailers and pictures are drawn. */
@Composable
internal fun DrawingPerformanceRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hardwareBitmaps by viewModel.hardwareBitmaps.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.beta_hardware_bitmaps_title),
        subtitle = stringResource(R.string.beta_hardware_bitmaps_subtitle),
        checked = hardwareBitmaps,
        onToggle = { viewModel.setHardwareBitmaps(!hardwareBitmaps) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_trailer_surface_title),
        subtitle = stringResource(R.string.beta_trailer_surface_subtitle),
        checked = settings.trailerVideoLayer,
        onToggle = { viewModel.setTrailerVideoLayer(!settings.trailerVideoLayer) }
    )
}

/** Content & Discovery: Home's generated highlights. */
@Composable
internal fun HighlightSettingsGroup(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.settings_group_highlights)
    ) {
        SliderSettingsItem(
            title = stringResource(R.string.beta_enrichment_title),
            subtitle = stringResource(R.string.beta_enrichment_subtitle),
            value = settings.enrichment,
            valueText = stringResource(
                when (settings.enrichment) {
                    BetaPerformanceSettings.ENRICHMENT_OFF -> R.string.beta_enrichment_off
                    BetaPerformanceSettings.ENRICHMENT_LOW -> R.string.beta_enrichment_low
                    BetaPerformanceSettings.ENRICHMENT_MEDIUM -> R.string.beta_enrichment_medium
                    else -> R.string.beta_enrichment_heavy
                }
            ),
            minValue = BetaPerformanceSettings.ENRICHMENT_OFF,
            maxValue = BetaPerformanceSettings.ENRICHMENT_HEAVY,
            step = 1,
            onValueChange = viewModel::setEnrichment
        )
        if (settings.smartHighlights) {
            SettingsToggleRow(
                title = stringResource(R.string.beta_smart_highlight_ai_names_title),
                subtitle = stringResource(R.string.beta_smart_highlight_ai_names_subtitle),
                checked = settings.smartHighlightAiNames,
                onToggle = { viewModel.setSmartHighlightAiNames(!settings.smartHighlightAiNames) }
            )
            val hiddenCount = settings.blockedHighlights.size + settings.snoozedHighlights.size +
                settings.highlightExclusions.size
            SettingsActionRow(
                title = stringResource(R.string.beta_smart_highlights_reset_title),
                subtitle = if (hiddenCount > 0) {
                    stringResource(R.string.beta_smart_highlights_reset_subtitle, hiddenCount)
                } else {
                    stringResource(R.string.beta_smart_highlights_reset_none)
                },
                enabled = hiddenCount > 0,
                onClick = { viewModel.resetBlockedHighlights() }
            )
        }
    }
}

/** Content & Discovery: Nuvio's built-in highlighted rows. */
@Composable
internal fun HighlightedRowsSettingsGroup(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.beta_settings_group_highlighted)
    ) {
        BuiltInRow.entries.forEach { row ->
            val shown = row.key !in settings.hiddenBuiltInRows
            SettingsToggleRow(
                title = stringResource(row.labelRes),
                subtitle = null,
                checked = shown,
                onToggle = { viewModel.setBuiltInRowShown(row, !shown) }
            )
        }
        SettingsActionRow(
            title = stringResource(R.string.beta_viewed_unit_title),
            subtitle = stringResource(R.string.beta_viewed_unit_subtitle),
            value = stringResource(
                if (settings.viewedThresholdUnit == ViewedThresholdUnit.MINUTES) R.string.beta_viewed_unit_minutes
                else R.string.beta_viewed_unit_percent
            ),
            onClick = { viewModel.cycleViewedUnit(settings.viewedThresholdUnit) }
        )
        if (settings.viewedThresholdUnit == ViewedThresholdUnit.MINUTES) {
            SliderSettingsItem(
                title = stringResource(R.string.beta_viewed_amount_title),
                value = settings.viewedThresholdMinutes,
                valueText = stringResource(R.string.beta_viewed_amount_minutes, settings.viewedThresholdMinutes),
                minValue = 5,
                maxValue = 120,
                step = 5,
                onValueChange = viewModel::setViewedMinutes
            )
        } else {
            SliderSettingsItem(
                title = stringResource(R.string.beta_viewed_amount_title),
                value = settings.viewedThresholdPercent,
                valueText = stringResource(R.string.beta_viewed_amount_percent, settings.viewedThresholdPercent),
                minValue = 5,
                maxValue = 100,
                step = 5,
                onValueChange = viewModel::setViewedPercent
            )
        }
    }
}

/** Playback → Stream Selection: when addons are asked for sources ahead of Play. */
@Composable
internal fun SourceFetchRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSectionLabel(text = stringResource(R.string.beta_settings_group_sources))
    SettingsToggleRow(
        title = stringResource(R.string.beta_prefetch_on_open_title),
        subtitle = stringResource(R.string.beta_prefetch_on_open_subtitle),
        checked = settings.prefetchOnOpen,
        onToggle = { viewModel.setPrefetchOnOpen(!settings.prefetchOnOpen) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_prefetch_on_focus_title),
        subtitle = stringResource(R.string.beta_prefetch_on_focus_subtitle),
        checked = settings.prefetchOnFocus,
        onToggle = { viewModel.setPrefetchOnFocus(!settings.prefetchOnFocus) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_fetch_on_expansion_title),
        subtitle = stringResource(R.string.beta_fetch_on_expansion_subtitle),
        checked = settings.fetchOnExpansion,
        onToggle = { viewModel.setFetchOnExpansion(!settings.fetchOnExpansion) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_warm_up_title),
        subtitle = stringResource(R.string.beta_warm_up_subtitle),
        checked = settings.warmUpAddonConnections,
        onToggle = { viewModel.setWarmUpAddonConnections(!settings.warmUpAddonConnections) }
    )
}

/** Playback → Stream Selection: starting playback, falling back, and Auto quality. */
@Composable
internal fun PlaybackStartRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSectionLabel(text = stringResource(R.string.settings_label_starting_playback))
    SettingsToggleRow(
        title = stringResource(R.string.beta_fast_playback_title),
        subtitle = stringResource(R.string.beta_fast_playback_subtitle),
        checked = settings.fastPlaybackStart,
        onToggle = { viewModel.setFastPlaybackStart(!settings.fastPlaybackStart) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_source_fallback_title),
        subtitle = stringResource(R.string.beta_source_fallback_subtitle),
        checked = settings.sourceFallback,
        onToggle = { viewModel.setSourceFallback(!settings.sourceFallback) }
    )
    SliderSettingsItem(
        title = stringResource(R.string.beta_source_switch_timeout_title),
        subtitle = stringResource(R.string.beta_source_switch_timeout_subtitle),
        value = settings.sourceSwitchTimeoutSeconds,
        valueText = stringResource(R.string.beta_playback_intro_length_value, settings.sourceSwitchTimeoutSeconds),
        minValue = BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MIN,
        maxValue = BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MAX,
        step = 1,
        onValueChange = viewModel::setSourceSwitchTimeout
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_auto_quality_title),
        subtitle = stringResource(R.string.beta_auto_quality_subtitle),
        checked = settings.autoQuality,
        onToggle = { viewModel.setAutoQuality(!settings.autoQuality) }
    )
    // Auto quality's own options: shown only while it's on.
    if (settings.autoQuality) {
        val experimental = stringResource(R.string.settings_badge_experimental)
        SettingsChipRow(
            items = listOf(
                AUTO_QUALITY_LOYAL to stringResource(R.string.beta_auto_quality_loyal_title),
                AUTO_QUALITY_HOT_SWAP to "${stringResource(R.string.beta_auto_quality_hot_swap_title)} · $experimental"
            ),
            selected = setOfNotNull(
                AUTO_QUALITY_LOYAL.takeIf { settings.autoQualityLanguageLoyal },
                AUTO_QUALITY_HOT_SWAP.takeIf { settings.autoQualityHotSwap }
            ),
            onToggle = { key ->
                when (key) {
                    AUTO_QUALITY_LOYAL -> viewModel.setAutoQualityLanguageLoyal(!settings.autoQualityLanguageLoyal)
                    AUTO_QUALITY_HOT_SWAP -> viewModel.setAutoQualityHotSwap(!settings.autoQualityHotSwap)
                }
            }
        )
        SliderSettingsItem(
            title = stringResource(R.string.beta_auto_quality_confirm_title),
            subtitle = stringResource(R.string.beta_auto_quality_confirm_subtitle),
            value = settings.autoQualityConfirmSeconds,
            valueText = if (settings.autoQualityConfirmSeconds == 0) {
                stringResource(R.string.beta_auto_quality_confirm_instant)
            } else {
                stringResource(R.string.beta_playback_intro_length_value, settings.autoQualityConfirmSeconds)
            },
            minValue = 0,
            maxValue = BetaPerformanceSettings.AUTO_QUALITY_CONFIRM_MAX,
            step = 1,
            onValueChange = viewModel::setAutoQualityConfirm
        )
    }
}

/** Playback → Player Interface: the playing quality, the logo intro and seek thumbnails. */
@Composable
internal fun PlayerExtrasRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = stringResource(R.string.beta_minimal_player_title),
        subtitle = stringResource(R.string.beta_minimal_player_subtitle),
        checked = settings.minimalPlayer,
        onToggle = { viewModel.setMinimalPlayer(!settings.minimalPlayer) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_playback_intro_title),
        subtitle = stringResource(R.string.beta_playback_intro_subtitle),
        checked = settings.playbackIntro,
        onToggle = { viewModel.setPlaybackIntro(!settings.playbackIntro) }
    )
    // The intro's length, as pills, and its preview: shown only while it's on.
    if (settings.playbackIntro) {
        SettingsChipRow(
            items = BetaPerformanceSettings.INTRO_LENGTHS.map { seconds ->
                seconds.toString() to stringResource(R.string.beta_playback_intro_length_value, seconds)
            },
            selected = setOf(settings.introLengthSeconds.toString()),
            onToggle = { key -> viewModel.setIntroLength(key.toInt()) }
        )
        SettingsActionRow(
            title = stringResource(R.string.beta_playback_intro_preview),
            subtitle = null,
            onClick = { PlaybackIntroState.start() }
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.beta_seek_thumbnails_title),
        subtitle = null,
        checked = settings.seekThumbnails,
        onToggle = { viewModel.setSeekThumbnails(!settings.seekThumbnails) }
    )
    // Everything below belongs to Seek thumbnails: shown only while it's on.
    if (settings.seekThumbnails) {
        val single = stringResource(R.string.beta_seek_preview_style_single)
        val filmstrip = stringResource(R.string.beta_seek_preview_style_filmstrip)
        SettingsChipRow(
            items = listOf(STYLE_SINGLE to single, STYLE_FILMSTRIP to filmstrip),
            selected = setOf(if (settings.seekPreviewFilmstrip) STYLE_FILMSTRIP else STYLE_SINGLE),
            onToggle = { key -> viewModel.setSeekPreviewFilmstrip(key == STYLE_FILMSTRIP) }
        )
        SliderSettingsItem(
            title = stringResource(R.string.beta_seek_preview_budget_title),
            subtitle = stringResource(R.string.beta_seek_preview_budget_subtitle),
            values = BetaPerformanceSettings.SEEK_PREVIEW_BUDGETS,
            selected = settings.seekPreviewBudgetMb,
            valueText = when (val mb = settings.seekPreviewBudgetMb) {
                0 -> stringResource(R.string.beta_seek_preview_budget_unlimited)
                in 1 until 1000 -> stringResource(R.string.beta_seek_preview_budget_mb, mb)
                else -> stringResource(R.string.beta_seek_preview_budget_gb, mb / 1000f)
            },
            onValueChange = viewModel::setSeekPreviewBudget
        )
        SettingsSectionLabel(
            text = stringResource(R.string.beta_seek_preview_quality_title),
            description = stringResource(R.string.beta_seek_preview_quality_subtitle)
        )
        // One pick of several, as chips: the selected one is the quality.
        val off = stringResource(R.string.beta_seek_preview_quality_off)
        SettingsChipRow(
            items = BetaPerformanceSettings.SEEK_PREVIEW_QUALITIES.map { height ->
                height.toString() to when (height) {
                    0 -> off
                    2160 -> "4K"
                    else -> "${height}p"
                }
            },
            selected = setOf(settings.seekPreviewQuality.toString()),
            onToggle = { key -> viewModel.setSeekPreviewQuality(key.toInt()) }
        )
    }
}

/** Playback → Audio: which languages the player's audio menu lists. */
@Composable
internal fun AudioMenuRows(viewModel: BetaPerformanceSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSectionLabel(
        text = stringResource(R.string.beta_settings_group_audio_menu),
        description = stringResource(R.string.beta_audio_menu_group_subtitle)
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_audio_menu_title),
        subtitle = stringResource(R.string.beta_audio_menu_subtitle),
        checked = settings.audioLanguageMenu,
        onToggle = { viewModel.setAudioLanguageMenu(!settings.audioLanguageMenu) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.beta_audio_menu_unknown_title),
        subtitle = null,
        checked = settings.showUnknownAudio,
        enabled = settings.audioLanguageMenu,
        onToggle = { viewModel.setShowUnknownAudio(!settings.showUnknownAudio) }
    )
    if (settings.audioLanguageMenu) {
        // One chip per language, in a row, as Layout lists the hero's catalogs.
        val chips = listOf(AudioLanguage.ORIGINAL_KEY to stringResource(R.string.audio_menu_original)) +
            AudioLanguage.entries.map { it.code to audioLanguageDisplayName(it) }
        SettingsChipRow(
            items = chips,
            selected = settings.audioMenuLanguages,
            onToggle = { key -> viewModel.setAudioMenuLanguage(key, key !in settings.audioMenuLanguages) }
        )
    }
}

private const val STYLE_SINGLE = "single"
private const val STYLE_FILMSTRIP = "filmstrip"
private const val AUTO_QUALITY_LOYAL = "loyal"
private const val AMBIENT_DETAILS = "details"
private const val AUTO_QUALITY_HOT_SWAP = "hot_swap"

/** A horizontal row of on/off chips (keys to labels), for lists too long for a switch each. */
@Composable
internal fun SettingsChipRow(
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    firstChipModifier: Modifier = Modifier
) {
    val firstChip = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.fillMaxWidth().settingsOptionRow(firstChip),
        contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        itemsIndexed(items, key = { _, item -> item.first }) { index, (key, label) ->
            SettingsChoiceChip(
                label = label,
                selected = key in selected,
                onClick = { onToggle(key) },
                modifier = if (index == 0) firstChipModifier.focusRequester(firstChip) else Modifier
            )
        }
    }
}

/** Advanced → Diagnostics: the speed test. */
@Composable
internal fun SpeedTestRow() {
    SettingsActionRow(
        title = stringResource(R.string.speed_test_title),
        subtitle = stringResource(R.string.beta_speed_test_subtitle),
        onClick = LocalOpenSpeedTest.current
    )
}

private fun maskOmdbKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
