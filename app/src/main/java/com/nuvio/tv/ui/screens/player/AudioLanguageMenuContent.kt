@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
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
    PopHint(text = stringResource(if (isLoadingSources) R.string.audio_menu_loading else R.string.audio_menu_hint))
    if (options.isEmpty()) return
    val focusKey = options.audioMenuFocusKey()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .heightIn(max = PopListMaxHeight)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PopRowText(title = title, primary = Color.White, secondary = Color.White)
            PopRowEnd(selected = false, note = stringResource(R.string.audio_menu_no_sources), secondary = Color.White)
        }
        return
    }

    val note = when {
        option.trackIndex != null -> stringResource(R.string.audio_menu_in_file)
        else -> pluralStringResource(R.plurals.quality_source_count, option.sourceCount, option.sourceCount)
    }
    PopRow(
        selected = option.isCurrent,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
    ) { primary, secondary ->
        PopRowText(title = title, primary = primary, secondary = secondary, below = trackLine)
        PopRowEnd(selected = option.isCurrent, note = note, secondary = secondary)
    }
}
