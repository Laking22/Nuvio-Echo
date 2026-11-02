@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player


import android.util.Log
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"
private const val SubtitleFocusTag = "SubtitleFocus"

private val OverlayTextColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

private val OverlayOutlineColors = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    useLibass: Boolean = false,
    isUsingMpv: Boolean = false,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val unknownLabel = stringResource(R.string.subtitle_language_unknown)
    val builtInLabel = stringResource(R.string.subtitle_built_in)
    val forcedLabel = stringResource(R.string.sub_forced_lang)
    val sessionPreferredLanguage = remember(visible) { subtitleStyle.preferredLanguage }
    val sessionSecondaryPreferredLanguage = remember(visible) { subtitleStyle.secondaryPreferredLanguage }
    val sessionShowOnlyPreferredLanguages = remember(visible) { subtitleStyle.showOnlyPreferredLanguages }
    val sessionSelectedInternalIndex = remember(visible) { selectedInternalIndex }
    val sessionInternalTracks = remember(visible) { internalTracks.map(TrackInfo::copy) }
    val sessionAddonSubtitles = remember(visible, addonSubtitles) { addonSubtitles.map(Subtitle::copy) }
    val sessionSelectedAddonSubtitle = remember(visible) { selectedAddonSubtitle?.copy() }
    val sessionInstalledSubtitleAddonOrder = remember(visible) { installedSubtitleAddonOrder.toList() }
    val sessionIsLoadingAddons = isLoadingAddons
    val sessionSelectedSubtitleLanguageKey = remember(visible) {
        selectedSubtitleLanguageKey(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = sessionSelectedInternalIndex,
            selectedAddonSubtitle = sessionSelectedAddonSubtitle
        )
    }
    val languageItems = remember(visible, sessionAddonSubtitles) {
        buildSubtitleLanguageRailItems(
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            preferredLanguage = sessionPreferredLanguage,
            secondaryPreferredLanguage = sessionSecondaryPreferredLanguage,
            showOnlyPreferredLanguages = sessionShowOnlyPreferredLanguages,
            currentLanguageKey = sessionSelectedSubtitleLanguageKey,
            noneLabel = noneLabel,
            unknownLabel = unknownLabel
        )
    }
    val sessionInitialLanguageKey = remember(visible, languageItems, sessionSelectedSubtitleLanguageKey) {
        sessionSelectedSubtitleLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull { it.key != SubtitleOffLanguageKey }?.key
            ?: SubtitleOffLanguageKey
    }
    val sessionInitialSelectedOptionId = remember(
        visible,
        sessionInitialLanguageKey,
        sessionSelectedSubtitleLanguageKey
    ) {
        val optionId = selectedSubtitleOptionId(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = sessionSelectedInternalIndex,
            selectedAddonSubtitle = sessionSelectedAddonSubtitle
        )
        optionId.takeIf { sessionInitialLanguageKey == sessionSelectedSubtitleLanguageKey }
    }
    fun buildSessionOptions(languageKey: String, activeSelectedOptionId: String?): List<SubtitleOptionRailItem> {
        return buildSubtitleOptionRailItems(
            selectedLanguageKey = languageKey,
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            installedAddonOrder = sessionInstalledSubtitleAddonOrder,
            selectedOptionId = activeSelectedOptionId,
            builtInLabel = builtInLabel,
            forcedLabel = forcedLabel
        )
    }

    val sessionInitialSubtitleOptions = remember(
        visible,
        sessionInitialLanguageKey,
        sessionInitialSelectedOptionId
    ) {
        buildSessionOptions(sessionInitialLanguageKey, sessionInitialSelectedOptionId)
    }
    val sessionInitialOptionTargetId = remember(visible) {
        sessionInitialSelectedOptionId
            ?.takeIf { id -> sessionInitialSubtitleOptions.any { it.id == id } }
            ?: sessionInitialSubtitleOptions.firstOrNull()?.id
    }
    var selectedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey)
    }
    var selectedOptionId by remember(visible) {
        mutableStateOf(sessionInitialSelectedOptionId)
    }
    val subtitleOptions = remember(
        selectedLanguageKey,
        selectedOptionId,
        sessionInternalTracks,
        sessionAddonSubtitles,
        sessionInstalledSubtitleAddonOrder
    ) {
        buildSessionOptions(selectedLanguageKey, selectedOptionId)
    }
    var lastFocusedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey.takeIf { key -> languageItems.any { it.key == key } })
    }
    var optionFocusMemory by remember(visible) {
        mutableStateOf<Map<String, String>>(
            sessionInitialOptionTargetId
                ?.let { mapOf(sessionInitialLanguageKey to it) }
                ?: emptyMap()
        )
    }
    var optionEntryLanguageKey by remember(visible) { mutableStateOf(sessionInitialLanguageKey) }
    var activeRail by remember(visible) { mutableStateOf<OverlayFocusRail?>(null) }
    var activeOptionFocusId by remember(visible) { mutableStateOf<String?>(sessionInitialOptionTargetId) }
    var activeStyleFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var languageFocusToken by remember(visible) { mutableStateOf(0) }
    var optionFocusToken by remember(visible) { mutableStateOf(0) }
    var pendingLanguageFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusId by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusLanguageKey by remember(visible) { mutableStateOf<String?>(null) }
    val overlaySessionKey = remember(visible) { Any() }
    val languageInitialVisibleIndex = remember(visible, languageItems, sessionInitialLanguageKey) {
        preferredVisibleStartIndex(languageItems.indexOfFirst { it.key == sessionInitialLanguageKey })
    }
    val optionInitialVisibleIndex = remember(visible, sessionInitialSubtitleOptions, sessionInitialSelectedOptionId) {
        preferredVisibleStartIndex(sessionInitialSubtitleOptions.indexOfFirst { it.id == sessionInitialSelectedOptionId })
    }
    val languageListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = languageInitialVisibleIndex)
    }
    val optionListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = optionInitialVisibleIndex)
    }
    val styleListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = 0)
    }
    val languageItemRequesters = rememberFocusRequesterMap(languageItems.map { it.key })
    val optionItemRequesters = rememberFocusRequesterMap(subtitleOptions.map { it.id })
    val styleRequesters = rememberStyleFocusRequesters()
    val optionRailVisible = selectedLanguageKey != SubtitleOffLanguageKey
    val languageTargetKey: String? = remember(languageItems, lastFocusedLanguageKey, selectedLanguageKey) {
        lastFocusedLanguageKey?.takeIf { key -> languageItems.any { it.key == key } }
            ?: selectedLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull()?.key
    }
    val optionTargetId: String? = remember(subtitleOptions, optionFocusMemory, selectedLanguageKey, selectedOptionId) {
        selectedOptionId?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: optionFocusMemory[selectedLanguageKey]?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: subtitleOptions.firstOrNull()?.id
    }
    val isStyleDisabledByLibass = remember(
        useLibass,
        isUsingMpv,
        subtitleOptions,
        selectedOptionId,
        sessionInternalTracks,
        sessionSelectedInternalIndex,
        sessionSelectedAddonSubtitle
    ) {
        if (!useLibass && !isUsingMpv) return@remember false
        val selectedOption = subtitleOptions.firstOrNull { it.id == selectedOptionId }
        val isAss = when (selectedOption?.kind) {
            SubtitleOptionKind.INTERNAL -> {
                val track = selectedOption.internalTrackIndex?.let { sessionInternalTracks.getOrNull(it) }
                val codec = track?.codec?.lowercase(java.util.Locale.US).orEmpty()
                codec.contains("ass") || codec.contains("ssa") || track?.name?.contains("ASS", ignoreCase = true) == true
            }
            SubtitleOptionKind.ADDON -> {
                val url = selectedOption.addonSubtitle?.url?.lowercase(java.util.Locale.US).orEmpty()
                url.contains(".ass") || url.contains(".ssa")
            }
            null -> {
                val currentInternalTrack = sessionInternalTracks.getOrNull(sessionSelectedInternalIndex)
                val internalCodec = currentInternalTrack?.codec?.lowercase(java.util.Locale.US).orEmpty()
                val addonUrl = sessionSelectedAddonSubtitle?.url?.lowercase(java.util.Locale.US).orEmpty()
                internalCodec.contains("ass") || internalCodec.contains("ssa") ||
                    currentInternalTrack?.name?.contains("ASS", ignoreCase = true) == true ||
                    addonUrl.contains(".ass") || addonUrl.contains(".ssa")
            }
        }
        isAss && (isUsingMpv || useLibass)
    }

    fun requestLanguageFocus(targetKey: String?) {
        val resolvedKey = targetKey
            ?.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull()?.key
            ?: return
        pendingLanguageFocusKey = resolvedKey
        languageFocusToken += 1
    }

    fun requestOptionFocus(
        targetId: String?,
        languageKey: String = selectedLanguageKey,
        reason: String
    ) {
        val resolvedId = targetId ?: subtitleOptions.firstOrNull()?.id
            ?: return
        if (pendingOptionFocusId == resolvedId && pendingOptionFocusLanguageKey == languageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=duplicate_pending source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        if (
            activeRail == OverlayFocusRail.OPTION &&
            languageKey == selectedLanguageKey &&
            activeOptionFocusId == resolvedId
        ) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=already_focused source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        pendingOptionFocusId = resolvedId
        pendingOptionFocusLanguageKey = languageKey
        Log.d(
            SubtitleFocusTag,
            "option_restore_schedule source=$reason language=$languageKey id=$resolvedId"
        )
        optionFocusToken += 1
    }

    fun moveFocusToLanguageRail() {
        requestLanguageFocus(optionEntryLanguageKey)
    }

    fun moveFocusToOptionRail() {
        optionEntryLanguageKey = lastFocusedLanguageKey ?: selectedLanguageKey
        requestOptionFocus(
            targetId = optionTargetId,
            languageKey = selectedLanguageKey,
            reason = "language_to_option"
        )
    }

    // The gear swaps the subtitle list for their style (delay, size, colour, outline).
    var showStyle by remember(visible) { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (sessionInitialLanguageKey != SubtitleOffLanguageKey && sessionInitialSelectedOptionId != null) {
            Log.d(
                SubtitleFocusTag,
                "overlay_open focus=option selectedLanguage=$sessionInitialLanguageKey selectedOption=$sessionInitialSelectedOptionId showStyle=true"
            )
            requestOptionFocus(
                targetId = sessionInitialSelectedOptionId,
                languageKey = sessionInitialLanguageKey,
                reason = "overlay_open"
            )
        } else {
            Log.d(
                SubtitleFocusTag,
                "overlay_open focus=language selectedLanguage=$sessionInitialLanguageKey showOption=${sessionInitialLanguageKey != SubtitleOffLanguageKey} showStyle=${sessionInitialSelectedOptionId != null}"
            )
            requestLanguageFocus(sessionInitialLanguageKey)
        }
    }

    LaunchedEffect(visible, sessionInitialLanguageKey, languageItems) {
        if (!visible) return@LaunchedEffect
        val targetIndex = languageItems.indexOfFirst { it.key == sessionInitialLanguageKey }
        if (targetIndex >= 0) {
            languageListState.scrollItemIntoView(targetIndex)
        }
    }

    PlayerPopCard(
        visible = visible,
        modifier = modifier,
        head = {
            Box(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                SubtitleLanguageRail(
                    items = languageItems,
                    selectedLanguageKey = selectedLanguageKey,
                    listState = languageListState,
                    itemFocusRequesters = languageItemRequesters,
                    focusTargetKey = pendingLanguageFocusKey,
                    focusToken = languageFocusToken,
                    onFocusRequestConsumed = {
                        pendingLanguageFocusKey = null
                    },
                    onMoveDown = if (optionRailVisible && subtitleOptions.isNotEmpty()) ::moveFocusToOptionRail else null,
                    onLanguageSelected = { languageKey ->
                        Log.d(
                            SubtitleFocusTag,
                            "language_select key=$languageKey previous=$selectedLanguageKey"
                        )
                        selectedLanguageKey = languageKey
                        lastFocusedLanguageKey = languageKey
                        optionEntryLanguageKey = languageKey
                        activeRail = OverlayFocusRail.LANGUAGE
                        if (languageKey == SubtitleOffLanguageKey) {
                            selectedOptionId = null
                            onDisableSubtitles()
                        } else {
                            val nextOptions = buildSessionOptions(languageKey, selectedOptionId)
                            val nextSelectedId = selectedOptionId?.takeIf { id ->
                                nextOptions.any { it.id == id }
                            }
                            selectedOptionId = nextSelectedId
                            val nextTargetId = nextSelectedId
                                ?: optionFocusMemory[languageKey]?.takeIf { id ->
                                    nextOptions.any { it.id == id }
                                }
                                ?: nextOptions.firstOrNull()?.id
                            if (nextTargetId != null) {
                                optionFocusMemory = optionFocusMemory + (languageKey to nextTargetId)
                            }
                            requestOptionFocus(
                                targetId = nextTargetId,
                                languageKey = languageKey,
                                reason = "language_select"
                            )
                        }
                    },
                    onLanguageFocused = { key ->
                        lastFocusedLanguageKey = key
                        optionEntryLanguageKey = key
                        activeRail = OverlayFocusRail.LANGUAGE
                        activeStyleFocusKey = null
                    }
                )
            }
            PopGearButton(
                open = showStyle,
                onClick = { showStyle = !showStyle },
                contentDescription = stringResource(R.string.player_subtitle_style)
            )
        }
    ) {
        if (showStyle) {
            SubtitleStyleRail(
                subtitleStyle = subtitleStyle,
                subtitleDelayMs = subtitleDelayMs,
                listState = styleListState,
                focusRequesters = styleRequesters,
                onStyleFocused = {
                    activeRail = OverlayFocusRail.STYLE
                    activeStyleFocusKey = it
                },
                onEvent = onEvent,
                isStyleDisabledByLibass = isStyleDisabledByLibass
            )
        } else {
            SubtitleOptionsRail(
                selectedLanguageKey = selectedLanguageKey,
                languageLabel = languageItems.firstOrNull { it.key == selectedLanguageKey }?.label.orEmpty(),
                options = subtitleOptions,
                isLoadingAddons = sessionIsLoadingAddons,
                listState = optionListState,
                itemFocusRequesters = optionItemRequesters,
                focusTargetId = pendingOptionFocusId,
                focusLanguageKey = pendingOptionFocusLanguageKey,
                focusToken = optionFocusToken,
                onFocusRequestConsumed = {
                    pendingOptionFocusId = null
                    pendingOptionFocusLanguageKey = null
                },
                onOptionFocused = {
                    optionFocusMemory = optionFocusMemory + (selectedLanguageKey to it)
                    activeOptionFocusId = it
                    activeRail = OverlayFocusRail.OPTION
                    activeStyleFocusKey = null
                },
                onMoveUp = ::moveFocusToLanguageRail,
                onInternalTrackSelected = { optionId, trackIndex ->
                    selectedOptionId = optionId
                    optionFocusMemory = optionFocusMemory + (selectedLanguageKey to optionId)
                    activeOptionFocusId = optionId
                    activeRail = OverlayFocusRail.OPTION
                    onInternalTrackSelected(trackIndex)
                },
                onAddonSubtitleSelected = { optionId, subtitle ->
                    selectedOptionId = optionId
                    optionFocusMemory = optionFocusMemory + (selectedLanguageKey to optionId)
                    activeOptionFocusId = optionId
                    activeRail = OverlayFocusRail.OPTION
                    onAddonSubtitleSelected(subtitle)
                },
                onOptionLongPressed = { optionId ->
                    activeOptionFocusId = optionId
                    activeRail = OverlayFocusRail.OPTION
                    if (optionId == selectedOptionId) {
                        selectedOptionId = null
                        onDisableSubtitles()
                    }
                }
            )
        }
    }
}

