@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.BetaPerformanceSettingsEntryPoint
import com.nuvio.tv.core.streams.QualityOption
import com.nuvio.tv.core.streams.StreamQuality
import com.nuvio.tv.core.streams.StreamQualityMenu
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.ui.theme.NuvioTheme
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

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp)
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            if (sectionTabs != null) sectionTabs() else Text(
                text = stringResource(R.string.quality_menu_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text(
                text = stringResource(
                    if (isLoading && options.isEmpty()) R.string.quality_menu_loading
                    else R.string.quality_menu_subtitle
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )
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
    QualityCard(selected = selected, focusRequester = focusRequester, onClick = onClick) { primary, secondary ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(text = stringResource(R.string.quality_auto), style = MaterialTheme.typography.titleMedium, color = primary)
            Text(
                text = if (selected && playing != null) playing.label else stringResource(R.string.quality_auto_desc),
                style = MaterialTheme.typography.bodySmall,
                color = secondary
            )
        }
        if (selected) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NuvioTheme.colors.OnSecondary)
        }
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
    QualityCard(selected = ticked, focusRequester = focusRequester, onClick = onClick) { primary, secondary ->
        QualityLabels(option.quality, primary = primary, secondary = secondary)
        Column(horizontalAlignment = Alignment.End) {
            when {
                ticked -> Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NuvioTheme.colors.OnSecondary)
                // Auto is picked: the quality it's playing is labelled, not ticked.
                option.isCurrent -> Text(
                    text = stringResource(R.string.quality_playing),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                else -> Text(
                    text = pluralStringResource(R.plurals.quality_source_count, option.sourceCount, option.sourceCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            // Only offered in another language: which, small, bottom right.
            if (languageNote != null) {
                Text(
                    text = languageNote,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    color = if (ticked) secondary else NuvioTheme.colors.TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

/** A focusable row of the menu, filled with the accent while [selected]. */
@Composable
private fun QualityCard(
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(primary: Color, secondary: Color) -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (selected) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent), shape = shape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primary = if (selected) NuvioTheme.colors.OnSecondary else Color.White
        val secondary = if (selected) NuvioTheme.colors.OnSecondary.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.72f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            content(primary, secondary)
        }
    }
}

@Composable
private fun QualityLabels(quality: StreamQuality, primary: Color, secondary: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(text = quality.label, style = MaterialTheme.typography.titleMedium, color = primary)
        Text(text = stringResource(quality.descriptionRes()), style = MaterialTheme.typography.bodySmall, color = secondary)
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
