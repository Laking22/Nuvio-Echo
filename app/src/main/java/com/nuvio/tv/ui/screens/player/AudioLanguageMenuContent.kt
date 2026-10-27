@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.AudioLanguage
import com.nuvio.tv.core.streams.AudioMenuOption
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.languageCodeToName

/** A language's name in the app's language, e.g. "Japanese". */
internal fun audioLanguageDisplayName(language: AudioLanguage): String {
    val name = runCatching { languageCodeToName(language.code) }.getOrNull()
    return name?.takeIf { it.isNotBlank() && !it.equals(language.code, ignoreCase = true) && it != "Unknown" }
        ?: language.englishName
}

/**
 * What a track's title says beyond its language and codec: "English Dub@Yameii [CR] (AAC Stereo)"
 * gives "Dub@Yameii [CR]", "Japanese (E-AC-3 Stereo)" gives nothing (the codec line covers it).
 */
internal fun trackTitleExtra(name: String, language: AudioLanguage?): String? {
    var extra = name.trim().replace(Regex("\\s*\\([^()]*\\)\\s*$"), "")
    if (language != null) {
        listOf(audioLanguageDisplayName(language), language.englishName).forEach { prefix ->
            if (extra.startsWith(prefix, ignoreCase = true)) extra = extra.substring(prefix.length)
        }
    }
    extra = extra.trim().trimStart('-', '·', ':', ',', '|', '/', '@').trim()
    return extra.takeIf { it.isNotEmpty() }
}

/** The row the menu focuses first: what's playing, else the first that can be picked. */
internal fun List<AudioMenuOption>.audioMenuFocusKey(): String? =
    (firstOrNull { it.isCurrent } ?: firstOrNull { it.isAvailable })?.key

/**
 * The audio menu's language list: the playing file's tracks and the languages other sources
 * have, filtered by the languages chosen in Beta speed-ups. OK picks; holding OK opens Sources.
 */
@Composable
internal fun AudioLanguageList(
    options: List<AudioMenuOption>,
    isLoadingSources: Boolean,
    listState: LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackSelected: (Int) -> Unit,
    onLanguageSelected: (AudioMenuOption) -> Unit,
    onSourcesRequested: (AudioMenuOption) -> Unit
) {
    Text(
        text = stringResource(
            if (isLoadingSources) R.string.audio_menu_loading else R.string.audio_menu_hint
        ),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
    )
    if (options.isEmpty()) {
        Text(
            text = stringResource(R.string.audio_lang_default),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.md)
        )
        return
    }
    val focusKey = options.audioMenuFocusKey()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = Modifier
            .heightIn(max = 620.dp)
            .fillMaxWidth()
    ) {
        items(items = options, key = { it.key }) { option ->
            AudioLanguageRow(
                option = option,
                focusRequester = if (option.key == focusKey) initialFocusRequester else null,
                rightFocusRequester = rightFocusRequester,
                onClick = {
                    val track = option.trackIndex
                    if (track != null) onTrackSelected(track) else onLanguageSelected(option)
                },
                onLongClick = { onSourcesRequested(option) }
            )
        }
    }
}

@Composable
private fun AudioLanguageRow(
    option: AudioMenuOption,
    focusRequester: FocusRequester?,
    rightFocusRequester: FocusRequester,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val title = option.language?.let { language ->
        val name = audioLanguageDisplayName(language)
        if (option.isOriginal) stringResource(R.string.audio_menu_original_named, name) else name
    } ?: stringResource(R.string.audio_menu_unknown)
    val trackLine = listOfNotNull(
        option.trackName?.let { trackTitleExtra(it, option.language) },
        option.trackDetail
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    if (!option.isAvailable) {
        // Greyed out and skipped by the D-pad: no source has this language.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.32f)
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                text = stringResource(R.string.audio_menu_no_sources),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
        return
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester },
        colors = CardDefaults.colors(
            containerColor = if (option.isCurrent) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (option.isCurrent) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent), shape = shape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primary = if (option.isCurrent) NuvioTheme.colors.OnSecondary else Color.White
        val secondary = if (option.isCurrent) NuvioTheme.colors.OnSecondary.copy(alpha = 0.78f) else NuvioTheme.colors.TextTertiary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = primary)
                if (trackLine != null) {
                    Text(
                        text = trackLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when {
                option.isCurrent -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.OnSecondary
                )
                option.trackIndex != null -> Text(
                    text = stringResource(R.string.audio_menu_in_file),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
                else -> Text(
                    text = pluralStringResource(R.plurals.quality_source_count, option.sourceCount, option.sourceCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary
                )
            }
        }
    }
}