/** Up or down on the D-pad (whichever [keyCode] is) runs [action] instead of moving focus; null leaves it alone. */
private fun Modifier.onDpad(keyCode: Int, action: (() -> Unit)?): Modifier = onPreviewKeyEvent { event ->
    if (action == null || event.nativeKeyEvent.keyCode != keyCode) return@onPreviewKeyEvent false
    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) action()
    true
}

/** Fades the end of the language pills, so it shows there are more to scroll to. */
private fun Modifier.fadeEnd(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(0.85f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn
        )
    }

/** The card's head: every language as a pill; OK picks one and lists its subtitles below. */
@Composable
private fun SubtitleLanguageRail(
    items: List<SubtitleLanguageRailItem>,
    selectedLanguageKey: String,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onMoveDown: (() -> Unit)?,
    onLanguageSelected: (String) -> Unit,
    onLanguageFocused: (String) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val targetKey = focusTargetKey ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.key == targetKey }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        Log.d(
            SubtitleFocusTag,
            "language_restore_request key=$targetKey index=$targetIndex selected=$selectedLanguageKey firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        itemFocusRequesters[targetKey]?.requestFocusAfterFrames()
        onFocusRequestConsumed()
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        // Room past the last pill, so it can scroll clear of the fade.
        contentPadding = PaddingValues(end = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .fadeEnd()
    ) {
        items(items = items, key = { item -> item.key }) { item ->
            PopPill(
                text = item.label,
                selected = item.key == selectedLanguageKey,
                count = item.count,
                onClick = { onLanguageSelected(item.key) },
                focusRequester = itemFocusRequesters[item.key],
                onFocused = { onLanguageFocused(item.key) },
                modifier = Modifier.onDpad(android.view.KeyEvent.KEYCODE_DPAD_DOWN, onMoveDown)
            )
        }
    }
}

