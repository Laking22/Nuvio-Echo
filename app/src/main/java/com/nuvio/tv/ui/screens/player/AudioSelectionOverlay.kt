@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player


import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.AudioMenuOption
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    onTrackSelected: (Int) -> Unit,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Languages across all sources (Beta speed-ups); null shows the playing file's tracks only. */
    languageOptions: List<AudioMenuOption>? = null,
    isLoadingSources: Boolean = false,
    onLanguageSelected: (AudioMenuOption) -> Unit = {},
    onLanguageSourcesRequested: (AudioMenuOption) -> Unit = {},
    /** New look: the Audio | Quality tabs of the merged panel, in place of the title. */
    sectionTabs: (@Composable () -> Unit)? = null
) {
    val tracksFocusRequester = remember { FocusRequester() }
    val delayMinusFocusRequester = remember { FocusRequester() }
    val delayPlusFocusRequester = remember { FocusRequester() }
    val ampMinusFocusRequester = remember { FocusRequester() }
    val ampPlusFocusRequester = remember { FocusRequester() }
    val centerMinusFocusRequester = remember { FocusRequester() }
    val centerPlusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingControlFocusTarget by rememberSaveable {
        mutableStateOf<AudioControlFocusTarget?>(null)
    }

    // Language rows arrive as sources load: focus what's playing once there's a row for it.
    val languageFocusKey = languageOptions?.audioMenuFocusKey()
    var languageFocusDone by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible, languageFocusKey) {
        if (!visible || languageOptions == null || languageFocusDone || languageFocusKey == null) return@LaunchedEffect
        val listIndex = languageOptions.indexOfFirst { it.key == languageFocusKey }.coerceAtLeast(0)
        listState.scrollToItem(listIndex)
        delay(120)
        runCatching { tracksFocusRequester.requestFocus() }
        languageFocusDone = true
    }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible || languageOptions != null) return@LaunchedEffect

        if (tracks.isNotEmpty()) {
            val selectedListIndex = tracks.indexOfFirst { it.index == selectedIndex }.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(selectedListIndex)
            delay(120)
            runCatching { tracksFocusRequester.requestFocus() }
        } else {
            delay(120)
            val initialControlsFocusRequester = when {
                canDecreaseDelay -> delayMinusFocusRequester
                canIncreaseDelay -> delayPlusFocusRequester
                canDecreaseAmp -> ampMinusFocusRequester
                canIncreaseAmp -> ampPlusFocusRequester
                canDecreaseCenterMix -> centerMinusFocusRequester
                canIncreaseCenterMix -> centerPlusFocusRequester
                else -> persistFocusRequester
            }
            runCatching { initialControlsFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(visible, pendingControlFocusTarget, audioAmplificationDb, isAmplificationAvailable) {
        if (!visible) return@LaunchedEffect
        val target = pendingControlFocusTarget ?: return@LaunchedEffect
        val targetCanFocus = when (target) {
            AudioControlFocusTarget.DelayMinus -> canDecreaseDelay
            AudioControlFocusTarget.DelayPlus -> canIncreaseDelay
            AudioControlFocusTarget.AmpMinus -> canDecreaseAmp
            AudioControlFocusTarget.AmpPlus -> canIncreaseAmp
            AudioControlFocusTarget.CenterMinus -> canDecreaseCenterMix
            AudioControlFocusTarget.CenterPlus -> canIncreaseCenterMix
            AudioControlFocusTarget.Persist -> true
        }
        if (!targetCanFocus) return@LaunchedEffect
        val controlFocusRequester = when (target) {
            AudioControlFocusTarget.DelayMinus -> delayMinusFocusRequester
            AudioControlFocusTarget.DelayPlus -> delayPlusFocusRequester
            AudioControlFocusTarget.AmpMinus -> ampMinusFocusRequester
            AudioControlFocusTarget.AmpPlus -> ampPlusFocusRequester
            AudioControlFocusTarget.CenterMinus -> centerMinusFocusRequester
            AudioControlFocusTarget.CenterPlus -> centerPlusFocusRequester
            AudioControlFocusTarget.Persist -> persistFocusRequester
        }
        delay(80)
        controlFocusRequester.requestFocusAfterFrames(frames = 2)
        delay(200)
        runCatching { controlFocusRequester.requestFocus() }
        pendingControlFocusTarget = null
    }

    // The gear swaps the list for the extras (delay, amplification, centre mix). With nothing to list, they show.
    var showSettings by remember(visible) { mutableStateOf(tracks.isEmpty() && languageOptions == null) }
    // Left of the steppers there's nothing in the card: the D-pad stays put.
    val stepperLeft = FocusRequester.Cancel

    PlayerPopCard(
        visible = visible,
        modifier = modifier,
        head = {
            Box(modifier = Modifier.weight(1f)) {
                if (sectionTabs != null) sectionTabs() else Text(
                    text = stringResource(R.string.audio_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            PopGearButton(
                open = showSettings,
                onClick = { showSettings = !showSettings },
                contentDescription = stringResource(R.string.player_audio_settings)
            )
        }
    ) {
        if (showSettings) {
            PopHint(text = stringResource(R.string.player_audio_settings))
            AudioControlsContent(
                audioDelayMs = audioDelayMs,
                audioAmplificationDb = audioAmplificationDb,
                isAmplificationAvailable = isAmplificationAvailable,
                centerMixLevelDb = centerMixLevelDb,
                isCenterMixAvailable = isCenterMixAvailable,
                persistAmplification = persistAmplification,
                delayMinusFocusRequester = delayMinusFocusRequester,
                delayPlusFocusRequester = delayPlusFocusRequester,
                ampMinusFocusRequester = ampMinusFocusRequester,
                ampPlusFocusRequester = ampPlusFocusRequester,
                centerMinusFocusRequester = centerMinusFocusRequester,
                centerPlusFocusRequester = centerPlusFocusRequester,
                persistFocusRequester = persistFocusRequester,
                leftFocusRequester = stepperLeft,
                onAudioDelayChange = onAudioDelayChange,
                onAmplificationChange = { nextDb, focusTarget ->
                    pendingControlFocusTarget = focusTarget
                    onAmplificationChange(nextDb)
                },
                onCenterMixLevelChange = onCenterMixLevelChange,
                onPersistAmplificationChange = onPersistAmplificationChange
            )
        } else if (languageOptions != null) {
            AudioLanguageList(
                options = languageOptions,
                isLoadingSources = isLoadingSources,
                listState = listState,
                initialFocusRequester = tracksFocusRequester,
                rightFocusRequester = FocusRequester.Cancel,
                onTrackSelected = onTrackSelected,
                onLanguageSelected = onLanguageSelected,
                onSourcesRequested = onLanguageSourcesRequested
            )
        } else AudioTracksContent(
            tracks = tracks,
            selectedIndex = selectedIndex,
            listState = listState,
            initialFocusRequester = tracksFocusRequester,
            rightFocusRequester = FocusRequester.Cancel,
            onTrackFocused = { lastFocusedAudioIndex = it },
            onTrackSelected = onTrackSelected
        )
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackFocused: (Int) -> Unit,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        PopHint(text = stringResource(R.string.audio_lang_default))
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .heightIn(max = PopListMaxHeight)
            .fillMaxWidth()
    ) {
        items(items = tracks, key = { track -> track.index }) { track ->
            AudioTrackCard(
                track = track,
                isSelected = track.index == selectedIndex,
                onFocused = { onTrackFocused(track.index) },
                onClick = { onTrackSelected(track.index) },
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (
                    track.index == selectedIndex || (selectedIndex < 0 && track == tracks.firstOrNull())
                ) {
                    initialFocusRequester
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?
) {
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" · ")
    val language = track.language?.takeIf { it.isNotBlank() && it != "und" }?.let { languageCodeToName(it) }

    PopRow(
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) { primary, secondary ->
        PopRowText(
            title = track.name,
            primary = primary,
            secondary = secondary,
            below = listOfNotNull(language?.takeIf { !track.name.contains(it, ignoreCase = true) }, metadata.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        )
        PopRowEnd(selected = isSelected, note = null, secondary = secondary)
    }
}

@Composable
private fun AudioControlsContent(
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    delayMinusFocusRequester: FocusRequester,
    delayPlusFocusRequester: FocusRequester,
    ampMinusFocusRequester: FocusRequester,
    ampPlusFocusRequester: FocusRequester,
    centerMinusFocusRequester: FocusRequester,
    centerPlusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int, AudioControlFocusTarget) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    val firstDelayFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        delayPlusFocusRequester
    }
    val firstAmpFocusRequester = when {
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val firstCenterFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val persistUpFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseDelay -> delayMinusFocusRequester
        else -> delayPlusFocusRequester
    }
    val delayPlusLeftFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val ampPlusLeftFocusRequester = if (canDecreaseAmp) {
        ampMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val centerPlusLeftFocusRequester = if (canDecreaseCenterMix) {
        centerMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val persistLeftFocusRequester = when {
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        else -> leftFocusRequester
    }
    val centerHelperText = if (isCenterMixAvailable) {
        stringResource(R.string.audio_center_mix_help)
    } else {
        stringResource(R.string.audio_center_mix_unavailable)
    }

    val amplificationHelperText = when {
        !isAmplificationAvailable -> stringResource(R.string.audio_mix_unavailable)
        persistAmplification -> stringResource(
            R.string.audio_mix_range_saved,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
        else -> stringResource(
            R.string.audio_mix_range,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PopListMaxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        AdjustmentSection(
            title = stringResource(R.string.audio_delay_label),
            valueText = formatAudioDelay(currentDelayMs),
            helperText = stringResource(
                R.string.audio_delay_range,
                AUDIO_DELAY_MIN_MS / 1000f,
                AUDIO_DELAY_MAX_MS / 1000f
            ),
            canDecrease = canDecreaseDelay,
            canIncrease = canIncreaseDelay,
            minusFocusRequester = delayMinusFocusRequester,
            plusFocusRequester = delayPlusFocusRequester,
            minusLeftFocusRequester = leftFocusRequester,
            plusLeftFocusRequester = delayPlusLeftFocusRequester,
            upFocusRequester = null,
            downFocusRequester = firstAmpFocusRequester,
            onDecrease = {
                val nextDelayMs = currentDelayMs - AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDelayMs = currentDelayMs + AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            },
            onDecreaseHold = { stepMs ->
                val nextDelayMs = currentDelayMs - stepMs
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncreaseHold = { stepMs ->
                val nextDelayMs = currentDelayMs + stepMs
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            }
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            AdjustmentSection(
                title = stringResource(R.string.audio_mix_label),
                valueText = stringResource(R.string.audio_mix_value_db, currentDb),
                helperText = amplificationHelperText,
                canDecrease = canDecreaseAmp,
                canIncrease = canIncreaseAmp,
                minusFocusRequester = ampMinusFocusRequester,
                plusFocusRequester = ampPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = ampPlusLeftFocusRequester,
                upFocusRequester = firstDelayFocusRequester,
                downFocusRequester = firstCenterFocusRequester,
                onDecrease = {
                    val nextDb = currentDb - 1
                    val target = if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        AudioControlFocusTarget.AmpPlus
                    } else {
                        AudioControlFocusTarget.AmpMinus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        runCatching { ampPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentDb + 1
                    val target = if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        AudioControlFocusTarget.AmpMinus
                    } else {
                        AudioControlFocusTarget.AmpPlus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        runCatching { ampMinusFocusRequester.requestFocus() }
                    }
                }
            )

            AdjustmentSection(
                title = stringResource(R.string.audio_center_mix_label),
                valueText = stringResource(R.string.audio_center_mix_value_db, currentCenterMixDb),
                helperText = centerHelperText,
                canDecrease = canDecreaseCenterMix,
                canIncrease = canIncreaseCenterMix,
                minusFocusRequester = centerMinusFocusRequester,
                plusFocusRequester = centerPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = centerPlusLeftFocusRequester,
                upFocusRequester = firstAmpFocusRequester,
                downFocusRequester = persistFocusRequester,
                onDecrease = {
                    val nextDb = currentCenterMixDb - 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb <= CENTER_MIX_LEVEL_MIN_DB && canIncreaseCenterMix) {
                        runCatching { centerPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentCenterMixDb + 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb >= CENTER_MIX_LEVEL_MAX_DB && canDecreaseCenterMix) {
                        runCatching { centerMinusFocusRequester.requestFocus() }
                    }
                }
            )

            PopSettingRow(label = stringResource(R.string.audio_mix_persist_label)) {
                PopPill(
                    text = stringResource(if (persistAmplification) R.string.subtitle_style_on else R.string.subtitle_style_off),
                    selected = persistAmplification,
                    onClick = { onPersistAmplificationChange(!persistAmplification) },
                    focusRequester = persistFocusRequester,
                    modifier = Modifier.focusProperties {
                        left = persistLeftFocusRequester
                        up = persistUpFocusRequester
                    }
                )
            }
        }
    }
}

@Composable
private fun AdjustmentSection(
    title: String,
    valueText: String,
    helperText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    minusLeftFocusRequester: FocusRequester,
    plusLeftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onDecreaseHold: ((Int) -> Unit)? = null,
    onIncreaseHold: ((Int) -> Unit)? = null
) {
    PopSettingRow(label = title, helper = helperText) {
        StepCard(
            icon = Icons.Default.Remove,
            enabled = canDecrease,
            focusRequester = minusFocusRequester,
            leftFocusRequester = minusLeftFocusRequester,
            rightFocusRequester = if (canIncrease) plusFocusRequester else FocusRequester.Default,
            upFocusRequester = upFocusRequester,
            downFocusRequester = downFocusRequester,
            onClick = onDecrease,
            onHoldTick = onDecreaseHold
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            PopStepperValue(text = valueText)
        }
        StepCard(
            icon = Icons.Default.Add,
            enabled = canIncrease,
            focusRequester = plusFocusRequester,
            leftFocusRequester = plusLeftFocusRequester,
            upFocusRequester = upFocusRequester,
            downFocusRequester = downFocusRequester,
            onClick = onIncrease,
            onHoldTick = onIncreaseHold
        )
    }
}

@Composable
private fun StepCard(
    icon: ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester = FocusRequester.Default,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onHoldTick: ((Int) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    if (onHoldTick != null) {
        val isPressed by interactionSource.collectIsPressedAsState()
        val latestOnHoldTick by rememberUpdatedState(onHoldTick)
        LaunchedEffect(isPressed, enabled) {
            if (!isPressed || !enabled) return@LaunchedEffect
            // Let the initial click apply its normal (small) step; only start
            // auto-repeating once the button has been held down continuously.
            // The repeat step itself ramps up the longer the hold continues:
            // AUDIO_DELAY_HOLD_STEP_MS after AUDIO_DELAY_HOLD_THRESHOLD_MS,
            // then AUDIO_DELAY_HOLD_FAST_STEP_MS after AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS.
            delay(AUDIO_DELAY_HOLD_THRESHOLD_MS)
            var heldMs = AUDIO_DELAY_HOLD_THRESHOLD_MS
            while (isActive) {
                val step = if (heldMs >= AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS) {
                    AUDIO_DELAY_HOLD_FAST_STEP_MS
                } else {
                    AUDIO_DELAY_HOLD_STEP_MS
                }
                latestOnHoldTick(step)
                delay(AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS)
                heldMs += AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS
            }
        }
    }

    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .size(30.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftFocusRequester
                right = rightFocusRequester
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            },
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = if (enabled) 0.10f else 0.04f),
            focusedContainerColor = if (enabled) Color.White else Color.White.copy(alpha = 0.04f)
        ),
        shape = CardDefaults.shape(StepShape),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val focused by interactionSource.collectIsFocusedAsState()
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> Color.White.copy(alpha = 0.3f)
                    focused -> Color(0xFF111111)
                    else -> Color.White
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private val StepShape = RoundedCornerShape(10.dp)

private enum class AudioControlFocusTarget {
    DelayMinus,
    DelayPlus,
    AmpMinus,
    AmpPlus,
    CenterMinus,
    CenterPlus,
    Persist
}

private fun formatAudioDelay(delayMs: Int): String {
    return if (delayMs == 0) {
        String.format(Locale.US, "%.3fs", 0f)
    } else {
        String.format(Locale.US, "%+.3fs", delayMs / 1000f)
    }
}
