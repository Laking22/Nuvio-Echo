@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaPerformanceSettingsEntryPoint
import com.nuvio.tv.core.streams.QualityOption
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityMenu
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.data.local.BetaPerformanceSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay

/** Beta speed-up switches for player UI, which isn't wired to the settings store directly. */
@Composable
internal fun rememberBetaPerformanceSettings(): State<BetaPerformanceSettings> {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, BetaPerformanceSettingsEntryPoint::class.java)
            .betaPerformanceSettings()
    }
    return store.current.collectAsState()
}

/**
 * Netflix-style quality picker: Auto (when it's switched on in Settings) and the resolutions the
 * sources offer, best first. Picking one switches to a source of that resolution at the same position;
 * picking Auto lets the quality follow the connection.
 */
@Composable
internal fun QualitySelectionOverlay(
    visible: Boolean,
    streams: List<Stream>,
    currentQuality: StreamQuality?,
    isLoading: Boolean,
    onQualitySelected: (StreamQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** New look: the Audio | Quality tabs of the merged panel, in place of the title. */
    sectionTabs: (@Composable () -> Unit)? = null,
    /** Qualities offered only in other languages than the one being listened to, with those languages. */
    languageNotes: Map<StreamQuality, String> = emptyMap(),
    /** Auto quality is switched on in Settings: its row heads the list. */
    autoShown: Boolean = false,
    /** Auto is the picked quality (the playing one is then only labelled, not ticked). */
    autoActive: Boolean = false,
    onAutoSelected: () -> Unit = {}
) {
    // Built only while the menu is open; the source list also changes behind the Sources panel.
    val options = if (visible) {
        remember(streams, currentQuality) { StreamQualityMenu.buildOptions(streams, currentQuality) }
    } else {
        emptyList()
    }
    val initialFocusRequester = remember { FocusRequester() }
    val autoFocused = autoShown && autoActive
    val focusTarget = if (autoFocused) null else options.firstOrNull { it.isCurrent }?.quality ?: options.firstOrNull()?.quality
    var initialFocusDone by remember(visible) { mutableStateOf(false) }

    // Sources can arrive after the menu opens; focus the first sensible row once there is one.
    LaunchedEffect(visible, focusTarget, autoFocused) {
        if (!visible || initialFocusDone || (focusTarget == null && !autoFocused)) return@LaunchedEffect
        delay(120)
        runCatching { initialFocusRequester.requestFocus() }
        initialFocusDone = true
    }

    PlayerPopCard(
        visible = visible,
        modifier = modifier,
        head = {
            Box(modifier = Modifier.weight(1f)) {
                if (sectionTabs != null) sectionTabs() else Text(
                    text = stringResource(R.string.quality_menu_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    ) {
        PopHint(
            text = stringResource(
                if (isLoading && options.isEmpty()) R.string.quality_menu_loading
                else R.string.quality_menu_subtitle
            )
        )
        Column(
            modifier = Modifier
                .heightIn(max = PopListMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (autoShown) {
                AutoQualityRow(
                    selected = autoActive,
                    playing = currentQuality,
                    focusRequester = if (autoFocused) initialFocusRequester else null,
                    onClick = onAutoSelected
                )
            }
            options.forEach { option ->
                QualityRow(
                    option = option,
                    languageNote = languageNotes[option.quality],
                    ticked = option.isCurrent && !autoActive,
                    focusRequester = if (option.quality == focusTarget) initialFocusRequester else null,
                    onClick = { onQualitySelected(option.quality) }
                )
            }
        }
    }
}

/** Auto: ticked while picked, with the quality it's playing now. */
@Composable
private fun AutoQualityRow(
    selected: Boolean,
    playing: StreamQuality?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    PopRow(
        selected = selected,
        onClick = onClick,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    ) { primary, secondary ->
        PopRowText(
            title = stringResource(R.string.quality_auto),
            primary = primary,
            secondary = secondary,
            inline = if (selected && playing != null) playing.label else stringResource(R.string.quality_auto_desc)
        )
        PopRowEnd(selected = selected, note = null, secondary = secondary)
    }
}

@Composable
private fun QualityRow(
    option: QualityOption,
    languageNote: String?,
    ticked: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    PopRow(
        selected = ticked,
        onClick = onClick,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    ) { primary, secondary ->
        PopRowText(
            title = option.quality.label,
            primary = primary,
            secondary = secondary,
            inline = stringResource(option.quality.descriptionRes()),
            // Only offered in another language: which.
            below = languageNote
        )
        PopRowEnd(
            selected = ticked,
            // Auto is picked: the quality it's playing is labelled, not ticked.
            note = if (option.isCurrent) stringResource(R.string.quality_playing)
            else pluralStringResource(R.plurals.quality_source_count, option.sourceCount, option.sourceCount),
            secondary = secondary
        )
    }
}

private fun StreamQuality.descriptionRes(): Int = when (this) {
    StreamQuality.P2160 -> R.string.quality_desc_2160
    StreamQuality.P1440 -> R.string.quality_desc_1440
    StreamQuality.P1080 -> R.string.quality_desc_1080
    StreamQuality.P720 -> R.string.quality_desc_720
    StreamQuality.P480, StreamQuality.P360 -> R.string.quality_desc_sd
    StreamQuality.P240, StreamQuality.P144 -> R.string.quality_desc_low
}