/** The card's body: the picked language's subtitles, one row each, as the quality list shows qualities. */
@Composable
private fun SubtitleOptionsRail(
    selectedLanguageKey: String,
    languageLabel: String,
    options: List<SubtitleOptionRailItem>,
    isLoadingAddons: Boolean,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetId: String?,
    focusLanguageKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onOptionFocused: (String) -> Unit,
    onMoveUp: () -> Unit,
    onInternalTrackSelected: (String, Int) -> Unit,
    onAddonSubtitleSelected: (String, Subtitle) -> Unit,
    onOptionLongPressed: (String) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val requestLanguageKey = focusLanguageKey
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        if (requestLanguageKey != selectedLanguageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_drop reason=language_mismatch requestLanguage=$requestLanguageKey selectedLanguage=$selectedLanguageKey target=$focusTargetId"
            )
            onFocusRequestConsumed()
            return@LaunchedEffect
        }
        val targetIndex = options.indexOfFirst { it.id == focusTargetId }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        listState.scrollItemIntoView(targetIndex)
        itemFocusRequesters[focusTargetId]?.requestFocusAfterFrames()
        onFocusRequestConsumed()
    }

    when {
        selectedLanguageKey == SubtitleOffLanguageKey -> PopHint(text = stringResource(R.string.subtitle_none))
        options.isEmpty() && isLoadingAddons -> OverlayLoadingCard(text = stringResource(R.string.subtitle_loading_addon))
        options.isEmpty() -> PopHint(text = stringResource(R.string.subtitle_no_addon))
        else -> {
            PopHint(text = stringResource(R.string.player_subtitles_available, languageLabel, options.size))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PopListMaxHeight)
            ) {
                itemsIndexed(items = options, key = { _, option -> option.id }) { index, option ->
                    SubtitleOptionCard(
                        item = option,
                        focusRequester = itemFocusRequesters[option.id],
                        // Up from the top row goes back to the language picked, wherever the pills have scrolled.
                        onMoveUp = if (index == 0) onMoveUp else null,
                        onFocused = { onOptionFocused(option.id) },
                        onClick = {
                            when (option.kind) {
                                SubtitleOptionKind.INTERNAL -> option.internalTrackIndex?.let { trackIndex ->
                                    onInternalTrackSelected(option.id, trackIndex)
                                }
                                SubtitleOptionKind.ADDON -> option.addonSubtitle?.let { subtitle ->
                                    onAddonSubtitleSelected(option.id, subtitle)
                                }
                            }
                        },
                        onLongClick = { onOptionLongPressed(option.id) }
                    )
                }
            }
        }
    }
}

