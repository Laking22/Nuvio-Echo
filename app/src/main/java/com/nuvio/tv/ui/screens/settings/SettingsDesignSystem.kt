@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.graphics.Shape
import com.nuvio.tv.ui.screens.detail.detailGlassLens
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioRadii

internal val SettingsContainerRadius = NuvioComponents.tokens.settings.containerRadius
internal val SettingsPillRadius = NuvioRadii.tokens.full
internal val SettingsSecondaryCardRadius = NuvioComponents.tokens.settings.secondaryCardRadius
internal val SettingsRailItemHeight = NuvioComponents.tokens.settings.railItemHeight

internal val SettingsZenRowShape = RoundedCornerShape(12.dp)
internal val SettingsHorizonRowShape = RoundedCornerShape(10.dp)
internal val SettingsHorizonGroupShape = RoundedCornerShape(16.dp)

/**
 * Marks a row that holds several options side by side. Without it, moving into the row uses a
 * geometric search and lands on whichever option sits nearest the full width row above, which is
 * the middle one. With it, coming back to the row takes the option last used, and anything else
 * takes [fallbackOption], which callers point at the first option.
 *
 * The fallback has to be named. Left at the default it hands the choice back to the same geometric
 * search, which puts focus in the middle again.
 */
internal fun Modifier.settingsOptionRow(fallbackOption: FocusRequester): Modifier = this
    .focusRestorer(fallbackOption)
    .focusGroup()

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun isFlatSettingsStyle(): Boolean = NuvioTheme.settingsUiStyle != SettingsUiStyle.CLASSIC

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun settingsFocusFillColor(): Color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.1f)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun settingsRowShape(): RoundedCornerShape = when (NuvioTheme.settingsUiStyle) {
    SettingsUiStyle.CLASSIC -> RoundedCornerShape(SettingsPillRadius)
    SettingsUiStyle.ZEN -> SettingsZenRowShape
    SettingsUiStyle.HORIZON -> SettingsHorizonRowShape
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun settingsTallRowShape(): RoundedCornerShape =
    if (isFlatSettingsStyle()) settingsRowShape() else RoundedCornerShape(SettingsSecondaryCardRadius)

/**
 * The app's one selection look, here as everywhere (Home's hero, the details page, Search, Library): the glass lens
 * behind whatever has focus. Rows clear their own fill while focused so the lens shows through.
 */
internal fun Modifier.settingsFocusGlass(focused: Boolean, shape: Shape): Modifier =
    // No drop shadow: under see-through glass it shows through as a dark box below the content.
    if (focused) detailGlassLens(shape, lifted = false) else this

/** An unfocused tile or button on Settings' glass: a faint white layer rather than the theme's solid black. */
internal val SettingsGlassFill = Color.White.copy(alpha = 0.06f)

internal enum class SettingsGlassLevel(val top: Float, val bottom: Float, val rimTop: Float, val rimBottom: Float) {
    /** The workspace: a faint pane over the page's colour. */
    PANEL(0.07f, 0.035f, 0.26f, 0.08f),
    /** A group inside it: a touch lighter, so it reads as a layer above. */
    CARD(0.06f, 0.04f, 0.18f, 0.06f)
}

/**
 * Settings' surfaces as liquid glass, the same as the menu and Home: a translucent white body over the ambient
 * colour behind, a rim lit along the top, no hard border. Plain drawing, no blur, so it costs the same on every TV.
 */
internal fun Modifier.settingsGlass(shape: Shape, level: SettingsGlassLevel): Modifier = this
    .clip(shape)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val body = Brush.verticalGradient(0f to Color.White.copy(alpha = level.top), 1f to Color.White.copy(alpha = level.bottom))
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = level.rimTop),
            0.25f to Color.White.copy(alpha = level.rimBottom * 0.6f),
            1f to Color.White.copy(alpha = level.rimBottom)
        )
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, body)
            drawOutline(outline, rim, style = stroke)
        }
    }

@Composable
/** Clear in every style: rows sit on the glass card, and focus is the glass lens. */
internal fun settingsRowColors(): CardColors = CardDefaults.colors(
    containerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent
)

/** No focus ring: focus is the glass lens ([settingsFocusGlass]). */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun settingsRowBorder(
    contentAlpha: Float = 1f,
    shape: RoundedCornerShape = settingsRowShape()
): CardBorder = CardDefaults.border(border = Border.None, focusedBorder = Border.None)

internal object SettingsTestTags {
    const val ROW_ICON = "settings_row_icon"
}

@Composable
private fun SettingsRowLeadingIcon(
    icon: ImageVector,
    enabled: Boolean,
    focused: Boolean
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = (if (focused && enabled) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary)
            .copy(alpha = contentAlpha),
        modifier = Modifier
            .size(NuvioTheme.spacing.xl)
            .testTag(SettingsTestTags.ROW_ICON)
    )
}

internal data class SettingsPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val trailing: String? = null,
    val titleFontFamily: FontFamily? = null
)