/** Behind the gear: the subtitles' delay and look, one setting a row. */
@Composable
private fun SubtitleStyleRail(
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    listState: LazyListState,
    focusRequesters: Map<String, FocusRequester>,
    onStyleFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    isStyleDisabledByLibass: Boolean = false
) {
    val dispatchStyleEvent: (PlayerEvent) -> Unit = { event ->
        if (!isStyleDisabledByLibass) {
            onEvent(event)
        }
    }
    PopHint(text = stringResource(R.string.player_subtitle_style))
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PopListMaxHeight)
            .then(if (isStyleDisabledByLibass) Modifier.alpha(0.35f) else Modifier)
    ) {
        item {
            PopSettingRow(label = stringResource(R.string.subtitle_tab_delay)) {
                StepperRow(
                    value = formatSubtitleDelay(subtitleDelayMs),
                    onDecrease = { dispatchStyleEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS, showOverlay = false)) },
                    onIncrease = { dispatchStyleEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS, showOverlay = false)) },
                    decrementFocusRequester = focusRequesters[StyleFocusKey.DelaySet],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.DelayIncrease],
                    decrementFocusKey = StyleFocusKey.DelaySet,
                    incrementFocusKey = StyleFocusKey.DelayIncrease,
                    onFocusChanged = onStyleFocused
                )
            }
        }
        item {
            PopSettingRow(label = stringResource(R.string.subtitle_style_font_size)) {
                StepperRow(
                    value = "${subtitleStyle.size}%",
                    onDecrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) },
                    onIncrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) },
                    decrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeIncrease],
                    decrementFocusKey = StyleFocusKey.FontSizeDecrease,
                    incrementFocusKey = StyleFocusKey.FontSizeIncrease,
                    onFocusChanged = onStyleFocused
                )
            }
        }
        item {
            PopSettingRow(label = stringResource(R.string.subtitle_style_bold)) {
                ToggleChip(
                    isEnabled = subtitleStyle.bold,
                    focusRequester = focusRequesters[StyleFocusKey.Bold],
                    focusKey = StyleFocusKey.Bold,
                    onFocused = onStyleFocused,
                    onClick = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                )
            }
        }
        item {
            PopSettingRow(label = stringResource(R.string.subtitle_style_text_color)) {
                ColorChipRow(
                    colors = OverlayTextColors,
                    selectedColor = subtitleStyle.textColor,
                    focusRequesters = focusRequesters,
                    focusKeyPrefix = StyleFocusKey.TextColorPrefix,
                    onFocused = onStyleFocused,
                    onColorSelected = { color -> dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(color)) }
                )
            }
        }
        item {
            val currentColor = Color(subtitleStyle.textColor)
            val currentAlphaPercent = (currentColor.alpha * 100f).roundToInt().coerceIn(0, 100)
            PopSettingRow(label = stringResource(R.string.subtitle_style_text_opacity)) {
                StepperRow(
                    value = "$currentAlphaPercent%",
                    onDecrease = {
                        val newAlpha = (currentAlphaPercent - 10).coerceAtLeast(0) / 100f
                        dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                    },
                    onIncrease = {
                        val newAlpha = (currentAlphaPercent + 10).coerceAtMost(100) / 100f
                        dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                    },
                    decrementFocusRequester = focusRequesters[StyleFocusKey.OpacityDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.OpacityIncrease],
                    decrementFocusKey = StyleFocusKey.OpacityDecrease,
                    incrementFocusKey = StyleFocusKey.OpacityIncrease,
                    onFocusChanged = onStyleFocused
                )
            }
        }
        item {
            Column {
                PopSettingRow(label = stringResource(R.string.subtitle_style_outline)) {
                    ToggleChip(
                        isEnabled = subtitleStyle.outlineEnabled,
                        focusRequester = focusRequesters[StyleFocusKey.OutlineToggle],
                        focusKey = StyleFocusKey.OutlineToggle,
                        onFocused = onStyleFocused,
                        onClick = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    ColorChipRow(
                        colors = OverlayOutlineColors,
                        selectedColor = subtitleStyle.outlineColor,
                        enabled = subtitleStyle.outlineEnabled,
                        focusRequesters = focusRequesters,
                        focusKeyPrefix = StyleFocusKey.OutlineColorPrefix,
                        onFocused = onStyleFocused,
                        onColorSelected = { color ->
                            if (!subtitleStyle.outlineEnabled) {
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                            }
                            dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineColor(color))
                        }
                    )
                }
            }
        }
        item {
            PopSettingRow(label = stringResource(R.string.subtitle_style_bottom_offset)) {
                StepperRow(
                    value = subtitleStyle.verticalOffset.toString(),
                    onDecrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) },
                    onIncrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) },
                    decrementFocusRequester = focusRequesters[StyleFocusKey.OffsetDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.OffsetIncrease],
                    decrementFocusKey = StyleFocusKey.OffsetDecrease,
                    incrementFocusKey = StyleFocusKey.OffsetIncrease,
                    onFocusChanged = onStyleFocused
                )
            }
        }
        item {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                PopPill(
                    text = stringResource(R.string.subtitle_reset_defaults),
                    selected = false,
                    onClick = { dispatchStyleEvent(PlayerEvent.OnResetSubtitleDefaults) },
                    focusRequester = focusRequesters[StyleFocusKey.Reset],
                    onFocused = { onStyleFocused(StyleFocusKey.Reset) }
                )
            }
        }
    }
}

@Composable
private fun SubtitleOptionCard(
    item: SubtitleOptionRailItem,
    focusRequester: FocusRequester?,
    onMoveUp: (() -> Unit)?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    PopRow(
        selected = item.isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onDpad(android.view.KeyEvent.KEYCODE_DPAD_UP, onMoveUp)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) { primary, secondary ->
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!item.meta.isNullOrBlank()) {
                    Text(
                        text = item.meta,
                        fontSize = 11.sp,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
        // Where it's from, until it's the one ticked.
        PopRowEnd(selected = item.isSelected, note = item.sourceLabel, secondary = secondary)
    }
}

@Composable
private fun OverlayLoadingCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LoadingIndicator(modifier = Modifier.size(20.dp))
        Text(text = text, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
    }
}

@Composable
private fun StepperRow(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decrementFocusRequester: FocusRequester? = null,
    incrementFocusRequester: FocusRequester? = null,
    decrementFocusKey: String? = null,
    incrementFocusKey: String? = null,
    onFocusChanged: ((String) -> Unit)? = null
) {
    StepperButton(
        icon = Icons.Default.Remove,
        onClick = onDecrease,
        focusRequester = decrementFocusRequester,
        focusKey = decrementFocusKey,
        onFocused = onFocusChanged
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        PopStepperValue(text = value)
    }
    StepperButton(
        icon = Icons.Default.Add,
        onClick = onIncrease,
        focusRequester = incrementFocusRequester,
        focusKey = incrementFocusKey,
        onFocused = onFocusChanged
    )
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(30.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color(0xFF111111)
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = IconButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleChip(
    isEnabled: Boolean,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    PopPill(
        text = stringResource(if (isEnabled) R.string.subtitle_style_on else R.string.subtitle_style_off),
        selected = isEnabled,
        onClick = onClick,
        focusRequester = focusRequester,
        onFocused = { if (focusKey != null) onFocused?.invoke(focusKey) }
    )
}

@Composable
private fun ColorChipRow(
    colors: List<Color>,
    selectedColor: Int,
    enabled: Boolean = true,
    focusRequesters: Map<String, FocusRequester>,
    focusKeyPrefix: String,
    onFocused: ((String) -> Unit)? = null,
    onColorSelected: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { color ->
            val focusKey = "$focusKeyPrefix:${color.toArgb()}"
            ColorChip(
                color = if (enabled) color else color.copy(alpha = 0.35f),
                // The opacity setting changes the text colour's alpha: match on the colour alone.
                isSelected = color.copy(alpha = 1f).toArgb() == Color(selectedColor).copy(alpha = 1f).toArgb(),
                enabled = enabled,
                focusRequester = focusRequesters[focusKey],
                focusKey = focusKey,
                onFocused = onFocused,
                onClick = { onColorSelected(color.toArgb()) }
            )
        }
    }
}

@Composable
private fun ColorChip(
    color: Color,
    isSelected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        modifier = Modifier
            .size(22.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                when {
                    isFocused -> Modifier.border(2.dp, Color.White, CircleShape).padding(3.dp).border(1.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
                    isSelected -> Modifier.border(2.dp, Color.White, CircleShape)
                    else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        shape = CardDefaults.shape(CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.18f, pressedScale = 1f)
    ) {}
}

private object StyleFocusKey {
    const val FontSizeDecrease = "font_size_decrease"
    const val FontSizeIncrease = "font_size_increase"
    const val Bold = "bold"
    const val OutlineToggle = "outline_toggle"
    const val OffsetDecrease = "offset_decrease"
    const val OffsetIncrease = "offset_increase"
    const val DelaySet = "delay_set"
    const val DelayIncrease = "delay_increase"
    const val Reset = "reset"
    const val TextColorPrefix = "text_color"
    const val OpacityDecrease = "opacity_decrease"
    const val OpacityIncrease = "opacity_increase"
    const val OutlineColorPrefix = "outline_color"
}

private enum class OverlayFocusRail {
    LANGUAGE,
    OPTION,
    STYLE
}

@Composable
private fun rememberFocusRequesterMap(keys: List<String>): Map<String, FocusRequester> {
    return remember(keys) { keys.associateWith { FocusRequester() } }
}

@Composable
private fun rememberStyleFocusRequesters(): Map<String, FocusRequester> {
    return remember {
        listOf(
            StyleFocusKey.FontSizeDecrease,
            StyleFocusKey.FontSizeIncrease,
            StyleFocusKey.Bold,
            StyleFocusKey.OpacityDecrease,
            StyleFocusKey.OpacityIncrease,
            StyleFocusKey.OutlineToggle,
            StyleFocusKey.OffsetDecrease,
            StyleFocusKey.OffsetIncrease,
            StyleFocusKey.DelaySet,
            StyleFocusKey.DelayIncrease,
            StyleFocusKey.Reset
        ).associateWith { FocusRequester() } +
            OverlayTextColors.associate { color ->
                "${StyleFocusKey.TextColorPrefix}:${color.toArgb()}" to FocusRequester()
            } +
            OverlayOutlineColors.associate { color ->
                "${StyleFocusKey.OutlineColorPrefix}:${color.toArgb()}" to FocusRequester()
            }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollItemIntoView(
    targetIndex: Int,
    contextItemsBefore: Int = 1
) {
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem((targetIndex - contextItemsBefore).coerceAtLeast(0))
}

private fun preferredVisibleStartIndex(targetIndex: Int): Int {
    if (targetIndex < 0) return 0
    return (targetIndex - 1).coerceAtLeast(0)
}

private data class SubtitleLanguageRailItem(
    val key: String,
    val label: String,
    val count: Int
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val isSelected: Boolean,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null
)

private fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    currentLanguageKey: String,
    noneLabel: String,
    unknownLabel: String
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    val languageEntries = if (showOnlyPreferredLanguages) {
        val preferredKeys = preferredOrder.toSet()
        counts.entries.filter { entry ->
            entry.key in preferredKeys || entry.key == currentLanguageKey
        }
    } else {
        counts.entries
    }

    val sortedItems = languageEntries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, count) ->
            SubtitleLanguageRailItem(
                key = key,
                label = subtitleLanguageLabel(key, unknownLabel),
                count = count
            )
        }

    return listOf(
        SubtitleLanguageRailItem(
            key = SubtitleOffLanguageKey,
            label = noneLabel,
            count = 0
        )
    ) + sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    installedAddonOrder: List<String>,
    selectedOptionId: String?,
    builtInLabel: String,
    forcedLabel: String
): List<SubtitleOptionRailItem> {
    if (selectedLanguageKey == SubtitleOffLanguageKey) return emptyList()

    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    fun toAddonItem(subtitle: Subtitle): SubtitleOptionRailItem {
        val optionId = addonSubtitleOptionId(subtitle)
        return SubtitleOptionRailItem(
            id = optionId,
            kind = SubtitleOptionKind.ADDON,
            title = if (subtitle.isStreamProvided) {
                streamProvidedSubtitleTitle(subtitle)
            } else {
                Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang))
            },
            sourceLabel = if (subtitle.isStreamProvided) builtInLabel else subtitle.addonName,
            meta = subtitle.id.takeIf { it.isNotBlank() && it != subtitle.lang && it != subtitle.url },
            isSelected = optionId == selectedOptionId,
            addonSubtitle = subtitle
        )
    }

    val matchingAddonSubtitles = addonSubtitles
        .filter { normalizeOverlayLanguageKey(it.lang) == selectedLanguageKey }
        .distinctBy { addonSubtitleOptionId(it) }

    val streamProvidedItems = matchingAddonSubtitles
        .filter { it.isStreamProvided }
        .map(::toAddonItem)

    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            SubtitleOptionRailItem(
                id = "internal:${track.index}",
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = builtInLabel,
                meta = listOfNotNull(
                    track.codec,
                    if (track.isForced) forcedLabel else null
                ).joinToString(" • ").ifBlank { null },
                isSelected = "internal:${track.index}" == selectedOptionId,
                internalTrackIndex = track.index
            )
        }

    val addonFetchedItems = matchingAddonSubtitles
        .filter { !it.isStreamProvided }
        .withIndex()
        .sortedWith(
            compareBy(
                { (_, subtitle) -> addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE },
                { (index, _) -> index }
            )
        )
        .map { (_, subtitle) -> toAddonItem(subtitle) }

    return internalItems + streamProvidedItems + addonFetchedItems
}

private fun selectedSubtitleLanguageKey(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun selectedSubtitleOptionId(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String? {
    selectedAddonSubtitle?.let { subtitle ->
        return addonSubtitleOptionId(subtitle)
    }

    internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { track ->
            return "internal:${track.index}"
        }

    internalTracks
        .firstOrNull { it.isSelected }
        ?.let { track ->
            return "internal:${track.index}"
        }

    return null
}

private fun addonSubtitleOptionId(subtitle: Subtitle): String {
    return "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}"
}

private fun streamProvidedSubtitleTitle(subtitle: Subtitle): String {
    val name = subtitle.addonName.trim()
    if (name.isNotBlank() && !name.equals("Plugin", ignoreCase = true)) {
        return name
    }
    return subtitle.lang
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String, unknownLabel: String): String {
    return when (key) {
        SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none")
        SubtitleUnknownLanguageKey -> unknownLabel
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none").lowercase()
    else -> Subtitle.languageCodeToName(key).lowercase()
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return when {
        delayMs > 0 -> "+${delayMs}ms"
        delayMs < 0 -> "${delayMs}ms"
        else -> "0ms"
    }
}