@Composable
internal fun SettingsStandaloneScaffold(
    title: String,
    subtitle: String,
    classicContainer: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl)
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize(),
            classicContainer = classicContainer
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsBrandPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showBuiltInHeader: Boolean = true
) {
    val titleColor = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
    val subtitleColor = if (showBuiltInHeader) NuvioTheme.colors.TextSecondary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = NuvioTheme.colors.Border,
                shape = RoundedCornerShape(SettingsContainerRadius)
            )
            .padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = titleColor
                )
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        BrandWordmark(
            contentDescription = stringResource(R.string.cd_nuvio_logo),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(72.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.settings_rounded_ui),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp,
            color = subtitleColor
        )
    }
}

@Composable
internal fun SettingsWorkspaceSurface(
    modifier: Modifier = Modifier,
    classicContainer: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    if (isFlatSettingsStyle() || !classicContainer) {
        Box(
            modifier = modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            content = content
        )
    } else {
        Box(
            modifier = modifier
                .settingsGlass(RoundedCornerShape(SettingsContainerRadius), SettingsGlassLevel.PANEL)
                .padding(20.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsRailButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    icon: ImageVector? = null,
    rawIconRes: Int? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val zen = isFlatSettingsStyle()
    val railShape = if (zen) SettingsZenRowShape else RoundedCornerShape(SettingsPillRadius)
    val appliedModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }

    Card(
        onClick = onClick,
        modifier = appliedModifier
            .padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
            .fillMaxWidth()
            .heightIn(min = SettingsRailItemHeight)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .settingsFocusGlass(isFocused, railShape),
        colors = CardDefaults.colors(
            // The open category keeps a soft fill; focus is the glass.
            containerColor = if (isSelected) settingsFocusFillColor() else Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        shape = CardDefaults.shape(railShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRailItemHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (zen) 14.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (zen) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(SettingsPillRadius))
                                .background(
                                    if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent
                                )
                        )
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                    }
                    if (rawIconRes != null) {
                        Image(
                            painter = rememberRawSvgPainter(rawIconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(
                                if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                            )
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (rawIconRes != null || icon != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    FocusMarqueeText(
                        text = title,
                        focused = isFocused,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    )
                }

                if (!zen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsTopBarTab(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    icon: ImageVector? = null,
    rawIconRes: Int? = null,
    onFocusedTabPositioned: ((LayoutCoordinates) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    var tabCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val glideIndicator = onFocusedTabPositioned != null
    val appliedModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.OnSecondary
            isSelected -> NuvioTheme.colors.TextPrimary
            else -> NuvioTheme.colors.TextSecondary
        },
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "topBarTabContentColor"
    )

    Card(
        onClick = onClick,
        modifier = appliedModifier
            .onGloballyPositioned { coordinates ->
                tabCoordinates = coordinates
                if (isFocused) onFocusedTabPositioned?.invoke(coordinates)
            }
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) {
                        onFocused()
                        tabCoordinates?.let { onFocusedTabPositioned?.invoke(it) }
                    }
                }
            },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) {
                NuvioTheme.colors.Secondary.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            },
            focusedContainerColor = if (glideIndicator) {
                Color.Transparent
            } else {
                NuvioTheme.colors.Secondary
            }
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rawIconRes != null) {
                Image(
                    painter = rememberRawSvgPainter(rawIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(contentColor)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    when (NuvioTheme.settingsUiStyle) {
        SettingsUiStyle.ZEN -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
        SettingsUiStyle.HORIZON -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        SettingsUiStyle.CLASSIC -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    when (NuvioTheme.settingsUiStyle) {
        SettingsUiStyle.ZEN, SettingsUiStyle.HORIZON -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = NuvioTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.2.sp,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg)
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsGlass(SettingsHorizonGroupShape, SettingsGlassLevel.CARD)
                    .padding(NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                content()
            }
        }
        SettingsUiStyle.CLASSIC -> Column(
            modifier = modifier
                .fillMaxWidth()
                .settingsGlass(RoundedCornerShape(SettingsSecondaryCardRadius), SettingsGlassLevel.CARD)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            content()
        }
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = NuvioTheme.colors.TextPrimary,
    expandSubtitleOnFocus: Boolean = false,
    /** A small tag after the title, such as "Experimental". */
    titleBadge: String? = null
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (enabled) onToggle()
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .focusProperties { canFocus = enabled }
            .semantics {
                if (!enabled) disabled()
            }
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .settingsFocusGlass(isFocused, settingsRowShape()),
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(NuvioTheme.spacing.lg)
                        )
                    }
                    if (titleBadge != null) {
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        SettingsTitleBadge(text = titleBadge, contentAlpha = contentAlpha)
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = if (expandSubtitleOnFocus && isFocused) 8 else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            SettingsTogglePill(
                checked = checked,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SettingsTitleBadge(text: String, contentAlpha: Float) {
    val tint = NuvioTheme.colors.Secondary.copy(alpha = contentAlpha)
    Box(
        modifier = Modifier
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.7f * contentAlpha)), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String?,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = NuvioTheme.colors.TextPrimary,
    leadingIcon: ImageVector? = null,
    @RawRes leadingRawIconRes: Int? = null,
    leadingArtworkSize: Dp = NuvioTheme.spacing.xl,
    valueColor: Color = NuvioTheme.colors.TextSecondary
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .semantics {
                if (!enabled) disabled()
            }
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .settingsFocusGlass(isFocused, settingsRowShape()),
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingRawIconRes != null) {
                Image(
                    painter = rememberRawSvgPainter(leadingRawIconRes, leadingArtworkSize),
                    contentDescription = null,
                    modifier = Modifier
                        .size(leadingArtworkSize)
                        .alpha(contentAlpha),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
            } else if (leadingIcon != null) {
                SettingsRowLeadingIcon(icon = leadingIcon, enabled = enabled, focused = isFocused)
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(NuvioTheme.spacing.lg)
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = valueColor.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = NuvioTheme.colors.TextTertiary.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun <T> SettingsSingleChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 420.dp,
    maxHeight: Dp = 320.dp
) {
    val focusRequester = remember { FocusRequester() }
    val focusedIndex = options.indexOfFirst { it.value == selectedValue }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusedIndex)

    LaunchedEffect(focusedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                itemsIndexed(
                    items = options,
                    key = { index, option -> "$index-${option.value}" }
                ) { index, option ->
                    val isSelected = option.value == selectedValue
                    var optionFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = { onOptionSelected(option.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == focusedIndex) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { optionFocused = it.isFocused }
                            .settingsFocusGlass(optionFocused, RoundedCornerShape(10.dp)),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) settingsFocusFillColor() else NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = Color.Transparent
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(NuvioTheme.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = option.titleFontFamily
                                )
                                if (!option.description.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                    Text(
                                        text = option.description,
                                        color = NuvioTheme.colors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (!option.trailing.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Text(
                                    text = option.trailing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioTheme.colors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> SettingsMultiChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValues: List<T>,
    onValuesSelected: (List<T>) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 520.dp,
    maxHeight: Dp = 420.dp
) {
    val focusRequester = remember { FocusRequester() }
    val selected = remember(selectedValues) { mutableStateListOf<T>().also { it.addAll(selectedValues) } }
    val firstSelectedIndex = options.indexOfFirst { option -> selectedValues.contains(option.value) }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstSelectedIndex)

    LaunchedEffect(firstSelectedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
                ) {
                    itemsIndexed(
                        items = options,
                        key = { index, option -> "$index-${option.value}" }
                    ) { index, option ->
                        val isSelected = selected.contains(option.value)
                        var optionFocused by remember { mutableStateOf(false) }
                        Card(
                            onClick = {
                                if (isSelected) {
                                    selected.remove(option.value)
                                } else {
                                    selected.add(option.value)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == firstSelectedIndex) Modifier.focusRequester(focusRequester) else Modifier)
                                .onFocusChanged { optionFocused = it.isFocused }
                                .settingsFocusGlass(optionFocused, RoundedCornerShape(10.dp)),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) settingsFocusFillColor() else NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = Color.Transparent
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(NuvioTheme.spacing.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.title,
                                        color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = option.titleFontFamily
                                    )
                                    if (!option.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                        Text(
                                            text = option.description,
                                            color = NuvioTheme.colors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.cd_selected),
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_clear),
                    onClick = { selected.clear() }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_save),
                    onClick = { onValuesSelected(options.map { it.value }.filter { selected.contains(it) }) },
                    primary = true
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogActionRow(
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .settingsFocusGlass(isFocused, RoundedCornerShape(SettingsPillRadius)),
        // Picked chips are outlined (no fill), so the glass alone says which one has focus.
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.TextPrimary.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = if (selected) Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.TextPrimary.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                textDirection = label.contentTextDirection()
            ),
            color = if (selected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 10.dp)
        )
    }
}

@Composable
private fun SettingsTogglePill(
    checked: Boolean,
    enabled: Boolean
) {
    // Neutral, like the rest of the glass: a white track when on, a faint one when off. The theme's colour stays for
    // the few things that need to stand out.
    val alpha = if (enabled) 1f else 0.35f
    val trackColor = if (checked) Color.White.copy(alpha = 0.92f * alpha) else Color.White.copy(alpha = 0.16f * alpha)
    val knobColor = if (checked) Color(0xFF151515).copy(alpha = alpha) else Color.White.copy(alpha = 0.85f * alpha)
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(NuvioTheme.spacing.xl)
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(trackColor)
            .padding(NuvioTheme.spacing.xxs),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(knobColor)
        )
    }
}

@Composable
internal fun rememberRawSvgPainter(
    @RawRes rawIconRes: Int,
    targetSize: Dp = 24.dp
): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { targetSize.roundToPx() }
    val request = remember(rawIconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}
